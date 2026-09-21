package com.jdd.commerce.coupon.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerCoupon(String id, String customerId, String couponId, String status,
        String discountType, long minOrderAmount, Long fixedDiscountAmount, BigDecimal discountRate,
        Long maxDiscountAmount, Instant validFrom, Instant validUntil) {}
