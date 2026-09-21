package com.jdd.commerce;

import com.jdd.commerce.common.BusinessEvents;
import com.jdd.commerce.logging.JsonBusinessEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:commerce-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true",
    "jdd.build-id=http-test", "jdd.commerce-log-root=build/test-evidence", "jdd.log-publish-delay-ms=60000"
})
class CommerceHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonBusinessEvents events;
    @Autowired PlatformTransactionManager transactions;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void seed() {
        for (String table : List.of("event_outbox", "order_operations", "inventory_movements", "coupon_usages",
                "refunds", "payments", "order_items", "orders", "customer_coupons", "coupons", "product_stock", "products")) {
            jdbc.update("DELETE FROM commerce." + table);
        }
        jdbc.update("INSERT INTO commerce.products VALUES ('p1','상품',50000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "('p2','보조 상품',60000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO commerce.product_stock VALUES ('p1',3,CURRENT_TIMESTAMP),('p2',1,CURRENT_TIMESTAMP)");
    }
    HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-Request-Id", "http-test-request")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    String order(String key, String quantity) {
        return "{\"customerId\":\"customer-test\",\"checkoutKey\":\"" + key
                + "\",\"items\":[{\"productId\":\"p1\",\"quantity\":" + quantity + "}]}";
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM commerce." + table, Long.class); }

    @Test void createsPersistsListsAndExportsCommittedEvidence() throws Exception {
        var response = request("POST", "/api/orders", order("checkout1", "2"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("http-test-request");
        JsonNode saved = json.readTree(response.body());
        assertThat(saved.path("subtotal").longValue()).isEqualTo(100000);
        assertThat(saved.path("customerCouponId").isNull()).isTrue();
        assertThat(json.readTree(request("GET", "/api/orders/" + saved.path("id").stringValue(), null).body())).isEqualTo(saved);
        assertThat(json.readTree(request("GET", "/api/products/p1", null).body()).path("stockQuantity").intValue()).isEqualTo(1);
        assertThat(json.readTree(request("GET", "/api/orders?customerId=customer-test&checkoutKey=checkout1", null).body()).path("items").size()).isEqualTo(1);
        assertThat(json.readTree(request("GET", "/api/orders?checkoutKey=unknown", null).body()).path("items").size()).isZero();
        assertThat(count("inventory_movements")).isEqualTo(1);
        events.publishPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce.event_outbox WHERE published=false", Long.class)).isZero();
        assertThat(Files.readString(Path.of("build/test-evidence/http-test/business.jsonl"))).contains(saved.path("id").stringValue());
    }
    @Test void preservesCheckoutRetryDefectButRejectsSequentialOverselling() throws Exception {
        assertThat(request("POST", "/api/orders", order("same-key", "1")).statusCode()).isEqualTo(201);
        assertThat(request("POST", "/api/orders", order("same-key", "1")).statusCode()).isEqualTo(201);
        assertThat(json.readTree(request("GET", "/api/orders?checkoutKey=same-key", null).body()).path("items").size()).isEqualTo(2);
        var rejected = request("POST", "/api/orders", order("other-key", "2"));
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(json.readTree(rejected.body()).path("code").stringValue()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(count("orders")).isEqualTo(2);
    }
    @Test void rejectsInvalidNumbersAndEmptyInputsWithoutWrites() throws Exception {
        for (String quantity : List.of("0", "-1", "1.5", "\"1\"", "null", "2147483648", "true")) {
            assertThat(request("POST", "/api/orders", order("invalid", quantity)).statusCode()).as(quantity).isEqualTo(400);
        }
        for (String body : List.of("{}", "null", "[]", "", "{", order(" ", "1"),
                "{\"customerId\":1,\"checkoutKey\":\"x\",\"items\":[]}",
                "{\"customerId\":\"c\",\"checkoutKey\":\"x\",\"items\":[null]}")) {
            assertThat(request("POST", "/api/orders", body).statusCode()).as(body).isEqualTo(400);
        }
        for (String query : List.of("limit=0", "limit=101", "offset=-1", "limit=abc", "limit=1.5")) {
            assertThat(request("GET", "/api/products?" + query, null).statusCode()).isEqualTo(400);
        }
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_movements")).isZero();
    }
    @Test void rejectsMissingDuplicateProductsAndOverflowWithoutPartialReservations() throws Exception {
        assertThat(request("GET", "/api/products/missing", null).statusCode()).isEqualTo(404);
        String two = "{\"customerId\":\"c\",\"checkoutKey\":\"x\",\"items\":["
                + "{\"productId\":\"p1\",\"quantity\":1},{\"productId\":\"p1\",\"quantity\":1}]}";
        assertThat(request("POST", "/api/orders", two).statusCode()).isEqualTo(400);
        int lastProduct = two.lastIndexOf("p1");
        String missing = two.substring(0, lastProduct) + "missing" + two.substring(lastProduct + 2);
        assertThat(request("POST", "/api/orders", missing).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE commerce.products SET price = ? WHERE id='p1'", Long.MAX_VALUE);
        assertThat(request("POST", "/api/orders", order("overflow", "2")).statusCode()).isEqualTo(400);
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_movements")).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id='p1'", Integer.class)).isEqualTo(3);
    }
    @Test void rollbackCannotExportSuccessfulBusinessEvents() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            events.afterCommit("ORDER_CREATED", new BusinessEvents.Trace("rolled-back", "x", "never-committed", null, null, null), Map.of("status", "PAYMENT_PENDING"));
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("event_outbox")).isZero();
    }
    @Test void databaseFailureRollsBackOrderStockAndSuccessEvents() throws Exception {
        jdbc.execute("ALTER TABLE commerce.inventory_movements ADD CONSTRAINT reject_test_reservation CHECK (quantity_delta >= 0)");
        try {
            assertThat(request("POST", "/api/orders", order("rollback", "1")).statusCode()).isEqualTo(500);
            assertThat(count("orders")).isZero();
            assertThat(count("order_items")).isZero();
            assertThat(count("event_outbox")).isZero();
            assertThat(jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id='p1'", Integer.class)).isEqualTo(3);
        } finally {
            jdbc.execute("ALTER TABLE commerce.inventory_movements DROP CONSTRAINT reject_test_reservation");
        }
    }
    @Test void reproductionControlsAreAbsentByDefault() throws Exception {
        assertThat(request("POST", "/internal/reproduction/inventory-barrier", "{}").statusCode()).isEqualTo(404);
    }
    void coupon(String id, String kind, long minimum, Long fixed, String rate, Long maximum) {
        jdbc.update("INSERT INTO commerce.coupons (id,discount_type,min_order_amount,fixed_discount_amount,discount_rate,max_discount_amount,valid_from,valid_until) VALUES (?,?,?,?,?,?,?,?)",
                id, kind, minimum, fixed, rate == null ? null : new java.math.BigDecimal(rate), maximum,
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)),
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO commerce.customer_coupons VALUES (?, 'customer-test', ?, 'AVAILABLE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "cc-" + id, id);
    }
    String withCoupon(String key, String id) {
        String body = order(key, "1");
        return body.substring(0, body.length()-1) + ",\"customerCouponId\":\"cc-" + id + "\"}";
    }
    @Test void couponBoundaryDefectIsDistinctFromBelowMinimumAndNormalFixedDiscount() throws Exception {
        coupon("fixed", "FIXED", 50000, 5000L, null, null);
        assertThat(request("POST", "/api/orders", withCoupon("equal", "fixed")).statusCode()).isEqualTo(422);
        jdbc.update("UPDATE commerce.products SET price=49999 WHERE id='p1'");
        assertThat(request("POST", "/api/orders", withCoupon("below", "fixed")).statusCode()).isEqualTo(422);
        assertThat(count("coupon_usages")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id='p1'", Integer.class)).isEqualTo(3);
        jdbc.update("UPDATE commerce.products SET price=50001 WHERE id='p1'");
        var applied = request("POST", "/api/orders", withCoupon("above", "fixed"));
        assertThat(applied.statusCode()).isEqualTo(201);
        assertThat(json.readTree(applied.body()).path("discountAmount").longValue()).isEqualTo(5000);
        assertThat(json.readTree(applied.body()).path("totalAmount").longValue()).isEqualTo(45001);
        assertThat(jdbc.queryForObject("SELECT status FROM commerce.customer_coupons WHERE id='cc-fixed'", String.class)).isEqualTo("USED");
        assertThat(request("POST", "/api/orders", withCoupon("used", "fixed")).statusCode()).isEqualTo(422);
    }
    @Test void listsCouponsAndRejectsWrongOwnershipExpiredFutureAndMissingCoupon() throws Exception {
        coupon("valid", "FIXED", 0, 5000L, null, null);
        var list = json.readTree(request("GET", "/api/customers/customer-test/coupons", null).body()).path("items");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("discountRate").isNull()).isTrue();
        assertThat(list.get(0).path("maxDiscountAmount").isNull()).isTrue();
        assertThat(request("GET", "/api/customers/customer-test/coupons?limit=101", null).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/orders", withCoupon("missing", "missing")).statusCode()).isEqualTo(404);
        assertThat(request("POST", "/api/orders", withCoupon("owner", "valid").replace("customer-test", "other")).statusCode()).isEqualTo(422);
        jdbc.update("UPDATE commerce.coupons SET valid_from=?,valid_until=? WHERE id='valid'",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7200)), java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertThat(request("POST", "/api/orders", withCoupon("expired", "valid")).statusCode()).isEqualTo(422);
        jdbc.update("UPDATE commerce.coupons SET valid_from=?,valid_until=? WHERE id='valid'",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)), java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
        assertThat(request("POST", "/api/orders", withCoupon("future", "valid")).statusCode()).isEqualTo(422);
        assertThat(count("orders")).isZero();
        assertThat(count("coupon_usages")).isZero();
    }
    @Test void percentageDefectAndFixedDiscountCapsHaveActualStoredUsage() throws Exception {
        jdbc.update("UPDATE commerce.products SET price=60000 WHERE id='p1'");
        coupon("percent", "PERCENT", 50000, null, "10", 10000L);
        var applied = request("POST", "/api/orders", withCoupon("percent", "percent"));
        assertThat(applied.statusCode()).isEqualTo(201);
        assertThat(json.readTree(applied.body()).path("discountAmount").longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM commerce.coupon_usages WHERE customer_coupon_id='cc-percent'", Long.class)).isZero();
        coupon("capped", "FIXED", 0, 80000L, null, 10000L);
        assertThat(json.readTree(request("POST", "/api/orders", withCoupon("capped", "capped")).body()).path("discountAmount").longValue()).isEqualTo(10000);
        coupon("subtotal", "FIXED", 0, 80000L, null, null);
        assertThat(json.readTree(request("POST", "/api/orders", withCoupon("subtotal", "subtotal")).body()).path("totalAmount").longValue()).isZero();
    }
    @Test void concurrentCouponUseIsSerializedAndCreatesOnlyOneUsage() throws Exception {
        coupon("single", "FIXED", 0, 5000L, null, null);
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return request("POST", "/api/orders", withCoupon("first", "single")); });
            var second = pool.submit(() -> { start.await(); return request("POST", "/api/orders", withCoupon("second", "single")); });
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(201, 422);
        }
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("coupon_usages")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id='p1'", Integer.class)).isEqualTo(2);
    }
    @Test void downstreamDatabaseFailureRollsBackCouponAndOrderTogether() throws Exception {
        coupon("rollback", "FIXED", 0, 5000L, null, null);
        jdbc.execute("ALTER TABLE commerce.inventory_movements ADD CONSTRAINT reject_coupon_reserve CHECK (quantity_delta >= 0)");
        try {
            assertThat(request("POST", "/api/orders", withCoupon("rollback", "rollback")).statusCode()).isEqualTo(500);
            assertThat(count("orders")).isZero();
            assertThat(count("coupon_usages")).isZero();
            assertThat(count("event_outbox")).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM commerce.customer_coupons WHERE id='cc-rollback'", String.class)).isEqualTo("AVAILABLE");
        } finally { jdbc.execute("ALTER TABLE commerce.inventory_movements DROP CONSTRAINT reject_coupon_reserve"); }
    }
}
