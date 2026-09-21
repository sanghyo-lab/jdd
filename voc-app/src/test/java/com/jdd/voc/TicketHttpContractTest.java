package com.jdd.voc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.create-schemas=true", "jdd.build-id=ticket-contract-test", "jdd.commit-sha=test"
})
class TicketHttpContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("VOC_TEST_DB_URL");
        // The external mode clears fixtures; never accept an application database here.
        if (url != null && !url.matches("jdbc:postgresql://[^/]+/jdd_voc_contract_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("VOC_TEST_DB_URL must name the isolated jdd_voc_contract_test database");
        }
        properties.add("spring.datasource.url", () -> url == null
                ? "jdbc:h2:mem:ticket_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        properties.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("VOC_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> url == null ? "" : System.getenv("VOC_TEST_DB_PASSWORD"));
    }

    @BeforeEach void clearFixtures() { jdbc.update("DELETE FROM voc.tickets"); }

    @Test void createsPersistentTicketAndReturnsAssigneesAndEmptyAnalysisHistory() throws Exception {
        JsonNode assignees = call("GET", "/api/assignees", null, 200).get("items");
        assertThat(assignees.size()).isEqualTo(3);
        assertThat(assignees.get(1).get("id").asText()).isEqualTo("areum");
        assertThat(assignees.get(1).get("displayName").asText()).isEqualTo("김아름");
        JsonNode ticket = create(Map.of("title", "주문 확인", "message", "결제 후 주문을 확인할 수 없습니다."));
        String id = ticket.get("ticketId").asText();
        assertThat(ticket.get("version").asLong()).isEqualTo(1);
        assertThat(ticket.get("status").asText()).isEqualTo("OPEN");
        assertThat(ticket.get("context").size()).isZero();
        assertThat(ticket.get("assigneeId").isNull()).isTrue();
        assertThat(Instant.parse(ticket.get("createdAt").asText())).isEqualTo(Instant.parse(ticket.get("updatedAt").asText()));
        assertThat(jdbc.queryForObject("SELECT message FROM voc.tickets WHERE ticket_id=?", String.class, id))
                .isEqualTo("결제 후 주문을 확인할 수 없습니다.");
        JsonNode detail = call("GET", "/api/tickets/" + id, null, 200);
        assertThat(detail.get("ticket")).isEqualTo(ticket);
        assertThat(detail.get("analyses").isArray()).isTrue();
        assertThat(detail.get("analyses").size()).isZero();
    }

    @Test void patchPreservesOmittedFieldsClearsAssignmentAndReplacesContext() throws Exception {
        JsonNode original = create(Map.of("title", "재고 문의", "message", "상품 수량을 확인해 주세요.",
                "assigneeId", "areum", "context", Map.of("orderId", "order-1", "productId", "product-1",
                        "occurredAt", "2026-09-21T12:34:56.123456789+09:00")));
        String path = "/api/tickets/" + original.get("ticketId").asText();
        assertThat(original.get("context").get("occurredAt").asText()).isEqualTo("2026-09-21T03:34:56.123456789Z");
        assertThat(call("GET", path, null, 200).get("ticket")).isEqualTo(original);
        JsonNode patched = call("PATCH", path,
                "{\"expectedVersion\":1,\"status\":\"RESOLVED\",\"assigneeId\":null,\"context\":{\"customerId\":\"customer-1\",\"orderId\":null}}", 200);
        assertThat(patched.get("version").asLong()).isEqualTo(2);
        assertThat(patched.get("title")).isEqualTo(original.get("title"));
        assertThat(patched.get("message")).isEqualTo(original.get("message"));
        assertThat(patched.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(patched.get("assigneeId").isNull()).isTrue();
        assertThat(patched.get("context").size()).isEqualTo(1);
        assertThat(patched.get("context").get("customerId").asText()).isEqualTo("customer-1");
        patched = call("PATCH", path, "{\"expectedVersion\":2,\"context\":{},\"status\":\"OPEN\"}", 200);
        assertThat(patched.get("version").asLong()).isEqualTo(3);
        assertThat(patched.get("context").size()).isZero();
        assertThat(patched.get("status").asText()).isEqualTo("OPEN");
        assertThat(call("GET", path, null, 200).get("ticket")).isEqualTo(patched);
    }

    @Test void concurrentPatchesWithSameVersionHaveExactlyOneWinner() throws Exception {
        String id = create(Map.of("title", "동시 수정", "message", "원본 문의")).get("ticketId").asText();
        HttpRequest first = request("PATCH", "/api/tickets/" + id, "{\"expectedVersion\":1,\"title\":\"수정 A\"}");
        HttpRequest second = request("PATCH", "/api/tickets/" + id, "{\"expectedVersion\":1,\"title\":\"수정 B\"}");
        var a = client.sendAsync(first, HttpResponse.BodyHandlers.ofString());
        var b = client.sendAsync(second, HttpResponse.BodyHandlers.ofString());
        var responses = List.of(a.join(), b.join());
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
        var conflict = responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat(JSON.readTree(conflict.body()).get("code").asText()).isEqualTo("TICKET_VERSION_CONFLICT");
        JsonNode winner = JSON.readTree(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow().body());
        assertThat(winner.get("version").asLong()).isEqualTo(2);
        assertThat(call("GET", "/api/tickets/" + id, null, 200).get("ticket")).isEqualTo(winner);
        assertThat(jdbc.queryForObject("SELECT version FROM voc.tickets WHERE ticket_id=?", Long.class, id)).isEqualTo(2);
    }

    @Test void filtersAndPaginatesWithStableIdTieBreaker() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create(Map.of("title", "목록 " + i, "message", "문의", "assigneeId", "areum"))
                    .get("ticketId").asText());
        }
        jdbc.update("UPDATE voc.tickets SET created_at=?", Timestamp.from(Instant.parse("2026-09-21T00:00:00Z")));
        ids.sort(java.util.Comparator.reverseOrder());
        JsonNode page = call("GET", "/api/tickets?status=OPEN&assigneeId=areum&limit=1&offset=1", null, 200).get("items");
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.get(0).get("ticketId").asText()).isEqualTo(ids.get(1));
        call("PATCH", "/api/tickets/" + ids.get(0), "{\"expectedVersion\":1,\"status\":\"IN_PROGRESS\"}", 200);
        assertThat(call("GET", "/api/tickets?status=IN_PROGRESS&assigneeId=areum", null, 200).get("items").size()).isEqualTo(1);
        assertThat(call("GET", "/api/tickets?assigneeId=jaehong", null, 200).get("items").size()).isZero();
        assertThat(call("GET", "/api/tickets?offset=10", null, 200).get("items").size()).isZero();
    }

    @Test void rejectsInvalidInputAndStaleVersionsWithoutChangingStoredTicket() throws Exception {
        for (String invalid : List.of("{}", "null", "{", "[]",
                "{\"title\":\" \",\"message\":\"문의\"}",
                "{\"title\":\"문의\",\"message\":null}",
                "{\"title\":\"문의\",\"message\":\"본문\",\"assigneeId\":\"unknown\"}",
                "{\"title\":\"문의\",\"message\":\"본문\",\"context\":{\"occurredAt\":\"yesterday\"}}",
                JSON.writeValueAsString(Map.of("title", "가".repeat(201), "message", "본문")),
                JSON.writeValueAsString(Map.of("title", "문의", "message", "가".repeat(10001))))) {
            assertThat(call("POST", "/api/tickets", invalid, 400).get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        JsonNode original = create(Map.of("title", "변경 금지", "message", "본문"));
        String path = "/api/tickets/" + original.get("ticketId").asText();
        for (String invalid : List.of("{\"expectedVersion\":1}",
                "{\"expectedVersion\":1,\"title\":null}", "{\"expectedVersion\":1,\"message\":null}",
                "{\"expectedVersion\":1,\"context\":null}", "{\"expectedVersion\":1,\"status\":null}",
                "{\"expectedVersion\":1.0,\"status\":\"OPEN\"}", "{\"expectedVersion\":\"1\",\"status\":\"OPEN\"}",
                "{\"expectedVersion\":0,\"status\":\"OPEN\"}", "{\"expectedVersion\":1,\"status\":\"COMPLETED\"}")) {
            assertThat(call("PATCH", path, invalid, 400).get("retryable").asBoolean()).isFalse();
        }
        assertThat(call("PATCH", path, "{\"expectedVersion\":2,\"title\":\"늦은 수정\"}", 409).get("code").asText())
                .isEqualTo("TICKET_VERSION_CONFLICT");
        assertThat(call("GET", path, null, 200).get("ticket")).isEqualTo(original);
        for (String query : List.of("limit=0", "limit=101", "limit=text", "offset=-1", "status=UNKNOWN", "assigneeId=unknown")) {
            assertThat(call("GET", "/api/tickets?" + query, null, 400).get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(call("GET", "/api/tickets/missing", null, 404).get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void frameworkRequestErrorsHaveClientStatusesAndDoNotCreateTickets() throws Exception {
        assertThat(call("DELETE", "/api/tickets", null, 405).path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(call("GET", "/api/missing-route", null, 404).path("code").asText()).isEqualTo("NOT_FOUND");
        var unsupported = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/api/tickets"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"문의\",\"message\":\"본문\"}")).build();
        var response = client.send(unsupported, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(JSON.readTree(response.body()).path("retryable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voc.tickets", Long.class)).isZero();
    }

    @Test void optionalIdentifiersRejectBlankTextAndPreserveMeaningfulCharacters() throws Exception {
        var original = create(Map.of("title", "식별자 검증", "message", "문의"));
        String path = "/api/tickets/" + original.get("ticketId").asText();
        for (String key : List.of("customerId", "orderId", "productId", "requestId", "checkoutKey")) {
            for (String blank : List.of("", " ", "\t\n", "\u2003")) {
                var context = Map.of(key, blank);
                var createBody = Map.of("title", "식별자 검증", "message", "문의", "context", context);
                var error = call("POST", "/api/tickets", JSON.writeValueAsString(createBody), 400);
                assertThat(error.get("code").asText()).isEqualTo("INVALID_REQUEST");
                assertThat(error.get("retryable").asBoolean()).isFalse();
                var patchBody = Map.of("expectedVersion", 1, "context", context);
                assertThat(call("PATCH", path, JSON.writeValueAsString(patchBody), 400).get("code").asText())
                        .isEqualTo("INVALID_REQUEST");
            }
            assertThat(call("GET", path, null, 200).get("ticket")).isEqualTo(original);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voc.tickets", Integer.class)).isEqualTo(1);
        Map<String, Object> optional = new java.util.LinkedHashMap<>();
        optional.put("orderId", null);
        optional.put("productId", " product-한글 ");
        var valid = create(Map.of("title", "보존", "message", "문의", "context", optional));
        assertThat(valid.get("context").size()).isEqualTo(1);
        assertThat(valid.get("context").get("productId").asText()).isEqualTo(" product-한글 ");
    }

    private JsonNode create(Map<String, ?> body) throws Exception {
        return call("POST", "/api/tickets", JSON.writeValueAsString(body), 201);
    }
    private HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private JsonNode call(String method, String path, String body, int expectedStatus) throws Exception {
        HttpResponse<String> response = client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }
}
