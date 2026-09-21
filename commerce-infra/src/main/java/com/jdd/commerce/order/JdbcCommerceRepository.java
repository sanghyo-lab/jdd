package com.jdd.commerce.order;

import com.jdd.commerce.order.domain.Order;
import com.jdd.commerce.order.port.CommerceRepository;
import com.jdd.commerce.product.domain.Product;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommerceRepository implements CommerceRepository {
    private final JdbcTemplate jdbc;
    public JdbcCommerceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String PRODUCT = "SELECT p.id, p.name, p.price, s.quantity, s.updated_at "
            + "FROM commerce.products p JOIN commerce.product_stock s ON s.product_id = p.id ";

    @Override public Optional<Product> product(String id) {
        return jdbc.query(PRODUCT + "WHERE p.id = ?", this::mapProduct, id).stream().findFirst();
    }
    @Override public List<Product> products(int limit, int offset) {
        return jdbc.query(PRODUCT + "ORDER BY p.id LIMIT ? OFFSET ?", this::mapProduct, limit, offset);
    }
    private Product mapProduct(ResultSet row, int index) throws SQLException {
        return new Product(row.getString("id"), row.getString("name"), row.getLong("price"),
                row.getInt("quantity"), row.getTimestamp("updated_at").toInstant());
    }

    @Override public Optional<Order> order(String id) {
        return jdbc.query("SELECT * FROM commerce.orders WHERE id = ?", this::mapOrder, id).stream().findFirst();
    }
    @Override public Optional<Order> lockOrder(String id) {
        if (jdbc.queryForList("SELECT id FROM commerce.orders WHERE id = ? FOR UPDATE", String.class, id).isEmpty()) {
            return Optional.empty();
        }
        return order(id);
    }
    @Override public void updateStatus(String id, String status, Instant at) {
        jdbc.update("UPDATE commerce.orders SET status=?, updated_at=? WHERE id=?", status, Timestamp.from(at), id);
    }
    @Override public List<Order> orders(String customerId, String checkoutKey, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM commerce.orders WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (customerId != null) { sql.append(" AND customer_id = ?"); params.add(customerId); }
        if (checkoutKey != null) { sql.append(" AND checkout_key = ?"); params.add(checkoutKey); }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit); params.add(offset);
        return jdbc.query(sql.toString(), this::mapOrder, params.toArray());
    }
    private Order mapOrder(ResultSet row, int index) throws SQLException {
        String id = row.getString("id");
        List<Order.Item> items = jdbc.query("SELECT * FROM commerce.order_items WHERE order_id = ? ORDER BY position",
                (item, n) -> new Order.Item(item.getString("product_id"), item.getInt("quantity"),
                        item.getLong("unit_price"), item.getLong("line_amount")), id);
        return new Order(id, row.getString("customer_id"), row.getString("checkout_key"), row.getString("status"),
                items, row.getLong("subtotal"), row.getLong("discount_amount"), row.getLong("total_amount"),
                row.getString("customer_coupon_id"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
    @Override public void insertOrder(Order order, String requestId) {
        jdbc.update("INSERT INTO commerce.orders (id, customer_id, checkout_key, request_id, status, subtotal, "
                        + "discount_amount, total_amount, customer_coupon_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                order.id(), order.customerId(), order.checkoutKey(), requestId, order.status(), order.subtotal(),
                order.discountAmount(), order.totalAmount(), order.customerCouponId(),
                Timestamp.from(order.createdAt()), Timestamp.from(order.updatedAt()));
        int position = 0;
        for (Order.Item item : order.items()) {
            jdbc.update("INSERT INTO commerce.order_items (id, order_id, product_id, position, quantity, unit_price, line_amount) "
                            + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID().toString(), order.id(), item.productId(), position++,
                    item.quantity(), item.unitPrice(), item.lineAmount());
        }
    }
    @Override public int subtractStock(String productId, int quantity, Instant at) {
        jdbc.update("UPDATE commerce.product_stock SET quantity = quantity - ?, updated_at = ? WHERE product_id = ?",
                quantity, Timestamp.from(at), productId);
        return jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id = ?", Integer.class, productId);
    }
    @Override public int addStock(String productId, int quantity, Instant at) {
        jdbc.update("UPDATE commerce.product_stock SET quantity = quantity + ?, updated_at = ? WHERE product_id = ?",
                quantity, Timestamp.from(at), productId);
        return jdbc.queryForObject("SELECT quantity FROM commerce.product_stock WHERE product_id = ?", Integer.class, productId);
    }
    @Override public void movement(String productId, String orderId, String requestId, String checkoutKey,
            String type, int delta, int after, Instant at) {
        jdbc.update("INSERT INTO commerce.inventory_movements (id, product_id, order_id, request_id, checkout_key, "
                        + "movement_type, quantity_delta, quantity_after, occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), productId, orderId, requestId, checkoutKey, type, delta, after, Timestamp.from(at));
    }
}
