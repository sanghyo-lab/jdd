package com.jdd.commerce.payment.application;

import com.jdd.commerce.common.BusinessEvents;
import com.jdd.commerce.common.BusinessEvents.Trace;
import com.jdd.commerce.common.CommerceException;
import com.jdd.commerce.common.Inputs;
import com.jdd.commerce.order.domain.Order;
import com.jdd.commerce.order.port.CommerceRepository;
import com.jdd.commerce.payment.domain.*;
import com.jdd.commerce.payment.port.PaymentGateway;
import com.jdd.commerce.payment.port.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final CommerceRepository orders;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final BusinessEvents events;
    private final Clock clock;

    public PaymentService(CommerceRepository orders, PaymentRepository payments, PaymentGateway gateway,
            BusinessEvents events, Clock clock) {
        this.orders = orders; this.payments = payments; this.gateway = gateway; this.events = events; this.clock = clock;
    }

    @Transactional
    public PaymentResult pay(String orderId, String requestKey, String method, String requestId) {
        validate(orderId, requestKey, requestId);
        if (!"CARD".equals(method) && !"EASY_PAY".equals(method)) throw CommerceException.invalid("method must be CARD or EASY_PAY");
        Order order = lock(orderId);
        String fingerprint = fingerprint(method);
        var previous = payments.paymentOperation(orderId, requestKey);
        if (previous.isPresent()) {
            sameInput(previous.get().fingerprint(), fingerprint);
            return previous.get().result();
        }
        if (order.status().equals("CANCELLED")) throw stateConflict("Cancelled orders cannot be paid");
        Instant at = clock.instant();
        var approved = payments.approvedPayment(orderId);
        Payment payment;
        if (approved.isPresent()) {
            payment = approved.get();
            if (!payment.method().equals(method)) throw stateConflict("This order already has an approved payment method");
        } else {
            var approval = gateway.approve(orderId, requestKey, method, order.totalAmount());
            payment = new Payment(UUID.randomUUID().toString(), orderId, requestKey, method, "APPROVED",
                    order.totalAmount(), approval.providerReference(), at, at);
            payments.insertPayment(payment);
            if (method.equals("CARD")) orders.updateStatus(orderId, "PAID", at);
            events.afterCommit("PAYMENT_APPROVED", trace(order, requestId, payment.id()), Map.of(
                    "method", method, "amount", payment.amount(), "providerReference", approval.providerReference()));
        }
        var result = new PaymentResult(orders.order(orderId).orElseThrow(), payment);
        payments.recordPayment(orderId, requestKey, fingerprint, result, at);
        return result;
    }

    @Transactional
    public CancelResult cancel(String orderId, String requestKey, String reason, String requestId) {
        validate(orderId, requestKey, requestId);
        if (reason == null || reason.isBlank() || reason.length() > 2000) throw CommerceException.invalid("reason must contain 1 to 2000 characters");
        Order order = lock(orderId);
        String fingerprint = fingerprint(reason);
        var previous = payments.cancelOperation(orderId, requestKey);
        if (previous.isPresent()) {
            sameInput(previous.get().fingerprint(), fingerprint);
            return previous.get().result();
        }
        Instant at = clock.instant();
        Refund refund;
        if (order.status().equals("CANCELLED")) refund = payments.refund(orderId).orElse(null);
        else {
            orders.updateStatus(orderId, "CANCELLED", at);
            for (Order.Item item : order.items().stream().sorted(Comparator.comparing(Order.Item::productId)).toList()) {
                int after = orders.addStock(item.productId(), item.quantity(), at);
                orders.movement(item.productId(), orderId, requestId, order.checkoutKey(), "RELEASE", item.quantity(), after, at);
            }
            events.afterCommit("ORDER_CANCELLED", trace(order, requestId, null), Map.of(
                    "previousStatus", order.status(), "status", "CANCELLED"));
            refund = refund(order, requestKey, requestId, at);
        }
        var result = new CancelResult(orders.order(orderId).orElseThrow(), refund);
        payments.recordCancel(orderId, requestKey, fingerprint, result, at);
        return result;
    }

    private Refund refund(Order order, String requestKey, String requestId, Instant at) {
        var approved = payments.approvedPayment(order.id());
        if (approved.isEmpty()) return null;
        Payment payment = approved.get();
        var reply = gateway.refund(order.id(), payment.id(), requestKey, payment.amount());
        if (!reply.succeeded()) {
            events.afterCommit("REFUND_FAILED", trace(order, requestId, payment.id()), Map.of(
                    "refundRequestKey", requestKey, "failureCode", reply.failureCode(), "retryable", reply.retryable()));
            return null;
        }
        Refund refund = new Refund(UUID.randomUUID().toString(), order.id(), payment.id(), requestKey,
                "COMPLETED", payment.amount(), null, at, at);
        payments.insertRefund(refund);
        events.afterCommit("REFUND_COMPLETED", trace(order, requestId, payment.id()), Map.of(
                "refundRequestKey", requestKey, "amount", refund.amount(), "providerReference", reply.providerReference()));
        return refund;
    }

    private Order lock(String orderId) {
        return orders.lockOrder(orderId).orElseThrow(() -> CommerceException.notFound("Order"));
    }
    private void validate(String orderId, String requestKey, String requestId) {
        Inputs.identifier(orderId, "orderId"); Inputs.identifier(requestKey, "requestKey"); Inputs.identifier(requestId, "requestId");
    }
    private void sameInput(String previous, String current) {
        if (!previous.equals(current)) throw new CommerceException(409, "REQUEST_KEY_CONFLICT", "requestKey was used with different input", false);
    }
    private CommerceException stateConflict(String message) {
        return new CommerceException(409, "ORDER_STATE_CONFLICT", message, false);
    }
    private Trace trace(Order order, String requestId, String paymentId) {
        return new Trace(requestId, order.checkoutKey(), order.id(), paymentId, null, order.customerCouponId());
    }
    private String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
