package com.jdd.scenario;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalHttpTest {
    @Test void refusesExternalUrlsAndDoesNotFollowRedirects() throws Exception {
        try (var http = new LocalHttp(ignored -> {})) {
            assertThrows(Json.VerificationFailure.class, () -> http.request("http://example.com", "GET", "/", null));
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Location", "https://example.com/");
                byte[] body = "{}".getBytes(); exchange.sendResponseHeaders(302, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            try { assertEquals(302, http.request("http://127.0.0.1:" + server.getAddress().getPort(), "GET", "/", null).status()); }
            finally { server.stop(0); }
        }
    }

    @Test void boundsBytesWhileReadingTheResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("[\"" + "가".repeat(1_500_000) + "\"]").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } catch (java.io.IOException cancelled) { /* expected */ }
            finally { exchange.close(); }
        }); server.start();
        try (var http = new LocalHttp(ignored -> {})) {
            assertThrows(Json.VerificationFailure.class, () -> http.request("http://127.0.0.1:" + server.getAddress().getPort(), "GET", "/", null));
        } finally { server.stop(0); }
    }

    @Test void wholeBodyDeadlineIncludesStalledResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try { Thread.sleep(1500); exchange.getResponseBody().write("{}".getBytes()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* expected */ }
            finally { exchange.close(); }
        }); server.start();
        try (var http = new LocalHttp(ignored -> {})) {
            long start = System.nanoTime();
            assertThrows(Json.VerificationFailure.class, () -> http.request("http://127.0.0.1:" + server.getAddress().getPort(), "GET", "/", null, Map.of(), 1));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1400);
        } finally { server.stop(0); }
    }
}
