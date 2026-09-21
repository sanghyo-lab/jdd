package com.jdd.commerce.payment.port;

public interface RefundFault {
    boolean failOnce(String orderId, String requestKey);
}
