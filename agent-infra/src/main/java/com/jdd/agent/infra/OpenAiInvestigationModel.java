package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.ApiError;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Deployment-only API-key Responses adapter. Every attempt uses the durable paid-call gate. */
public final class OpenAiInvestigationModel implements InvestigationModel, AutoCloseable {
    public record Settings(ModelPricing pricing, long modelInputCeiling, int maxRequestBytes, int estimatedInputLimit,
                           int maxOutputTokens, String tokenizer, Duration connectTimeout, Duration requestTimeout,
                           String reasoningEffort) {
        public Settings {
            if (pricing == null || modelInputCeiling < 1 || modelInputCeiling > 2_000_000
                    || maxRequestBytes < 1024 || maxRequestBytes > 512 * 1024 || estimatedInputLimit < 1
                    || estimatedInputLimit > modelInputCeiling || maxOutputTokens < 1 || maxOutputTokens > 8192
                    || connectTimeout == null || requestTimeout == null || connectTimeout.isNegative()
                    || connectTimeout.isZero() || requestTimeout.isNegative() || requestTimeout.isZero()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0 || requestTimeout.compareTo(Duration.ofSeconds(120)) > 0
                    || tokenizer == null || EncodingType.fromName(tokenizer).isEmpty()
                    || !List.of("none", "low", "medium", "high", "xhigh", "max").contains(reasoningEffort))
                throw new IllegalArgumentException("Invalid OpenAI model settings");
        }
    }
    private static final EncodingRegistry ENCODINGS = Encodings.newLazyEncodingRegistry();
    private static final List<String> BILLING_CODES = List.of("credit_balance_exhausted", "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "insufficient_quota");
    private final Settings settings;
    private final PaidModelGate gate;
    private final JsonMapper json;
    private final Clock clock;
    private final ResponsesHttp network;
    private final ResponsesProtocol protocol;
    private final URI endpoint;
    private final String apiKey;
    private final Mode mode;
    public static OpenAiInvestigationModel openAi(String apiKey, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock) {
        return new OpenAiInvestigationModel(URI.create("https://api.openai.com/v1/responses"), apiKey, settings, gate, json, clock, Mode.OPENAI);
    }
    /** Explicit synthetic loopback transport; never selected from an environment variable. */
    public static OpenAiInvestigationModel localMock(URI base, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock) {
        return new OpenAiInvestigationModel(ResponsesHttp.loopback(URI.create(base + "/responses")), "synthetic-key", settings, gate, json, clock, Mode.MOCK);
    }
    private OpenAiInvestigationModel(URI endpoint, String apiKey, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock, Mode mode) {
        this.endpoint = endpoint; this.apiKey = apiKey; this.settings = settings; this.gate = gate;
        this.json = json; this.clock = clock; this.mode = mode;
        protocol = new ResponsesProtocol(json); network = new ResponsesHttp(json, settings.connectTimeout(), settings.requestTimeout());
    }
    @Override public Mode mode() { return mode; }
    @Override public void close() { network.close(); }
    @Override public Reply next(Request request) {
        var payload = protocol.payload(request, settings.pricing().model());
        payload.put("max_output_tokens", settings.maxOutputTokens()); payload.put("service_tier", "default");
        payload.put("reasoning", Map.of("effort", settings.reasoningEffort()));
        String body = json.writeValueAsString(payload);
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > settings.maxRequestBytes()) throw limitFailure();
        int tokens = ENCODINGS.getEncoding(EncodingType.fromName(settings.tokenizer()).orElseThrow()).countTokensOrdinary(body);
        if (tokens > settings.estimatedInputLimit()) throw limitFailure();
        var metadata = Map.of("tokenizer", settings.tokenizer(), "estimatedInputTokens", tokens, "estimateOnly", true,
                "requestBytes", bytes, "reasoningEffort", settings.reasoningEffort(), "maxOutputTokens", settings.maxOutputTokens(),
                "maxRetries", 0, "requestSha256", SourceEvidenceTools.sha256(body.getBytes(StandardCharsets.UTF_8)));
        var call = new ModelCallLedger.Request(UUID.randomUUID().toString(), request.investigationId(), request.iteration(),
                endpoint.toString(), "default", request.prompt().version(), request.prompt().sha256(),
                ReadOnlyInvestigationTools.SCHEMA_VERSION, json.writeValueAsString(metadata), settings.pricing(),
                settings.modelInputCeiling(), settings.maxOutputTokens());
        ResponsesHttp.Received received;
        try {
            received = gate.call(call, () -> {
                var result = network.post(endpoint, Map.of("Authorization", "Bearer " + apiKey), body);
                var response = result.response();
                var usage = response == null ? null : OpenAiUsageParser.parse(response);
                String actual = response == null ? null : response.path("model").asText(null);
                String tier = response == null ? null : response.path("service_tier").asText(null);
                String billing = billingCode(result);
                boolean verified = result.status() == 200 && "default".equals(tier) && usage != null
                        && usage.inputTokens() != null && usage.outputTokens() != null
                        && usage.inputTokens() <= settings.modelInputCeiling() && usage.outputTokens() <= settings.maxOutputTokens();
                return new PaidModelGate.Result<>(result, new ModelCallLedger.Receipt(result.requestId(), actual, usage,
                        "HTTP_" + result.status() + (billing == null ? "" : "_" + billing) + (verified ? "" : "_TARIFF_UNVERIFIED"),
                        clock.instant(), tier, verified));
            });
        } catch (PaidModelGate.Rejected rejected) {
            if (Thread.currentThread().isInterrupted()) throw ResponsesHttp.cancelled();
            throw rejected;
        }
        if (billingCode(received) != null) throw new InvestigationFailure(new ApiError("INVESTIGATION_BUDGET_EXCEEDED",
                "모델 제공자의 크레딧·지출 또는 사용 한도 확인이 필요합니다.", false));
        if (received.status() >= 300 && received.status() < 500 && received.status() != 408 && received.status() != 429)
            throw InvestigationFailure.modelConfiguration();
        if (received.status() != 200 || received.response() == null) throw InvestigationFailure.unavailable();
        if ("incomplete".equals(received.response().path("status").asText())) throw limitFailure();
        return protocol.reply(received.response());
    }
    private static String billingCode(ResponsesHttp.Received received) {
        if (received.response() == null) return null;
        var error = received.response().path("error");
        String code = error.path("code").asText("");
        if (BILLING_CODES.contains(code)) return code;
        return "insufficient_quota".equals(error.path("type").asText()) ? "insufficient_quota" : null;
    }
    private static InvestigationFailure limitFailure() {
        return new InvestigationFailure(new ApiError("INVESTIGATION_BUDGET_EXCEEDED", "조사 입력·출력 또는 호출 한도를 초과했습니다.", false));
    }
}
