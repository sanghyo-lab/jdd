package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real worker/HTTP/PostgreSQL saturation; inference is a test-only controlled mock. */
@EnabledIfEnvironmentVariable(named = "JDD_QUEUE_TEST_DB_URL", matches = ".+/jdd_agent_queue_test(?:\\?.*)?")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=true", "jdd.agent.worker.concurrency=1", "jdd.agent.worker.maximum-runtime=PT30S",
    "jdd.agent.queue.maximum-wait=PT2S", "spring.flyway.create-schemas=true", "server.address=127.0.0.1"
})
@Import(QueueWaitingTest.SyntheticConfiguration.class)
@DirtiesContext
class QueueWaitingTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("JDD_QUEUE_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> "jdd_agent");
        properties.add("spring.datasource.password", () -> System.getenv("JDD_QUEUE_TEST_DB_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired ControlledModel model;
    @LocalServerPort int port;

    @Test void saturatedWorkerExpiresWaitingRequestWithoutCallingModelAndKeepsReplayIdentity() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("jdd_agent_queue_test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Integer.class))
                .as("Prepare the dedicated synthetic database before starting the test").isZero();
        try (var client = HttpClient.newHttpClient()) {
            var first = input("running-" + UUID.randomUUID(), "first", null);
            String runningId = post(client, first).path("investigationId").asText();
            assertThat(model.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var waiting = input("waiting-" + UUID.randomUUID(), "same-key", null);
            String waitingId = post(client, waiting).path("investigationId").asText();
            var expired = await(client, waitingId, "FAILED");
            assertThat(expired.path("error").path("code").asText()).isEqualTo("INVESTIGATION_TIMEOUT");
            assertThat(expired.path("error").path("message").asText()).contains("대기");
            assertThat(expired.path("error").path("retryable").asBoolean()).isFalse();
            assertThat(expired.path("progress").isEmpty()).isTrue();
            assertThat(expired.path("report").isNull()).isTrue();
            assertThat(get(client, runningId).path("status").asText()).isEqualTo("RUNNING");
            assertThat(model.calls).hasValue(1);
            for (int replay = 0; replay < 3; replay++) {
                var accepted = post(client, waiting);
                assertThat(accepted.path("investigationId").asText()).isEqualTo(waitingId);
                assertThat(accepted.path("status").asText()).isEqualTo("FAILED");
                assertThat(get(client, waitingId)).isEqualTo(expired);
            }
            assertThat(model.calls).hasValue(1);
            model.release.countDown();
            await(client, runningId, "NEEDS_INPUT");
            String newId = post(client, input(waiting.ticketId(), "explicit-new-key", waitingId)).path("investigationId").asText();
            assertThat(newId).isNotEqualTo(waitingId);
            await(client, newId, "NEEDS_INPUT");
            assertThat(model.calls).hasValue(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Integer.class)).isEqualTo(3);
            Path report = Path.of(System.getenv("JDD_QUEUE_TEST_REPORT"));
            Files.createDirectories(report.getParent());
            Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "status", "PASSED", "mode", "MOCK_MODEL_REAL_HTTP_POSTGRES_WORKER",
                    "runningInvestigationId", runningId, "expiredInvestigationId", waitingId,
                    "newInvestigationId", newId, "expiredView", expired,
                    "mockModelCalls", model.calls.get(), "paidModelCalls", 0,
                    "waitingSeconds", 2, "runningSeconds", 30)) + "\n", StandardOpenOption.CREATE_NEW);
        } finally { model.release.countDown(); }
    }

    private InvestigationInput input(String ticket, String key, String previous) {
        return new InvestigationInput("1.0", ticket, 1, key, "합성 대기 만료 검사", null, previous);
    }
    private JsonNode post(HttpClient client, InvestigationInput input) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base())).timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body());
    }
    private JsonNode get(HttpClient client, String id) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base() + "/" + id)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private JsonNode await(HttpClient client, String id, String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode view;
        do { view = get(client, id); if (view.path("status").asText().equals(status)) return view; Thread.sleep(25); }
        while (System.nanoTime() < deadline);
        throw new AssertionError("Investigation did not reach " + status + ": " + view.path("status").asText());
    }
    private String base() { return "http://127.0.0.1:" + port + "/api/investigations"; }

    static final class ControlledModel implements InvestigationModel {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private final JsonMapper json;
        ControlledModel(JsonMapper json) { this.json = json; }
        @Override public Mode mode() { return Mode.MOCK; }
        @Override public Reply next(Request request) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw InvestigationFailure.unavailable(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw InvestigationFailure.unavailable(); }
            }
            return new Reply(json.writeValueAsString(new AnalysisReport("1.0", "합성 입력 부족 결과", List.of(), List.of(), List.of(),
                    List.of(), List.of(new MissingInformation("context.orderId", "합성 검사의 주문 ID가 필요합니다.")))), List.of());
        }
    }
    @TestConfiguration static class SyntheticConfiguration {
        @Bean @Primary ControlledModel controlledModel(JsonMapper json) { return new ControlledModel(json); }
    }
}
