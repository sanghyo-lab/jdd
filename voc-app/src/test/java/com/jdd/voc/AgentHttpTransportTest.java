package com.jdd.voc;

import com.jdd.voc.domain.AgentGateway;
import com.jdd.voc.domain.AnalysisRequest;
import com.jdd.voc.infra.HttpAgentGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP with explicit synthetic responses; no model or application database. */
@Timeout(10)
class AgentHttpTransportTest {
    private static final int BODY_LIMIT = 4 * 1024 * 1024;
    private static final byte[] ACCEPTED = ("{\"investigationId\":\"fixture\",\"ticketId\":\"ticket\","
            + "\"status\":\"QUEUED\"}").getBytes(StandardCharsets.UTF_8);
    private static final AnalysisRequest.Input INPUT = new AnalysisRequest.Input(
            "1.0", "ticket", 1, "same-key", "합성 전송 검사", Map.of(), null);

    @Test void deadlineIncludesStalledResponseBodyAndAllowsRecoveryWithoutInternalRetry() throws Exception {
        var release = new CountDownLatch(1);
        var headers = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var server = new Upstream(exchange -> {
            int call = calls.incrementAndGet();
            exchange.sendResponseHeaders(202, 0);
            if (call == 2) {
                exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                headers.countDown(); waitFor(release);
            }
            exchange.getResponseBody().write(ACCEPTED);
        }); var gateway = server.gateway(Duration.ofMillis(400));
             var executor = Executors.newSingleThreadExecutor()) {
            assertEquals("fixture", gateway.submit(INPUT).investigationId());
            Future<AgentGateway.Failure> waiting = executor.submit(() -> assertThrows(
                    AgentGateway.Failure.class, () -> gateway.submit(INPUT)));
            try {
                assertTrue(headers.await(2, TimeUnit.SECONDS));
                AgentGateway.Failure failure = waiting.get(2, TimeUnit.SECONDS);
                assertEquals("AGENT_UNAVAILABLE", failure.error().code());
                assertTrue(failure.error().retryable());
                assertEquals(2, calls.get());
            } finally { release.countDown(); waiting.cancel(true); }
            assertEquals("fixture", gateway.submit(INPUT).investigationId());
            assertEquals(3, calls.get());
        }
    }

    @Test void rejectsOversizedUtf8BytesEvenWhenCharacterCountIsBelowTheLimit() throws Exception {
        String body = new String(ACCEPTED, StandardCharsets.UTF_8);
        body = body.substring(0, body.length() - 1) + ",\"padding\":\"" + "가".repeat(1_500_000) + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertTrue(body.length() < BODY_LIMIT);
        assertTrue(bytes.length > BODY_LIMIT);
        try (var server = new Upstream(exchange -> write(exchange, bytes));
             var gateway = server.gateway(Duration.ofSeconds(3))) {
            assertProtocol(assertThrows(AgentGateway.Failure.class, () -> gateway.submit(INPUT)));
        }
    }

    @Test void cancelsOversizedChunkedBodyBeforeTheServerFinishesIt() throws Exception {
        var release = new CountDownLatch(1);
        byte[] chunk = new byte[65536]; java.util.Arrays.fill(chunk, (byte) ' ');
        try (var server = new Upstream(exchange -> {
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().write(ACCEPTED);
            for (int i = 0; i < 64; i++) exchange.getResponseBody().write(chunk);
            exchange.getResponseBody().flush(); waitFor(release);
        }); var gateway = server.gateway(Duration.ofSeconds(5));
             var executor = Executors.newSingleThreadExecutor()) {
            Future<AgentGateway.Failure> waiting = executor.submit(() -> assertThrows(
                    AgentGateway.Failure.class, () -> gateway.submit(INPUT)));
            try {
                assertProtocol(waiting.get(2, TimeUnit.SECONDS));
                assertEquals(1, release.getCount());
            } finally { release.countDown(); waiting.cancel(true); }
        }
    }

    @Test void acceptsExactlyTheByteLimit() throws Exception {
        byte[] bytes = new byte[BODY_LIMIT]; java.util.Arrays.fill(bytes, (byte) ' ');
        System.arraycopy(ACCEPTED, 0, bytes, 0, ACCEPTED.length);
        try (var server = new Upstream(exchange -> write(exchange, bytes));
             var gateway = server.gateway(Duration.ofSeconds(3))) {
            assertEquals("fixture", gateway.submit(INPUT).investigationId());
        }
    }

    @Test void interruptionCancelsTheBodyAndRetainsTheCallingThreadsInterruptFlag() throws Exception {
        var headers = new CountDownLatch(1); var release = new CountDownLatch(1);
        var result = new AtomicReference<Throwable>(); var interrupted = new AtomicReference<Boolean>();
        try (var server = new Upstream(exchange -> {
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
            headers.countDown(); waitFor(release); exchange.getResponseBody().write(ACCEPTED);
        }); var gateway = server.gateway(Duration.ofSeconds(5))) {
            Thread worker = Thread.ofPlatform().start(() -> {
                try { gateway.submit(INPUT); }
                catch (Throwable failure) { result.set(failure); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertTrue(headers.await(2, TimeUnit.SECONDS));
                worker.interrupt(); worker.join(2000);
                assertFalse(worker.isAlive());
                var failure = assertInstanceOf(AgentGateway.Failure.class, result.get());
                assertEquals("AGENT_UNAVAILABLE", failure.error().code());
                assertEquals(Boolean.TRUE, interrupted.get());
            } finally { release.countDown(); worker.interrupt(); worker.join(2000); }
        }
    }

    private static void assertProtocol(AgentGateway.Failure failure) {
        assertEquals("AGENT_PROTOCOL_ERROR", failure.error().code());
        assertFalse(failure.error().retryable());
        assertEquals(502, failure.status());
    }
    private static void write(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(202, body.length); exchange.getResponseBody().write(body);
    }
    private static void waitFor(CountDownLatch release) {
        try { release.await(4, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
    private static final class Upstream implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        Upstream(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/investigations", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    handler.handle(exchange);
                } catch (IOException cancelled) { /* A bounded client is allowed to close this connection early. */ }
            });
            server.start();
        }
        HttpAgentGateway gateway(Duration timeout) {
            return new HttpAgentGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    JsonMapper.builder().build(), Duration.ofSeconds(2), timeout);
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
