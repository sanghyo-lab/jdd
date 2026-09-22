package com.jdd.agent;

import com.jdd.agent.domain.*;
import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationExecutionRepository.*;
import com.jdd.agent.domain.InvestigationModel.*;
import com.jdd.agent.infra.InvestigationPromptLoader;
import com.jdd.agent.infra.InvestigationReportDecoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "jdd.agent.worker.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:runner;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"
})
class InvestigationRunnerTest {
    @Autowired InvestigationRepository repository;
    @Autowired InvestigationExecutionRepository executions;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    @BeforeEach void clearSyntheticRows() {
        jdbc.update("DELETE FROM agent.investigation_evidence");
        jdbc.update("DELETE FROM agent.investigations");
    }

    @Test void runsToolStoresEvidenceThenReturnsReportAndRepeatedHttpReadsDoNotCallModel() throws Exception {
        var claim = start();
        var runner = runner(request -> {
            assertThat(request.prompt().version()).isEqualTo("investigation-system-v9");
            assertThat(request.prompt().sha256()).hasSize(64);
            assertThat(request.prompt().text()).contains("같은 조사에 실제 저장한 관측", "requiresHumanAction");
            if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
            var last = request.history().getLast();
            assertThat(last.kind()).isEqualTo(MessageKind.TOOL);
            var evidence = last.observations().getFirst();
            assertThat(repository.findEvidence(claim.investigationId(), evidence.evidenceId())).contains(evidence);
            assertThat(view(claim).progress().getFirst().status()).isEqualTo(ToolStatus.SUCCEEDED);
            return reportReply(new AnalysisReport("1.0", "합성 관측을 확인했습니다.",
                    List.of(new Fact("f1", "수량 관측", List.of(evidence.evidenceId()))), List.of(), List.of(), List.of(), List.of()));
        }, tools(false), 8, 24);
        runner.run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(1);
        for (int index = 0; index < 3; index++) {
            var result = get("/" + claim.investigationId());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(json.readTree(result.body()).get("status").asText()).isEqualTo("COMPLETED");
        }
        String evidenceId = view(claim).evidence().getFirst().evidenceId();
        assertThat(get("/" + claim.investigationId() + "/evidence/" + evidenceId).statusCode()).isEqualTo(200);
        var resend = http.send(HttpRequest.newBuilder(URI.create(base())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(claim.stored().input()))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resend.statusCode()).isEqualTo(202);
        assertThat(json.readTree(resend.body()).get("investigationId").asText()).isEqualTo(claim.investigationId());
        assertThat(modelCalls).hasValue(2);
    }

    @Test void repairsAReportOnceUsingValidationFeedback() {
        var claim = start();
        runner(request -> {
            if (request.iteration() == 1) return new Reply("{invalid json", List.of());
            assertThat(request.history().getLast().kind()).isEqualTo(MessageKind.FEEDBACK);
            return reportReply(new AnalysisReport("1.0", "대상을 식별할 입력이 필요합니다.", List.of(), List.of(), List.of(), List.of(),
                    List.of(new MissingInformation("context.orderId", "어떤 주문인지 확인할 수 없습니다."))));
        }, tools(false), 8, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(0);
    }

    @Test void complexReportIsReviewedWithTheSameStoredEvidenceBeforeItBecomesVisible() {
        var claim = start();
        runner(request -> {
            if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
            var saved = request.history().stream().filter(m -> m.kind() == MessageKind.TOOL)
                    .flatMap(m -> m.observations().stream()).toList();
            if (request.iteration() == 3) {
                assertThat(view(claim).status()).isEqualTo(Status.RUNNING);
                assertThat(view(claim).report()).isNull();
                assertThat(request.tools()).isEmpty();
                assertThat(request.history().getLast().kind()).isEqualTo(MessageKind.FEEDBACK);
                assertThat(request.history().getLast().text()).contains("최종 인용 검수");
                assertThat(request.history().stream().filter(m -> m.kind() == MessageKind.ASSISTANT).count()).isEqualTo(2);
                assertThat(repository.findEvidence(claim.investigationId(), saved.getFirst().evidenceId())).contains(saved.getFirst());
            }
            return request.reviewDraft() == null ? reportReply(complexReport(saved, "Draft scope")) : reviewReply("Reviewed scope");
        }, tools(false), 3, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
        assertThat(view(claim).report().summary()).isEqualTo("Reviewed scope");
        assertThat(modelCalls).hasValue(3);
        assertThat(toolCalls).hasValue(1);
        assertThat(view(claim).evidence()).hasSize(1);
    }

    @Test void finalReviewCannotBypassTheModelLimitOrRunAnotherTool() {
        for (int maximum : List.of(2, 3)) {
            modelCalls.set(0); toolCalls.set(0);
            var claim = start();
            runner(request -> {
                if (request.iteration() == 1 || request.iteration() == 3)
                    return toolReply("read-" + request.iteration(), "getInventoryContext", "{}");
                var saved = request.history().stream().filter(m -> m.kind() == MessageKind.TOOL)
                        .flatMap(m -> m.observations().stream()).toList();
                return reportReply(complexReport(saved, "Unreviewed draft"));
            }, tools(false), maximum, 24).run(claim);
            assertThat(view(claim).status()).isEqualTo(Status.FAILED);
            assertThat(view(claim).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED");
            assertThat(view(claim).report()).isNull();
            assertThat(modelCalls).hasValue(maximum);
            assertThat(toolCalls).hasValue(1);
        }
    }

    @Test void partialReviewPreservesTheUnchangedCauseAndItsDirectCitations() {
        var claim = start();
        runner(request -> {
            if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
            var saved = request.history().stream().filter(m -> m.kind() == MessageKind.TOOL)
                    .flatMap(m -> m.observations().stream()).toList();
            if (request.iteration() == 2) return reportReply(complexReport(saved, "Original summary"));
            var changes = new java.util.LinkedHashMap<String, Object>();
            changes.put("summary", null);
            changes.put("facts", List.of(new Fact("f", "Only the observed scope", List.of(saved.getFirst().evidenceId()))));
            changes.put("hypotheses", List.of()); changes.put("actions", List.of());
            changes.put("prevention", List.of()); changes.put("removeItemIds", List.of());
            return new Reply(json.writeValueAsString(changes), List.of());
        }, tools(false), 3, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
        assertThat(view(claim).report().summary()).isEqualTo("Original summary");
        assertThat(view(claim).report().facts().getFirst().description()).isEqualTo("Only the observed scope");
        assertThat(view(claim).report().hypotheses()).containsExactly(new Hypothesis("h", "Bounded explanation",
                SupportLevel.PARTIAL, List.of(view(claim).evidence().getFirst().evidenceId()), List.of("Limited scope")));
        assertThat(modelCalls).hasValue(3);
        assertThat(toolCalls).hasValue(1);
    }

    @Test void finalReviewIsValidatedAndUsesTheExistingRepairAllowance() {
        var claim = start();
        runner(request -> {
            if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
            if (request.iteration() >= 3) {
                assertThat(request.tools()).isEmpty();
                assertThat(view(claim).status()).isEqualTo(Status.RUNNING);
                assertThat(view(claim).report()).isNull();
            }
            if (request.iteration() == 3) return new Reply("{invalid review", List.of());
            if (request.iteration() == 4) assertThat(request.history().getLast().text())
                    .contains("보고서 검증 오류", "수정할 기존 항목만 반환", "이전 검수 변경은 아직 적용되지 않았으므로")
                    .doesNotContain("수정하지 않은 사실·인용도 모두 포함한 전체 보고서");
            var saved = request.history().stream().filter(m -> m.kind() == MessageKind.TOOL)
                    .flatMap(m -> m.observations().stream()).toList();
            return request.reviewDraft() == null ? reportReply(complexReport(saved, "Draft")) : reviewReply("Repaired review");
        }, tools(false), 4, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
        assertThat(view(claim).report().summary()).isEqualTo("Repaired review");
        assertThat(modelCalls).hasValue(4);
        assertThat(toolCalls).hasValue(1);
    }

    @Test void reviewChangesCannotIntroduceUnknownEvidenceOrPublishAnEmptyFactSet() {
        for (boolean unknownEvidence : List.of(true, false)) {
            modelCalls.set(0); toolCalls.set(0);
            var claim = start();
            runner(request -> {
                if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
                if (request.reviewDraft() != null) {
                    assertThat(view(claim).report()).isNull();
                    var changes = new ReportReview(null,
                            unknownEvidence ? List.of(new Fact("f", "Unsupported", List.of("unknown-evidence"))) : List.of(),
                            List.of(), List.of(), List.of(), unknownEvidence ? List.of() : List.of("f"));
                    return new Reply(json.writeValueAsString(changes), List.of());
                }
                var saved = request.history().stream().filter(m -> m.kind() == MessageKind.TOOL)
                        .flatMap(m -> m.observations().stream()).toList();
                return reportReply(complexReport(saved, "Unpublished draft"));
            }, tools(false), 4, 24).run(claim);
            assertThat(view(claim).status()).isEqualTo(Status.FAILED);
            assertThat(view(claim).error().code()).isEqualTo("REPORT_VALIDATION_FAILED");
            assertThat(view(claim).report()).isNull();
            assertThat(view(claim).evidence()).hasSize(1);
            assertThat(modelCalls).hasValue(4);
            assertThat(toolCalls).hasValue(1);
        }
    }

    @Test void missingDirectCauseCoverageUsesExistingRepairBudgetAndNeverPublishesUnrepairedReport() {
        for (boolean repair : List.of(true, false)) {
            modelCalls.set(0); toolCalls.set(0);
            var claim = start();
            var observations = new InvestigationTools() {
                @Override public List<ToolDefinition> definitions() { return tools(false).definitions(); }
                @Override public List<String> validate(ToolCall call) { return List.of(); }
                @Override public Outcome execute(ToolCall call) {
                    toolCalls.incrementAndGet();
                    return new Outcome(List.of(EvidenceType.DATA, EvidenceType.LOG, EvidenceType.CODE, EvidenceType.POLICY)
                            .stream().map(type -> new Observation(type, "Synthetic " + type, now,
                                    type == EvidenceType.CODE ? Map.<String, Object>of("path", "commerce-core/Example.java") : Map.<String, Object>of(),
                                    Map.of("synthetic", true), false)).toList(), "Four saved synthetic observations");
                }
            };
            runner(request -> {
                if (request.iteration() == 1) return toolReply("read-1", "getInventoryContext", "{}");
                var saved = request.history().stream().filter(message -> message.kind() == MessageKind.TOOL)
                        .flatMap(message -> message.observations().stream()).toList();
                if (request.iteration() == 3) {
                    assertThat(request.history().getLast().kind()).isEqualTo(MessageKind.FEEDBACK);
                    assertThat(request.history().getLast().text()).contains(
                            "hypotheses with CODE evidence must directly cite available DATA observations");
                    assertThat(request.history().getLast().text()).contains("수정하지 않은 사실·인용도 모두 포함한 전체 보고서")
                            .doesNotContain("수정할 기존 항목만 반환");
                    assertThat(view(claim).status()).isEqualTo(Status.RUNNING);
                    assertThat(view(claim).report()).isNull();
                    assertThat(request.tools()).isEmpty();
                }
                if (request.iteration() == 4) {
                    assertThat(request.history().getLast().text()).contains("최종 인용 검수");
                    assertThat(request.tools()).isEmpty();
                    assertThat(view(claim).report()).isNull();
                }
                if (request.reviewDraft() != null) return reviewReply(null);
                var causeIds = saved.stream().filter(e -> (repair && request.iteration() >= 3) || e.type() != EvidenceType.DATA)
                        .map(EvidenceDetail::evidenceId).toList();
                return reportReply(new AnalysisReport("1.0", "Synthetic coverage check",
                        List.of(new Fact("f", "Stored observations", saved.stream().map(EvidenceDetail::evidenceId).toList())),
                        List.of(new Hypothesis("h", "Implementation explanation", SupportLevel.SUPPORTED, causeIds, List.of())),
                        List.of(), List.of(), List.of()));
            }, observations, 4, 1).run(claim);
            assertThat(modelCalls).hasValue(repair ? 4 : 3);
            assertThat(toolCalls).hasValue(1);
            assertThat(view(claim).evidence()).hasSize(4);
            if (repair) assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
            else {
                assertThat(view(claim).status()).isEqualTo(Status.FAILED);
                assertThat(view(claim).error().code()).isEqualTo("REPORT_VALIDATION_FAILED");
                assertThat(view(claim).report()).isNull();
            }
        }
    }

    @Test void batchesIndependentReadsAndReservesTheLastModelCallForStoredEvidenceReport() {
        var claim = start();
        runner(request -> {
            if (request.iteration() == 1) {
                assertThat(request.remaining()).isEqualTo(new Remaining(2, 2));
                assertThat(request.tools()).hasSize(1);
                return new Reply(null, List.of(new ToolCall("read-a", "getInventoryContext", "{}"),
                        new ToolCall("read-b", "getInventoryContext", "{}")));
            }
            assertThat(request.remaining()).isEqualTo(new Remaining(1, 0));
            assertThat(request.tools()).isEmpty();
            var saved = request.history().stream().filter(message -> message.kind() == MessageKind.TOOL)
                    .flatMap(message -> message.observations().stream()).toList();
            assertThat(saved).hasSize(2);
            saved.forEach(evidence -> assertThat(repository.findEvidence(claim.investigationId(), evidence.evidenceId())).contains(evidence));
            return reportReply(new AnalysisReport("1.0", "합성 두 관측 확인", List.of(new Fact("f1", "두 저장 관측",
                    saved.stream().map(EvidenceDetail::evidenceId).toList())), List.of(), List.of(), List.of(), List.of()));
        }, tools(false), 2, 2).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.COMPLETED);
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(2);
        assertThat(view(claim).evidence()).hasSize(2);
    }

    @Test void multipleToolCallsCannotBypassTheSharedExecutionLimit() {
        var claim = start();
        runner(request -> new Reply(null, List.of(new ToolCall("a", "getInventoryContext", "{}"),
                new ToolCall("b", "getInventoryContext", "{}"), new ToolCall("c", "getInventoryContext", "{}"))),
                tools(false), 8, 2).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED");
        assertThat(view(claim).report()).isNull();
        assertThat(modelCalls).hasValue(1);
        assertThat(toolCalls).hasValue(2);
        assertThat(view(claim).evidence()).hasSize(2);
    }

    @Test void rejectsRepeatedInventedEvidenceWithoutPublishingReport() {
        var claim = start();
        var invented = new AnalysisReport("1.0", "잘못된 합성 보고서", List.of(new Fact("f1", "없는 관측", List.of("invented"))),
                List.of(), List.of(), List.of(), List.of());
        runner(request -> reportReply(invented), tools(false), 8, 24).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("REPORT_VALIDATION_FAILED");
        assertThat(view(claim).report()).isNull();
        assertThat(modelCalls).hasValue(2);
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void rejectedReportsLogBoundedReasonsWithoutModelTextOrEvidenceValues(org.springframework.boot.test.system.CapturedOutput output) {
        var claim = start();
        var candidate = new AnalysisReport("1.0", "sensitive-summary", List.of(new Fact("private-id", "sensitive-description",
                java.util.Collections.nCopies(100, "sensitive-unknown-observation"))), List.of(), List.of(), List.of(), List.of());
        runner(request -> {
            if (request.iteration() == 2) assertThat(request.history().getLast().text())
                    .contains("수정하지 않은 사실·인용도 모두 포함한 전체 보고서", "facts.evidenceIds contains an unknown observation")
                    .doesNotContain("sensitive-summary", "private-id", "sensitive-description", "sensitive-unknown-observation");
            return reportReply(candidate);
        }, tools(false), 8, 24).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("REPORT_VALIDATION_FAILED");
        assertThat(modelCalls).hasValue(2);
        assertThat(output.getAll()).contains("Report validation rejected", claim.investigationId(), "facts.evidenceIds contains an unknown observation")
                .doesNotContain("sensitive-summary", "private-id", "sensitive-description", "sensitive-unknown-observation");
        var malformed = start();
        runner(request -> new Reply("sensitive-malformed-json", List.of()), tools(false), 8, 24).run(malformed);
        assertThat(output.getAll()).contains("Report JSON does not match the required schema").doesNotContain("sensitive-malformed-json");
        assertThat(view(malformed).report()).isNull();
    }
    @Test void anEmptyUnsubstantiatedConclusionCannotBePublishedAsCompleted() {
        var claim = start();
        runner(request -> reportReply(emptyReport()), tools(false), 8, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.FAILED);
        assertThat(view(claim).error().code()).isEqualTo("REPORT_VALIDATION_FAILED");
        assertThat(view(claim).report()).isNull();
        assertThat(view(claim).evidence()).isEmpty();
        assertThat(modelCalls).hasValue(2);
    }

    @Test void unknownToolsGetBoundedFeedbackAndNeverExecute() {
        var claim = start();
        runner(request -> toolReply("bad-" + request.iteration(), "executeShell", "{}"), tools(false), 8, 24).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(0);
        assertThat(view(claim).progress()).isEmpty();
    }

    @Test void necessaryToolFailurePreservesPreviousObservations() {
        var claim = start();
        runner(request -> toolReply("read-" + request.iteration(), "getInventoryContext", "{}"), tools(true), 8, 24).run(claim);
        assertThat(view(claim).status()).isEqualTo(Status.FAILED);
        assertThat(view(claim).error().code()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(view(claim).evidence()).hasSize(1);
        assertThat(view(claim).progress()).extracting(ToolExecution::status).containsExactlyInAnyOrder(ToolStatus.SUCCEEDED, ToolStatus.FAILED);
    }

    @Test void lateModelResponseCannotOverwriteTimeout() {
        var claim = start();
        runner(request -> {
            executions.expire(claim.deadline());
            return reportReply(emptyReport());
        }, tools(false), 8, 24).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("INVESTIGATION_TIMEOUT");
        assertThat(view(claim).report()).isNull();
        assertThat(modelCalls).hasValue(1);
    }

    @Test void modelAndToolLoopsHaveIndependentBounds() {
        var claim = start();
        runner(request -> toolReply("read-" + request.iteration(), "getInventoryContext", "{}"), tools(false), 2, 24).run(claim);
        assertThat(view(claim).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED");
        assertThat(modelCalls).hasValue(2);
        assertThat(toolCalls).hasValue(1);
        var next = start();
        runner(request -> toolReply("read-" + request.iteration(), "getInventoryContext", "{}"), tools(false), 8, 1).run(next);
        assertThat(view(next).error().code()).isEqualTo("INVESTIGATION_BUDGET_EXCEEDED");
        assertThat(view(next).progress()).hasSize(1);
    }

    @Test void agreedModelFailuresAppearInStoredHttpResults() throws Exception {
        Map<PaidModelGate.Rejection, String> expected = Map.of(
                PaidModelGate.Rejection.CONFIGURATION, "LLM_CONFIGURATION_ERROR",
                PaidModelGate.Rejection.TRANSPORT, "LLM_UNAVAILABLE",
                PaidModelGate.Rejection.BUDGET_OR_LIMIT, "INVESTIGATION_BUDGET_EXCEEDED");
        for (var reason : PaidModelGate.Rejection.values()) {
            var claim = start();
            runner(request -> { throw new PaidModelGate.Rejected(reason); }, tools(false), 8, 24).run(claim);
            var response = get("/" + claim.investigationId());
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.get("status").asText()).isEqualTo("FAILED");
            assertThat(body.get("report").isNull()).isTrue();
            assertThat(body.get("error").get("code").asText()).isEqualTo(expected.get(reason));
            assertThat(body.get("error").get("retryable").asBoolean()).isEqualTo(reason == PaidModelGate.Rejection.TRANSPORT);
        }
    }

    private InvestigationRunner runner(Function<Request, Reply> function, InvestigationTools tools, int modelLimit, int toolLimit) {
        InvestigationModel model = request -> { modelCalls.incrementAndGet(); return function.apply(request); };
        return new InvestigationRunner(repository, executions, model, tools, InvestigationPromptLoader.load(),
                new InvestigationReportDecoder(json), new InvestigationRunner.Limits(modelLimit, toolLimit, 1, 1), clock);
    }
    private InvestigationTools tools(boolean failSecond) {
        return new InvestigationTools() {
            @Override public List<ToolDefinition> definitions() { return List.of(new ToolDefinition("getInventoryContext", "합성 조회 도구", "{}")); }
            @Override public List<String> validate(ToolCall call) { return List.of(); }
            @Override public Outcome execute(ToolCall call) {
                if (toolCalls.incrementAndGet() == 2 && failSecond) throw new IllegalStateException("Synthetic storage failure");
                return new Outcome(List.of(new Observation(EvidenceType.DATA, "합성 관측", now, Map.of("schema", "commerce", "table", "product_stock"),
                        Map.of("columns", List.of("quantity"), "rows", List.of(List.of(1))), false)), "합성 조회 결과 1건");
            }
        };
    }
    private Claim start() {
        new InvestigationService(repository, clock).submit(new InvestigationInput("1.0", UUID.randomUUID().toString(), 1,
                "test", "합성 조사", null, null));
        return executions.claimNext(now, Duration.ofMinutes(3)).orElseThrow();
    }
    private Investigation view(Claim claim) { return repository.find(claim.investigationId()).orElseThrow().investigation(); }
    private Reply toolReply(String id, String name, String arguments) { return new Reply(null, List.of(new ToolCall(id, name, arguments))); }
    private Reply reportReply(AnalysisReport report) { return new Reply(json.writeValueAsString(report), List.of()); }
    private Reply reviewReply(String summary) {
        return new Reply(json.writeValueAsString(new ReportReview(summary, List.of(), List.of(), List.of(), List.of(), List.of())), List.of());
    }
    private AnalysisReport complexReport(List<EvidenceDetail> saved, String summary) {
        var ids = saved.stream().map(EvidenceDetail::evidenceId).toList();
        return new AnalysisReport("1.0", summary, List.of(new Fact("f", "Observed state", ids)),
                List.of(new Hypothesis("h", "Bounded explanation", SupportLevel.PARTIAL, ids, List.of("Limited scope"))),
                List.of(), List.of(), List.of());
    }
    private AnalysisReport emptyReport() { return new AnalysisReport("1.0", "합성 검증용 보고서", List.of(), List.of(), List.of(), List.of(), List.of()); }
    private String base() { return "http://127.0.0.1:" + port + "/api/investigations"; }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
