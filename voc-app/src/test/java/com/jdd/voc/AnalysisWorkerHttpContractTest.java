package com.jdd.voc;

import com.jdd.voc.domain.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real VOC HTTP/JDBC and loopback Agent HTTP; all upstream reports are explicit fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.flyway.create-schemas=true", "jdd.voc.worker.enabled=false", "jdd.build-id=worker-contract", "jdd.commit-sha=test"})
@Import(AnalysisWorkerHttpContractTest.TestClock.class)
class AnalysisWorkerHttpContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final FakeAgent AGENT = new FakeAgent();
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisService analyses;
    @Autowired AnalysisWorkRepository work;
    @Autowired AgentGateway gateway;
    @Autowired MutableClock clock;

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>();
        void reset() { value.set(Instant.parse("2026-09-21T00:00:00Z")); }
        void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value.get(); }
    }
    @TestConfiguration static class TestClock {
        @Bean @Primary MutableClock testClock() { var clock = new MutableClock(); clock.reset(); return clock; }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        String url = System.getenv("VOC_TEST_DB_URL");
        if (url != null && !url.matches("jdbc:postgresql://[^/]+/jdd_voc_contract_test(?:\\?.*)?"))
            throw new IllegalArgumentException("Worker HTTP contracts require the isolated jdd_voc_contract_test database");
        properties.add("spring.datasource.url", () -> url == null
                ? "jdbc:h2:mem:worker_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : url);
        properties.add("spring.datasource.username", () -> url == null ? "sa" : System.getenv("VOC_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> url == null ? "" : System.getenv("VOC_TEST_DB_PASSWORD"));
        properties.add("AGENT_BASE_URL", () -> "http://127.0.0.1:" + AGENT.server.getAddress().getPort());
    }

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM voc.analysis_requests"); jdbc.update("DELETE FROM voc.tickets");
        clock.reset(); AGENT.reset();
    }
    @AfterAll static void close() { HTTP.close(); AGENT.close(); }

    @Test void serverWorkerRunsWithoutBrowserAndScopesEvidenceWithoutChangingTicketState() throws Exception {
        String ticket = ticket("원래 문의");
        AnalysisRequest saved = request(ticket, "automatic", 1);
        assertThat(AGENT.posts.get()).isZero();
        AGENT.status.set("COMPLETED");
        try (var worker = new AnalysisWorker(processor(840), 4, 20)) {
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(view(saved).investigation()).containsEntry("status", "COMPLETED"));
        }
        var result = view(saved);
        assertThat(result.submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.SUBMITTED);
        assertThat(result.syncError()).isNull();
        assertThat(result.lastSyncedAt()).isEqualTo(clock.instant());
        assertThat(call("GET", "/api/tickets/" + ticket, null, 200).path("ticket").path("status").asText()).isEqualTo("OPEN");
        String evidencePath = path(saved) + "/evidence/evidence-fixture";
        assertThat(call("GET", evidencePath, null, 200).path("content").asText()).isEqualTo("// synthetic contract fixture");
        int reads = AGENT.evidenceReads.get();
        String other = ticket("다른 문의");
        call("GET", evidencePath.replace(ticket, other), null, 404);
        call("GET", path(saved) + "/evidence/not-owned", null, 404);
        assertThat(AGENT.evidenceReads.get()).isEqualTo(reads);
        assertThat(AGENT.posts.get()).isEqualTo(1);
    }

    @Test void lostAdmissionResponseReplaysTheOriginalSnapshotAfterTicketEditAndProcessorRestart() throws Exception {
        String ticket = ticket("수정 전 문의");
        AnalysisRequest saved = request(ticket, "lost-response", 1);
        AGENT.dropNext.set(true);
        assertThat(processor(840).processNext()).isTrue();
        assertThat(view(saved).submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.PENDING);
        assertThat(view(saved).submissionError().code()).isEqualTo("AGENT_UNAVAILABLE");
        String acceptedId = AGENT.entries.values().iterator().next().id();
        call("PATCH", "/api/tickets/" + ticket, Map.of("expectedVersion", 1, "message", "수정 후 문의",
                "context", Map.of("orderId", "changed")), 200);
        assertThat(processor(840).processNext()).isFalse();
        clock.advance(1);
        assertThat(processor(840).processNext()).isTrue(); // A fresh processor reads only persisted state.
        assertThat(view(saved).investigationId()).isEqualTo(acceptedId);
        assertThat(AGENT.posts.get()).isEqualTo(2);
        assertThat(AGENT.inputs.get(0)).isEqualTo(AGENT.inputs.get(1));
        assertThat(AGENT.inputs.get(1).path("message").asText()).isEqualTo("수정 전 문의");
        AGENT.status.set("NEEDS_INPUT");
        processor(840).processNext();
        assertThat(view(saved).investigation()).containsEntry("status", "NEEDS_INPUT");
        var next = call("POST", "/api/tickets/" + ticket + "/analyses",
                Map.of("requestKey", "new-input", "ticketVersion", 2, "previousInvestigationId", acceptedId), 202);
        assertThat(next.path("input").path("message").asText()).isEqualTo("수정 후 문의");
        assertThat(next.path("input").path("previousInvestigationId").asText()).isEqualTo(acceptedId);
        assertThat(view(saved).input().message()).isEqualTo("수정 전 문의");
    }

    @Test void queueRetryAfterAndRetryBudgetSurviveRestartAndManualReplayUsesTheSameKey() throws Exception {
        var saved = request(ticket("대기열 문의"), "queue-key", 1);
        for (int i=0; i<4; i++) AGENT.postReplies.add(new Reply(429,
                JSON.writeValueAsString(Map.of("code", "INVESTIGATION_QUEUE_FULL", "message", "synthetic full", "retryable", true)), "9"));
        processor(840).processNext();
        assertThat(next(saved)).isEqualTo(clock.instant().plusSeconds(9));
        clock.advance(8); assertThat(processor(840).processNext()).isFalse();
        clock.advance(1); processor(840).processNext();
        assertThat(next(saved)).isEqualTo(clock.instant().plusSeconds(10));
        clock.advance(10); processor(840).processNext();
        assertThat(next(saved)).isEqualTo(clock.instant().plusSeconds(20));
        clock.advance(20); processor(840).processNext();
        assertThat(view(saved).submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.FAILED);
        assertThat(view(saved).submissionError().code()).isEqualTo("INVESTIGATION_QUEUE_FULL");
        assertThat(view(saved).submissionError().retryable()).isTrue();
        assertThat(next(saved)).isNull();
        assertThat(processor(840).processNext()).isFalse();
        assertThat(AGENT.posts.get()).isEqualTo(4);
        assertThat(AGENT.entries).isEmpty();
        assertThat(AGENT.inputs).allMatch(input -> input.equals(AGENT.inputs.get(0)));
        assertThat(jdbc.queryForObject("SELECT delivery_attempts FROM voc.analysis_requests WHERE analysis_request_id=?",
                Integer.class, saved.analysisRequestId())).isEqualTo(4);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<JsonNode>> requests = new ArrayList<>();
            for (int i=0; i<8; i++) requests.add(() -> call("POST", "/api/tickets/" + saved.ticketId() + "/analyses",
                    Map.of("requestKey", saved.input().requestKey(), "ticketVersion", 1), 202));
            for (var future : executor.invokeAll(requests))
                assertThat(future.get().path("analysisRequestId").asText()).isEqualTo(saved.analysisRequestId());
        }
        processor(840).processNext();
        assertThat(view(saved).submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.SUBMITTED);
        assertThat(AGENT.posts.get()).isEqualTo(5);
        assertThat(AGENT.inputs.get(4)).isEqualTo(AGENT.inputs.get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voc.analysis_requests", Integer.class)).isEqualTo(1);
    }

    @Test void transientTransportStopsAfterThreeAttemptsWhilePermanentRejectionNeverAutomaticallyRetries() throws Exception {
        var saved = request(ticket("전달 오류 문의"), "connection", 1);
        for (int i=0; i<3; i++) AGENT.postReplies.add(new Reply(503, "<html>synthetic proxy failure</html>", null));
        processor(840).processNext(); clock.advance(1);
        processor(840).processNext(); clock.advance(2);
        processor(840).processNext();
        assertThat(view(saved).submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.FAILED);
        assertThat(view(saved).submissionError().retryable()).isTrue();
        assertThat(AGENT.posts.get()).isEqualTo(3);
        assertThat(processor(840).processNext()).isFalse();
        var rejected = request(ticket("잘못된 기존 입력"), "invalid", 1);
        AGENT.postReplies.add(new Reply(400, JSON.writeValueAsString(Map.of("code", "INVALID_REQUEST",
                "message", "synthetic-secret-must-not-be-persisted", "retryable", true)), null));
        processor(840).processNext();
        assertThat(view(rejected).submissionError().code()).isEqualTo("INVALID_REQUEST");
        assertThat(view(rejected).submissionError().retryable()).isFalse();
        assertThat(view(rejected).submissionError().message()).doesNotContain("synthetic-secret");
        request(rejected.ticketId(), "invalid", 1);
        assertThat(processor(840).processNext()).isFalse();
        assertThat(AGENT.posts.get()).isEqualTo(4);
    }

    @Test void pollingFailuresPreserveTheLastResultAndModelFailureDoesNotBecomeTicketResolutionOrNewSubmission() throws Exception {
        var saved = request(ticket("조회 문의"), "poll", 1);
        var processor = processor(840);
        processor.processNext(); processor.processNext();
        var observed = view(saved);
        clock.advance(5);
        AGENT.getReplies.add(new Reply(503, "gateway unavailable", null));
        processor.processNext();
        assertThat(view(saved).investigation()).isEqualTo(observed.investigation());
        assertThat(view(saved).lastSyncedAt()).isEqualTo(observed.lastSyncedAt());
        assertThat(view(saved).syncError().code()).isEqualTo("AGENT_UNAVAILABLE");
        clock.advance(5); AGENT.status.set("FAILED"); processor.processNext();
        assertThat(view(saved).investigation()).containsEntry("status", "FAILED");
        assertThat(view(saved).syncError()).isNull();
        assertThat(view(saved).lastSyncedAt()).isEqualTo(clock.instant());
        assertThat(view(saved).submissionStatus()).isEqualTo(AnalysisRequest.SubmissionStatus.SUBMITTED);
        assertThat(call("GET", path(saved), null, 200).path("investigation").path("error").path("code").asText())
                .isEqualTo("LLM_CONFIGURATION_ERROR");
        request(saved.ticketId(), saved.input().requestKey(), 1);
        call("GET", path(saved) + "?refresh=true", null, 200);
        assertThat(processor.processNext()).isFalse();
        assertThat(AGENT.posts.get()).isEqualTo(1);
        assertThat(call("GET", "/api/tickets/" + saved.ticketId(), null, 200).path("ticket").path("status").asText()).isEqualTo("OPEN");
    }

    @Test void observationWindowDoesNotResetOnRestartAndManualRefreshOnlyReadsTheExistingInvestigation() throws Exception {
        var saved = request(ticket("관측 종료 문의"), "observation", 1);
        processor(10).processNext(); processor(10).processNext();
        var observed = view(saved);
        clock.advance(10);
        processor(840).processNext(); // New configuration cannot extend the saved window.
        assertThat(view(saved).syncError().code()).isEqualTo("AGENT_OBSERVATION_EXPIRED");
        assertThat(view(saved).investigation()).isEqualTo(observed.investigation());
        assertThat(view(saved).lastSyncedAt()).isEqualTo(observed.lastSyncedAt());
        assertThat(next(saved)).isNull();
        assertThat(AGENT.gets.get()).isEqualTo(1);
        call("GET", path(saved) + "?refresh=true", null, 200);
        processor(840).processNext();
        assertThat(AGENT.gets.get()).isEqualTo(2);
        assertThat(next(saved)).isNull();
        assertThat(view(saved).syncError().code()).isEqualTo("AGENT_OBSERVATION_EXPIRED");
        AGENT.status.set("COMPLETED");
        call("GET", path(saved) + "?refresh=true", null, 200); processor(840).processNext();
        assertThat(view(saved).investigation()).containsEntry("status", "COMPLETED");
        assertThat(view(saved).syncError()).isNull();
        assertThat(AGENT.posts.get()).isEqualTo(1);
    }

    @Test void expiredWorkerCannotOverwriteTheRecoveredLeaseAndTheAcceptedRequestIsNotDuplicated() throws Exception {
        var saved = request(ticket("작업 점유 복구"), "lease", 1);
        var old = work.claim(clock.instant(), Duration.ofSeconds(30)).orElseThrow();
        String accepted = gateway.submit(old.analysis().input()).investigationId();
        assertThat(work.claim(clock.instant(), Duration.ofSeconds(30))).isEmpty();
        clock.advance(31);
        var recovered = work.claim(clock.instant(), Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(gateway.submit(recovered.analysis().input()).investigationId()).isEqualTo(accepted);
        assertThat(work.submitted(recovered, accepted, clock.instant(), clock.instant().plusSeconds(840))).isTrue();
        assertThat(work.submitted(old, "stale-result", clock.instant(), clock.instant().plusSeconds(840))).isFalse();
        assertThat(view(saved).investigationId()).isEqualTo(accepted);
        assertThat(AGENT.entries).hasSize(1);
        assertThat(AGENT.inputs.get(0)).isEqualTo(AGENT.inputs.get(1));
    }

    @Test void concurrentClaimersOnlyLeaseOneDeliveryAndRefreshDuringPollingIsNotLost() throws Exception {
        var saved = request(ticket("동시 점유"), "claim", 1);
        List<AnalysisWorkRepository.Work> claims = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Optional<AnalysisWorkRepository.Work>>> tasks = new ArrayList<>();
            for (int i=0; i<16; i++) tasks.add(() -> work.claim(clock.instant(), Duration.ofSeconds(30)));
            for (var result : pool.invokeAll(tasks)) result.get().ifPresent(claims::add);
        }
        assertThat(claims).hasSize(1);
        var delivery = claims.get(0);
        work.submitted(delivery, gateway.submit(delivery.analysis().input()).investigationId(), clock.instant(), clock.instant().plusSeconds(10));
        var polling = work.claim(clock.instant(), Duration.ofSeconds(30)).orElseThrow();
        clock.advance(11);
        work.requestRefresh(saved.ticketId(), saved.analysisRequestId(), clock.instant());
        assertThat(work.polled(polling, gateway.investigation(view(saved)), null, null, clock.instant())).isTrue();
        assertThat(next(saved)).isEqualTo(clock.instant());
        processor(840).processNext();
        assertThat(next(saved)).isNull();
        assertThat(AGENT.gets.get()).isEqualTo(2);
    }

    @Test void wrongOwnershipAndUnsupportedReportsNeverReplaceAValidCachedResult() throws Exception {
        var saved = request(ticket("응답 검증"), "validation", 1);
        processor(840).processNext(); processor(840).processNext();
        var observed = view(saved);
        var entry = AGENT.entries.values().iterator().next();
        var wrongTicket = AGENT.view(entry, "RUNNING"); wrongTicket.put("ticketId", "another-ticket");
        var wrongVersion = AGENT.view(entry, "RUNNING"); wrongVersion.put("ticketVersion", 999);
        var missingReport = AGENT.view(entry, "COMPLETED"); missingReport.put("report", null);
        var wrongEvidence = AGENT.view(entry, "COMPLETED");
        var badReport = new LinkedHashMap<>(AGENT.report("COMPLETED"));
        badReport.put("facts", List.of(Map.of("id", "f", "description", "unbacked fixture", "evidenceIds", List.of("not-owned"))));
        wrongEvidence.put("report", badReport);
        for (var malformed : List.of(wrongTicket, wrongVersion, missingReport, wrongEvidence)) {
            AGENT.getReplies.add(new Reply(200, JSON.writeValueAsString(malformed), null));
            call("GET", path(saved) + "?refresh=true", null, 200); processor(840).processNext();
            assertThat(view(saved).syncError().code()).isEqualTo("AGENT_PROTOCOL_ERROR");
            assertThat(view(saved).investigation()).isEqualTo(observed.investigation());
            assertThat(view(saved).lastSyncedAt()).isEqualTo(observed.lastSyncedAt());
            assertThat(next(saved)).isNull();
        }
        assertThat(AGENT.posts.get()).isEqualTo(1);
    }

    private AnalysisProcessor processor(long observationSeconds) {
        return new AnalysisProcessor(work, gateway, clock, new AnalysisProcessor.Settings(Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofSeconds(observationSeconds), 3, 3), () -> 0);
    }
    private String ticket(String message) throws Exception {
        return call("POST", "/api/tickets", Map.of("title", "합성 worker 검사", "message", message), 201).path("ticketId").asText();
    }
    private AnalysisRequest request(String ticket, String key, int version) throws Exception {
        var response = call("POST", "/api/tickets/" + ticket + "/analyses", Map.of("requestKey", key, "ticketVersion", version), 202);
        return analyses.get(ticket, response.path("analysisRequestId").asText());
    }
    private AnalysisRequest view(AnalysisRequest saved) { return analyses.get(saved.ticketId(), saved.analysisRequestId()); }
    private String path(AnalysisRequest saved) { return "/api/tickets/" + saved.ticketId() + "/analyses/" + saved.analysisRequestId(); }
    private Instant next(AnalysisRequest saved) {
        Timestamp value = jdbc.queryForObject("SELECT next_work_at FROM voc.analysis_requests WHERE analysis_request_id=?", Timestamp.class, saved.analysisRequestId());
        return value == null ? null : value.toInstant();
    }
    private JsonNode call(String method, String path, Object body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    record Reply(int status, String body, String retryAfter) {}
    record Entry(String id, JsonNode input) {}
    static final class FakeAgent implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
        final List<JsonNode> inputs = Collections.synchronizedList(new ArrayList<>());
        final Queue<Reply> postReplies = new ConcurrentLinkedQueue<>(), getReplies = new ConcurrentLinkedQueue<>();
        final AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger(), evidenceReads = new AtomicInteger();
        final AtomicReference<String> status = new AtomicReference<>("RUNNING");
        final AtomicBoolean dropNext = new AtomicBoolean();
        FakeAgent() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor); server.createContext("/api/investigations", this::handle); server.start();
            } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
        }
        void reset() {
            entries.clear(); inputs.clear(); postReplies.clear(); getReplies.clear();
            posts.set(0); gets.set(0); evidenceReads.set(0); status.set("RUNNING"); dropNext.set(false);
        }
        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                Reply reply;
                if (exchange.getRequestMethod().equals("POST")) {
                    posts.incrementAndGet();
                    JsonNode input = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    inputs.add(input);
                    reply = postReplies.poll();
                    if (reply == null) {
                        var entry = entries.computeIfAbsent(input.path("ticketId").asText() + ":" + input.path("requestKey").asText(),
                                ignored -> new Entry(UUID.randomUUID().toString(), input));
                        reply = new Reply(202, JSON.writeValueAsString(Map.of("investigationId", entry.id(),
                                "ticketId", input.path("ticketId").asText(), "status", "QUEUED")), null);
                        if (dropNext.getAndSet(false)) {
                            exchange.sendResponseHeaders(202, reply.body().getBytes(StandardCharsets.UTF_8).length + 100);
                            exchange.getResponseBody().write(reply.body().getBytes(StandardCharsets.UTF_8));
                            return; // Admission committed; response body intentionally truncated.
                        }
                    }
                } else {
                    String path = exchange.getRequestURI().getPath();
                    boolean evidence = path.contains("/evidence/");
                    if (evidence) evidenceReads.incrementAndGet(); else gets.incrementAndGet();
                    Entry entry = entries.values().stream().filter(item -> path.contains("/" + item.id())).findFirst().orElseThrow();
                    reply = evidence ? null : getReplies.poll();
                    if (reply == null) {
                        Map<String, Object> body;
                        if (evidence) {
                            body = new LinkedHashMap<>(evidence()); body.put("content", "// synthetic contract fixture"); body.put("truncated", false);
                        } else body = view(entry, status.get());
                        reply = new Reply(200, JSON.writeValueAsString(body), null);
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", reply.retryAfter());
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), body.length); exchange.getResponseBody().write(body);
            }
        }
        Map<String, Object> view(Entry entry, String status) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "1.0"); result.put("investigationId", entry.id());
            result.put("ticketId", entry.input().path("ticketId").asText()); result.put("ticketVersion", entry.input().path("ticketVersion").asLong());
            result.put("status", status); result.put("createdAt", "2026-09-21T00:00:00Z"); result.put("updatedAt", "2026-09-21T00:00:00Z");
            result.put("progress", List.of()); result.put("evidence", List.of(evidence()));
            result.put("report", Set.of("COMPLETED", "NEEDS_INPUT").contains(status) ? report(status) : null);
            result.put("error", status.equals("FAILED") ? Map.of("code", "LLM_CONFIGURATION_ERROR", "message", "synthetic disabled model", "retryable", false) : null);
            return result;
        }
        Map<String, Object> report(String status) {
            return Map.of("schemaVersion", "1.0", "summary", "명시적인 HTTP 계약용 합성 보고서",
                    "facts", List.of(Map.of("id", "fact", "description", "합성 근거 확인", "evidenceIds", List.of("evidence-fixture"))),
                    "hypotheses", List.of(), "actions", List.of(), "prevention", List.of(), "missingInformation",
                    status.equals("NEEDS_INPUT") ? List.of(Map.of("field", "context.orderId", "reason", "합성 추가 입력")) : List.of());
        }
        Map<String, Object> evidence() {
            return Map.of("evidenceId", "evidence-fixture", "type", "CODE", "summary", "명시적인 합성 근거", "observedAt", "2026-09-21T00:00:00Z",
                    "source", Map.of("buildId", "synthetic-test", "path", "synthetic/Fixture.java", "startLine", 1, "endLine", 1));
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
