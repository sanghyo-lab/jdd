package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.infra.OpenAiDemoModelFactory;
import com.jdd.agent.infra.InvestigationPromptLoader;
import com.jdd.agent.infra.CommerceEvidenceDatabase;
import com.jdd.agent.infra.CommerceDataTools;
import com.jdd.agent.infra.LogEvidenceTools;
import com.jdd.agent.infra.SourceEvidenceTools;
import com.jdd.agent.infra.ReadOnlyInvestigationTools;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class InvestigationExecutionConfiguration {
    @Bean @ConditionalOnMissingBean InvestigationModel investigationModel(Environment environment, ModelCallLedger ledger, JsonMapper json) {
        return OpenAiDemoModelFactory.create(environment::getProperty, ledger, json, Clock.systemUTC());
    }

    @Bean @ConditionalOnMissingBean InvestigationTools investigationTools(JsonMapper json,
            @Value("${EVIDENCE_DB_URL:jdbc:postgresql://localhost:5432/jdd}") String url,
            @Value("${EVIDENCE_DB_USERNAME:jdd_evidence}") String username,
            @Value("${EVIDENCE_DB_PASSWORD:}") String password,
            @Value("${SOURCE_ROOT:runtime/evidence/source}") String sourceRoot,
            @Value("${LOG_ROOT:runtime/evidence/logs/commerce}") String logRoot,
            @Value("${POLICY_PATH:docs/business-policy.md}") String policyPath) {
        var clock = Clock.systemUTC();
        return new ReadOnlyInvestigationTools(new CommerceDataTools(new CommerceEvidenceDatabase(url, username, password, clock)),
                new LogEvidenceTools(Path.of(logRoot), json, clock),
                new SourceEvidenceTools(Path.of(sourceRoot), Path.of(policyPath), json, clock), json);
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
