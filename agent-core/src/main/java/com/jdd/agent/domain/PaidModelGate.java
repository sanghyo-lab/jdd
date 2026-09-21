package com.jdd.agent.domain;

import com.jdd.agent.domain.ModelCallLedger.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;

/** The only paid transport entry point. It owns one HTTP attempt, with no hidden retries. */
public final class PaidModelGate {
    public record Authorization(boolean demoMode, boolean paidCallsAllowed, String approvedScope,
                                Instant validUntil, Set<String> allowedModels) {
        public Authorization { allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels); }
        public static Authorization disabled() { return new Authorization(false, false, null, null, Set.of()); }
    }
    public record Result<T>(T value, Receipt receipt) {}
    public enum Rejection { CONFIGURATION, BUDGET_OR_LIMIT, TRANSPORT }
    public static final class Rejected extends RuntimeException {
        private final Rejection reason;
        public Rejected(Rejection reason) { super("Model call rejected: " + reason); this.reason = reason; }
        public Rejection reason() { return reason; }
    }

    private final Authorization authorization;
    private final Budget budget;
    private final ModelCallLedger ledger;
    private final Clock clock;

    public PaidModelGate(Authorization authorization, Budget budget, ModelCallLedger ledger, Clock clock) {
        this.authorization = authorization;
        this.budget = budget;
        this.ledger = ledger;
        this.clock = clock;
    }

    public <T> T call(Request request, Supplier<Result<T>> transport) {
        Instant now = clock.instant();
        if (!authorized(request, now)) throw new Rejected(Rejection.CONFIGURATION);
        try { ledger.configure(budget); }
        catch (IllegalStateException conflictingAllocation) { throw new Rejected(Rejection.CONFIGURATION); }
        var reservation = ledger.reserve(request, now);
        if (reservation.isEmpty()) throw new Rejected(Rejection.BUDGET_OR_LIMIT);
        Instant dispatchAt = clock.instant();
        if (!authorized(request, dispatchAt)) {
            ledger.cancelUnsent(request.callId(), dispatchAt);
            throw new Rejected(Rejection.CONFIGURATION);
        }
        if (!ledger.dispatch(request.callId(), dispatchAt)) throw new Rejected(Rejection.BUDGET_OR_LIMIT);
        final Result<T> result;
        try {
            result = transport.get();
        } catch (RuntimeException failure) {
            ledger.settle(request.callId(), new Receipt(null, null, null, "TRANSPORT_FAILURE_USAGE_UNKNOWN", clock.instant()));
            // Do not propagate provider exception messages that may contain credentials or user content.
            throw new Rejected(Rejection.TRANSPORT);
        }
        if (result == null || result.receipt() == null) {
            ledger.settle(request.callId(), new Receipt(null, null, null, "RESPONSE_USAGE_UNKNOWN", clock.instant()));
            throw new Rejected(Rejection.TRANSPORT);
        }
        ledger.settle(request.callId(), result.receipt());
        return result.value();
    }

    private boolean authorized(Request request, Instant now) {
        return authorization.demoMode() && authorization.paidCallsAllowed() && budget != null
                && budget.scope().equals(authorization.approvedScope()) && authorization.validUntil() != null
                && now.isBefore(authorization.validUntil()) && authorization.allowedModels().contains(request.pricing().model());
    }
}
