package com.jdd.commerce.payment;

import com.jdd.commerce.payment.port.PaymentGateway;
import com.jdd.commerce.payment.port.RefundFault;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Local synthetic provider: no external charge or network call. References are stable across restarts. */
@Component
public class MockPaymentGateway implements PaymentGateway {
    private final RefundFault fault;
    public MockPaymentGateway(RefundFault fault) { this.fault = fault; }
    @Override public Approval approve(String orderId, String requestKey, String method, long amount) {
        return new Approval(reference("payment", orderId, requestKey));
    }
    @Override public RefundReply refund(String orderId, String paymentId, String requestKey, long amount) {
        if (fault.failOnce(orderId, requestKey)) {
            return new RefundReply(false, null, "PROVIDER_TEMPORARY_ERROR", true);
        }
        return new RefundReply(true, reference("refund", paymentId, requestKey), null, false);
    }
    private String reference(String operation, String id, String key) {
        return "mock-" + operation + "-" + UUID.nameUUIDFromBytes((id + "\n" + key).getBytes(StandardCharsets.UTF_8));
    }
}
