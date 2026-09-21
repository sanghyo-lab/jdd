package com.jdd.agent;

import com.jdd.agent.domain.InvestigationModel;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local integration diagnostics, not a business API or a completed MVP. */
@RestController
public class RuntimeController {
    private final JdbcTemplate jdbc;
    private final String buildId;
    private final String commitSha;
    private final InvestigationModel model;
    private final boolean workerEnabled;
    private final Map<String, String> llm;

    public RuntimeController(JdbcTemplate jdbc,
            @Value("${jdd.build-id}") String buildId,
            @Value("${jdd.commit-sha}") String commitSha, InvestigationModel model,
            @Value("${jdd.agent.worker.enabled:true}") boolean workerEnabled, Environment environment) {
        this.jdbc = jdbc;
        this.buildId = buildId;
        this.commitSha = commitSha;
        this.model = model;
        this.workerEnabled = workerEnabled;
        // The model factory uses this same Environment. Capture configuration at startup;
        // this is neither a successful authentication check nor the actual response model.
        String configuredModel = switch (model.mode()) {
            case CODEX_OAUTH -> setting(environment, "CODEX_MODEL");
            case OPENAI -> setting(environment, "OPENAI_MODEL");
            case MOCK -> "mock";
            default -> "";
        };
        this.llm = Map.of("runtime", setting(environment, "APP_RUNTIME"),
                "provider", setting(environment, "LLM_PROVIDER"), "configuredModel", configuredModel);
    }

    private static String setting(Environment environment, String name) {
        String value = environment.getProperty(name);
        return value == null ? "" : value;
    }

    @GetMapping("/internal/runtime")
    public Map<String, Object> runtime() {
        String storedName = jdbc.queryForObject(
                "SELECT service_name FROM agent.bootstrap_probe WHERE id = 'bootstrap'", String.class);
        return Map.of("service", storedName, "buildId", buildId, "commitSha", commitSha,
                "stage", "IMPLEMENTING", "schema", "agent", "businessReady", false,
                "investigationModel", model.mode().name(), "workerEnabled", workerEnabled, "llm", llm);
    }
}
