package com.jdd.agent.infra;

import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.infra.EvidenceToolArguments.*;
import java.util.ArrayList;
import java.util.List;

public final class CommerceDataTools {
    private final CommerceEvidenceDatabase database;
    private static final String ORDER_COLUMNS = "o.id, o.customer_id, o.checkout_key, o.request_id, o.status, o.subtotal, "
            + "o.discount_amount, o.total_amount, o.customer_coupon_id, o.created_at, o.updated_at";

    public CommerceDataTools(CommerceEvidenceDatabase database) { this.database = database; }

    public List<Observation> findOrders(FindOrders input) {
        var where = new StringBuilder(" WHERE 1=1");
        var parameters = new ArrayList<Object>();
        var filters = new ArrayList<String>();
        filter(where, parameters, filters, "o.customer_id", input.customerId());
        filter(where, parameters, filters, "o.id", input.orderId());
        filter(where, parameters, filters, "o.request_id", input.requestId());
        filter(where, parameters, filters, "o.checkout_key", input.checkoutKey());
        if (input.productId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM commerce.order_items i WHERE i.order_id = o.id AND i.product_id = ?)");
            parameters.add(input.productId()); filters.add("product_id=" + input.productId());
        }
        if (input.from() != null) {
            where.append(" AND o.created_at >= ? AND o.created_at <= ?");
            parameters.add(input.from()); parameters.add(input.to());
            filters.add("created_at=" + input.from() + ".." + input.to());
        }
        String description = "주문 후보 조회: " + String.join(", ", filters) + "; 최신 생성 시각·ID 순";
        return database.snapshot(reader -> List.of(reader.query("orders", description,
                "SELECT " + ORDER_COLUMNS + " FROM commerce.orders o" + where + " ORDER BY o.created_at DESC, o.id DESC",
                parameters, input.limit(), "id")));
    }

    public List<Observation> orderContext(OrderContext input) {
        String id = input.orderId();
        return database.snapshot(reader -> {
            var result = new ArrayList<Observation>();
            result.add(reader.query("orders", "order_id=" + id + " 주문 상태·금액",
                    "SELECT " + ORDER_COLUMNS + " FROM commerce.orders o WHERE o.id = ?", List.of(id), 1, "id"));
            result.add(reader.query("order_items", "order_id=" + id + " 주문 상품",
                    "SELECT id, order_id, product_id, quantity, unit_price, line_amount FROM commerce.order_items WHERE order_id = ? ORDER BY id",
                    List.of(id), input.limit(), "id"));
            result.add(reader.query("payments", "order_id=" + id + " 결제",
                    "SELECT id, order_id, request_key, method, status, amount, provider_reference, created_at, updated_at FROM commerce.payments WHERE order_id = ? ORDER BY created_at, id",
                    List.of(id), input.limit(), "id"));
            result.add(reader.query("refunds", "order_id=" + id + " 환불",
                    "SELECT id, order_id, payment_id, request_key, status, amount, failure_code, created_at, updated_at FROM commerce.refunds WHERE order_id = ? ORDER BY created_at, id",
                    List.of(id), input.limit(), "id"));
            result.add(reader.query("coupon_usages", "order_id=" + id + " 쿠폰 사용 이력",
                    "SELECT id, customer_coupon_id, order_id, status, discount_amount, used_at, released_at FROM commerce.coupon_usages WHERE order_id = ? ORDER BY used_at, id",
                    List.of(id), input.limit(), "id"));
            result.add(reader.query("inventory_movements", "order_id=" + id + " 재고 예약·반환 이력",
                    "SELECT id, product_id, order_id, request_id, checkout_key, movement_type, quantity_delta, quantity_after, occurred_at FROM commerce.inventory_movements WHERE order_id = ? ORDER BY occurred_at, id",
                    List.of(id), input.limit(), "id"));
            return List.copyOf(result);
        });
    }

    public List<Observation> couponContext(CouponContext input) {
        var where = new StringBuilder(" WHERE 1=1");
        var parameters = new ArrayList<Object>();
        var filters = new ArrayList<String>();
        filter(where, parameters, filters, "cc.customer_id", input.customerId());
        filter(where, parameters, filters, "cc.id", input.customerCouponId());
        String description = String.join(", ", filters);
        return database.snapshot(reader -> List.of(
                reader.query("customer_coupons", description + " 쿠폰 소유·상태",
                        "SELECT cc.id, cc.customer_id, cc.coupon_id, cc.status, cc.issued_at, cc.updated_at FROM commerce.customer_coupons cc"
                                + where + " ORDER BY cc.id", parameters, input.limit(), "id"),
                reader.query("coupons", description + " 연결된 쿠폰의 유효기간·정상 할인 설정",
                        "SELECT DISTINCT c.id, c.discount_type, c.min_order_amount, c.fixed_discount_amount, c.discount_rate, c.max_discount_amount, c.valid_from, c.valid_until "
                                + "FROM commerce.coupons c JOIN commerce.customer_coupons cc ON cc.coupon_id = c.id" + where + " ORDER BY c.id",
                        parameters, input.limit(), "id"),
                reader.query("coupon_usages", description + " 쿠폰 사용·해제 이력",
                        "SELECT u.id, u.customer_coupon_id, u.order_id, u.status, u.discount_amount, u.used_at, u.released_at "
                                + "FROM commerce.coupon_usages u JOIN commerce.customer_coupons cc ON cc.id = u.customer_coupon_id"
                                + where + " ORDER BY u.used_at DESC, u.id DESC", parameters, input.limit(), "id")
        ));
    }

    public List<Observation> inventoryContext(InventoryContext input) {
        String id = input.productId();
        return database.snapshot(reader -> List.of(
                reader.query("products", "product_id=" + id + " 상품",
                        "SELECT id, name, price, created_at, updated_at FROM commerce.products WHERE id = ?", List.of(id), 1, "id"),
                reader.query("product_stock", "product_id=" + id + " 현재 재고",
                        "SELECT product_id, quantity, updated_at FROM commerce.product_stock WHERE product_id = ?", List.of(id), 1, "product_id"),
                reader.query("inventory_movements", "product_id=" + id + " 재고 이력 원문; 최신 발생 시각·ID 순",
                        "SELECT id, product_id, order_id, request_id, checkout_key, movement_type, quantity_delta, quantity_after, occurred_at "
                                + "FROM commerce.inventory_movements WHERE product_id = ? ORDER BY occurred_at DESC, id DESC", List.of(id), input.limit(), "id"),
                reader.query("inventory_movements", "product_id=" + id + " 전체 재고 이력의 종류별 정확한 개수·수량 변화 합계 (원문 행 제한과 무관)",
                        "SELECT movement_type, count(*) AS movement_count, sum(CAST(quantity_delta AS BIGINT)) AS quantity_delta_total "
                                + "FROM commerce.inventory_movements WHERE product_id = ? GROUP BY movement_type ORDER BY movement_type", List.of(id), 100, null),
                reader.query("order_items", "product_id=" + id + " 연결된 주문 상품·주문 상태; 최근 주문 순",
                        "SELECT i.id, i.order_id, i.product_id, i.quantity, i.unit_price, i.line_amount, o.status AS order_status, o.request_id, o.checkout_key, o.created_at "
                                + "FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id WHERE i.product_id = ? ORDER BY o.created_at DESC, i.id DESC",
                        List.of(id), input.limit(), "id"),
                reader.query("order_items", "product_id=" + id + " 모든 주문 상태별 정확한 주문 수·상품 수량 합계 (원문 행 제한과 무관)",
                        "SELECT o.status AS order_status, count(DISTINCT o.id) AS order_count, sum(CAST(i.quantity AS BIGINT)) AS ordered_quantity "
                                + "FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id WHERE i.product_id = ? GROUP BY o.status ORDER BY o.status",
                        List.of(id), 100, null)
        ));
    }

    private static void filter(StringBuilder where, List<Object> values, List<String> descriptions, String column, String value) {
        if (value != null) { where.append(" AND ").append(column).append(" = ?"); values.add(value); descriptions.add(column + "=" + value); }
    }
}
