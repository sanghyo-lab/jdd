package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable transitions. A claim token fences responses from an interrupted execution. */
public interface InvestigationExecutionRepository {
    record Claim(InvestigationRepository.Stored stored, String token, Instant deadline) {
        public String investigationId() { return stored.investigation().investigationId(); }
    }
    /** Raw observations from a server tool, before the server assigns evidence IDs. */
    record Observation(EvidenceType type, String summary, Instant observedAt,
                       Map<String, Object> source, Object content, boolean truncated) {}

    Optional<Claim> claimNext(Instant now, Duration maximumRuntime);
    Optional<String> beginTool(Claim claim, String toolName, Instant now);
    Optional<List<EvidenceDetail>> completeTool(Claim claim, String toolExecutionId, String summary,
                                               List<Observation> observations, Instant now);
    boolean complete(Claim claim, AnalysisReport report, Instant now);
    boolean fail(Claim claim, ApiError error, Instant now);
    int expire(Instant now);

    /** Only the exclusive worker owner may call this on startup, before claiming queued jobs. */
    int recoverInterrupted(Instant now);
}
