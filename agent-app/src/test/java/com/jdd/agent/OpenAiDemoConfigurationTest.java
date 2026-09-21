package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.infra.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "jdd.agent.worker.enabled=false", "spring.datasource.url=jdbc:h2:mem:demo-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class OpenAiDemoConfigurationTest {
    @Autowired JsonMapper json;
    @Autowired InvestigationModel defaultModel;
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
    private final ModelCallLedger ledger = mock(ModelCallLedger.class);
    private final AtomicInteger clients = new AtomicInteger();
    private final AtomicReference<OpenAiInvestigationModel.Settings> configured = new AtomicReference<>();

    @Test void ordinaryApplicationIsDisabledAndAKeyAloneDoesNotEnableOrReadCredentials() {
        assertThat(defaultModel.mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        var read = new ArrayList<String>();
        var model = OpenAiDemoModelFactory.create(name -> {
            read.add(name);
            if (name.contains("KEY")) throw new AssertionError("Credentials must not be read while disabled");
            return null;
        }, ledger, json, clock, this::client);
        assertThat(model.mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        assertThat(read).containsExactly("JDD_AGENT_MODEL_MODE");
        assertThat(clients.get()).isZero(); verifyNoInteractions(ledger);
    }

    @Test void bothExplicitDemoSwitchesAreRequiredBeforeProfileOrKeyAccess() {
        for (String missing : new String[]{"JDD_AGENT_DEMO_MODE", "JDD_AGENT_PAID_CALLS_ALLOWED"}) {
            var environment = new HashMap<>(Map.of("JDD_AGENT_MODEL_MODE", "OPENAI", "JDD_AGENT_DEMO_MODE", "true", "JDD_AGENT_PAID_CALLS_ALLOWED", "true"));
            environment.remove(missing);
            var model = OpenAiDemoModelFactory.create(name -> {
                if (name.contains("KEY") || name.endsWith("PROFILE")) throw new AssertionError("Premature sensitive configuration access");
                return environment.get(name);
            }, ledger, json, clock, this::client);
            assertThat(model.mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        }
        assertThat(clients.get()).isZero(); verifyNoInteractions(ledger);
    }

    @Test void explicitProfileSelectsVersionedCandidateWithoutNetworkOrBudgetMutation() throws Exception {
        for (String model : new String[]{"gpt-5.6-luna", "gpt-5.6-terra"}) {
            var profile = profile(); profile.put("model", model);
            assertThat(create(profile, clock).mode()).isEqualTo(InvestigationModel.Mode.MOCK);
            var settings = configured.get();
            assertThat(settings.modelInputCeiling()).isEqualTo(1_050_000);
            assertThat(settings.pricing().model()).isEqualTo(model);
            assertThat(settings.pricing().maximumCost(settings.modelInputCeiling(), settings.maxOutputTokens()))
                    .isEqualByComparingTo(model.endsWith("luna") ? "0.5323728" : "5.323728");
        }
        assertThat(clients.get()).isEqualTo(2); verifyNoInteractions(ledger);
    }

    @Test void invalidProfilesFailClosedBeforeCredentialAccess() throws Exception {
        for (var bad : Map.<String, Object>ofEntries(
                Map.entry("schemaVersion", "2.0"), Map.entry("scope", " "), Map.entry("validUntil", "2026-09-21T09:00:00Z"),
                Map.entry("catalogVersion", "unreviewed"), Map.entry("model", "unregistered"), Map.entry("localBudgetUsd", 31),
                Map.entry("maximumCalls", 0), Map.entry("concurrentCalls", 5), Map.entry("maxOutputTokens", 8193),
                Map.entry("reasoningEffort", "unsupported"), Map.entry("apiKey", "synthetic-do-not-log")).entrySet()) {
            var values = profile(); values.put(bad.getKey(), bad.getValue());
            Path file = directory.resolve("invalid.json"); Files.writeString(file, json.writeValueAsString(values));
            var environment = environment(file);
            var result = OpenAiDemoModelFactory.create(name -> {
                if (name.contains("KEY")) throw new AssertionError("Invalid profile must not read credentials");
                return environment.get(name);
            }, ledger, json, clock, this::client);
            assertThat(result.mode()).as(bad.getKey()).isEqualTo(InvestigationModel.Mode.DISABLED);
        }
        assertThat(clients.get()).isZero(); verifyNoInteractions(ledger);
    }

    @Test void staleCatalogAndOverlongApprovalWindowAreRejected() throws Exception {
        var future = profile(); future.put("validUntil", "2026-09-22T10:00:01Z");
        assertThat(create(future, clock).mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        future.put("validUntil", "2026-09-30T11:00:00Z");
        assertThat(create(future, Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC)).mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        assertThat(clients.get()).isZero();
    }

    @Test void keyFileIsSeparateAndAmbiguousOrMissingKeysDoNotCreateAClient() throws Exception {
        Path file = directory.resolve("profile.json"), key = directory.resolve("synthetic-key.txt");
        Files.writeString(file, json.writeValueAsString(profile())); Files.writeString(key, "synthetic-private-key\n");
        var environment = environment(file); environment.remove("OPENAI_API_KEY");
        assertThat(OpenAiDemoModelFactory.create(environment::get, ledger, json, clock, this::client).mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        environment.put("JDD_AGENT_OPENAI_API_KEY_FILE", key.toString());
        assertThat(OpenAiDemoModelFactory.create(environment::get, ledger, json, clock, this::client).mode()).isEqualTo(InvestigationModel.Mode.MOCK);
        environment.put("OPENAI_API_KEY", "synthetic-other-key");
        assertThat(OpenAiDemoModelFactory.create(environment::get, ledger, json, clock, this::client).mode()).isEqualTo(InvestigationModel.Mode.DISABLED);
        assertThat(clients.get()).isEqualTo(1); verifyNoInteractions(ledger);
    }

    private InvestigationModel create(Map<String, Object> values, Clock time) throws Exception {
        Path file = directory.resolve("profile.json"); Files.writeString(file, json.writeValueAsString(values));
        return OpenAiDemoModelFactory.create(environment(file)::get, ledger, json, time, this::client);
    }
    private Map<String, String> environment(Path profile) {
        return new HashMap<>(Map.of("JDD_AGENT_MODEL_MODE", "OPENAI", "JDD_AGENT_DEMO_MODE", "true", "JDD_AGENT_PAID_CALLS_ALLOWED", "true",
                "JDD_AGENT_DEMO_PROFILE", profile.toString(), "OPENAI_API_KEY", "synthetic-private-key"));
    }
    private Map<String, Object> profile() {
        return new HashMap<>(Map.ofEntries(Map.entry("schemaVersion", "1.0"), Map.entry("scope", "synthetic-demo"),
                Map.entry("validUntil", "2026-09-21T11:00:00Z"), Map.entry("catalogVersion", "openai-standard-text-2026-09-21"),
                Map.entry("model", "gpt-5.6-luna"), Map.entry("localBudgetUsd", 10), Map.entry("maximumCalls", 16),
                Map.entry("callsPerInvestigation", 8), Map.entry("concurrentCalls", 2), Map.entry("maxRequestBytes", 131072),
                Map.entry("estimatedInputLimit", 32000), Map.entry("maxOutputTokens", 4096), Map.entry("connectTimeoutMillis", 3000),
                Map.entry("requestTimeoutMillis", 45000), Map.entry("reasoningEffort", "low")));
    }
    private InvestigationModel client(String key, OpenAiInvestigationModel.Settings settings, PaidModelGate gate, JsonMapper mapper, Clock time) {
        assertThat(key).isEqualTo("synthetic-private-key"); clients.incrementAndGet(); configured.set(settings);
        return new InvestigationModel() {
            @Override public Mode mode() { return Mode.MOCK; }
            @Override public Reply next(Request request) { throw new AssertionError("Configuration must not call the model"); }
        };
    }
}
