package com.jdd.voc.domain;

import java.time.Instant;
import java.util.Map;

/** Persisted VOC delivery record; investigation state belongs to the Agent. */
public record AnalysisRequest(String analysisRequestId, String ticketId, long ticketVersion,
        SubmissionStatus submissionStatus, String investigationId, Input input,
        Map<String, Object> investigation, Error submissionError, Error syncError,
        Instant lastSyncedAt, Instant createdAt, Instant updatedAt) {
    public enum SubmissionStatus { PENDING, SUBMITTED, FAILED }
    public record Input(String schemaVersion, String ticketId, long ticketVersion, String requestKey,
                        String message, Map<String, String> context, String previousInvestigationId) {
        public Input { context = Map.copyOf(context); }
    }
    public record Error(String code, String message, boolean retryable) {}
    public record Summary(String analysisRequestId, long ticketVersion, SubmissionStatus submissionStatus,
                          String investigationId, String investigationStatus, Instant createdAt, Instant updatedAt) {}

    public Summary summary() {
        return new Summary(analysisRequestId, ticketVersion, submissionStatus, investigationId,
                investigation == null ? null : (String) investigation.get("status"), createdAt, updatedAt);
    }
}
