package com.jdd.voc.infra;

import com.jdd.voc.domain.AgentGateway;
import com.jdd.voc.domain.AnalysisRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed server origin, bounded requests, no redirects or model credentials. */
public final class HttpAgentGateway implements AgentGateway, AutoCloseable {
    private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "COMPLETED", "NEEDS_INPUT", "FAILED");
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private final URI base;
    private final JsonMapper json;
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpAgentGateway(String baseUrl, JsonMapper json, Duration connectTimeout, Duration requestTimeout) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getRawQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("AGENT_BASE_URL must be a fixed HTTP server URL without credentials");
        if (connectTimeout.isZero() || connectTimeout.isNegative() || requestTimeout.isZero() || requestTimeout.isNegative())
            throw new IllegalArgumentException("Agent HTTP timeouts must be positive");
        this.json = json; this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Accepted submit(AnalysisRequest.Input input) {
        JsonNode result = exchange("api/investigations", json.writeValueAsString(input), 202);
        require(input.ticketId().equals(text(result, "ticketId")) && STATES.contains(text(result, "status")));
        return new Accepted(text(result, "investigationId"));
    }

    @Override public Map<String, Object> investigation(AnalysisRequest analysis) {
        JsonNode result = exchange("api/investigations/" + segment(analysis.investigationId()), null, 200);
        require("1.0".equals(text(result, "schemaVersion"))
                && analysis.investigationId().equals(text(result, "investigationId"))
                && analysis.ticketId().equals(text(result, "ticketId")));
        JsonNode version = result.path("ticketVersion");
        require(version.isIntegralNumber() && version.canConvertToLong() && version.asLong() == analysis.ticketVersion());
        String status = text(result, "status");
        require(STATES.contains(status));
        timestamp(result, "createdAt"); timestamp(result, "updatedAt");
        require(result.path("progress").isArray() && result.path("evidence").isArray());
        Set<String> evidenceIds = new HashSet<>();
        for (JsonNode evidence : result.path("evidence")) {
            require(evidenceIds.add(text(evidence, "evidenceId")));
            require(Set.of("DATA", "LOG", "CODE", "POLICY").contains(text(evidence, "type")));
            text(evidence, "summary"); timestamp(evidence, "observedAt"); require(evidence.path("source").isObject());
        }
        for (JsonNode progress : result.path("progress")) {
            text(progress, "toolExecutionId"); text(progress, "toolName"); timestamp(progress, "startedAt");
            require(Set.of("RUNNING", "SUCCEEDED", "FAILED").contains(text(progress, "status")));
            references(progress.path("evidenceIds"), evidenceIds, false);
        }
        if (status.equals("QUEUED") || status.equals("RUNNING")) {
            require(result.path("report").isNull() && result.path("error").isNull());
        } else if (status.equals("FAILED")) {
            require(result.path("report").isNull());
            errorShape(result.path("error"));
        } else {
            require(result.path("error").isNull());
            report(result.path("report"), evidenceIds, status);
        }
        return json.convertValue(result, OBJECT);
    }

    @Override public Map<String, Object> evidence(AnalysisRequest analysis, String evidenceId) {
        JsonNode result = exchange("api/investigations/" + segment(analysis.investigationId())
                + "/evidence/" + segment(evidenceId), null, 200);
        require(evidenceId.equals(text(result, "evidenceId")) && result.path("truncated").isBoolean()
                && result.has("content") && !result.path("content").isNull());
        // The caller checked ticket ownership. Check the returned detail against the
        // exact summary already observed on that investigation, including its source.
        JsonNode cached = json.valueToTree(analysis.investigation());
        JsonNode expected = null;
        for (JsonNode item : cached.path("evidence"))
            if (evidenceId.equals(item.path("evidenceId").asText())) expected = item;
        require(expected != null);
        for (String field : Set.of("type", "summary", "observedAt", "source"))
            require(expected.path(field).equals(result.path(field)));
        return json.convertValue(result, OBJECT);
    }

    private JsonNode exchange(String path, String body, int expected) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(requestTimeout)
                .header("Accept", "application/json");
        if (body != null) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response;
        var pending = client.sendAsync(request.build(), ignored -> new BoundedBody());
        try {
            // HttpRequest.timeout can end once response headers arrive. Bound the
            // whole body too, and cancel its network subscription on expiry.
            response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw unavailable();
        } catch (ExecutionException transport) {
            for (Throwable cause = transport; cause != null; cause = cause.getCause())
                if (cause instanceof ResponseTooLarge) throw protocol();
            throw unavailable();
        } catch (CancellationException cancelled) {
            throw unavailable();
        }
        JsonNode result = null;
        try {
            result = json.readTree(response.body());
        } catch (RuntimeException malformed) { /* Classify status even when a proxy returns HTML. */ }
        if (response.statusCode() != expected) {
            String code = result == null ? "" : result.path("code").asText("");
            if (!code.matches("[A-Z][A-Z0-9_]{0,79}"))
                code = response.statusCode() >= 500 ? "AGENT_UNAVAILABLE" : "AGENT_REJECTED";
            boolean queue = response.statusCode() == 429 && code.equals("INVESTIGATION_QUEUE_FULL")
                    && result != null && result.path("retryable").isBoolean() && result.path("retryable").asBoolean();
            boolean retryable = queue || response.statusCode() >= 500;
            // Do not persist a proxy/provider's raw message, URL or credential-bearing diagnostic.
            String message = queue ? "Agent 대기열이 가득 찼습니다. 같은 요청으로 다시 전달합니다."
                    : retryable ? "Agent에 연결하지 못했습니다. 마지막 확인 상태를 유지합니다."
                    : "Agent가 요청을 거절했습니다. 오류 코드와 입력을 확인해 주세요.";
            throw new Failure(new AnalysisRequest.Error(code, message, retryable), response.statusCode(),
                    queue ? retryAfter(response.headers().firstValue("Retry-After").orElse("5")) : Duration.ZERO);
        }
        require(result != null && result.isObject());
        return result;
    }

    /** Reject while receiving; never buffer an unbounded body before measuring it. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean finished;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value; delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - received) {
                    finished = true; subscription.cancel(); delegate.onError(new ResponseTooLarge()); return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) {
            if (!finished) { finished = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!finished) { finished = true; delegate.onComplete(); }
        }
    }
    private static final class ResponseTooLarge extends IOException {}

    private static void report(JsonNode report, Set<String> evidence, String status) {
        require(report.isObject() && "1.0".equals(text(report, "schemaVersion")));
        text(report, "summary");
        for (String group : Set.of("facts", "hypotheses", "actions", "prevention", "missingInformation"))
            require(report.path(group).isArray());
        require(status.equals("NEEDS_INPUT") == !report.path("missingInformation").isEmpty());
        for (String group : Set.of("facts", "hypotheses", "actions", "prevention")) {
            for (JsonNode item : report.path(group)) {
                text(item, "id"); text(item, "description");
                boolean required = group.equals("facts");
                if (group.equals("hypotheses")) {
                    String support = text(item, "supportLevel");
                    require(Set.of("SUPPORTED", "PARTIAL", "UNVERIFIED").contains(support));
                    require(item.path("limitations").isArray());
                    required = !support.equals("UNVERIFIED");
                    if (!required && item.path("evidenceIds").isEmpty()) require(!item.path("limitations").isEmpty());
                }
                if (group.equals("actions")) require(item.path("requiresHumanAction").isBoolean()
                        && item.path("requiresHumanAction").asBoolean());
                if (group.equals("prevention")) require(item.path("targetPaths").isArray() && item.path("validationSteps").isArray());
                references(item.path("evidenceIds"), evidence, required);
            }
        }
        for (JsonNode item : report.path("missingInformation")) {
            require(Set.of("message", "context.customerId", "context.orderId", "context.productId", "context.requestId",
                    "context.checkoutKey", "context.occurredAt").contains(text(item, "field")));
            text(item, "reason");
        }
    }

    private static void references(JsonNode references, Set<String> known, boolean required) {
        require(references.isArray() && (!required || !references.isEmpty()));
        for (JsonNode reference : references) require(reference.isString() && known.contains(reference.asText()));
    }
    private static void errorShape(JsonNode error) {
        text(error, "code"); text(error, "message"); require(error.path("retryable").isBoolean());
    }
    private static void timestamp(JsonNode object, String field) {
        try { Instant.parse(text(object, field)); } catch (RuntimeException invalid) { throw protocol(); }
    }
    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        require(value.isString() && !value.asText().isBlank());
        return value.asText();
    }
    private static String segment(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        return encoded.equals(".") || encoded.equals("..") ? encoded.replace(".", "%2E") : encoded;
    }
    private static Duration retryAfter(String value) {
        try {
            Duration delay = value.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (delay.compareTo(Duration.ofDays(365)) > 0) throw protocol();
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (java.time.DateTimeException | NumberFormatException invalid) { return Duration.ofSeconds(5); }
    }
    private static void require(boolean condition) { if (!condition) throw protocol(); }
    private static Failure protocol() {
        return new Failure(new AnalysisRequest.Error("AGENT_PROTOCOL_ERROR", "Agent 응답의 형식 또는 조사 소속을 확인하지 못했습니다.", false), 502, Duration.ZERO);
    }
    private static Failure unavailable() {
        return new Failure(new AnalysisRequest.Error("AGENT_UNAVAILABLE", "Agent 접수·조회 응답을 확인하지 못했습니다.", true), 503, Duration.ZERO);
    }
    @Override public void close() { client.close(); }
}
