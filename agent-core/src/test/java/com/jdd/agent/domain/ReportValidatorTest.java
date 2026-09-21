package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportValidatorTest {
    private final ReportValidator validator = new ReportValidator();
    private final String sourcePath = "commerce-core/src/main/java/Orders.java";
    private final List<EvidenceSummary> evidence = List.of(
            new EvidenceSummary("data-1", EvidenceType.DATA, "Stored observation", Instant.EPOCH, Map.of()),
            new EvidenceSummary("code-1", EvidenceType.CODE, "Stored source", Instant.EPOCH, Map.of("path", sourcePath)));

    @Test void acceptsNormalAndNeedsInputReportsWithoutInventedCauses() {
        assertTrue(validator.validate(report(observedFact(), List.of(), List.of()), evidence).isEmpty());
        assertFalse(validator.validate(report(List.of(), List.of(), List.of()), evidence).isEmpty());
        var needsInput = new AnalysisReport("1.0", "주문을 구분할 정보가 필요합니다.", List.of(), List.of(),
                List.of(), List.of(), List.of(new MissingInformation("context.orderId", "여러 주문이 있습니다.")));
        assertTrue(validator.validate(needsInput, List.of()).isEmpty());
    }

    @Test void rejectsMissingRequiredFieldsAndNullItems() {
        assertFalse(validator.validate(null, evidence).isEmpty());
        assertFalse(validator.validate(new AnalysisReport("1.0", "", null, null, null, null, null), evidence).isEmpty());
        assertFalse(validator.validate(report(java.util.Arrays.asList((Fact) null), List.of(), List.of()), evidence).isEmpty());
    }

    @Test void requiresFactEvidenceFromThisInvestigation() {
        assertFalse(validator.validate(report(List.of(new Fact("f", "주장", List.of())), List.of(), List.of()), evidence).isEmpty());
        var valid = report(List.of(new Fact("f", "관측", List.of("data-1"))), List.of(), List.of());
        assertTrue(validator.validate(valid, evidence).isEmpty());
        assertFalse(validator.validate(valid, List.of()).isEmpty());
    }

    @Test void checksSupportLevelsAndLimitations() {
        assertFalse(validator.validate(report(observedFact(), List.of(
                new Hypothesis("h", "원인 후보", SupportLevel.SUPPORTED, List.of(), List.of())), List.of()), evidence).isEmpty());
        assertFalse(validator.validate(report(observedFact(), List.of(
                new Hypothesis("h", "원인 후보", SupportLevel.UNVERIFIED, List.of(), List.of())), List.of()), evidence).isEmpty());
        assertTrue(validator.validate(report(observedFact(), List.of(
                new Hypothesis("h", "원인 후보", SupportLevel.PARTIAL, List.of("data-1"), List.of("로그 확인 필요"))), List.of()), evidence).isEmpty());
    }

    @Test void requiresHumanActionsAndObservedSourceTargets() {
        var candidate = new AnalysisReport("1.0", "검토", observedFact(), List.of(),
                List.of(new Action("a", "확인", List.of("data-1"), false)), List.of(), List.of());
        assertFalse(validator.validate(candidate, evidence).isEmpty());
        var observed = new Prevention("p", "변경 제안", List.of(sourcePath), List.of("code-1"), List.of("동시 요청 검증"));
        assertTrue(validator.validate(report(observedFact(), List.of(), List.of(observed)), evidence).isEmpty());
        assertFalse(validator.validate(report(observedFact(), List.of(), List.of(observed)), evidence.subList(0, 1)).isEmpty());
    }

    @Test void rejectsServerFaultsAsUserMissingInformation() {
        var candidate = new AnalysisReport("1.0", "검토", List.of(), List.of(), List.of(), List.of(),
                List.of(new MissingInformation("OPENAI_API_KEY", "API 인증 실패")));
        assertFalse(validator.validate(candidate, evidence).isEmpty());
    }

    private AnalysisReport report(List<Fact> facts, List<Hypothesis> hypotheses, List<Prevention> prevention) {
        return new AnalysisReport("1.0", "관측과 한계를 검토했습니다.", facts, hypotheses, List.of(), prevention, List.of());
    }
    private List<Fact> observedFact() { return List.of(new Fact("observed", "저장한 관측을 확인했습니다.", List.of("data-1"))); }
}
