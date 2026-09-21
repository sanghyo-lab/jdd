package com.jdd.scenario;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/** Fixed loopback service endpoints, no redirects or write retries; bounded complete bodies. */
final class LocalHttp implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.function.Consumer<JsonNode> recorder;
    LocalHttp(java.util.function.Consumer<JsonNode> recorder) { this.recorder = recorder; }

    record Response(int status, JsonNode body) {}

    Response request(String base, String method, String path, JsonNode body) {
        return request(base, method, path, body, Map.of(), 12);
    }
    Response request(String base, String method, String path, JsonNode body, Map<String, String> headers, int timeout) {
        URI uri = URI.create(base + path);
        Json.require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() > 0 && uri.getFragment() == null,
                "Only explicit loopback HTTP services are allowed");
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body)));
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(builder.build(), ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = future.get(timeout, TimeUnit.SECONDS);
            JsonNode value = Json.MAPPER.readTree(response.body());
            Json.require(value != null && (value.isObject() || value.isArray()), "HTTP response is not structured JSON");
            recorder.accept(Json.object("at", Instant.now().toString(), "method", method, "path", path,
                    "status", response.statusCode(), "sha256", Json.sha(response.body()), "bytes", response.body().length));
            return new Response(response.statusCode(), value);
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new Json.VerificationFailure("HTTP observation interrupted");
        } catch (Json.VerificationFailure failure) { throw failure; }
        catch (Exception failure) {
            future.cancel(true);
            throw new Json.VerificationFailure("HTTP observation failed: " + method + " " + path);
        }
    }
    JsonNode expect(String base, String method, String path, JsonNode body, int status) {
        Response response = request(base, method, path, body);
        Json.require(response.status == status, "Unexpected HTTP " + response.status + " for " + method + " " + path);
        return response.body;
    }
    @Override public void close() { client.close(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private static final int LIMIT = 4 * 1024 * 1024;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > LIMIT - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("HTTP body limit")); return;
                }
                byte[] piece = new byte[buffer.remaining()]; buffer.get(piece); bytes.writeBytes(piece);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
