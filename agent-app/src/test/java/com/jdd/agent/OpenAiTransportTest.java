package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationModel.*;
import com.jdd.agent.infra.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "jdd.agent.worker.enabled=false", "spring.datasource.url=jdbc:h2:mem:openai-wire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class OpenAiTransportTest {
    @Autowired ModelCallLedger ledger;
    @Autowired InvestigationRepository repository;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<OpenAiInvestigationModel> clients = new ArrayList<>();
    private final AtomicReference<ModelCallLedger.State> dispatchState = new AtomicReference<>();
    private int status = 200;
    private long delay;
    private String redirect;
    private String response;
    private String investigation;
    private final Clock clock = Clock.systemUTC();
    private final ModelPricing price = new ModelPricing("synthetic-transport", "mock-priced-model", new BigDecimal("2"),
            new BigDecimal("0.2"), new BigDecimal("2.5"), new BigDecimal("12"), true, 272000L, new BigDecimal("2"), new BigDecimal("1.5"));

    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM agent.model_calls"); jdbc.update("DELETE FROM agent.demo_budget");
        jdbc.update("DELETE FROM agent.investigation_evidence"); jdbc.update("DELETE FROM agent.investigations");
        investigation = new InvestigationService(repository, clock).submit(new InvestigationInput("1.0", UUID.randomUUID().toString(),
                1, "synthetic", "합성 테스트 문의", null, null)).investigationId();
        response = completion("{\"summary\":\"synthetic\"}", false, true, "default");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                hits.incrementAndGet(); requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
                String state = jdbc.queryForObject("SELECT state FROM agent.model_calls ORDER BY created_at DESC LIMIT 1", String.class);
                dispatchState.set(ModelCallLedger.State.valueOf(state));
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("x-request-id", "request-synthetic");
                if (redirect != null) exchange.getResponseHeaders().add("Location", redirect);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException clientDisconnected) { /* Deliberately timed-out test client. */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { clients.forEach(OpenAiInvestigationModel::close); server.stop(0); executor.shutdownNow(); }

    @Test void disabledGateBlocksTheActualSocketAndCreatesNoBudget() {
        var model = model(false, 128 * 1024, Duration.ofSeconds(2));
        assertThatThrownBy(() -> model.next(request(1, List.of()))).isInstanceOf(PaidModelGate.Rejected.class);
        assertThat(hits.get()).isZero(); assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.demo_budget", Integer.class)).isZero();
    }
    @Test void springAiSendsVersionedPromptStrictToolsAndSchemaAndPreservesNativeUsage() {
        response = completion(null, true, true, "default");
        var model = model(true, 128 * 1024, Duration.ofSeconds(2));
        var reply = model.next(request(1, List.of()));
        assertThat(reply.toolCalls()).containsExactly(new ToolCall("call-synthetic", "getInventoryContext", "{\"productId\":\"p\"}"));
        assertThat(hits.get()).isEqualTo(1); assertThat(dispatchState.get()).isEqualTo(ModelCallLedger.State.DISPATCHED);
        var sent = requests.getFirst();
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo(InvestigationPromptLoader.load().text());
        assertThat(sent.path("tools").get(0).path("function").path("strict").asBoolean()).isTrue();
        assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(sent.path("response_format").path("json_schema").path("schema").path("required").size()).isEqualTo(7);
        assertThat(sent.path("parallel_tool_calls").asBoolean()).isFalse(); assertThat(sent.path("store").asBoolean()).isFalse();
        var row = entry(); assertThat(row.state()).isEqualTo(ModelCallLedger.State.CONFIRMED);
        assertThat(row.receipt().usage()).isEqualTo(new ModelUsage(100L, 40L, 20L, 30L, 10L));
        assertThat(row.receipt().providerRequestId()).isEqualTo("request-synthetic");
        assertThat(row.receipt().actualServiceTier()).isEqualTo("default"); assertThat(row.receipt().tariffVerified()).isTrue();
        assertThat(row.confirmedUsd()).isEqualByComparingTo("0.000659");
        assertThat(row.request().maximumCost()).isEqualByComparingTo("5.323728");
        assertThat(json.readTree(row.request().modelOptionsJson()).path("estimateOnly").asBoolean()).isTrue();
    }
    @Test void followupContainsOriginalToolCallAndStoredEvidenceAndCountsOnlyEachResponse() {
        var model = model(true, 128 * 1024, Duration.ofSeconds(2));
        response = completion(null, true, true, "default");
        var first = model.next(request(1, List.of()));
        var evidence = new EvidenceDetail("saved-evidence", EvidenceType.DATA, "합성 관측", clock.instant(),
                Map.of("schema", "commerce", "table", "product_stock"), Map.of("quantity", -1), false);
        response = completion("{}", false, true, "default");
        model.next(request(2, List.of(Message.assistant(first), Message.tool(first.toolCalls().getFirst(), List.of(evidence), "서버 저장 근거"))));
        var messages = requests.get(1).path("messages");
        assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call-synthetic");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call-synthetic");
        assertThat(messages.get(3).path("content").asText()).contains("saved-evidence", "quantity", "서버 저장 근거");
        assertThat(hits.get()).isEqualTo(2);
        assertThat(ledger.totals().confirmedUsd()).isEqualByComparingTo("0.001318");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT call_id) FROM agent.model_calls", Integer.class)).isEqualTo(2);
    }
    @Test void missingCacheWritesRemainUnknownAndBlockAnotherPaidDispatch() {
        response = completion("{}", false, false, "default");
        var model = model(true, 128 * 1024, Duration.ofSeconds(2)); model.next(request(1, List.of()));
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(entry().receipt().usage().cacheWriteTokens()).isNull();
        assertThat(entry().receipt().usage().inputTokens()).isEqualTo(100);
        assertThatThrownBy(() -> model.next(request(2, List.of()))).isInstanceOf(PaidModelGate.Rejected.class);
        assertThat(hits.get()).isEqualTo(1);
    }
    @Test void unexpectedServiceTierKeepsObservedUsageButDoesNotInventAPrice() {
        response = completion("{}", false, true, "priority"); model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of()));
        var row = entry(); assertThat(row.state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(row.receipt().actualServiceTier()).isEqualTo("priority"); assertThat(row.receipt().tariffVerified()).isFalse();
        assertThat(row.receipt().usage().outputTokens()).isEqualTo(40); assertThat(row.confirmedUsd()).isNull();
    }
    @Test void authenticationFailureIsConfigurationAndDoesNotRetryOrClaimZeroUsage() {
        status = 401; response = "{\"error\":{\"message\":\"do not expose this provider text\"}}";
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class).satisfies(error -> assertThat(((InvestigationFailure) error).error().code()).isEqualTo("LLM_CONFIGURATION_ERROR"));
        assertThat(hits.get()).isEqualTo(1); assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(entry().receipt().usage()).isNull();
    }
    @Test void rateLimitHasOneHttpAttemptAndPreservesUnknownLiability() {
        status = 429; response = "{\"error\":{\"message\":\"synthetic rate limit\",\"type\":\"rate_limit_error\"}}";
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class).satisfies(error -> assertThat(((InvestigationFailure) error).error().code()).isEqualTo("LLM_UNAVAILABLE"));
        assertThat(hits.get()).isEqualTo(1); assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
    }
    @ParameterizedTest
    @ValueSource(strings = {"credit_balance_exhausted", "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "insufficient_quota"})
    void providerBillingLimitsAreNotPresentedAsTransientFailures(String code) {
        status = 429;
        response = json.writeValueAsString(Map.of("error", Map.of("code", code, "type", "insufficient_quota", "message", "private-provider-canary")));
        try (var model = model(true, 128 * 1024, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> model.next(request(1, List.of())))
                    .isInstanceOf(InvestigationFailure.class).satisfies(error -> {
                        var api = ((InvestigationFailure) error).error();
                        assertThat(api.code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED");
                        assertThat(api.retryable()).isFalse();
                        assertThat(api.message()).doesNotContain("private-provider-canary");
                    });
            assertThat(hits.get()).isEqualTo(1);
            assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
            assertThat(entry().confirmedUsd()).isNull(); assertThat(entry().receipt().usage()).isNull();
            assertThat(entry().receipt().outcome()).contains(code);
            assertThatThrownBy(() -> model.next(request(2, List.of()))).isInstanceOf(PaidModelGate.Rejected.class);
            assertThat(hits.get()).isEqualTo(1);
        }
    }

    @Test void broaderInsufficientQuotaTypeIsRecognizedWithoutTrustingArbitraryErrorText() {
        status = 429;
        response = "{\"error\":{\"code\":\"private-unknown-code\",\"type\":\"insufficient_quota\",\"message\":\"private-provider-canary\"}}";
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class).satisfies(error -> assertThat(((InvestigationFailure) error).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED"));
        assertThat(entry().receipt().outcome()).contains("insufficient_quota").doesNotContain("private-");
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(hits.get()).isEqualTo(1);
    }
    @Test void timeoutKeepsReservationAndDoesNotDispatchAnAutomaticRetry() {
        delay = 500;
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofMillis(150)).next(request(1, List.of()))).isInstanceOf(PaidModelGate.Rejected.class);
        assertThat(hits.get()).isEqualTo(1); assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(ledger.totals().unknownUsd()).isEqualByComparingTo("5.323728");
    }
    @Test void oversizedPayloadIsRejectedBeforeReservationOrTransport() {
        assertThatThrownBy(() -> model(true, 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class).satisfies(error -> assertThat(((InvestigationFailure) error).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED"));
        assertThat(hits.get()).isZero(); assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
    }
    @Test void specialTokenLookingEvidenceIsCountedAsOrdinaryTextWithoutDroppingIt() {
        var original = request(1, List.of(Message.feedback("literal evidence: <|endoftext|> <|fim_prefix|> 한국어")));
        model(true, 128 * 1024, Duration.ofSeconds(2)).next(original);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(requests.getFirst().path("messages").get(2).path("content").asText())
                .isEqualTo("literal evidence: <|endoftext|> <|fim_prefix|> 한국어");
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.CONFIRMED);
    }
    @Test void redirectIsNotFollowedAndCannotSendCredentialsToAnotherEndpoint() {
        var redirected = new AtomicInteger();
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        status = 307; redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected";
        response = "{}";
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class);
        assertThat(hits.get()).isEqualTo(1); assertThat(redirected.get()).isZero();
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
    }
    @Test void inconsistentUsageCannotProduceAConfirmedCharge() {
        response = response.replace("\"total_tokens\":140", "\"total_tokens\":139");
        model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of()));
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(entry().receipt().usage()).isNull(); assertThat(entry().confirmedUsd()).isNull();
    }
    @Test void unexpectedActualModelPreservesUsageAndItsUnresolvedReservation() {
        response = response.replace("\"model\":\"mock-priced-model\"", "\"model\":\"unpriced-model\"");
        model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of()));
        assertThat(entry().state()).isEqualTo(ModelCallLedger.State.UNKNOWN);
        assertThat(entry().receipt().actualModel()).isEqualTo("unpriced-model");
        assertThat(entry().receipt().usage().inputTokens()).isEqualTo(100);
    }
    @Test void malformedCompletionFailsAfterRecordingTheObservedCharge() {
        response = response.replace("\"choices\":[", "\"ignored_choices\":[");
        assertThatThrownBy(() -> model(true, 128 * 1024, Duration.ofSeconds(2)).next(request(1, List.of())))
                .isInstanceOf(InvestigationFailure.class);
        assertThat(hits.get()).isEqualTo(1); assertThat(entry().state()).isEqualTo(ModelCallLedger.State.CONFIRMED);
        assertThat(entry().confirmedUsd()).isEqualByComparingTo("0.000659");
    }
    @Test void oldSingleTierPriceAndReceiptJsonRemainReadable() {
        var old = json.readValue("{\"version\":\"old\",\"model\":\"mock\",\"input\":2,\"cachedInput\":0.2,\"cacheWrite\":2.5,\"output\":12,\"chargesCacheWrites\":true}", ModelPricing.class);
        assertThat(old.longContextThreshold()).isNull(); assertThat(old.maximumCost(1000, 200)).isEqualByComparingTo("0.0049");
        var receipt = json.readValue("{\"providerRequestId\":null,\"actualModel\":null,\"usage\":null,\"outcome\":\"legacy\",\"finishedAt\":\"2026-09-21T00:00:00Z\"}", ModelCallLedger.Receipt.class);
        assertThat(receipt.actualServiceTier()).isNull(); assertThat(receipt.tariffVerified()).isNull();
    }
    private OpenAiInvestigationModel model(boolean allowed, int maxBytes, Duration timeout) {
        var authorization = allowed ? new PaidModelGate.Authorization(true, true, "synthetic-wire", clock.instant().plusSeconds(60), Set.of(price.model())) : PaidModelGate.Authorization.disabled();
        var gate = new PaidModelGate(authorization, new ModelCallLedger.Budget("synthetic-wire", new BigDecimal("30"), 8, 2), ledger, clock);
        var settings = new OpenAiInvestigationModel.Settings(price, 1_050_000, maxBytes, 32000, 4096, "o200k_base", Duration.ofMillis(100), timeout, "low");
        var client = OpenAiInvestigationModel.localMock(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), settings, gate, json, clock);
        clients.add(client);
        return client;
    }
    private Request request(int iteration, List<Message> history) {
        return new Request(investigation, repository.find(investigation).orElseThrow().input(), InvestigationPromptLoader.load(), iteration,
                List.of(new ToolDefinition("getInventoryContext", "합성 도구 정의", "{\"type\":\"object\",\"properties\":{\"productId\":{\"type\":\"string\"}},\"required\":[\"productId\"],\"additionalProperties\":false}")), history);
    }
    private ModelCallLedger.Entry entry() { return ledger.find(jdbc.queryForObject("SELECT call_id FROM agent.model_calls ORDER BY created_at DESC LIMIT 1", String.class)).orElseThrow(); }
    private String completion(String content, boolean tool, boolean writes, String tier) {
        var message = new java.util.LinkedHashMap<String, Object>(); message.put("role", "assistant"); message.put("content", content);
        if (tool) message.put("tool_calls", List.of(Map.of("id", "call-synthetic", "type", "function", "function", Map.of("name", "getInventoryContext", "arguments", "{\"productId\":\"p\"}"))));
        var details = new java.util.LinkedHashMap<String, Object>(); details.put("cached_tokens", 20); if (writes) details.put("cache_write_tokens", 30);
        return json.writeValueAsString(Map.of("id", "chatcmpl-synthetic", "object", "chat.completion", "created", 1,
                "model", price.model(), "service_tier", tier, "choices", List.of(Map.of("index", 0, "finish_reason", tool ? "tool_calls" : "stop", "message", message)),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 40, "total_tokens", 140, "prompt_tokens_details", details, "completion_tokens_details", Map.of("reasoning_tokens", 10))));
    }
}
