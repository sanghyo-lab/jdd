package com.jdd.agent.infra;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.ApiError;
import com.knuddels.jtokkit.api.EncodingType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.Interceptor;
import okhttp3.Response;
import okio.Buffer;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One Spring AI ChatModel call per iteration. The HTTP boundary owns reservation, dispatch and native usage. */
public final class OpenAiInvestigationModel implements InvestigationModel {
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
    private static final int RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String SERVICE_TIER = "default";
    private final Settings settings;
    private final PaidModelGate gate;
    private final JsonMapper json;
    private final Clock clock;
    private final OpenAiChatModel chat;
    private final okhttp3.OkHttpClient network;
    private final OpenAiChatOptions options;
    private final OpenAiChatProtocol protocol;
    private final JTokkitTokenCountEstimator estimator;
    private final URI endpoint;
    private final Mode mode;
    private final ThreadLocal<Attempt> current = new ThreadLocal<>();
    private static final class Attempt {
        final Request request;
        int dispatches, status;
        PaidModelGate.Rejected gateRejection;
        InvestigationFailure localFailure;
        boolean malformed;
        Attempt(Request request) { this.request = request; }
    }

    public static OpenAiInvestigationModel openAi(String apiKey, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock) {
        return new OpenAiInvestigationModel(URI.create("https://api.openai.com/v1"), apiKey, settings, gate, json, clock, Mode.OPENAI);
    }
    /** The test factory accepts only an explicit loopback server and still requires the persistent cost gate. */
    public static OpenAiInvestigationModel localMock(URI base, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                || !"/v1".equals(base.getPath()) || base.getRawQuery() != null || base.getFragment() != null || base.getUserInfo() != null)
            throw new IllegalArgumentException("Mock transport must use the explicit IPv4 loopback /v1 endpoint");
        return new OpenAiInvestigationModel(base, "synthetic-key", settings, gate, json, clock, Mode.MOCK);
    }
    private OpenAiInvestigationModel(URI base, String apiKey, Settings settings, PaidModelGate gate, JsonMapper json, Clock clock, Mode mode) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Model credential is missing");
        this.settings = settings; this.gate = gate; this.json = json; this.clock = clock; this.mode = mode;
        endpoint = URI.create(base.toString() + "/chat/completions");
        protocol = new OpenAiChatProtocol(json);
        estimator = new JTokkitTokenCountEstimator(EncodingType.fromName(settings.tokenizer()).orElseThrow());
        options = OpenAiChatOptions.builder().apiKey(apiKey).baseUrl(base.toString()).model(settings.pricing().model())
                .maxRetries(0).timeout(settings.requestTimeout()).maxCompletionTokens(settings.maxOutputTokens())
                .reasoningEffort(settings.reasoningEffort()).serviceTier(SERVICE_TIER).store(false).build();
        network = new okhttp3.OkHttpClient.Builder().connectTimeout(settings.connectTimeout())
                .readTimeout(settings.requestTimeout()).callTimeout(settings.requestTimeout())
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        // Spring AI's client builder enables connection recovery and does not expose a switch.
        // Its interceptor is therefore a terminal transport: only this explicitly non-retrying client sends bytes.
        chat = OpenAiChatModel.builder().options(options).httpClientBuilderCustomizer(builder -> builder
                .timeout(settings.requestTimeout()).interceptor(this::metered)).build();
    }
    @Override public Mode mode() { return mode; }
    @Override public Reply next(Request request) {
        if (current.get() != null) throw InvestigationFailure.modelConfiguration();
        var attempt = new Attempt(request);
        current.set(attempt);
        try {
            var response = chat.call(protocol.prompt(request, options));
            if (response == null || response.getResults().size() != 1) throw InvestigationFailure.unavailable();
            var generation = response.getResult();
            if ("length".equalsIgnoreCase(generation.getMetadata().getFinishReason())) throw limitFailure();
            var message = generation.getOutput();
            Object refusal = message.getMetadata().get("refusal");
            if (refusal instanceof String value && !value.isBlank())
                throw new InvestigationFailure(new ApiError("REPORT_VALIDATION_FAILED", "모델이 조사 보고서를 반환하지 못했습니다.", false));
            return new Reply(message.getText(), message.getToolCalls().stream()
                    .map(call -> new ToolCall(call.id(), call.name(), call.arguments())).toList());
        } catch (RuntimeException error) {
            if (attempt.gateRejection != null) throw attempt.gateRejection;
            if (attempt.localFailure != null) throw attempt.localFailure;
            if (error instanceof InvestigationFailure failure) throw failure;
            if (attempt.status >= 300 && attempt.status < 500 && attempt.status != 408 && attempt.status != 429)
                throw InvestigationFailure.modelConfiguration();
            throw InvestigationFailure.unavailable(); // never expose an SDK error body, key, or user prompt
        } finally { current.remove(); }
    }

    private Response metered(Interceptor.Chain chain) throws IOException {
        try { return dispatch(chain); }
        catch (InvestigationFailure failure) {
            var attempt = current.get();
            if (attempt != null) attempt.localFailure = failure;
            throw failure;
        }
    }

    private Response dispatch(Interceptor.Chain chain) throws IOException {
        Attempt attempt = current.get();
        if (attempt == null || attempt.dispatches != 0 || Thread.currentThread().isInterrupted()) throw InvestigationFailure.modelConfiguration();
        var request = chain.request();
        if (!request.url().uri().equals(endpoint) || !request.method().equals("POST") || request.body() == null)
            throw InvestigationFailure.modelConfiguration();
        long declaredLength = request.body().contentLength();
        if (declaredLength > settings.maxRequestBytes()) throw limitFailure();
        var buffer = new Buffer(); request.body().writeTo(buffer);
        if (buffer.size() > settings.maxRequestBytes()) throw limitFailure();
        String body = buffer.readUtf8();
        int estimatedTokens = estimator.estimate(body);
        if (estimatedTokens > settings.estimatedInputLimit()) throw limitFailure();
        var payload = json.readTree(body);
        if (!settings.pricing().model().equals(payload.path("model").asText())
                || payload.path("max_completion_tokens").asLong() != settings.maxOutputTokens()
                || !SERVICE_TIER.equals(payload.path("service_tier").asText()) || payload.path("stream").asBoolean())
            throw InvestigationFailure.modelConfiguration();
        var metadata = Map.of("tokenizer", settings.tokenizer(), "estimatedInputTokens", estimatedTokens,
                "estimateOnly", true, "requestBytes", body.getBytes(StandardCharsets.UTF_8).length,
                "reasoningEffort", settings.reasoningEffort(), "maxOutputTokens", settings.maxOutputTokens(),
                "maxRetries", 0, "requestSha256", SourceEvidenceTools.sha256(body.getBytes(StandardCharsets.UTF_8)));
        var call = new ModelCallLedger.Request(UUID.randomUUID().toString(), attempt.request.investigationId(), attempt.request.iteration(),
                endpoint.toString(), SERVICE_TIER, attempt.request.prompt().version(), attempt.request.prompt().sha256(),
                ReadOnlyInvestigationTools.SCHEMA_VERSION, json.writeValueAsString(metadata), settings.pricing(),
                settings.modelInputCeiling(), settings.maxOutputTokens());
        Response[] received = new Response[1];
        boolean returned = false;
        try {
            Response response = gate.call(call, () -> {
                attempt.dispatches++;
                try {
                    received[0] = network.newCall(request).execute();
                    attempt.status = received[0].code();
                    try (var peek = received[0].peekBody(RESPONSE_BYTES + 1L)) {
                        byte[] bytes = peek.bytes();
                        JsonNode parsed = null;
                        if (bytes.length <= RESPONSE_BYTES) {
                            try { parsed = json.readTree(bytes); } catch (RuntimeException malformed) { /* unknown usage retained */ }
                        }
                        var usage = parsed == null ? null : OpenAiUsageParser.parse(parsed);
                        String actualModel = parsed == null || !parsed.path("model").isString() ? null : parsed.path("model").asText();
                        String tier = parsed == null || !parsed.path("service_tier").isString() ? null : parsed.path("service_tier").asText();
                        boolean tariffVerified = attempt.status == 200 && SERVICE_TIER.equals(tier) && usage != null
                                && usage.inputTokens() != null && usage.outputTokens() != null
                                && usage.inputTokens() <= settings.modelInputCeiling() && usage.outputTokens() <= settings.maxOutputTokens();
                        attempt.malformed = attempt.status == 200 && (parsed == null || !parsed.path("choices").isArray() || parsed.path("choices").size() != 1);
                        return new PaidModelGate.Result<>(received[0], new ModelCallLedger.Receipt(received[0].header("x-request-id"),
                                actualModel, usage, "HTTP_" + attempt.status + (tariffVerified ? "" : "_TARIFF_UNVERIFIED"),
                                clock.instant(), tier, tariffVerified));
                    }
                } catch (IOException transport) { throw new UncheckedIOException("Model transport failed", transport); }
            });
            if (attempt.malformed) throw InvestigationFailure.unavailable();
            returned = true;
            return response;
        } catch (PaidModelGate.Rejected rejected) {
            attempt.gateRejection = rejected;
            throw rejected;
        } finally {
            if (!returned && received[0] != null) received[0].close();
        }
    }
    private static InvestigationFailure limitFailure() {
        return new InvestigationFailure(new ApiError("INVESTIGATION_BUDGET_EXCEEDED", "조사 입력·출력 또는 호출 한도를 초과했습니다.", false));
    }
}
