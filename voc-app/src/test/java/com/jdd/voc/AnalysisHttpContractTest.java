package com.jdd.voc;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.create-schemas=true", "jdd.build-id=analysis-contract-test", "jdd.commit-sha=test"
})
@Import(AnalysisHttpContractTest.FixedClock.class)
class AnalysisHttpContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-09-21T00:00:00.123456789Z"), ZoneOffset.UTC); }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("VOC_TEST_DB_URL");
        if (url != null && !url.matches("jdbc:postgresql://[^/]+/jdd_voc_contract_test(?:\\?.*)?"))
            throw new IllegalArgumentException("VOC_TEST_DB_URL must name the isolated jdd_voc_contract_test database");
        properties.add("spring.datasource.url", () -> url == null
                ? "jdbc:h2:mem:analysis_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        properties.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("VOC_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> url == null ? "" : System.getenv("VOC_TEST_DB_PASSWORD"));
    }

    @BeforeEach void clearFixtures() {
        jdbc.update("DELETE FROM voc.analysis_requests");
        jdbc.update("DELETE FROM voc.tickets");
    }
    @AfterAll static void closeHttp() { CLIENT.close(); }

    @Test void acceptsOnlyAfterPersistingImmutableInputAndReturnsTheStoredView() throws Exception {
        var ticket = createTicket("원래 문의", Map.of("orderId", " order-한글 ", "occurredAt", "2026-09-21T09:00:00.123456789+09:00"));
        String path = analyses(ticket);
        var response = CLIENT.send(request("POST", path, body("같은 키 / +", 1)), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        var analysis = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains(path + "/" + id(analysis));
        assertThat(analysis.path("submissionStatus").asText()).isEqualTo("PENDING");
        for (String nullable : List.of("investigationId", "investigation", "submissionError", "syncError", "lastSyncedAt"))
            assertThat(analysis.has(nullable) && analysis.get(nullable).isNull()).as(nullable).isTrue();
        var input = analysis.path("input");
        assertThat(input.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(input.path("ticketId")).isEqualTo(ticket.path("ticketId"));
        assertThat(input.path("ticketVersion").asLong()).isEqualTo(1);
        assertThat(input.path("requestKey").asText()).isEqualTo("같은 키 / +");
        assertThat(input.path("message").asText()).isEqualTo("원래 문의");
        assertThat(input.path("context")).isEqualTo(ticket.path("context"));
        assertThat(input.path("previousInvestigationId").isNull()).isTrue();
        assertThat(analysis.path("createdAt").asText()).isEqualTo("2026-09-21T00:00:00.123456Z");
        assertThat(JSON.readTree(jdbc.queryForObject("SELECT input_json FROM voc.analysis_requests WHERE analysis_request_id=?",
                String.class, id(analysis)))).isEqualTo(input);
        assertThat(call("GET", path + "/" + id(analysis), null, 200)).isEqualTo(analysis);
        assertThat(call("GET", ticketPath(ticket), null, 200).path("ticket")).isEqualTo(ticket);
    }

    @Test void sameKeyKeepsOldSnapshotAfterTicketChangesAndChecksKeyConflictBeforeVersion() throws Exception {
        var ticket = createTicket("처음 문의", Map.of("orderId", "old"));
        var first = call("POST", analyses(ticket), body("retry-key", 1), 202);
        call("PATCH", ticketPath(ticket), JSON.writeValueAsString(Map.of("expectedVersion", 1, "message", "수정 문의",
                "context", Map.of("orderId", "new"))), 200);
        assertThat(call("POST", analyses(ticket), "{\"requestKey\":\"retry-key\",\"ticketVersion\":1,\"previousInvestigationId\":null}", 202)).isEqualTo(first);
        assertError(call("POST", analyses(ticket), body("retry-key", 2), 409), "REQUEST_KEY_CONFLICT");
        assertError(call("POST", analyses(ticket), "{\"requestKey\":\"retry-key\",\"ticketVersion\":1,\"previousInvestigationId\":\"unknown\"}", 409), "REQUEST_KEY_CONFLICT");
        assertError(call("POST", analyses(ticket), body("new-key", 1), 409), "TICKET_VERSION_CONFLICT");
        var second = call("POST", analyses(ticket), body("new-key", 2), 202);
        assertThat(second.path("input").path("message").asText()).isEqualTo("수정 문의");
        assertThat(second.path("input").path("context").path("orderId").asText()).isEqualTo("new");
        assertThat(call("GET", analyses(ticket) + "/" + id(first), null, 200)).isEqualTo(first);
        assertThat(count()).isEqualTo(2);
        // Fixed time exercises the documented ID tie-break, independently of machine clock resolution.
        var history = call("GET", ticketPath(ticket), null, 200).path("analyses");
        var expected = List.of(id(first), id(second)).stream().sorted(Comparator.reverseOrder()).toList();
        assertThat(history.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(history.get(i).path("analysisRequestId").asText()).isEqualTo(expected.get(i));
            assertThat(history.get(i).path("investigationStatus").isNull()).isTrue();
            assertThat(history.get(i).has("input")).isFalse();
        }
    }

    @Test void concurrentSameKeyCreatesOneDurableRequest() throws Exception {
        var ticket = createTicket("동시 요청", Map.of());
        var requests = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 8; i++)
            requests.add(CLIENT.sendAsync(request("POST", analyses(ticket), body("shared-key", 1)), HttpResponse.BodyHandlers.ofString()));
        JsonNode first = null;
        for (var pending : requests) {
            var response = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            var result = JSON.readTree(response.body());
            if (first == null) first = result;
            assertThat(result).isEqualTo(first);
        }
        assertThat(count()).isEqualTo(1);
    }

    @Test void snapshotAndConcurrentPatchNeverMixTicketVersions() throws Exception {
        for (int i = 0; i < 12; i++) {
            var ticket = createTicket("old-" + i, Map.of("orderId", "old-" + i));
            var create = CLIENT.sendAsync(request("POST", analyses(ticket), body("race-key", 1)), HttpResponse.BodyHandlers.ofString());
            var patch = CLIENT.sendAsync(request("PATCH", ticketPath(ticket), JSON.writeValueAsString(Map.of(
                    "expectedVersion", 1, "message", "new-" + i, "context", Map.of("orderId", "new-" + i)))), HttpResponse.BodyHandlers.ofString());
            assertThat(patch.get().statusCode()).isEqualTo(200);
            var response = create.get();
            var result = JSON.readTree(response.body());
            if (response.statusCode() == 202) {
                assertThat(result.path("ticketVersion").asLong()).isEqualTo(1);
                assertThat(result.path("input").path("message").asText()).isEqualTo("old-" + i);
                assertThat(result.path("input").path("context").path("orderId").asText()).isEqualTo("old-" + i);
            } else {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                assertError(result, "TICKET_VERSION_CONFLICT");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM voc.analysis_requests WHERE ticket_id=?", Integer.class,
                        ticket.path("ticketId").asText())).isZero();
            }
        }
    }

    @Test void previousInvestigationMustBelongToTheTicketAndHistoryRemainsUnchanged() throws Exception {
        var ticket = createTicket("첫 조사", Map.of());
        var other = createTicket("다른 티켓", Map.of());
        var initial = call("POST", analyses(ticket), body("first", 1), 202);
        String investigationId = markSubmittedFailed(ticket, initial);
        var saved = call("GET", analyses(ticket) + "/" + id(initial), null, 200);
        assertThat(call("POST", analyses(ticket), body("first", 1), 202)).isEqualTo(saved);
        assertError(call("GET", analyses(other) + "/" + id(initial), null, 404), "NOT_FOUND");
        assertError(call("POST", analyses(other), withPrevious("next", 1, investigationId), 404), "NOT_FOUND");
        assertError(call("POST", analyses(ticket), withPrevious("next", 1, "missing"), 404), "NOT_FOUND");
        call("PATCH", ticketPath(ticket), "{\"expectedVersion\":1,\"message\":\"보완 입력\"}", 200);
        var next = call("POST", analyses(ticket), withPrevious("next", 2, investigationId), 202);
        assertThat(next.path("input").path("previousInvestigationId").asText()).isEqualTo(investigationId);
        assertThat(next.path("input").path("message").asText()).isEqualTo("보완 입력");
        assertThat(call("GET", analyses(ticket) + "/" + id(initial), null, 200)).isEqualTo(saved);
        assertThat(call("GET", ticketPath(ticket), null, 200).path("ticket").path("status").asText()).isEqualTo("OPEN");
        var separate = call("POST", analyses(other), body("first", 1), 202);
        assertThat(id(separate)).isNotEqualTo(id(initial));
        assertThat(call("GET", ticketPath(other), null, 200).path("analyses").size()).isEqualTo(1);
    }

    @Test void manualDeliveryRetryReusesOnlyRetryableFailedRequests() throws Exception {
        var ticket = createTicket("재전송", Map.of());
        var first = call("POST", analyses(ticket), body("same", 1), 202);
        setDeliveryError(id(first), "AGENT_UNAVAILABLE", true);
        call("PATCH", ticketPath(ticket), "{\"expectedVersion\":1,\"message\":\"변경됨\"}", 200);
        var requests = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 4; i++)
            requests.add(CLIENT.sendAsync(request("POST", analyses(ticket), body("same", 1)), HttpResponse.BodyHandlers.ofString()));
        for (var future : requests) {
            var response = future.get();
            assertThat(response.statusCode()).isEqualTo(202);
            var retried = JSON.readTree(response.body());
            assertThat(id(retried)).isEqualTo(id(first));
            assertThat(retried.path("input")).isEqualTo(first.path("input"));
            assertThat(retried.path("submissionStatus").asText()).isEqualTo("PENDING");
            assertThat(retried.path("submissionError").isNull()).isTrue();
        }
        setDeliveryError(id(first), "INVALID_REQUEST", false);
        var failed = call("GET", analyses(ticket) + "/" + id(first), null, 200);
        assertThat(call("POST", analyses(ticket), body("same", 1), 202)).isEqualTo(failed);
        assertThat(count()).isEqualTo(1);
    }

    @Test void invalidRequestsAndUnknownTicketsDoNotCreateRecords() throws Exception {
        var ticket = createTicket("유효성", Map.of());
        for (String invalid : List.of("{}", "null", "[]", "{\"requestKey\":null,\"ticketVersion\":1}",
                "{\"requestKey\":\" \",\"ticketVersion\":1}", "{\"requestKey\":4,\"ticketVersion\":1}",
                "{\"requestKey\":\"key\",\"ticketVersion\":1.0}", "{\"requestKey\":\"key\",\"ticketVersion\":0}",
                "{\"requestKey\":\"key\",\"ticketVersion\":\"1\"}", "{\"requestKey\":\"key\",\"ticketVersion\":true}",
                "{\"requestKey\":\"key\",\"ticketVersion\":1,\"previousInvestigationId\":\"\"}",
                "{\"requestKey\":\"key\",\"ticketVersion\":1,\"previousInvestigationId\":2}",
                "{\"requestKey\":\"key\",\"ticketVersion\":1,\"message\":\"override\"}"))
            assertError(call("POST", analyses(ticket), invalid, 400), "INVALID_REQUEST");
        assertError(call("POST", "/api/tickets/missing/analyses", body("key", 1), 404), "NOT_FOUND");
        assertError(call("GET", analyses(ticket) + "/missing", null, 404), "NOT_FOUND");
        assertThat(count()).isZero();
    }

    @Test void lostClientResponseIsRecoveredFromStorageWithTheSameKey() throws Exception {
        var ticket = createTicket("응답 유실", Map.of());
        byte[] payload = body("lost-response", 1).getBytes(StandardCharsets.UTF_8);
        try (var socket = new Socket("127.0.0.1", port())) {
            var output = socket.getOutputStream();
            output.write(("POST " + analyses(ticket) + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(payload); output.flush();
            // Wait for commit without reading the response; the client then disappears.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count()).isEqualTo(1));
        }
        String savedId = jdbc.queryForObject("SELECT analysis_request_id FROM voc.analysis_requests WHERE request_key='lost-response'", String.class);
        var recovered = call("POST", analyses(ticket), body("lost-response", 1), 202);
        assertThat(id(recovered)).isEqualTo(savedId);
        assertThat(call("GET", analyses(ticket) + "/" + savedId, null, 200)).isEqualTo(recovered);
        assertThat(count()).isEqualTo(1);
    }

    private void setDeliveryError(String id, String code, boolean retryable) {
        // Synthetic delivery outcome, not a claim that an Agent worker has run.
        jdbc.update("UPDATE voc.analysis_requests SET submission_status='FAILED', submission_error_json=? WHERE analysis_request_id=?",
                JSON.writeValueAsString(Map.of("code", code, "message", "합성 전달 오류", "retryable", retryable)), id);
    }
    private String markSubmittedFailed(JsonNode ticket, JsonNode analysis) {
        String investigationId = UUID.randomUUID().toString();
        var view = new LinkedHashMap<String, Object>();
        view.put("schemaVersion", "1.0"); view.put("investigationId", investigationId);
        view.put("ticketId", ticket.path("ticketId").asText()); view.put("ticketVersion", 1);
        view.put("status", "FAILED"); view.put("createdAt", analysis.path("createdAt").asText());
        view.put("updatedAt", analysis.path("updatedAt").asText()); view.put("progress", List.of());
        view.put("evidence", List.of()); view.put("report", null);
        view.put("error", Map.of("code", "LLM_CONFIGURATION_ERROR", "message", "합성 조사 결과", "retryable", false));
        jdbc.update("UPDATE voc.analysis_requests SET submission_status='SUBMITTED', investigation_id=?, investigation_json=? WHERE analysis_request_id=?",
                investigationId, JSON.writeValueAsString(view), id(analysis));
        return investigationId;
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM voc.analysis_requests", Integer.class); }
    private int port() { return Integer.parseInt(environment.getRequiredProperty("local.server.port")); }
    private String id(JsonNode analysis) { return analysis.path("analysisRequestId").asText(); }
    private String ticketPath(JsonNode ticket) { return "/api/tickets/" + ticket.path("ticketId").asText(); }
    private String analyses(JsonNode ticket) { return ticketPath(ticket) + "/analyses"; }
    private String body(String key, long version) { return JSON.writeValueAsString(Map.of("requestKey", key, "ticketVersion", version)); }
    private String withPrevious(String key, long version, String previous) {
        return JSON.writeValueAsString(Map.of("requestKey", key, "ticketVersion", version, "previousInvestigationId", previous));
    }
    private void assertError(JsonNode error, String code) {
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("retryable").asBoolean()).isFalse();
    }
    private JsonNode createTicket(String message, Map<String, String> context) throws Exception {
        return call("POST", "/api/tickets", JSON.writeValueAsString(Map.of("title", "합성 문의", "message", message, "context", context)), 201);
    }
    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private JsonNode call(String method, String path, String body, int status) throws Exception {
        var response = CLIENT.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
}
