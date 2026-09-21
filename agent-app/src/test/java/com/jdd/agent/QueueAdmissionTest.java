package com.jdd.agent;

import com.jdd.agent.domain.InvestigationExecutionRepository;
import com.jdd.agent.domain.InvestigationException;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationService;
import com.jdd.agent.infra.JdbcInvestigationRepository;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP and optional isolated PostgreSQL. The worker is off, so no model runs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=false", "jdd.agent.queue.capacity=3",
    "spring.datasource.url=jdbc:h2:mem:queue-admission;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class QueueAdmissionTest {
    @DynamicPropertySource static void dedicatedPostgres(DynamicPropertyRegistry properties) {
        String url = System.getenv("JDD_ADMISSION_TEST_DB_URL");
        if (url == null) return;
        if (!url.matches("jdbc:postgresql://[^/]+/jdd_agent_admission_test(?:\\?.*)?"))
            throw new IllegalStateException("Admission tests require their dedicated disposable database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("JDD_ADMISSION_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("JDD_ADMISSION_TEST_DB_PASSWORD"));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired InvestigationExecutionRepository execution;
    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();

    @BeforeEach void clearOnlyTheDedicatedDatabase() {
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        if (!url.startsWith("jdbc:h2:mem:queue-admission") && !url.matches("jdbc:postgresql://[^/]+/jdd_agent_admission_test(?:\\?.*)?"))
            throw new IllegalStateException("Refusing to clear application data");
        assertNoModelCalls();
        jdbc.update("DELETE FROM agent.investigation_evidence");
        jdbc.update("DELETE FROM agent.investigations");
    }
    @AfterEach void recordActualResponses(TestInfo info) throws Exception {
        String directory = System.getenv("JDD_ADMISSION_TEST_REPORT_DIR");
        if (directory == null) return;
        var target = Path.of(directory).resolve(info.getTestMethod().orElseThrow().getName() + ".json");
        Files.writeString(target, json.writeValueAsString(Map.of("responses", observations,
                "stored", jdbc.queryForList("SELECT investigation_id, ticket_id, request_key, execution_status, queued_deadline_at FROM agent.investigations ORDER BY ticket_id"),
                "modelCalls", count("model_calls"), "oauthCalls", count("oauth_model_calls"))), StandardOpenOption.CREATE_NEW);
    }

    @Test void concurrentNewKeysCannotExceedCapacityAndRejectedRequestsLeaveNoRows() throws Exception {
        var payloads = new ArrayList<String>();
        for (int i = 0; i < 16; i++) payloads.add(input("new-" + i, "same-key", "문의"));
        var responses = concurrent(payloads);
        assertThat(responses.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(3);
        for (int i = 0; i < responses.size(); i++) {
            var response = responses.get(i);
            if (response.statusCode() == 202) continue;
            assertFull(response);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations WHERE ticket_id=?", Integer.class, "new-" + i)).isZero();
        }
        assertThat(count("investigations")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations WHERE execution_status='QUEUED'", Integer.class)).isEqualTo(3);
        assertNoModelCalls();
    }

    @Test void sameKeyRaceUsesOneRemainingSlotAndConflictsStillWinOverFullQueue() throws Exception {
        assertThat(post(input("existing-a", "key", "문의")).statusCode()).isEqualTo(202);
        assertThat(post(input("existing-b", "key", "문의")).statusCode()).isEqualTo(202);
        String original = input("same-ticket", "race-key", "문의");
        var responses = concurrent(Collections.nCopies(16, original));
        var ids = new HashSet<String>();
        for (var response : responses) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            ids.add(body(response).path("investigationId").asText());
        }
        assertThat(ids).hasSize(1);
        assertThat(count("investigations")).isEqualTo(3);
        assertThat(body(post(original)).path("investigationId").asText()).isEqualTo(ids.iterator().next());
        var conflict = post(input("same-ticket", "race-key", "다른 문의"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(body(conflict).path("code").asText()).isEqualTo("REQUEST_KEY_CONFLICT");
        assertThat(body(conflict).path("retryable").asBoolean()).isFalse();
        assertFull(post(input("different-ticket", "new-key", "문의")));
        assertThat(post(input("invalid-ticket", "key", " ")).statusCode()).isEqualTo(400);
        assertThat(count("investigations")).isEqualTo(3);
        assertNoModelCalls();
    }

    @Test void claimAndWaitingExpiryReleaseCapacityWithoutChangingOldRequestIdentity() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post(input("queued-" + i, "key", "문의")).statusCode()).isEqualTo(202);
        var running = execution.claimNext(Instant.now(), Duration.ofMinutes(3)).orElseThrow();
        assertThat(post(input("after-claim", "key", "문의")).statusCode()).isEqualTo(202);
        assertFull(post(input("still-full", "key", "문의")));
        jdbc.update("UPDATE agent.investigations SET queued_deadline_at=? WHERE execution_status='QUEUED'",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThat(execution.expireQueued(Instant.now())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT execution_status FROM agent.investigations WHERE investigation_id=?", String.class,
                running.investigationId())).isEqualTo("RUNNING");
        var old = body(post(input("after-claim", "key", "문의")));
        assertThat(old.path("status").asText()).isEqualTo("FAILED");
        String oldId = old.path("investigationId").asText();
        var view = http.send(HttpRequest.newBuilder(uri("/" + oldId)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(body(view).path("error").path("code").asText()).isEqualTo("INVESTIGATION_TIMEOUT");
        assertThat(post(input("after-expiry", "key", "문의")).statusCode()).isEqualTo(202);
        assertThat(body(post(input("after-claim", "key", "문의"))).path("investigationId").asText()).isEqualTo(oldId);
        assertNoModelCalls();
    }

    @Test void capacityBoundsAndLoweredConfigurationPreserveAcceptedRequests() {
        for (int invalid : List.of(-1, 0, 101)) {
            assertThatThrownBy(() -> new JdbcInvestigationRepository(jdbc, json, Duration.ofMinutes(10), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int capacity : List.of(1, 100)) {
            jdbc.update("DELETE FROM agent.investigations");
            var service = new InvestigationService(new JdbcInvestigationRepository(jdbc, json, Duration.ofMinutes(10), capacity), Clock.systemUTC());
            var first = new InvestigationInput("1.0", "boundary-0", 1, "key", "문의", null, null);
            String firstId = service.submit(first).investigationId();
            for (int i = 1; i < capacity; i++)
                service.submit(new InvestigationInput("1.0", "boundary-" + i, 1, "key", "문의", null, null));
            assertThat(count("investigations")).isEqualTo(capacity);
            var smaller = new InvestigationService(new JdbcInvestigationRepository(jdbc, json, Duration.ofMinutes(10), 1), Clock.systemUTC());
            assertThat(smaller.submit(first).investigationId()).isEqualTo(firstId);
            var newInput = new InvestigationInput("1.0", "extra", 1, "key", "문의", null, null);
            for (var current : List.of(service, smaller)) {
                assertThatThrownBy(() -> current.submit(newInput)).isInstanceOf(InvestigationException.class)
                        .satisfies(failure -> assertThat(((InvestigationException) failure).code()).isEqualTo("INVESTIGATION_QUEUE_FULL"));
            }
            assertThat(count("investigations")).isEqualTo(capacity);
        }
        assertNoModelCalls();
    }

    List<HttpResponse<String>> concurrent(List<String> payloads) throws Exception {
        var barrier = new CyclicBarrier(payloads.size());
        try (var pool = Executors.newFixedThreadPool(payloads.size())) {
            var tasks = new ArrayList<Callable<HttpResponse<String>>>();
            for (String payload : payloads) tasks.add(() -> { barrier.await(5, TimeUnit.SECONDS); return post(payload); });
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : pool.invokeAll(tasks, 20, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        }
    }
    String input(String ticket, String key, String message) {
        return json.writeValueAsString(Map.of("schemaVersion", "1.0", "ticketId", ticket,
                "ticketVersion", 1, "requestKey", key, "message", message));
    }
    HttpResponse<String> post(String payload) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri("")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        observations.add(Map.of("input", json.readTree(payload), "status", response.statusCode(),
                "retryAfter", response.headers().firstValue("Retry-After").orElse(""), "body", body(response)));
        return response;
    }
    void assertFull(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        assertThat(body(response).path("code").asText()).isEqualTo("INVESTIGATION_QUEUE_FULL");
        assertThat(body(response).path("retryable").asBoolean()).isTrue();
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }
    void assertNoModelCalls() {
        assertThat(count("model_calls")).isZero();
        assertThat(count("oauth_model_calls")).isZero();
        assertThat(count("demo_budget")).isZero();
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM agent." + table, Integer.class); }
    URI uri(String suffix) { return URI.create("http://127.0.0.1:" + port + "/api/investigations" + suffix); }
    JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
}
