package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, expiring deployed API budget allocation. Reading a key or instantiating a client never sends a request. */
public final class OpenAiDeploymentModelFactory {
    private static final String CATALOG = "openai-standard-text-2026-09-21";
    public record Profile(String schemaVersion, String scope, Instant validUntil, String catalogVersion,
                          String model, BigDecimal localBudgetUsd, int maximumCalls, int callsPerInvestigation,
                          int concurrentCalls, int maxRequestBytes, int estimatedInputLimit, int maxOutputTokens,
                          long connectTimeoutMillis, long requestTimeoutMillis, String reasoningEffort) {}
    public record Catalog(String version, Instant checkedAt, List<Candidate> models) {}
    public record Candidate(String source, long inputCeiling, String tokenizer, ModelPricing pricing) {}
    @FunctionalInterface public interface ClientFactory {
        InvestigationModel create(String key, OpenAiInvestigationModel.Settings settings, PaidModelGate gate, JsonMapper json, Clock clock);
    }
    private OpenAiDeploymentModelFactory() {}

    public static InvestigationModel create(Function<String, String> environment, ModelCallLedger ledger, JsonMapper json, Clock clock) {
        return create(environment, ledger, json, clock, OpenAiInvestigationModel::openAi);
    }
    public static InvestigationModel create(Function<String, String> environment, ModelCallLedger ledger, JsonMapper json,
                                           Clock clock, ClientFactory clients) {
        if (!"deployed".equals(environment.apply("APP_RUNTIME")) || !"openai_api".equals(environment.apply("LLM_PROVIDER")))
            throw LlmRuntimeConfiguration.invalid("OpenAI requires deployed/openai_api");
        try {
            var profile = json.readValue(readRegularFile(environment.apply("JDD_AGENT_API_PROFILE"), 16384), Profile.class);
            if (!profile.model().equals(environment.apply("OPENAI_MODEL")))
                throw LlmRuntimeConfiguration.invalid("OPENAI_MODEL must match the API budget profile");
            Instant now = clock.instant();
            if (!"1.0".equals(profile.schemaVersion()) || profile.scope() == null
                    || !profile.scope().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || profile.validUntil() == null || !now.isBefore(profile.validUntil())
                    || profile.validUntil().isAfter(now.plus(Duration.ofHours(24)))
                    || !CATALOG.equals(profile.catalogVersion()) || profile.maximumCalls() < 1 || profile.maximumCalls() > 1000
                    || profile.callsPerInvestigation() < 1 || profile.callsPerInvestigation() > 16
                    || profile.concurrentCalls() < 1 || profile.concurrentCalls() > 4)
                throw new IllegalArgumentException("Invalid demo profile");
            Catalog catalog;
            try (var resource = OpenAiDeploymentModelFactory.class.getResourceAsStream("/models/" + CATALOG + ".json")) {
                if (resource == null) throw new IllegalArgumentException("Missing model catalog");
                catalog = json.readValue(resource, Catalog.class);
            }
            if (!CATALOG.equals(catalog.version()) || catalog.checkedAt().isAfter(now)
                    || now.isAfter(catalog.checkedAt().plus(Duration.ofDays(7))))
                throw new IllegalArgumentException("Tariff catalog requires review");
            var candidate = catalog.models().stream().filter(row -> row.pricing().model().equals(profile.model())).findFirst().orElseThrow();
            var settings = new OpenAiInvestigationModel.Settings(candidate.pricing(), candidate.inputCeiling(), profile.maxRequestBytes(),
                    profile.estimatedInputLimit(), profile.maxOutputTokens(), candidate.tokenizer(),
                    Duration.ofMillis(profile.connectTimeoutMillis()), Duration.ofMillis(profile.requestTimeoutMillis()), profile.reasoningEffort());
            var budget = new ModelCallLedger.Budget(profile.scope(), profile.localBudgetUsd(), profile.callsPerInvestigation(),
                    profile.concurrentCalls(), profile.maximumCalls());
            if (candidate.pricing().maximumCost(candidate.inputCeiling(), profile.maxOutputTokens()).compareTo(budget.limitUsd()) > 0)
                throw new IllegalArgumentException("Allocation cannot reserve one call");
            // Credentials are accessed last and are never included in the profile, model metadata, or error logs.
            String key = environment.apply("OPENAI_API_KEY");
            if (key == null || key.isBlank() || key.length() > 8192 || key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Missing model credential");
            var authorization = new PaidModelGate.Authorization(true, true, profile.scope(), profile.validUntil(), Set.of(profile.model()));
            return clients.create(key, settings, new PaidModelGate(authorization, budget, ledger, clock), json, clock);
        } catch (IOException | RuntimeException invalid) {
            // Parser exceptions may contain profile contents; expose no nested exception.
            throw LlmRuntimeConfiguration.invalid("OPENAI_API_KEY / OPENAI_MODEL / JDD_AGENT_API_PROFILE must be valid (profile, allocation, expiry and tariff catalog)");
        }
    }
    private static String readRegularFile(String value, int maximumBytes) throws IOException {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration file");
        Path path = Path.of(value);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Configuration must be a regular file");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IllegalArgumentException("Configuration exceeds size limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
