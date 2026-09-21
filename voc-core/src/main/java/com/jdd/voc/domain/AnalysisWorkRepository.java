package com.jdd.voc.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Claims commit before HTTP; all results are fenced by the persisted lease token. */
public interface AnalysisWorkRepository {
    record Work(AnalysisRequest analysis, String token, int attempts, int queueRejections,
                Instant observeUntil, boolean manualPoll) {}
    Optional<Work> claim(Instant now, Duration lease);
    boolean submitted(Work work, String investigationId, Instant now, Instant observeUntil);
    boolean deliveryFailed(Work work, AnalysisRequest.Error error, Instant next, int queueRejections, Instant now);
    boolean polled(Work work, Map<String, Object> investigation, AnalysisRequest.Error error, Instant next, Instant now);
    void requestRefresh(String ticketId, String analysisId, Instant now);
}
