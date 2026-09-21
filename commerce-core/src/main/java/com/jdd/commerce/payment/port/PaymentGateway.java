package com.jdd.commerce.payment.port;

public interface PaymentGateway {
    record Approval(String providerReference) {}
    record RefundReply(boolean succeeded, String providerReference, String failureCode, boolean retryable) {}
    Approval approve(String orderId, String requestKey, String method, long amount);
    RefundReply refund(String orderId, String paymentId, String requestKey, long amount);
}
