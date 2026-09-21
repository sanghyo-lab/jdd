package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.InvestigationModel.*;
import com.jdd.agent.infra.InvestigationPromptLoader;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class InvestigationExecutionConfiguration {
    @Bean @ConditionalOnMissingBean InvestigationModel investigationModel() {
        // An API key in the environment does not enable a paid client.
        return new InvestigationModel() {
            @Override public Mode mode() { return Mode.DISABLED; }
            @Override public Reply next(Request request) { throw InvestigationFailure.modelConfiguration(); }
        };
    }

    @Bean @ConditionalOnMissingBean InvestigationTools investigationTools() {
        return new InvestigationTools() {
            @Override public List<ToolDefinition> definitions() { return List.of(); }
            @Override public List<String> validate(ToolCall call) { return List.of("Tool provider is not configured"); }
            @Override public List<InvestigationExecutionRepository.Observation> execute(ToolCall call) {
                throw new IllegalStateException("Tool provider is not configured");
            }
        };
    }

    @Bean InvestigationRunner investigationRunner(InvestigationRepository repository,
            InvestigationExecutionRepository executions, InvestigationModel model, InvestigationTools tools, JsonMapper json,
            @Value("${jdd.agent.limits.model-calls:8}") int modelCalls,
            @Value("${jdd.agent.limits.tool-calls:24}") int toolCalls,
            @Value("${jdd.agent.limits.report-repairs:1}") int reportRepairs,
            @Value("${jdd.agent.limits.argument-repairs:1}") int argumentRepairs) {
        return new InvestigationRunner(repository, executions, model, tools, InvestigationPromptLoader.load(),
                candidate -> json.readValue(candidate, Investigation.AnalysisReport.class),
                new InvestigationRunner.Limits(modelCalls, toolCalls, reportRepairs, argumentRepairs), Clock.systemUTC());
    }
}
