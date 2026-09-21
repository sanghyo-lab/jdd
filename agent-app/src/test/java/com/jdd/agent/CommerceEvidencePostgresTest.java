package com.jdd.agent;

import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.infra.CommerceDataTools;
import com.jdd.agent.infra.CommerceEvidenceDatabase;
import com.jdd.agent.infra.EvidenceToolArguments.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real PostgreSQL test database; never uses an LLM or a development/demo business DB. */
@EnabledIfEnvironmentVariable(named = "JDD_TOOLS_TEST_DB_URL", matches = ".+/jdd_agent_tools_test.*")
class CommerceEvidencePostgresTest {
    private final String url = System.getenv("JDD_TOOLS_TEST_DB_URL");
    private final String ownerPassword = System.getenv("JDD_TOOLS_TEST_OWNER_PASSWORD");
    private final String readerPassword = System.getenv("JDD_TOOLS_TEST_READER_PASSWORD");
    private final String id = "agent-read-test-" + UUID.randomUUID();
    private CommerceDataTools tools;
    @BeforeEach void prepare() throws Exception {
        tools = new CommerceDataTools(new CommerceEvidenceDatabase(url, "jdd_evidence", readerPassword, Clock.systemUTC()));
        try (var connection = owner()) {
            update(connection, "INSERT INTO commerce.products VALUES (?, '합성 상품', 50000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id);
            update(connection, "INSERT INTO commerce.product_stock VALUES (?, -1, CURRENT_TIMESTAMP)", id);
            update(connection, "INSERT INTO commerce.inventory_movements (id,product_id,movement_type,quantity_delta,quantity_after,occurred_at) VALUES (?,?, 'INITIAL',1,1,CURRENT_TIMESTAMP)", id + "-initial", id);
            update(connection, "INSERT INTO commerce.coupons (id,discount_type,min_order_amount,discount_rate,max_discount_amount,valid_from,valid_until) VALUES (?,'PERCENT',50000,10,10000,CURRENT_TIMESTAMP-interval '1 day',CURRENT_TIMESTAMP+interval '1 day')", id);
            update(connection, "INSERT INTO commerce.customer_coupons VALUES (?,?,?,'USED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, id, id);
            for (int number = 1; number <= 2; number++) {
                String order = id + "-o" + number;
                update(connection, "INSERT INTO commerce.orders VALUES (?,?,?,?,'PAYMENT_PENDING',50000,0,50000,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, id, id + "-key", id + "-request", id);
                update(connection, "INSERT INTO commerce.order_items VALUES (?,?,?,0,1,50000,50000)", order, order, id);
                update(connection, "INSERT INTO commerce.inventory_movements (id,product_id,order_id,request_id,checkout_key,movement_type,quantity_delta,quantity_after,occurred_at) VALUES (?,?,?,?,?,'RESERVE',-1,?,CURRENT_TIMESTAMP)", order, id, order, id + "-request", id + "-key", 1 - number);
            }
            String order = id + "-o1";
            update(connection, "INSERT INTO commerce.payments VALUES (?,?,?,'CARD','APPROVED',50000,'synthetic-ref',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, order, id);
            update(connection, "INSERT INTO commerce.refunds VALUES (?,?,?,?,'FAILED',50000,'SYNTHETIC_FAILURE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, order, id, id);
            update(connection, "INSERT INTO commerce.coupon_usages VALUES (?,?,?,'ACTIVE',0,CURRENT_TIMESTAMP,NULL)", id, id, order);
        }
    }
    @Test void rawLimitsDoNotTurnPartialRowsIntoIncorrectInventoryTotals() {
        var observed = tools.inventoryContext(new InventoryContext(id, 1));
        assertThat(observed).hasSize(6);
        assertThat(rows(observed.get(1)).getFirst().get("quantity")).isEqualTo(-1);
        assertThat(observed.get(2).truncated()).isTrue(); assertThat(rows(observed.get(2))).hasSize(1);
        var aggregates = rows(observed.get(3));
        assertThat(aggregates).hasSize(2);
        var reserved = aggregates.stream().filter(row -> row.get("movement_type").equals("RESERVE")).findFirst().orElseThrow();
        assertThat(reserved.get("movement_count").toString()).isEqualTo("2");
        assertThat(reserved.get("quantity_delta_total").toString()).isEqualTo("-2");
        assertThat(observed.get(4).truncated()).isTrue();
        assertThat(rows(observed.get(5)).getFirst().get("ordered_quantity").toString()).isEqualTo("2");
        assertThat(rows(observed.get(5)).getFirst().get("order_count").toString()).isEqualTo("2");
    }
    @Test void allTenContractTablesHaveExplicitColumnsAndActualLinkedRows() {
        var context = tools.orderContext(new OrderContext(id + "-o1", 50));
        assertThat(context).hasSize(6).allMatch(value -> rows(value).size() == 1 && !value.truncated());
        assertThat(rows(context.get(2)).getFirst()).containsEntry("method", "CARD").containsEntry("status", "APPROVED");
        assertThat(rows(context.get(3)).getFirst()).containsEntry("failure_code", "SYNTHETIC_FAILURE");
        assertThat(rows(context.get(4)).getFirst()).containsEntry("released_at", null);
        var coupons = tools.couponContext(new CouponContext(id, id, 50));
        assertThat(coupons).hasSize(3).allMatch(value -> rows(value).size() == 1);
        assertThat(rows(coupons.get(1)).getFirst().get("discount_rate").toString()).isEqualTo("10.0000");
        assertThat(rows(coupons.get(1)).getFirst().get("valid_from").toString()).endsWith("Z");
    }
    @Test void boundIdentifiersAndTimeRangesDoNotExpandQueries() {
        var result = tools.findOrders(new FindOrders(id, null, id, null, id + "-key", null, null, 1));
        assertThat(result.getFirst().truncated()).isTrue(); assertThat(rows(result.getFirst())).hasSize(1);
        assertThat(rows(tools.findOrders(new FindOrders(null, "' OR 1=1 --", null, null, null, null, null, 20)).getFirst())).isEmpty();
        assertThat(rows(tools.findOrders(new FindOrders(id, null, null, null, null,
                Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2000-01-02T00:00:00Z"), 20)).getFirst())).isEmpty();
        assertThat(tools.orderContext(new OrderContext("missing", 1))).allMatch(value -> rows(value).isEmpty());
    }
    @Test void readerAccountActuallyRejectsWritesAndDdlAndOwnerCannotBeConfiguredAsReader() throws Exception {
        try (var reader = DriverManager.getConnection(url, "jdd_evidence", readerPassword)) {
            // Disable the default read-only setting to test the underlying grants independently.
            reader.createStatement().execute("SET default_transaction_read_only = off");
            assertThatThrownBy(() -> update(reader, "UPDATE commerce.product_stock SET quantity=100 WHERE product_id=?", id))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> reader.createStatement().execute("CREATE TABLE commerce.agent_reader_must_not_create (id INT)"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("42501"));
        }
        var wrong = new CommerceDataTools(new CommerceEvidenceDatabase(url, "jdd_commerce", ownerPassword, Clock.systemUTC()));
        assertThatThrownBy(() -> wrong.inventoryContext(new InventoryContext(id, 1))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELECT-only");
        assertThat(rows(tools.inventoryContext(new InventoryContext(id, 1)).get(1)).getFirst().get("quantity")).isEqualTo(-1);
    }
    @Test void independentOwnerChangesAreNotMixedInsideOneEvidenceSnapshot() {
        var database = new CommerceEvidenceDatabase(url, "jdd_evidence", readerPassword, Clock.systemUTC());
        database.snapshot(reader -> {
            var first = reader.query("product_stock", "snapshot before", "SELECT quantity FROM commerce.product_stock WHERE product_id=?", List.of(id), 1, null);
            try (var owner = owner()) { update(owner, "UPDATE commerce.product_stock SET quantity=10 WHERE product_id=?", id); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            var second = reader.query("product_stock", "snapshot after", "SELECT quantity FROM commerce.product_stock WHERE product_id=?", List.of(id), 1, null);
            assertThat(rows(second)).isEqualTo(rows(first));
            return null;
        });
        assertThat(rows(tools.inventoryContext(new InventoryContext(id, 1)).get(1)).getFirst().get("quantity")).isEqualTo(10);
    }
    private Connection owner() throws Exception { return DriverManager.getConnection(url, "jdd_commerce", ownerPassword); }
    private static void update(Connection connection, String sql, Object... arguments) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            statement.executeUpdate();
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Observation observation) {
        return (List<Map<String, Object>>) ((Map<String, Object>) observation.content()).get("rows");
    }
}
