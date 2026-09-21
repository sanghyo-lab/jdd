package com.jdd.agent.domain;

import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationModel.ToolCall;
import com.jdd.agent.domain.InvestigationModel.ToolDefinition;
import java.util.List;

public interface InvestigationTools {
    /** The summary also preserves empty-search scope and scan limits without inventing evidence. */
    record Outcome(List<Observation> observations, String summary) {
        public Outcome {
            observations = List.copyOf(observations);
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("Tool summary is required");
        }
    }
    List<ToolDefinition> definitions();
    /** Returns safe, deterministic argument errors; must not query evidence or execute model-provided code. */
    List<String> validate(ToolCall call);
    Outcome execute(ToolCall call);
}
