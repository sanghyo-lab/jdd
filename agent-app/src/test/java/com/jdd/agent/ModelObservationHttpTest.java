package com.jdd.agent;

import com.jdd.agent.domain.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Stored synthetic observations over real HTTP/SQL; no model transport or credentials. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:observations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class ModelObservationHttpTest {
    @DynamicPropertySource static void dedicatedPostgres(DynamicPropertyRegistry properties) {
        String url = System.getenv("JDD_BUDGET_TEST_DB_URL");
        if (url == null) return;
        if (!url.matches("jdbc:postgresql://[^/]+/jdd_agent_budget_test(?:\\?.*)?"))
            throw new IllegalStateException("Observation tests require the dedicated test database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("JDD_BUDGET_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("JDD_BUDGET_TEST_DB_PASSWORD"));
    }

    @LocalServerPort int port;
    @Autowired InvestigationService investigations;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    private final Instant time = Instant.parse("2026-09-21T00:00:00Z");

    @Test void observesBothLedgersWithoutInventingUsageLatencyOrLeakingOtherInvestigationMetadata() throws Exception {
        String id = investigation();
        String other = investigation();
        String observed = oauth(id, "HTTP_200_completed", new ModelUsage(100L, 30L, 20L, null, 10L), 25L);
        String pending = oauth(id, "DISPATCHED", null, null);
        String privateCall = oauth(other, "HTTP_200_completed", new ModelUsage(5L, 2L, 0L, 0L, 0L), 1L);
        var states = List.of("RESERVED", "DISPATCHED", "CANCELLED", "UNKNOWN", "CONFIRMED");
        var callIds = new java.util.LinkedHashMap<String, String>();
        for (String state : states) callIds.put(state, api(id, state));
        var before = snapshot();
        var first = get(id);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        var body = json.readTree(first.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("schemaVersion", "investigationId", "runtime", "provider", "calls");
        assertThat(body.path("runtime").asText()).isEqualTo("test");
        assertThat(body.path("provider").asText()).isEqualTo("mock");
        assertThat(body.path("investigationId").asText()).isEqualTo(id);
        assertThat(body.path("calls").size()).isEqualTo(7);
        assertThat(first.body()).doesNotContain(privateCall, "private-prompt-metadata", "private-request-options",
                "private-provider-request", "request_json", "receipt_json", "Authorization", "auth.json");
        var observedCall = call(body, observed);
        assertThat(observedCall.propertyNames()).containsExactlyInAnyOrder("callId", "requestedModel", "actualModel",
                "provider", "outcome", "ledgerState", "usage", "createdAt", "elapsedMillis");
        assertThat(observedCall.path("provider").asText()).isEqualTo("codex_oauth");
        assertThat(observedCall.path("elapsedMillis").asLong()).isEqualTo(25);
        assertThat(observedCall.path("ledgerState").isNull()).isTrue();
        assertThat(observedCall.path("usage").path("cacheWriteTokens").isNull()).isTrue();
        assertThat(observedCall.path("usage").path("reasoningTokens").asLong()).isEqualTo(10);
        assertThat(call(body, pending).path("usage").isNull()).isTrue();
        assertThat(call(body, pending).path("elapsedMillis").isNull()).isTrue();
        for (var entry : callIds.entrySet()) {
            var saved = call(body, entry.getValue());
            assertThat(saved.path("provider").asText()).isEqualTo("openai_api");
            assertThat(saved.path("requestedModel").asText()).isEqualTo("synthetic-requested");
            assertThat(saved.path("ledgerState").asText()).isEqualTo(entry.getKey());
            assertThat(saved.path("elapsedMillis").isNull()).isTrue();
            if (entry.getKey().equals("CONFIRMED")) {
                assertThat(saved.path("actualModel").asText()).isEqualTo("synthetic-actual");
                assertThat(saved.path("usage").path("inputTokens").asLong()).isEqualTo(100);
            } else assertThat(saved.path("usage").isNull()).isTrue();
        }
        assertThat(call(body, callIds.get("UNKNOWN")).path("outcome").asText()).isEqualTo("INTERRUPTED_USAGE_UNKNOWN");
        assertThat(get(id).body()).isEqualTo(first.body());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void absentAndOrphanedInvestigationsAre404AndExistingEmptyInvestigationsHaveNoCalls() throws Exception {
        String absent = UUID.randomUUID().toString();
        oauth(absent, "DISPATCHED", null, null);
        var before = snapshot();
        var response = get(absent);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
        var empty = get(investigation());
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(json.readTree(empty.body()).path("calls").isEmpty()).isTrue();
    }

    @Test void corruptStoredUsageFailsWithoutEchoingStoredContent() throws Exception {
        String id = investigation();
        String call = oauth(id, "HTTP_200_completed", null, null);
        jdbc.update("UPDATE agent.oauth_model_calls SET usage_json = ? WHERE call_id = ?",
                "{\"inputTokens\":-1,\"private-corrupt-value\":\"never-return-this\"}", call);
        var before = snapshot();
        var response = get(id);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("private-corrupt-value", "never-return-this", "inputTokens");
        assertThat(snapshot()).isEqualTo(before);
    }

    private String investigation() {
        var input = json.readValue(json.writeValueAsString(Map.of("schemaVersion", "1.0", "ticketId", UUID.randomUUID().toString(),
                "ticketVersion", 1, "requestKey", "observations", "message", "합성 관측 검사")), InvestigationInput.class);
        return investigations.submit(input).investigationId();
    }
    private String oauth(String investigation, String outcome, ModelUsage usage, Long elapsed) {
        String call = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent.oauth_model_calls (call_id, investigation_id, iteration, requested_model,
                actual_model, usage_json, outcome, elapsed_millis, created_at) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)
                """, call, investigation, "synthetic-requested", usage == null ? null : "synthetic-actual",
                usage == null ? null : json.writeValueAsString(usage), outcome, elapsed, time.atOffset(ZoneOffset.UTC));
        return call;
    }
    private String api(String investigation, String state) {
        String call = UUID.randomUUID().toString();
        var pricing = new ModelPricing("synthetic-price", "synthetic-requested", BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, false);
        var request = new ModelCallLedger.Request(call, investigation, 1, "https://synthetic.invalid/responses", "default",
                "private-prompt-metadata", "synthetic-hash", "synthetic-tools", "private-request-options", pricing, 1000, 1000);
        ModelCallLedger.Receipt receipt = switch (state) {
            case "CONFIRMED" -> new ModelCallLedger.Receipt("private-provider-request", "synthetic-actual",
                    new ModelUsage(100L, 30L, 20L, null, 10L), "HTTP_200", time.plusSeconds(4));
            case "UNKNOWN" -> new ModelCallLedger.Receipt(null, null, null, "INTERRUPTED_USAGE_UNKNOWN", time.plusSeconds(4));
            default -> null;
        };
        jdbc.update("""
                INSERT INTO agent.model_calls (call_id, investigation_id, request_json, state, reserved_usd,
                confirmed_usd, receipt_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, call, investigation, json.writeValueAsString(request), state, new BigDecimal("0.001"),
                state.equals("CONFIRMED") ? new BigDecimal("0.0001") : null,
                receipt == null ? null : json.writeValueAsString(receipt), time.atOffset(ZoneOffset.UTC),
                time.plusSeconds(4).atOffset(ZoneOffset.UTC));
        return call;
    }
    private HttpResponse<String> get(String id) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/internal/investigations/" + id + "/model-observations")).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private JsonNode call(JsonNode response, String id) {
        for (var call : response.path("calls")) if (call.path("callId").asText().equals(id)) return call;
        throw new AssertionError("Missing persisted call");
    }
    private List<?> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM agent.oauth_model_calls ORDER BY call_id"),
                jdbc.queryForList("SELECT * FROM agent.model_calls ORDER BY call_id"),
                jdbc.queryForList("SELECT * FROM agent.demo_budget"),
                jdbc.queryForList("SELECT * FROM agent.investigations ORDER BY investigation_id"));
    }
}
