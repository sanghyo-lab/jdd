package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportReviewTest {
    private final Fact fact = new Fact("f", "Observed event", List.of("log"));
    private final Hypothesis cause = new Hypothesis("h", "Bounded cause", SupportLevel.SUPPORTED,
            List.of("data", "log", "code", "policy"), List.of());
    private final AnalysisReport draft = new AnalysisReport("1.0", "Original summary", List.of(fact), List.of(cause),
            List.of(new Action("a", "Human proposal", List.of("data"), true)),
            List.of(new Prevention("p", "Prevention", List.of(), List.of("code"), List.of("Verify"))), List.of());

    @Test void appliesOnlySelectedChangesWithoutRewritingOtherItemsOrMutatingDraft() {
        var replacement = new Fact("f", "Narrower observation", List.of("log"));
        var result = new ReportReview(null, List.of(replacement), List.of(), List.of(), List.of(), List.of("a")).applyTo(draft);
        assertEquals(draft.summary(), result.summary());
        assertEquals(List.of(replacement), result.facts());
        assertEquals(List.of(cause), result.hypotheses());
        assertEquals(draft.prevention(), result.prevention());
        assertTrue(result.actions().isEmpty());
        assertEquals(List.of(fact), draft.facts());
        assertEquals(1, draft.actions().size());
    }

    @Test void emptyChangesKeepTheWholeDraftAndSummaryChangeDoesNotRewriteReferences() {
        assertEquals(draft, review(null, List.of(), List.of()).applyTo(draft));
        var result = review("Updated summary", List.of(), List.of()).applyTo(draft);
        assertEquals("Updated summary", result.summary());
        assertEquals(List.of(cause), result.hypotheses());
    }

    @Test void rejectsUnknownCrossSectionDuplicateAndConflictingChangesAtomically() {
        for (var invalid : List.of(
                review(null, List.of(new Fact("new", "Unsupported addition", List.of("log"))), List.of()),
                review(null, List.of(new Fact("h", "Wrong section", List.of("log"))), List.of()),
                review(null, List.of(fact, fact), List.of()),
                review(null, List.of(fact), List.of("f")),
                review(null, List.of(), List.of("unknown")),
                review(null, List.of(), List.of("a", "a")),
                review(null, null, List.of()),
                review(null, List.of(), null))) {
            assertEquals("Invalid report review changes", assertThrows(IllegalArgumentException.class, () -> invalid.applyTo(draft)).getMessage());
            assertEquals(List.of(fact), draft.facts());
            assertEquals(List.of(cause), draft.hypotheses());
        }
    }

    private ReportReview review(String summary, List<Fact> facts, List<String> removed) {
        return new ReportReview(summary, facts, List.of(), List.of(), List.of(), removed);
    }
}
