package com.jdd.agent;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationService;
import com.jdd.agent.infra.JdbcInvestigationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:investigations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class InvestigationApiTest {
    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void acceptsAndReadsPersistedSnapshotWithContractShape() throws Exception {
        String ticket = UUID.randomUUID().toString();
        var accepted = post(input(ticket, "first", "\"context\":{\"orderId\":\"order-1\"}"));
        assertThat(accepted.statusCode()).isEqualTo(202);
        String id = body(accepted).get("investigationId").asText();
        var view = body(get("/" + id));
        assertThat(view.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(view.get("ticketId").asText()).isEqualTo(ticket);
        assertThat(view.get("ticketVersion").asInt()).isEqualTo(1);
        assertThat(view.get("status").asText()).isEqualTo("QUEUED");
        assertThat(view.get("progress").isEmpty()).isTrue();
        assertThat(view.get("evidence").isEmpty()).isTrue();
        assertThat(view.has("report") && view.get("report").isNull()).isTrue();
        assertThat(view.has("error") && view.get("error").isNull()).isTrue();
        assertThat(Instant.parse(view.get("createdAt").asText())).isNotNull();

        // A newly constructed service and repository must load the same persisted input/view.
        var repository = new JdbcInvestigationRepository(jdbc, json, java.time.Duration.ofMinutes(10));
        var fresh = new InvestigationService(repository, Clock.systemUTC());
        assertThat(fresh.get(id).investigationId()).isEqualTo(id);
        assertThat(repository.find(id).orElseThrow().input().context().orderId()).isEqualTo("order-1");
    }

    @Test void nullAndOmittedContextFieldsAreTheSameRequest() throws Exception {
        String ticket = UUID.randomUUID().toString();
        var original = body(post(input(ticket, "normalize", "")));
        for (String extra : List.of("\"context\":null", "\"context\":{}",
                "\"context\":{\"orderId\":null,\"occurredAt\":null},\"previousInvestigationId\":null")) {
            var retry = post(input(ticket, "normalize", extra));
            assertThat(retry.statusCode()).isEqualTo(202);
            assertThat(body(retry)).isEqualTo(original);
        }
    }

    @Test void timestampOffsetsAndJsonKeyOrderDoNotChangeRequestIdentity() throws Exception {
        String ticket = UUID.randomUUID().toString();
        var first = post(input(ticket, "time", "\"context\":{\"orderId\":\"o1\",\"occurredAt\":\"2026-09-21T00:00:00Z\"}"));
        var second = post(input(ticket, "time", "\"context\":{\"occurredAt\":\"2026-09-21T09:00:00+09:00\",\"orderId\":\"o1\"}"));
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(body(second)).isEqualTo(body(first));
    }

    @Test void changedInputsConflictAndTicketsHaveIndependentKeys() throws Exception {
        String ticket = UUID.randomUUID().toString();
        String original = input(ticket, "same-key", "");
        assertThat(post(original).statusCode()).isEqualTo(202);
        for (String changed : List.of(original.replace("문의", "다른 문의"),
                original.replace("\"ticketVersion\":1", "\"ticketVersion\":2"),
                input(ticket, "same-key", "\"context\":{\"orderId\":\"new\"}"),
                input(ticket, "same-key", "\"previousInvestigationId\":\"missing\""))) {
            assertError(post(changed), 409, "REQUEST_KEY_CONFLICT");
        }
        assertThat(post(input(UUID.randomUUID().toString(), "same-key", "")).statusCode()).isEqualTo(202);
    }

    @Test void previousInvestigationMustBelongToSameTicket() throws Exception {
        String ticket = UUID.randomUUID().toString();
        String id = body(post(input(ticket, "first", ""))).get("investigationId").asText();
        String previous = "\"previousInvestigationId\":\"" + id + "\"";
        var followup = post(input(ticket, "followup", previous));
        assertThat(followup.statusCode()).isEqualTo(202);
        assertThat(body(followup).get("investigationId").asText()).isNotEqualTo(id);
        assertError(post(input("other-ticket", "key", previous)), 404, "NOT_FOUND");
        assertError(post(input(ticket, "missing", "\"previousInvestigationId\":\"absent\"")), 404, "NOT_FOUND");
    }

    @Test void concurrentSubmissionsCreateExactlyOneInvestigation() throws Exception {
        String ticket = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            Callable<String> submit = () -> {
                barrier.await();
                var result = post(input(ticket, "race", ""));
                assertThat(result.statusCode()).isEqualTo(202);
                return body(result).get("investigationId").asText();
            };
            var results = executor.invokeAll(java.util.Collections.nCopies(8, submit));
            var ids = new java.util.HashSet<String>();
            for (var result : results) ids.add(result.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent.investigations WHERE ticket_id = ?",
                Integer.class, ticket)).isEqualTo(1);
    }

    @Test void evidenceIsScopedToItsInvestigationAndReturnsStoredContent() throws Exception {
        String ticket = UUID.randomUUID().toString();
        String first = body(post(input(ticket, "first", ""))).get("investigationId").asText();
        String second = body(post(input(ticket, "second", ""))).get("investigationId").asText();
        var detail = new Investigation.EvidenceDetail("evidence-1", Investigation.EvidenceType.CODE,
                "Synthetic test fixture", Instant.parse("2026-09-21T00:00:00Z"),
                Map.of("buildId", "test", "path", "commerce-core/src/main/java/Example.java", "startLine", 1, "endLine", 1),
                "// stored source snapshot", false);
        jdbc.update("INSERT INTO agent.investigation_evidence VALUES (?, ?, ?)", first, "evidence-1", json.writeValueAsString(detail));
        var response = get("/" + first + "/evidence/evidence-1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("content").asText()).isEqualTo("// stored source snapshot");
        assertThat(body(response).get("truncated").asBoolean()).isFalse();
        assertError(get("/" + second + "/evidence/evidence-1"), 404, "NOT_FOUND");
        assertError(get("/absent"), 404, "NOT_FOUND");
        assertError(get("/absent/evidence/evidence-1"), 404, "NOT_FOUND");
    }

    @Test void malformedAndUnsupportedInputsReturnStableErrorsWithoutPayloads() throws Exception {
        String valid = input("validation-ticket", "key", "");
        for (String invalid : List.of("{}", "null", "{", valid.replace("1.0", "2.0"),
                valid.replace("\"ticketVersion\":1", "\"ticketVersion\":0"),
                valid.replace("\"ticketVersion\":1", "\"ticketVersion\":1.5"),
                valid.replace("\"ticketVersion\":1", "\"ticketVersion\":\"1\""),
                valid.replace("문의", " "), valid.replace("문의", "x".repeat(10_001)),
                input("validation-ticket", "key", "\"context\":{\"occurredAt\":\"secret-invalid-date\"}"),
                input("validation-ticket", "key", "\"context\":{\"orderId\":7}"),
                input("validation-ticket", "key", "\"context\":{\"orderId\":1.5}"),
                input("validation-ticket", "key", "\"context\":{\"orderId\":true}"),
                valid.replace("\"requestKey\":\"key\"", "\"requestKey\":7"),
                input("validation-ticket", "key", "\"context\":{\"occurredAt\":1789948800}"),
                input("validation-ticket", "key", "\"context\":{\"defectId\":\"answer\"}"))) {
            var response = post(invalid);
            assertThat(response.statusCode()).as("Rejected input: %s", invalid).isEqualTo(400);
            assertError(response, 400, "INVALID_REQUEST");
            assertThat(response.body()).doesNotContain("secret-invalid-date");
        }
    }

    @Test void unsupportedMediaTypeDoesNotCreateAnInvestigationOrReserveACall() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Long.class);
        long calls = jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class);
        var response = http.send(HttpRequest.newBuilder(uri("")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(input(UUID.randomUUID().toString(), "media", ""))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(response, 415, "INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class)).isEqualTo(calls);
    }

    @Test void unmappedMethodsAndPathsKeepTheErrorContractWithoutCreatingWork() throws Exception {
        long investigations = jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Long.class);
        long calls = jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class);
        var unsupported = http.send(HttpRequest.newBuilder(uri("")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(body(unsupported).hasNonNull("code")).as(unsupported.body()).isTrue();
        assertError(unsupported, 405, "INVALID_REQUEST");
        var absent = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/missing-route"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(body(absent).hasNonNull("code")).as(absent.body()).isTrue();
        assertError(absent, 404, "NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.investigations", Long.class)).isEqualTo(investigations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Long.class)).isEqualTo(calls);
    }

    private String input(String ticket, String key, String extra) {
        return "{\"schemaVersion\":\"1.0\",\"ticketId\":\"" + ticket + "\",\"ticketVersion\":1,"
                + "\"requestKey\":\"" + key + "\",\"message\":\"문의\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String suffix) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(suffix)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String suffix) { return URI.create("http://127.0.0.1:" + port + "/api/investigations" + suffix); }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(body(response).get("code").asText()).isEqualTo(code);
        assertThat(body(response).get("retryable").asBoolean()).isFalse();
    }
}
