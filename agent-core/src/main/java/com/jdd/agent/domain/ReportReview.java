package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Replacements/removals of existing draft items only. The resulting report still needs full validation. */
public record ReportReview(String summary, List<Fact> facts, List<Hypothesis> hypotheses,
                           List<Action> actions, List<Prevention> prevention, List<String> removeItemIds) {
    public AnalysisReport applyTo(AnalysisReport draft) {
        var existing = new HashSet<String>();
        draft.facts().forEach(item -> existing.add(item.id()));
        draft.hypotheses().forEach(item -> existing.add(item.id()));
        draft.actions().forEach(item -> existing.add(item.id()));
        draft.prevention().forEach(item -> existing.add(item.id()));
        if (removeItemIds == null) throw invalid();
        var removed = new HashSet<String>();
        for (String id : removeItemIds) if (!existing.contains(id) || !removed.add(id)) throw invalid();
        return new AnalysisReport(draft.schemaVersion(), summary == null ? draft.summary() : summary,
                replace(draft.facts(), facts, Fact::id, removed),
                replace(draft.hypotheses(), hypotheses, Hypothesis::id, removed),
                replace(draft.actions(), actions, Action::id, removed),
                replace(draft.prevention(), prevention, Prevention::id, removed), draft.missingInformation());
    }

    private static <T> List<T> replace(List<T> original, List<T> updates, Function<T, String> id, Set<String> removed) {
        if (updates == null) throw invalid();
        var values = new LinkedHashMap<String, T>();
        original.forEach(item -> values.put(id.apply(item), item));
        var replaced = new HashSet<String>();
        for (T item : updates) {
            if (item == null) throw invalid();
            String key = id.apply(item);
            if (!values.containsKey(key) || removed.contains(key) || !replaced.add(key)) throw invalid();
            values.put(key, item);
        }
        removed.forEach(values::remove);
        return List.copyOf(values.values());
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid report review changes"); }
}
