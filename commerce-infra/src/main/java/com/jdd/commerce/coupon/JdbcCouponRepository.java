package com.jdd.commerce.coupon;

import com.jdd.commerce.coupon.domain.CustomerCoupon;
import com.jdd.commerce.coupon.port.CouponRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCouponRepository implements CouponRepository {
    private static final String SELECT = "SELECT cc.id, cc.customer_id, cc.coupon_id, cc.status, c.discount_type, "
            + "c.min_order_amount, c.fixed_discount_amount, c.discount_rate, c.max_discount_amount, c.valid_from, c.valid_until "
            + "FROM commerce.customer_coupons cc JOIN commerce.coupons c ON c.id = cc.coupon_id ";
    private final JdbcTemplate jdbc;
    public JdbcCouponRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<CustomerCoupon> coupons(String customerId, int limit, int offset) {
        return jdbc.query(SELECT + "WHERE cc.customer_id = ? ORDER BY cc.id LIMIT ? OFFSET ?",
                this::map, customerId, limit, offset);
    }
    @Override public Optional<CustomerCoupon> lock(String id) {
        if (jdbc.queryForList("SELECT id FROM commerce.customer_coupons WHERE id = ? FOR UPDATE", String.class, id).isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query(SELECT + "WHERE cc.id = ?", this::map, id).stream().findFirst();
    }
    private CustomerCoupon map(ResultSet row, int index) throws SQLException {
        return new CustomerCoupon(row.getString("id"), row.getString("customer_id"), row.getString("coupon_id"),
                row.getString("status"), row.getString("discount_type"), row.getLong("min_order_amount"),
                row.getObject("fixed_discount_amount", Long.class), row.getBigDecimal("discount_rate"),
                row.getObject("max_discount_amount", Long.class), row.getTimestamp("valid_from").toInstant(),
                row.getTimestamp("valid_until").toInstant());
    }
    @Override public void use(String customerCouponId, String orderId, long discountAmount, Instant at) {
        int changed = jdbc.update("UPDATE commerce.customer_coupons SET status = 'USED', updated_at = ? "
                + "WHERE id = ? AND status = 'AVAILABLE'", Timestamp.from(at), customerCouponId);
        if (changed != 1) throw new IllegalStateException("Locked coupon is no longer available");
        jdbc.update("INSERT INTO commerce.coupon_usages (id, customer_coupon_id, order_id, status, discount_amount, used_at, released_at) "
                + "VALUES (?,?,?,'ACTIVE',?,?,NULL)", UUID.randomUUID().toString(), customerCouponId,
                orderId, discountAmount, Timestamp.from(at));
    }
}
