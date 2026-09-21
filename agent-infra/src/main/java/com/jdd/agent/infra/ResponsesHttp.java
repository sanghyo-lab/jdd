package com.jdd.agent.infra;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import okhttp3.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One direct HTTP attempt. No redirects, SDK retries, CLI processes or provider discovery. */
final class ResponsesHttp implements AutoCloseable {
    static com.jdd.agent.domain.InvestigationFailure cancelled() {
        return new com.jdd.agent.domain.InvestigationFailure(new com.jdd.agent.domain.Investigation.ApiError(
                "LLM_UNAVAILABLE", "조사 모델 호출이 취소되었습니다.", false));
    }
    record Received(int status, String requestId, JsonNode response) {}
    private static final ScheduledExecutorService CANCELLATIONS = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "llm-cancellation"); thread.setDaemon(true); return thread;
    });
    private final OkHttpClient client;
    private final JsonMapper json;
    ResponsesHttp(JsonMapper json, Duration connect, Duration timeout) {
        this.json = json;
        client = new OkHttpClient.Builder().connectTimeout(connect).readTimeout(timeout).callTimeout(timeout)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }
    Received post(URI endpoint, Map<String, String> headers, String body) {
        if (Thread.currentThread().isInterrupted()) throw new UncheckedIOException(new InterruptedIOException("Model call cancelled"));
        var request = new okhttp3.Request.Builder().url(endpoint.toString()).header("User-Agent", "jdd-agent/0.1")
                .header("Accept", "text/event-stream").post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")));
        headers.forEach(request::header);
        var call = client.newCall(request.build());
        Thread owner = Thread.currentThread();
        var cancellation = CANCELLATIONS.scheduleAtFixedRate(() -> { if (owner.isInterrupted()) call.cancel(); }, 0, 25, TimeUnit.MILLISECONDS);
        try (var response = call.execute()) {
            JsonNode parsed = null;
            if (response.code() == 200 && response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                parsed = ResponsesSse.read(response.body().byteStream(), json, ignored -> {});
            } else if (response.code() != 200) {
                byte[] bytes = response.body().byteStream().readNBytes(65537);
                if (bytes.length <= 65536) {
                    try { parsed = json.readTree(bytes); } catch (RuntimeException ignored) { /* never log provider text */ }
                }
            } else throw new IOException("Expected Responses SSE");
            return new Received(response.code(), response.header("x-request-id"), parsed);
        } catch (IOException error) { throw new UncheckedIOException("Model HTTP attempt failed", error); }
        finally { cancellation.cancel(false); }
    }
    static URI loopback(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Test transport requires explicit IPv4 loopback");
        return endpoint;
    }
    @Override public void close() {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
