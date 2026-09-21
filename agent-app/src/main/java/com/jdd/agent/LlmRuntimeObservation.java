package com.jdd.agent;

import com.jdd.agent.domain.InvestigationModel;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Startup configuration only. Never opens credentials, authenticates, or invokes a model. */
@Component
public final class LlmRuntimeObservation {
    private final InvestigationModel.Mode mode;
    private final Map<String, String> configuration;

    public LlmRuntimeObservation(InvestigationModel model, Environment environment) {
        mode = model.mode();
        String configuredModel = switch (mode) {
            case CODEX_OAUTH -> setting(environment, "CODEX_MODEL");
            case OPENAI -> setting(environment, "OPENAI_MODEL");
            case MOCK -> "mock";
            default -> "";
        };
        configuration = Map.of("runtime", setting(environment, "APP_RUNTIME"),
                "provider", setting(environment, "LLM_PROVIDER"), "configuredModel", configuredModel);
    }

    public Map<String, String> configuration() { return configuration; }
    public InvestigationModel.Mode mode() { return mode; }
    public boolean usesActualAdapter() {
        return !configuration.get("configuredModel").isBlank() && (
                mode == InvestigationModel.Mode.CODEX_OAUTH && "local".equals(configuration.get("runtime"))
                        && "codex_oauth".equals(configuration.get("provider"))
                || mode == InvestigationModel.Mode.OPENAI && "deployed".equals(configuration.get("runtime"))
                        && "openai_api".equals(configuration.get("provider")));
    }

    private static String setting(Environment environment, String name) {
        String value = environment.getProperty(name);
        return value == null ? "" : value;
    }
}
