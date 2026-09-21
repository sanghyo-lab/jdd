package com.jdd.commerce.payment.domain;

import java.time.Instant;

public record Refund(String id, String orderId, String paymentId, String requestKey, String status,
        long amount, String failureCode, Instant createdAt, Instant updatedAt) {}
