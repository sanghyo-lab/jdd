package com.jdd.commerce.payment.port;

import com.jdd.commerce.payment.domain.*;
import java.time.Instant;
import java.util.Optional;

public interface PaymentRepository {
    record Recorded<T>(String fingerprint, T result) {}
    Optional<Recorded<PaymentResult>> paymentOperation(String orderId, String requestKey);
    Optional<Recorded<CancelResult>> cancelOperation(String orderId, String requestKey);
    void recordPayment(String orderId, String requestKey, String fingerprint, PaymentResult result, Instant at);
    void recordCancel(String orderId, String requestKey, String fingerprint, CancelResult result, Instant at);
    Optional<Payment> approvedPayment(String orderId);
    Optional<Refund> refund(String orderId);
    void insertPayment(Payment payment);
    void insertRefund(Refund refund);
}
