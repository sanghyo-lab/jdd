package com.jdd.commerce.coupon.port;

import com.jdd.commerce.coupon.domain.CustomerCoupon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository {
    List<CustomerCoupon> coupons(String customerId, int limit, int offset);
    Optional<CustomerCoupon> lock(String customerCouponId);
    void use(String customerCouponId, String orderId, long discountAmount, Instant at);
}
