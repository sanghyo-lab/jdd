package com.jdd.voc.domain;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

public final class AnalysisAccessService {
    private final AnalysisService analyses;
    private final AnalysisWorkRepository work;
    private final AgentGateway agent;
    private final Clock clock;
    public AnalysisAccessService(AnalysisService analyses, AnalysisWorkRepository work, AgentGateway agent, Clock clock) {
        this.analyses = analyses; this.work = work; this.agent = agent; this.clock = clock;
    }

    public AnalysisRequest view(String ticketId, String analysisId, boolean refresh) {
        AnalysisRequest saved = analyses.get(ticketId, analysisId);
        if (refresh && saved.submissionStatus() == AnalysisRequest.SubmissionStatus.SUBMITTED
                && !AnalysisProcessor.terminal(saved.investigation()))
            work.requestRefresh(ticketId, analysisId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return saved;
    }

    public Map<String, Object> evidence(String ticketId, String analysisId, String evidenceId) {
        AnalysisRequest saved = analyses.get(ticketId, analysisId);
        if (saved.investigationId() == null || saved.investigation() == null
                || !(saved.investigation().get("evidence") instanceof List<?> evidence)
                || evidence.stream().noneMatch(item -> item instanceof Map<?, ?> summary
                        && evidenceId.equals(summary.get("evidenceId"))))
            throw new VocFailure("NOT_FOUND", "이 티켓의 분석에 속한 근거를 찾을 수 없습니다.");
        return agent.evidence(saved, evidenceId);
    }
}
