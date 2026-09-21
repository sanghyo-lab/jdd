package com.jdd.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Investigation(String schemaVersion, String investigationId, String ticketId,
                            int ticketVersion, Status status, Instant createdAt, Instant updatedAt,
                            List<ToolExecution> progress, List<EvidenceSummary> evidence,
                            AnalysisReport report, ApiError error) {
    public enum Status { QUEUED, RUNNING, COMPLETED, NEEDS_INPUT, FAILED }
    public enum ToolStatus { RUNNING, SUCCEEDED, FAILED }
    public enum EvidenceType { DATA, LOG, CODE, POLICY }
    public enum SupportLevel { SUPPORTED, PARTIAL, UNVERIFIED }

    public record Accepted(String investigationId, String ticketId, Status status) {}
    public record ApiError(String code, String message, boolean retryable) {}
    public record ToolExecution(String toolExecutionId, String toolName, ToolStatus status,
                                Instant startedAt, Instant finishedAt, String summary,
                                List<String> evidenceIds, ApiError error) {}
    public record EvidenceSummary(String evidenceId, EvidenceType type, String summary,
                                  Instant observedAt, Map<String, Object> source) {}
    public record EvidenceDetail(String evidenceId, EvidenceType type, String summary,
                                 Instant observedAt, Map<String, Object> source,
                                 Object content, boolean truncated) {}
    public record AnalysisReport(String schemaVersion, String summary, List<Fact> facts,
                                 List<Hypothesis> hypotheses, List<Action> actions,
                                 List<Prevention> prevention, List<MissingInformation> missingInformation) {}
    public record Fact(String id, String description, List<String> evidenceIds) {}
    public record Hypothesis(String id, String description, SupportLevel supportLevel,
                             List<String> evidenceIds, List<String> limitations) {}
    public record Action(String id, String description, List<String> evidenceIds, boolean requiresHumanAction) {}
    public record Prevention(String id, String description, List<String> targetPaths,
                             List<String> evidenceIds, List<String> validationSteps) {}
    public record MissingInformation(String field, String reason) {}
}
