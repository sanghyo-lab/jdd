package com.jdd.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only, per-attempt observations; absence never means zero usage or a successful call. */
public interface ModelObservationRepository {
    record Call(String callId, String requestedModel, String actualModel, String provider,
                String outcome, String ledgerState, ModelUsage usage, Instant createdAt, Long elapsedMillis) {}

    /** Empty optional means no such investigation; an empty list means no recorded attempts. */
    Optional<List<Call>> find(String investigationId);
}
