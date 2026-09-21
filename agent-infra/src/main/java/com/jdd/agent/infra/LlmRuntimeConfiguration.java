package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Explicit runtime selection is the only route to a model. Missing/invalid combinations fail startup. */
public final class LlmRuntimeConfiguration {
    public interface Clients {
        InvestigationModel oauth(Path auth, String model, JsonMapper json, Clock clock);
        InvestigationModel api(String key, OpenAiInvestigationModel.Settings settings, PaidModelGate gate, JsonMapper json, Clock clock);
    }
    private LlmRuntimeConfiguration() {}
    /** Runs before Spring creates database/network beans as well as at adapter selection. */
    public static void validateRuntime(Function<String, String> env) {
        String runtime = env.apply("APP_RUNTIME"), provider = env.apply("LLM_PROVIDER");
        if ("test".equals(runtime) && "mock".equals(provider)) return;
        if ("local".equals(runtime) && "codex_oauth".equals(provider)) {
            model(env.apply("CODEX_MODEL"), "CODEX_MODEL");
            String path = env.apply("CODEX_AUTH_FILE");
            try {
                if (path == null || path.isBlank() || !Path.of(path).isAbsolute()) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw invalid("CODEX_AUTH_FILE requires an absolute project-specific path"); }
            return;
        }
        if ("deployed".equals(runtime) && "openai_api".equals(provider)) {
            model(env.apply("OPENAI_MODEL"), "OPENAI_MODEL");
            String key = env.apply("OPENAI_API_KEY"), profile = env.apply("JDD_AGENT_API_PROFILE");
            if (key == null || key.isBlank() || key.length() > 8192 || key.chars().anyMatch(Character::isISOControl))
                throw invalid("OPENAI_API_KEY deployment secret is required");
            if (profile == null || profile.isBlank()) throw invalid("JDD_AGENT_API_PROFILE is required");
            return;
        }
        throw invalid("required pairs: APP_RUNTIME=local/LLM_PROVIDER=codex_oauth, deployed/openai_api, test/mock");
    }
    public static InvestigationModel create(Function<String, String> env, ModelCallLedger ledger, JsonMapper json, Clock clock) {
        return create(env, ledger, json, clock, OAuthCallJournal.NONE);
    }
    public static InvestigationModel create(Function<String, String> env, ModelCallLedger ledger, JsonMapper json, Clock clock, OAuthCallJournal journal) {
        return create(env, ledger, json, clock, new Clients() {
            public InvestigationModel oauth(Path path, String model, JsonMapper mapper, Clock time) {
                return new CodexOAuthInvestigationModel(path, model, mapper, time, journal);
            }
            public InvestigationModel api(String key, OpenAiInvestigationModel.Settings settings, PaidModelGate gate, JsonMapper mapper, Clock time) {
                return OpenAiInvestigationModel.openAi(key, settings, gate, mapper, time);
            }
        });
    }
    public static InvestigationModel create(Function<String, String> env, ModelCallLedger ledger, JsonMapper json, Clock clock, Clients clients) {
        validateRuntime(env);
        String runtime = env.apply("APP_RUNTIME"), provider = env.apply("LLM_PROVIDER");
        if ("test".equals(runtime) && "mock".equals(provider)) {
            diagnostics(runtime, provider, "mock", false);
            return new InvestigationModel() {
                @Override public Mode mode() { return Mode.MOCK; }
                // Honest offline failure fixture: never fabricates a completed investigation.
                @Override public Reply next(Request request) { throw InvestigationFailure.modelConfiguration(); }
            };
        }
        if ("local".equals(runtime) && "codex_oauth".equals(provider)) {
            String model = model(env.apply("CODEX_MODEL"), "CODEX_MODEL");
            String value = env.apply("CODEX_AUTH_FILE");
            Path path;
            try {
                if (value == null || value.isBlank()) throw new IllegalArgumentException();
                path = Path.of(value);
                if (!path.isAbsolute() || !path.getFileName().toString().equals("auth.json")) throw new IllegalArgumentException();
                Path normalized = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
                if (normalized.startsWith(Path.of(System.getProperty("user.home"), ".codex").toAbsolutePath().normalize()))
                    throw new IllegalArgumentException();
                for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
                    if ((Files.exists(root.resolve(".git")) || Files.exists(root.resolve("settings.gradle"))) && normalized.startsWith(root))
                        throw new IllegalArgumentException();
                }
            } catch (java.io.IOException | RuntimeException invalid) { throw invalid("CODEX_AUTH_FILE requires an absolute project-specific path outside the repository"); }
            var selected = clients.oauth(path, model, json, clock);
            diagnostics(runtime, provider, model, Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
            return selected;
        }
        if ("deployed".equals(runtime) && "openai_api".equals(provider)) {
            String model = model(env.apply("OPENAI_MODEL"), "OPENAI_MODEL");
            var selected = OpenAiDeploymentModelFactory.create(env, ledger, json, clock, clients::api);
            diagnostics(runtime, provider, model, true);
            return selected;
        }
        throw invalid("required pairs: APP_RUNTIME=local/LLM_PROVIDER=codex_oauth, deployed/openai_api, test/mock");
    }
    private static String model(String value, String name) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}") || value.startsWith("replace-"))
            throw invalid(name + " requires an explicit accessible model identifier");
        return value;
    }
    static IllegalStateException invalid(String detail) { return new IllegalStateException("AI configuration error: " + detail); }
    private static void diagnostics(String runtime, String provider, String model, boolean configured) {
        LoggerFactory.getLogger(LlmRuntimeConfiguration.class).info("AI runtime={} provider={} model={} authConfigured={}", runtime, provider, model, configured);
    }
}
