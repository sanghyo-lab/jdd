package com.jdd.agent;

import com.jdd.agent.domain.InvestigationModel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Configuration diagnostics only; no authentication, file access, HTTP or model calls. */
class RuntimeObservationTest {
    @Test void localReadsOnlySelectedPublicConfigurationAndKeepsStartupValues() {
        var values = new HashMap<>(Map.of("APP_RUNTIME", "local", "LLM_PROVIDER", "codex_oauth",
                "CODEX_MODEL", "synthetic-local-model"));
        var environment = limitedEnvironment(values);
        var controller = controller(InvestigationModel.Mode.CODEX_OAUTH, environment);
        values.put("CODEX_MODEL", "changed-after-startup");
        assertThat(controller.runtime()).containsEntry("llm", Map.of("runtime", "local",
                "provider", "codex_oauth", "configuredModel", "synthetic-local-model"));
        assertThat(controller.runtime()).containsEntry("businessReady", false);
        verify(environment).getProperty("CODEX_MODEL");
        verify(environment).getProperty("APP_RUNTIME");
        verify(environment).getProperty("LLM_PROVIDER");
        verifyNoMoreInteractions(environment);
    }

    @Test void deployedDoesNotReadOauthOrEitherCredential() {
        var environment = limitedEnvironment(Map.of("APP_RUNTIME", "deployed", "LLM_PROVIDER", "openai_api",
                "OPENAI_MODEL", "synthetic-api-model"));
        assertThat(controller(InvestigationModel.Mode.OPENAI, environment).runtime())
                .containsEntry("llm", Map.of("runtime", "deployed", "provider", "openai_api",
                        "configuredModel", "synthetic-api-model"));
        verify(environment).getProperty("OPENAI_MODEL");
        verify(environment).getProperty("APP_RUNTIME");
        verify(environment).getProperty("LLM_PROVIDER");
        verifyNoMoreInteractions(environment);
    }

    @Test void mockAndUnknownAdaptersNeverReadModelNamesOrInventReadiness() {
        for (var mode : Set.of(InvestigationModel.Mode.MOCK, InvestigationModel.Mode.UNKNOWN)) {
            var environment = limitedEnvironment(Map.of("APP_RUNTIME", "test", "LLM_PROVIDER", "mock"));
            var result = controller(mode, environment).runtime();
            assertThat(result).containsEntry("businessReady", false).containsEntry("investigationModel", mode.name())
                    .containsEntry("llm", Map.of("runtime", "test", "provider", "mock",
                            "configuredModel", mode == InvestigationModel.Mode.MOCK ? "mock" : ""));
            verify(environment).getProperty("APP_RUNTIME");
            verify(environment).getProperty("LLM_PROVIDER");
            verifyNoMoreInteractions(environment);
        }
    }

    private Environment limitedEnvironment(Map<String, String> allowed) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            if (!allowed.containsKey(name)) throw new AssertionError("Unexpected configuration/credential read: " + name);
            return allowed.get(name);
        });
        return environment;
    }

    private RuntimeController controller(InvestigationModel.Mode mode, Environment environment) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("agent-app");
        InvestigationModel model = new InvestigationModel() {
            @Override public Mode mode() { return mode; }
            @Override public Reply next(Request request) { throw new AssertionError("Diagnostics must not call a model"); }
        };
        return new RuntimeController(jdbc, "synthetic-build", "synthetic-commit", model, true, environment);
    }
}
