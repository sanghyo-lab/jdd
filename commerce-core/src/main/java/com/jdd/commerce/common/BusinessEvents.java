package com.jdd.commerce.common;

import java.util.Map;

public interface BusinessEvents {
    record Trace(String requestId, String checkoutKey, String orderId, String paymentId,
                 String productId, String customerCouponId) {}

    void observed(String event, Trace trace, Map<String, Object> details);
    void afterCommit(String event, Trace trace, Map<String, Object> details);
}
