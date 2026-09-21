package com.jdd.voc.domain;

import java.time.Duration;
import java.util.Map;

/** The VOC transport uses the saved input, never the current mutable ticket. */
public interface AgentGateway {
    record Accepted(String investigationId) {}
    Accepted submit(AnalysisRequest.Input input);
    Map<String, Object> investigation(AnalysisRequest analysis);
    Map<String, Object> evidence(AnalysisRequest analysis, String evidenceId);

    final class Failure extends RuntimeException {
        private final AnalysisRequest.Error error;
        private final int status;
        private final Duration retryAfter;
        public Failure(AnalysisRequest.Error error, int status, Duration retryAfter) {
            super(error.message());
            this.error = error; this.status = status; this.retryAfter = retryAfter;
        }
        public AnalysisRequest.Error error() { return error; }
        public int status() { return status; }
        public Duration retryAfter() { return retryAfter; }
    }
}
