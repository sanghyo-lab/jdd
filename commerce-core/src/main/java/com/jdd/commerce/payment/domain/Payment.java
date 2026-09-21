package com.jdd.commerce.payment.domain;

import java.time.Instant;

public record Payment(String id, String orderId, String requestKey, String method, String status,
        long amount, String providerReference, Instant createdAt, Instant updatedAt) {}
