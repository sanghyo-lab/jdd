package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.infra.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmRuntimeIsolationTest {
    @TempDir Path directory;
    final JsonMapper json = JsonMapper.builder().build();
    final Clock clock = Clock.systemUTC();
    final ModelCallLedger ledger = mock(ModelCallLedger.class);
    HttpServer server; ExecutorService executor;
    final AtomicInteger oauth = new AtomicInteger(), api = new AtomicInteger();
    final List<AutoCloseable> resources = new ArrayList<>();
    volatile int status = 200; volatile long delay;
    volatile String response = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"한국어\"}]}]}}\r\n\r\n";
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    final List<String> accounts = new CopyOnWriteArrayList<>();
    final List<tools.jackson.databind.JsonNode> bodies = new CopyOnWriteArrayList<>();
    @BeforeEach void prepare() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/oauth", exchange -> {
            oauth.incrementAndGet(); authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            accounts.add(exchange.getRequestHeaders().getFirst("ChatGPT-Account-ID"));
            bodies.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(status, 0);
                // Deliberately split every UTF-8 byte and event boundary on the real socket.
                for (byte value : response.getBytes(StandardCharsets.UTF_8)) { exchange.getResponseBody().write(value); exchange.getResponseBody().flush(); }
            } catch (Exception disconnected) { /* intentional timeout/cancellation */ }
            finally { exchange.close(); }
        });
        server.createContext("/api", exchange -> { api.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start(); auth("first", "workspace-one", 3600);
    }
    @AfterEach void stop() throws Exception { for (var resource : resources) resource.close(); server.stop(0); executor.shutdownNow(); }
    Path authFile() { return directory.resolve("auth.json"); }
    void auth(String label, String account, int expiry) throws Exception {
        String claims = json.writeValueAsString(Map.of("exp", clock.instant().plusSeconds(expiry).getEpochSecond()));
        String token = "eyJhbGciOiJub25lIn0." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + "." + label;
        Files.writeString(authFile(), json.writeValueAsString(Map.of("auth_mode", "chatgpt", "tokens", Map.of("access_token", token, "account_id", account, "refresh_token", "never-read-or-refresh"))));
    }
    LlmRuntimeConfiguration.Clients clients(Duration timeout) {
        return new LlmRuntimeConfiguration.Clients() {
            public InvestigationModel oauth(Path path, String model, JsonMapper mapper, Clock time) {
                var result = CodexOAuthInvestigationModel.localMock(path, model, mapper, time,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth"), timeout);
                resources.add(result); return result;
            }
            public InvestigationModel api(String key, OpenAiInvestigationModel.Settings settings, PaidModelGate gate, JsonMapper mapper, Clock time) {
                api.incrementAndGet(); throw new AssertionError("API adapter must never be created locally");
            }
        };
    }
    InvestigationModel local(Duration timeout) {
        var env = Map.of("APP_RUNTIME", "local", "LLM_PROVIDER", "codex_oauth", "CODEX_MODEL", "explicit-workspace-model",
                "CODEX_AUTH_FILE", authFile().toString(), "OPENAI_API_KEY", "paid-key-canary", "NODE_ENV", "production");
        return LlmRuntimeConfiguration.create(name -> {
            if (name.equals("OPENAI_API_KEY")) throw new AssertionError("Even reading the paid key is forbidden locally");
            return env.get(name);
        }, ledger, json, clock, clients(timeout));
    }
    InvestigationModel.Request request() {
        return new InvestigationModel.Request("synthetic", new InvestigationInput("1.0", "ticket", 1, "key", "합성 문의", null, null),
                InvestigationPromptLoader.load(), 1, List.of(), List.of());
    }
    @Test void localProductionUsesOnlyOauthAndReloadsCredentialsOnEveryRequest() throws Exception {
        var model = local(Duration.ofSeconds(3));
        assertThat(model.next(request()).text()).isEqualTo("한국어");
        auth("second", "workspace-two", 3600);
        assertThat(model.next(request()).text()).isEqualTo("한국어");
        assertThat(authHeaders.get(0)).endsWith(".first"); assertThat(authHeaders.get(1)).endsWith(".second");
        assertThat(accounts).containsExactly("workspace-one", "workspace-two");
        assertThat(bodies.getFirst().path("store").asBoolean()).isFalse();
        assertThat(bodies.getFirst().has("max_output_tokens")).isFalse();
        assertThat(bodies.getFirst().path("stream").asBoolean()).isTrue();
        assertThat(oauth.get()).isEqualTo(2); assertThat(api.get()).isZero(); verifyNoInteractions(ledger);
    }
    @Test void oauthErrorsAndTimeoutNeverFallBackOrAutomaticallyRetry() {
        for (int error : List.of(401, 403, 404, 429, 500)) {
            status = error;
            assertThatThrownBy(() -> local(Duration.ofSeconds(2)).next(request())).isInstanceOf(InvestigationFailure.class)
                    .satisfies(failure -> {
                        var apiError = ((InvestigationFailure) failure).error();
                        assertThat(apiError.message()).doesNotContain("paid-key-canary", "first");
                        if (error == 401 || error == 403) assertThat(apiError.message()).contains("./scripts/llm login");
                    });
        }
        status = 200; delay = 500;
        assertThatThrownBy(() -> local(Duration.ofMillis(100)).next(request())).isInstanceOf(InvestigationFailure.class);
        assertThat(oauth.get()).isEqualTo(6); assertThat(api.get()).isZero(); verifyNoInteractions(ledger);
    }
    @Test void expiredMissingAndApiKeyAuthFilesFailBeforeNetwork() throws Exception {
        auth("expired", "workspace-one", -1);
        assertThatThrownBy(() -> local(Duration.ofSeconds(2)).next(request())).isInstanceOf(InvestigationFailure.class);
        Files.writeString(authFile(), "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"private-canary\"}");
        assertThatThrownBy(() -> local(Duration.ofSeconds(2)).next(request())).isInstanceOf(InvestigationFailure.class);
        Files.delete(authFile());
        assertThatThrownBy(() -> local(Duration.ofSeconds(2)).next(request())).isInstanceOf(InvestigationFailure.class);
        assertThat(oauth.get()).isZero(); assertThat(api.get()).isZero();
    }
    @Test void invalidRuntimePairsFailBeforeCredentialsClientsAndNetwork() {
        for (var pair : List.of(List.of("", ""), List.of("LOCAL", "codex_oauth"), List.of("local", "openai_api"),
                List.of("deployed", "codex_oauth"), List.of("test", "openai_api"), List.of("local", "mock"), List.of("test", ""))) {
            assertThatThrownBy(() -> LlmRuntimeConfiguration.create(name -> switch (name) {
                case "APP_RUNTIME" -> pair.get(0); case "LLM_PROVIDER" -> pair.get(1);
                default -> throw new AssertionError("Invalid pair must fail before other configuration access");
            }, ledger, json, clock, clients(Duration.ofSeconds(1)))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(oauth.get()).isZero(); assertThat(api.get()).isZero(); verifyNoInteractions(ledger);
    }
    @Test void testRuntimeConstructsNoNetworkClientAndReadsNoCredentials() {
        var model = LlmRuntimeConfiguration.create(name -> switch (name) {
            case "APP_RUNTIME" -> "test"; case "LLM_PROVIDER" -> "mock";
            default -> throw new AssertionError("Mock must not read credentials");
        }, ledger, json, clock, clients(Duration.ofSeconds(1)));
        assertThat(model.mode()).isEqualTo(InvestigationModel.Mode.MOCK);
        assertThatThrownBy(() -> model.next(request())).isInstanceOf(InvestigationFailure.class);
        assertThat(oauth.get()).isZero(); assertThat(api.get()).isZero(); verifyNoInteractions(ledger);
    }
    @Test void missingLocalSettingsAndCredentialsInsideRepositoryFailBeforeClientCreation() {
        for (var values : List.of(Map.of("CODEX_AUTH_FILE", authFile().toString()),
                Map.of("CODEX_MODEL", "explicit-model"),
                Map.of("CODEX_MODEL", "explicit-model", "CODEX_AUTH_FILE", Path.of("auth.json").toAbsolutePath().toString()))) {
            var env = new HashMap<>(values); env.put("APP_RUNTIME", "local"); env.put("LLM_PROVIDER", "codex_oauth");
            assertThatThrownBy(() -> LlmRuntimeConfiguration.create(env::get, ledger, json, clock, clients(Duration.ofSeconds(1))))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(resources).isEmpty(); assertThat(oauth.get()).isZero(); assertThat(api.get()).isZero();
    }
    @Test void threadCancellationClosesTheStreamWithoutFallback() throws Exception {
        delay = 3000;
        var model = local(Duration.ofSeconds(10));
        var result = new AtomicReference<InvestigationFailure>();
        var caller = new Thread(() -> { try { model.next(request()); } catch (InvestigationFailure error) { result.set(error); } });
        caller.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (oauth.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        caller.interrupt(); caller.join(1000);
        assertThat(caller.isAlive()).isFalse(); assertThat(result.get().error().message()).contains("취소");
        assertThat(api.get()).isZero(); verifyNoInteractions(ledger);
    }
}
