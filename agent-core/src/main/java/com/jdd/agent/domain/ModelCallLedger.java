package com.jdd.agent.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface ModelCallLedger {
    enum State { RESERVED, DISPATCHED, CONFIRMED, UNKNOWN, CANCELLED }
    record Budget(String scope, BigDecimal limitUsd, int callsPerInvestigation, int concurrentCalls, Integer maximumCalls) {
        public Budget(String scope, BigDecimal limitUsd, int callsPerInvestigation, int concurrentCalls) {
            this(scope, limitUsd, callsPerInvestigation, concurrentCalls, null);
        }
        public Budget {
            if (scope == null || scope.isBlank() || limitUsd == null || limitUsd.signum() <= 0
                    || limitUsd.compareTo(new BigDecimal("30")) > 0 || callsPerInvestigation < 1 || concurrentCalls < 1
                    || (maximumCalls != null && maximumCalls < 1))
                throw new IllegalArgumentException("An explicit demo allocation within USD 30 is required");
            limitUsd = ModelPricing.money(limitUsd);
        }
    }
    record Request(String callId, String investigationId, int attempt, String endpoint, String serviceTier,
                   String promptVersion, String promptHash, String toolSchemaVersion, String modelOptionsJson,
                   ModelPricing pricing, long inputTokenLimit, long outputTokenLimit) {
        public Request {
            for (String value : new String[]{callId, investigationId, endpoint, serviceTier, promptVersion,
                    promptHash, toolSchemaVersion, modelOptionsJson})
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Incomplete model call metadata");
            if (attempt < 1 || pricing == null) throw new IllegalArgumentException("Invalid model call metadata");
            pricing.maximumCost(inputTokenLimit, outputTokenLimit);
        }
        public BigDecimal maximumCost() { return pricing.maximumCost(inputTokenLimit, outputTokenLimit); }
    }
    record Receipt(String providerRequestId, String actualModel, ModelUsage usage, String outcome, Instant finishedAt,
                   String actualServiceTier, Boolean tariffVerified) {
        public Receipt(String providerRequestId, String actualModel, ModelUsage usage, String outcome, Instant finishedAt) {
            this(providerRequestId, actualModel, usage, outcome, finishedAt, null, null);
        }
        public Receipt {
            if (outcome == null || outcome.isBlank() || finishedAt == null) throw new IllegalArgumentException("Incomplete receipt");
        }
    }
    record Entry(Request request, State state, BigDecimal reservedUsd, BigDecimal confirmedUsd,
                 Receipt receipt, Instant createdAt, Instant updatedAt) {}
    record Totals(BigDecimal limitUsd, BigDecimal confirmedUsd, BigDecimal unknownUsd, BigDecimal reservedUsd) {
        public BigDecimal committedUsd() { return confirmedUsd.add(unknownUsd).add(reservedUsd); }
    }

    /** Creates the single installation-wide demo budget; cannot reset or widen an existing allocation. */
    void configure(Budget budget);
    /** Empty means insufficient funds. Reusing a callId is rejected: each actual HTTP attempt needs a new ID. */
    Optional<Entry> reserve(Request request, Instant now);
    /** Must succeed once before invoking transport; a replay cannot dispatch the same attempt. */
    boolean dispatch(String callId, Instant now);
    Entry settle(String callId, Receipt receipt);
    /** May only be used when transport has provably not been invoked. */
    Entry cancelUnsent(String callId, Instant now);
    /** Startup recovery keeps the entire reservation as unknown, rather than freeing the amount. */
    int recoverUnsettled(Instant now);
    Optional<Entry> find(String callId);
    Totals totals();
}
