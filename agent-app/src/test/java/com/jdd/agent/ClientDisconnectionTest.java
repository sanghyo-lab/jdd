package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationModel.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real TCP/HTTP, PostgreSQL and background worker; inference and observations are explicit test doubles. */
@EnabledIfEnvironmentVariable(named = "JDD_DISCONNECT_TEST_DB_URL", matches = ".+/jdd_agent_disconnect_test(?:\\?.*)?")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=true", "jdd.agent.worker.concurrency=1", "spring.flyway.create-schemas=true",
    "server.address=127.0.0.1", "jdd.build-id=local-disconnection-mock-check"
})
@Import(ClientDisconnectionTest.SyntheticConfiguration.class)
@DirtiesContext
class ClientDisconnectionTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("JDD_DISCONNECT_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> "jdd_agent");
        properties.add("spring.datasource.password", () -> System.getenv("JDD_DISCONNECT_TEST_DB_PASSWORD"));
    }
    @Autowired InvestigationRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ControlledModel model;
    @LocalServerPort int port;

    @Test void responseLossAndReconnectionDoNotCancelOrRepeatTheBackgroundInvestigation() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("jdd_agent_disconnect_test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Integer.class))
                .as("Prepare the dedicated synthetic DB before starting this test").isZero();
        String ticket = "disconnected-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        var input = new InvestigationInput("1.0", ticket, 1, key, "합성 연결 유실 검사", null, null);
        byte[] body = json.writeValueAsBytes(input);
        // Send the complete request and lose its response. The client reads no acceptance or investigation ID.
        try (var connection = new Socket("127.0.0.1", port)) {
            var output = connection.getOutputStream();
            output.write(("POST /api/investigations HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body); output.flush();
        }
        try {
            assertThat(model.evidenceStored.await(15, TimeUnit.SECONDS)).isTrue();
            var running = repository.findByRequest(ticket, key).orElseThrow().investigation();
            String id = running.investigationId();
            assertThat(running.status()).isEqualTo(Status.RUNNING);
            assertThat(running.progress()).hasSize(1).allMatch(tool -> tool.status() == ToolStatus.SUCCEEDED);
            assertThat(running.evidence()).hasSize(1);
            assertThat(model.calls).hasValue(2);

            // A fresh client recovers the ID through the same request key while the original execution is active.
            try (var reconnected = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + port + "/api/investigations";
                var retry = reconnected.send(HttpRequest.newBuilder(URI.create(base)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(retry.statusCode()).isEqualTo(202);
                assertThat(json.readTree(retry.body()).path("investigationId").asText()).isEqualTo(id);
                assertThat(json.readTree(retry.body()).path("status").asText()).isEqualTo("RUNNING");
                JsonNode saved = get(reconnected, base + "/" + id + "/evidence/" + running.evidence().getFirst().evidenceId());
                assertThat(saved.path("content").path("testOnly").asBoolean()).isTrue();
                assertThat(model.calls).hasValue(2);
                model.finish.countDown();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                JsonNode completed;
                do {
                    completed = get(reconnected, base + "/" + id);
                    if (!completed.path("status").asText().equals("RUNNING")) break;
                    Thread.sleep(25);
                } while (System.nanoTime() < deadline);
                assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
                for (int refresh = 0; refresh < 3; refresh++) {
                    assertThat(get(reconnected, base + "/" + id).path("status").asText()).isEqualTo("COMPLETED");
                    assertThat(get(reconnected, base + "/" + id + "/evidence/" + running.evidence().getFirst().evidenceId())).isEqualTo(saved);
                }
                assertThat(model.calls).hasValue(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigation_evidence", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
                Path report = Path.of(System.getenv("JDD_DISCONNECT_TEST_REPORT"));
                Files.createDirectories(report.getParent());
                Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "status", "PASSED", "mode", "MOCK_MODEL_REAL_TCP_HTTP_POSTGRES_WORKER",
                        "publicNgrokValidated", false, "paidModelCalls", 0, "mockModelCalls", model.calls.get(),
                        "acceptanceResponseRead", false, "recoveredInvestigationId", id,
                        "investigation", completed, "reopenedEvidence", saved)) + "\n");
            }
        } finally { model.finish.countDown(); }
    }

    private JsonNode get(HttpClient client, String uri) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    static final class ControlledModel implements InvestigationModel {
        final CountDownLatch evidenceStored = new CountDownLatch(1), finish = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private final JsonMapper json;
        ControlledModel(JsonMapper json) { this.json = json; }
        @Override public Mode mode() { return Mode.MOCK; }
        @Override public Reply next(Request request) {
            if (calls.incrementAndGet() == 1) return new Reply(null, List.of(new ToolCall("synthetic-read", "observeSynthetic", "{}")));
            var evidence = request.history().getLast().observations().getFirst();
            evidenceStored.countDown();
            try {
                if (!finish.await(25, TimeUnit.SECONDS)) throw InvestigationFailure.unavailable();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw InvestigationFailure.unavailable(); }
            return new Reply(json.writeValueAsString(new AnalysisReport("1.0", "연결 유실 후 합성 관측 검증",
                    List.of(new Fact("synthetic-fact", "테스트 전용 관측을 보존했습니다.", List.of(evidence.evidenceId()))),
                    List.of(), List.of(), List.of(), List.of())), List.of());
        }
    }
    @TestConfiguration static class SyntheticConfiguration {
        @Bean @Primary ControlledModel controlledModel(JsonMapper json) { return new ControlledModel(json); }
        @Bean @Primary InvestigationTools syntheticTools() {
            return new InvestigationTools() {
                @Override public List<ToolDefinition> definitions() { return List.of(new ToolDefinition("observeSynthetic", "Test-only observation",
                        "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")); }
                @Override public List<String> validate(ToolCall call) { return "observeSynthetic".equals(call.name()) ? List.of() : List.of("Unknown test tool"); }
                @Override public Outcome execute(ToolCall call) { return new Outcome(List.of(new Observation(EvidenceType.DATA,
                        "연결 검사용 합성 관측", Instant.now(), Map.of("kind", "synthetic-test"), Map.of("testOnly", true), false)), "합성 관측 저장"); }
            };
        }
    }
}
