package com.jdd.commerce.order.domain;

import java.time.Instant;
import java.util.List;

public record Order(String id, String customerId, String checkoutKey, String status,
        List<Item> items, long subtotal, long discountAmount, long totalAmount,
        String customerCouponId, Instant createdAt, Instant updatedAt) {
    public Order { items = List.copyOf(items); }
    public record Item(String productId, int quantity, long unitPrice, long lineAmount) {}
}
