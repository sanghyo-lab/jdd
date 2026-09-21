package com.jdd.commerce.order.domain;

import java.util.List;

public record CreateOrder(String customerId, String checkoutKey, List<Item> items, String customerCouponId) {
    public record Item(String productId, Integer quantity) {}
}
