package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationModel.*;
import com.jdd.agent.infra.InvestigationPromptLoader;
import com.jdd.agent.infra.InvestigationReportDecoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real commerce HTTP reproduction -> real evidence tools -> separate PostgreSQL storage; model is explicitly mocked. */
@EnabledIfEnvironmentVariable(named = "JDD_HANDOFF_AGENT_DB_URL", matches = ".+/jdd_agent_handoff_test.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=false", "spring.flyway.create-schemas=true", "server.address=127.0.0.1"
})
class CommerceHandoffTest {
    @DynamicPropertySource static void environment(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("JDD_HANDOFF_AGENT_DB_URL"));
        properties.add("spring.datasource.username", () -> "jdd_agent");
        properties.add("spring.datasource.password", () -> System.getenv("JDD_HANDOFF_AGENT_DB_PASSWORD"));
        properties.add("EVIDENCE_DB_URL", () -> System.getenv("JDD_HANDOFF_EVIDENCE_DB_URL"));
        properties.add("EVIDENCE_DB_PASSWORD", () -> System.getenv("JDD_HANDOFF_EVIDENCE_DB_PASSWORD"));
        properties.add("SOURCE_ROOT", () -> System.getenv("JDD_HANDOFF_SOURCE_ROOT"));
        properties.add("LOG_ROOT", () -> System.getenv("JDD_HANDOFF_LOG_ROOT"));
        properties.add("POLICY_PATH", () -> System.getenv("JDD_HANDOFF_POLICY_PATH"));
    }
    @Autowired InvestigationRepository repository;
    @Autowired InvestigationExecutionRepository executions;
    @Autowired InvestigationTools tools;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void realProviderEvidenceIsSavedAndCanBeReopenedWithoutAnotherModelCall() throws Exception {
        JsonNode input = json.readTree(Files.readString(Path.of(System.getenv("JDD_HANDOFF_INPUT_PATH"))));
        String product = input.path("productId").asText(), order = input.path("orderId").asText();
        String customer = input.path("customerId").asText(), build = input.path("buildId").asText();
        assertThat(product).startsWith("jdd-v07-"); assertThat(build).isNotBlank(); assertThat(order).isNotBlank();
        var request = new InvestigationInput("1.0", "handoff-" + UUID.randomUUID(), 1, UUID.randomUUID().toString(),
                "합성 재고 재현 데이터의 실제 근거 저장 경로를 모의 모델로 검증합니다.",
                new InvestigationInput.Context(customer, order, product, null, null, null), null);
        var http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port + "/api/investigations";
        var posted = http.send(HttpRequest.newBuilder(URI.create(base)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).isEqualTo(202);
        String id = json.readTree(posted.body()).path("investigationId").asText();
        var clock = Clock.systemUTC();
        var claim = executions.claimNext(clock.instant(), Duration.ofMinutes(3)).orElseThrow();
        assertThat(claim.investigationId()).isEqualTo(id);
        var modelCalls = new AtomicInteger();
        InvestigationModel mock = modelRequest -> {
            if (modelCalls.incrementAndGet() == 1) return new Reply(null, List.of(
                    call("findOrders", Map.of("productId", product)), call("getOrderContext", Map.of("orderId", order)),
                    call("getInventoryContext", Map.of("productId", product)), call("getCouponContext", Map.of("customerId", customer)),
                    call("searchLogs", Map.of("buildId", build, "productId", product)),
                    call("searchCode", Map.of("buildId", build, "query", "subtractStock")),
                    call("readCode", Map.of("buildId", build, "path", "commerce-core/src/main/java/com/jdd/commerce/order/application/OrderService.java", "startLine", 65, "endLine", 130)),
                    call("readBusinessPolicy", Map.of("buildId", build, "version", "demo-v1", "section", "재고"))));
            var evidence = modelRequest.history().stream().filter(message -> message.kind() == MessageKind.TOOL)
                    .flatMap(message -> message.observations().stream()).toList();
            assertThat(evidence).isNotEmpty();
            for (var item : evidence) assertThat(repository.findEvidence(id, item.evidenceId())).isPresent();
            var stock = evidence.stream().filter(item -> item.type() == EvidenceType.DATA
                    && "product_stock".equals(item.source().get("table"))).findFirst().orElseThrow();
            int quantity = json.valueToTree(stock.content()).path("rows").get(0).path("quantity").intValue();
            assertThat(quantity).isEqualTo(-1);
            var report = new AnalysisReport("1.0", "모의 모델을 사용한 실제 근거 저장·재조회 검증입니다. 실제 AI 조사 품질 평가는 아닙니다.",
                    List.of(new Fact("observed-stock", "조회 시점의 합성 상품 재고 수량은 " + quantity + "입니다.", List.of(stock.evidenceId()))),
                    List.of(), List.of(), List.of(), List.of());
            return new Reply(json.writeValueAsString(report), List.of());
        };
        new InvestigationRunner(repository, executions, mock, tools, InvestigationPromptLoader.load(),
                new InvestigationReportDecoder(json), new InvestigationRunner.Limits(3, 12, 0, 0), clock).run(claim);
        var view = repository.find(id).orElseThrow().investigation();
        assertThat(view.status()).withFailMessage("Investigation failed: %s, progress=%s", view.error(), view.progress()).isEqualTo(Status.COMPLETED);
        assertThat(view.progress()).hasSize(8).allMatch(tool -> tool.status() == ToolStatus.SUCCEEDED);
        assertThat(view.evidence().stream().map(EvidenceSummary::type).distinct().toList())
                .containsExactlyInAnyOrder(EvidenceType.DATA, EvidenceType.LOG, EvidenceType.CODE, EvidenceType.POLICY);
        var details = new ArrayList<JsonNode>();
        for (var evidence : view.evidence()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/" + id + "/evidence/" + evidence.evidenceId())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var reopened = json.readTree(response.body());
            assertThat(reopened.path("evidenceId").asText()).isEqualTo(evidence.evidenceId());
            assertThat(reopened.path("content").isNull()).isFalse();
            details.add(reopened);
        }
        var same = http.send(HttpRequest.newBuilder(URI.create(base)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(same.statusCode()).isEqualTo(202); assertThat(json.readTree(same.body()).path("investigationId").asText()).isEqualTo(id);
        assertThat(modelCalls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class)).isZero();
        Path report = Path.of(System.getenv("JDD_HANDOFF_REPORT_PATH")); Files.createDirectories(report.getParent());
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "mode", "MOCK_MODEL_REAL_COMMERCE_POSTGRES", "paidModelCalls", 0, "mockModelCalls", modelCalls.get(),
                "commerceBuildId", build, "investigation", view, "evidence", details,
                "actualModelQualityValidated", false)) + "\n");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JDD_HANDOFF_BUSINESS_INPUT_PATH", matches = ".+")
    void allRetainedBusinessCasesUseTheSameToolsAndReopenTheirOwnStoredEvidence() throws Exception {
        JsonNode cases = json.readTree(Files.readString(Path.of(System.getenv("JDD_HANDOFF_BUSINESS_INPUT_PATH"))));
        assertThat(cases.size()).isEqualTo(6);
        Path directory = Path.of(System.getenv("JDD_HANDOFF_REPORT_PATH")).getParent().resolve("business");
        Files.createDirectories(directory);
        for (JsonNode input : cases) checkBusinessCase(input, directory);
    }

    private void checkBusinessCase(JsonNode input, Path directory) throws Exception {
        String label = input.path("caseId").asText(), customer = input.path("customerId").asText();
        String product = input.path("productId").asText(), build = input.path("buildId").asText();
        assertThat(label).matches("VOC-0[1-6]"); assertThat(customer).startsWith("jdd-v0");
        var request = new InvestigationInput("1.0", "handoff-" + UUID.randomUUID(), 1, UUID.randomUUID().toString(),
                "합성 업무의 실제 데이터·로그·소스·정책 저장과 재조회를 모의 모델로 확인합니다.",
                new InvestigationInput.Context(customer, null, product, null, null, null), null);
        var service = new InvestigationService(repository, Clock.systemUTC());
        String id = service.submit(request).investigationId();
        var clock = Clock.systemUTC();
        var claim = executions.claimNext(clock.instant(), Duration.ofMinutes(3)).orElseThrow();
        assertThat(claim.investigationId()).isEqualTo(id);
        var modelCalls = new AtomicInteger();
        // This common test double receives identifiers only. It does not read expected answers or fixture files.
        InvestigationModel mock = modelRequest -> {
            int iteration = modelCalls.incrementAndGet();
            if (iteration == 1) {
                var calls = new ArrayList<ToolCall>();
                calls.add(call("findOrders", Map.of("customerId", customer)));
                calls.add(call("getCouponContext", Map.of("customerId", customer)));
                calls.add(call("getInventoryContext", Map.of("productId", product)));
                for (JsonNode key : input.path("checkoutKeys"))
                    calls.add(call("searchLogs", Map.of("buildId", build, "checkoutKey", key.asText())));
                for (String type : List.of("OrderService", "PaymentService", "CouponService"))
                    calls.add(call("searchCode", Map.of("buildId", build, "query", "class " + type)));
                return new Reply(null, calls);
            }
            var evidence = modelRequest.history().stream().filter(message -> message.kind() == MessageKind.TOOL)
                    .flatMap(message -> message.observations().stream()).toList();
            for (var item : evidence) assertThat(repository.findEvidence(id, item.evidenceId())).isPresent();
            var orders = evidence.stream().filter(item -> item.type() == EvidenceType.DATA
                    && "orders".equals(item.source().get("table"))).findFirst().orElseThrow();
            JsonNode rows = json.valueToTree(orders.content()).path("rows");
            assertThat(rows.isArray()).isTrue(); assertThat(rows.isEmpty()).isFalse();
            if (iteration == 2) {
                var calls = new ArrayList<ToolCall>();
                for (JsonNode order : rows) calls.add(call("getOrderContext", Map.of("orderId", order.path("id").asText())));
                var paths = new LinkedHashSet<String>();
                var versions = new LinkedHashSet<String>();
                for (var item : evidence) if (item.type() == EvidenceType.CODE) {
                    paths.add(item.source().get("path").toString());
                    versions.add(item.source().get("policyVersion").toString());
                }
                assertThat(paths).hasSize(3); assertThat(versions).hasSize(1);
                for (String path : paths) calls.add(call("readCode", Map.of("buildId", build, "path", path, "startLine", 1, "endLine", 300)));
                calls.add(call("readBusinessPolicy", Map.of("buildId", build, "version", versions.iterator().next())));
                return new Reply(null, calls);
            }
            assertThat(iteration).isEqualTo(3);
            return new Reply(json.writeValueAsString(new AnalysisReport("1.0",
                    "공통 모의 모델의 실제 근거 인수 검사입니다. 원인·조치의 AI 품질 평가는 아닙니다.",
                    List.of(new Fact("observed-orders", "조회 시점 고객의 주문 " + rows.size() + "건을 관측했습니다.", List.of(orders.evidenceId()))),
                    List.of(), List.of(), List.of(), List.of())), List.of());
        };
        new InvestigationRunner(repository, executions, mock, tools, InvestigationPromptLoader.load(),
                new InvestigationReportDecoder(json), new InvestigationRunner.Limits(3, 24, 0, 0), clock).run(claim);
        var view = repository.find(id).orElseThrow().investigation();
        var details = new ArrayList<JsonNode>();
        var report = new LinkedHashMap<String, Object>();
        report.put("caseId", label); report.put("mode", "MOCK_MODEL_REAL_COMMERCE_POSTGRES");
        report.put("paidModelCalls", 0); report.put("mockModelCalls", modelCalls.get());
        report.put("commerceBuildId", build); report.put("actualModelQualityValidated", false);
        report.put("investigation", view); report.put("evidence", details);
        try {
            assertThat(view.status()).withFailMessage("%s: %s, progress=%s", label, view.error(), view.progress()).isEqualTo(Status.COMPLETED);
            assertThat(view.progress()).allMatch(tool -> tool.status() == ToolStatus.SUCCEEDED);
            assertThat(view.progress().stream().map(ToolExecution::toolName).distinct())
                    .containsExactlyInAnyOrder("findOrders", "getOrderContext", "getCouponContext", "getInventoryContext", "searchLogs", "searchCode", "readCode", "readBusinessPolicy");
            assertThat(view.evidence().stream().map(EvidenceSummary::type).distinct())
                    .containsExactlyInAnyOrder(EvidenceType.DATA, EvidenceType.LOG, EvidenceType.CODE, EvidenceType.POLICY);
            var http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port + "/api/investigations/" + id;
            for (var evidence : view.evidence()) {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + "/evidence/" + evidence.evidenceId())).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var reopened = json.readTree(response.body());
                assertThat(reopened).isEqualTo(json.valueToTree(repository.findEvidence(id, evidence.evidenceId()).orElseThrow()));
                details.add(reopened);
            }
            for (int refresh = 0; refresh < 3; refresh++)
                assertThat(http.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(service.submit(request).investigationId()).isEqualTo(id);
            assertThat(modelCalls.get()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class)).isZero();
        } finally {
            Files.writeString(directory.resolve(label + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
    }
    private ToolCall call(String name, Map<String, Object> input) { return new ToolCall(UUID.randomUUID().toString(), name, json.writeValueAsString(input)); }
}
