package com.jdd.agent.domain;

import com.jdd.agent.domain.InvestigationExecutionRepository.Observation;
import com.jdd.agent.domain.InvestigationModel.ToolCall;
import com.jdd.agent.domain.InvestigationModel.ToolDefinition;
import java.util.List;

public interface InvestigationTools {
    List<ToolDefinition> definitions();
    /** Returns safe, deterministic argument errors; must not query evidence or execute model-provided code. */
    List<String> validate(ToolCall call);
    List<Observation> execute(ToolCall call);
}
