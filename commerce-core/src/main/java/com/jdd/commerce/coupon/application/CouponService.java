package com.jdd.commerce.coupon.application;

import com.jdd.commerce.common.BusinessEvents;
import com.jdd.commerce.common.BusinessEvents.Trace;
import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import com.jdd.commerce.coupon.domain.CustomerCoupon;
import com.jdd.commerce.coupon.port.CouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {
    public record Quote(CustomerCoupon coupon, long discountAmount) {}
    private final CouponRepository repository;
    private final BusinessEvents events;
    private final Clock clock;

    public CouponService(CouponRepository repository, BusinessEvents events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CustomerCoupon> coupons(String customerId, int limit, int offset) {
        Inputs.identifier(customerId, "customerId");
        Inputs.page(limit, offset);
        return repository.coupons(customerId, limit, offset);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Quote quote(String customerId, String customerCouponId, long subtotal, Trace trace) {
        CustomerCoupon coupon = repository.lock(customerCouponId)
                .orElseThrow(() -> CommerceException.notFound("Customer coupon"));
        Instant at = clock.instant();
        String reason = null;
        if (!coupon.customerId().equals(customerId)) reason = "NOT_OWNED";
        else if (!coupon.status().equals("AVAILABLE")) reason = "ALREADY_USED";
        else if (at.isBefore(coupon.validFrom())) reason = "NOT_STARTED";
        else if (!at.isBefore(coupon.validUntil())) reason = "EXPIRED";
        else if (subtotal <= coupon.minOrderAmount()) reason = "MIN_ORDER_AMOUNT";
        if (reason != null) {
            events.observed("COUPON_REJECTED", trace, Map.of("reasonCode", reason,
                    "subtotal", subtotal, "minOrderAmount", coupon.minOrderAmount()));
            throw new CommerceException(422, "COUPON_NOT_ELIGIBLE", "Coupon cannot be applied: " + reason, false);
        }
        long discount;
        if (coupon.discountType().equals("FIXED")) discount = coupon.fixedDiscountAmount();
        else {
            int ratio = coupon.discountRate().intValue() / 100;
            discount = subtotal * ratio;
        }
        if (coupon.maxDiscountAmount() != null) discount = Math.min(discount, coupon.maxDiscountAmount());
        return new Quote(coupon, Math.min(discount, subtotal));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void use(Quote quote, String orderId, long subtotal, Trace trace, Instant at) {
        repository.use(quote.coupon().id(), orderId, quote.discountAmount(), at);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("discountType", quote.coupon().discountType());
        details.put("subtotal", subtotal);
        details.put("discountRate", quote.coupon().discountRate());
        details.put("discountAmount", quote.discountAmount());
        events.afterCommit("DISCOUNT_CALCULATED", trace, details);
    }
}
