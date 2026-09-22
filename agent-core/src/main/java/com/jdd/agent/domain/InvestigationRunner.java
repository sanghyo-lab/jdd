package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationExecutionRepository.Claim;
import com.jdd.agent.domain.InvestigationModel.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Bounded service-owned loop. Reads and writes are local; inference is behind the model port. */
public final class InvestigationRunner {
    private static final System.Logger LOG = System.getLogger(InvestigationRunner.class.getName());
    public record Limits(int modelCalls, int toolCalls, int reportRepairs, int argumentRepairs) {
        public Limits {
            if (modelCalls < 1 || toolCalls < 1 || reportRepairs < 0 || argumentRepairs < 0)
                throw new IllegalArgumentException("Invalid investigation limits");
        }
    }
    public interface ReportDecoder {
        AnalysisReport decode(String candidate);
        ReportReview decodeReview(String candidate);
    }
    private final InvestigationRepository repository;
    private final InvestigationExecutionRepository executions;
    private final InvestigationModel model;
    private final InvestigationTools tools;
    private final Prompt prompt;
    private final ReportDecoder reports;
    private final ReportValidator validator = new ReportValidator();
    private final Limits limits;
    private final Clock clock;

    public InvestigationRunner(InvestigationRepository repository, InvestigationExecutionRepository executions,
                               InvestigationModel model, InvestigationTools tools, Prompt prompt,
                               ReportDecoder reports, Limits limits, Clock clock) {
        this.repository = repository;
        this.executions = executions;
        this.model = model;
        this.tools = tools;
        this.prompt = prompt;
        this.reports = reports;
        this.limits = limits;
        this.clock = clock;
    }

    public void run(Claim claim) {
        try {
            investigate(claim);
        } catch (InvestigationFailure failure) {
            executions.fail(claim, failure.error(), clock.instant());
        } catch (PaidModelGate.Rejected rejected) {
            executions.fail(claim, InvestigationFailure.from(rejected), clock.instant());
        } catch (RuntimeException unexpected) {
            executions.fail(claim, new ApiError("INTERNAL_ERROR", "조사 실행 중 내부 오류가 발생했습니다.", false), clock.instant());
        }
    }

    private void investigate(Claim claim) {
        var history = new ArrayList<Message>();
        var seenCallIds = new HashSet<String>();
        var allowedTools = new HashSet<String>();
        var definitions = List.copyOf(tools.definitions());
        definitions.forEach(tool -> allowedTools.add(tool.name()));
        int executedTools = 0, repairedReports = 0, repairedArguments = 0;
        AnalysisReport reviewDraft = null;
        for (int iteration = 1; iteration <= limits.modelCalls(); iteration++) {
            if (!active(claim)) return;
            int remainingModels = limits.modelCalls() - iteration + 1;
            int remainingTools = limits.toolCalls() - executedTools;
            // Reserve the final inference for a report; no new evidence can be consumed afterwards.
            var available = reviewDraft != null || remainingModels == 1 || remainingTools == 0
                    ? List.<ToolDefinition>of() : definitions;
            var reply = model.next(new Request(claim.investigationId(), claim.stored().input(), prompt, iteration,
                    available, List.copyOf(history), new Remaining(remainingModels, remainingTools), reviewDraft));
            if (!active(claim)) return;
            if (reply == null || reply.toolCalls() == null) throw reportFailure();
            history.add(Message.assistant(reply));
            if (!reply.toolCalls().isEmpty()) {
                if (available.isEmpty()) throw limitFailure();
                for (var call : reply.toolCalls()) {
                    if (!active(claim)) return;
                    if (call == null || call.id() == null || call.id().isBlank() || !seenCallIds.add(call.id()))
                        throw toolFailure();
                    List<String> errors = allowedTools.contains(call.name()) ? tools.validate(call) : List.of("Unknown tool name");
                    if (!errors.isEmpty()) {
                        if (++repairedArguments > limits.argumentRepairs()) throw toolFailure();
                        history.add(Message.tool(call, List.of(), "Invalid tool arguments: " + String.join(", ", errors)));
                        continue;
                    }
                    if (++executedTools > limits.toolCalls()) throw limitFailure();
                    String toolId = executions.beginTool(claim, call.name(), clock.instant()).orElse(null);
                    if (toolId == null) return;
                    final InvestigationTools.Outcome outcome;
                    try {
                        outcome = tools.execute(call);
                    } catch (RuntimeException toolError) {
                        throw toolFailure();
                    }
                    var saved = executions.completeTool(claim, toolId, outcome.summary(),
                            outcome.observations(), clock.instant());
                    if (saved.isEmpty()) return;
                    history.add(Message.tool(call, saved.get(), outcome.summary() + " 서버에 저장된 관측의 잘림과 출처를 확인하세요."));
                }
                continue;
            }
            AnalysisReport report = null;
            ReportReview review = null;
            List<String> errors = List.of();
            try {
                if (reviewDraft == null) report = reports.decode(reply.text());
                else {
                    review = reports.decodeReview(reply.text());
                    report = review.applyTo(reviewDraft);
                }
            } catch (RuntimeException invalidFormat) {
                errors = List.of("Report JSON does not match the required schema");
            }
            if (errors.isEmpty()) {
                var current = repository.find(claim.investigationId()).orElseThrow().investigation();
                errors = validator.validate(report, current.evidence());
            }
            if (errors.isEmpty()) {
                if (reviewDraft == null && requiresFinalReview(report)) {
                    if (remainingModels < 2) throw limitFailure();
                    reviewDraft = report;
                    LOG.log(System.Logger.Level.INFO, "Report final review requested: investigationId={0}, iteration={1}",
                            claim.investigationId(), iteration);
                    history.add(Message.feedback("최종 인용 검수: 직전 보고서의 각 항목과 한계에 담긴 사실 표현을 "
                            + "그 항목이 인용한 저장 원문과 대조하세요. 관측 사실과 인과 추정을 분리하고, "
                            + "빈 조회만으로 특정 실패 단계나 처리 불필요를 단정하지 마세요. 인용을 바로잡거나 "
                            + "미지지 표현을 삭제·축소하세요. 전체 보고서를 다시 쓰지 말고 수정 항목만 review JSON으로 "
                            + "반환하세요. 바꾸지 않은 항목의 내용·인용은 보존됩니다. 새 도구나 근거는 사용할 수 없습니다."));
                    continue;
                }
                if (review != null) LOG.log(System.Logger.Level.INFO,
                        "Report final review applied: investigationId={0}, iteration={1}, replacementItems={2}, removedItems={3}, summaryProvided={4}",
                        claim.investigationId(), iteration,
                        review.facts().size() + review.hypotheses().size() + review.actions().size() + review.prevention().size(),
                        review.removeItemIds().size(), review.summary() != null);
                executions.complete(claim, report, clock.instant());
                return;
            }
            // Validator messages contain only server-defined field names/reasons, never model values.
            errors = errors.stream().distinct().limit(16).toList();
            LOG.log(System.Logger.Level.WARNING, "Report validation rejected: investigationId={0}, iteration={1}, reasons={2}",
                    claim.investigationId(), iteration, errors);
            if (++repairedReports > limits.reportRepairs()) throw reportFailure();
            String format = reviewDraft == null
                    ? "수정하지 않은 사실·인용도 모두 포함한 전체 보고서를 반환하세요. facts=[]는 기존 사실 보존이 아니라 사실이 없다는 뜻입니다. "
                    : "원래 reviewDraft를 기준으로 수정할 기존 항목만 반환하세요. 이전 검수 변경은 아직 적용되지 않았으므로 필요한 교정을 모두 포함하세요. ";
            history.add(Message.feedback("보고서 검증 오류를 수정하세요. " + format
                    + "새 근거 ID를 만들지 마세요: " + String.join(", ", errors)));
        }
        throw limitFailure();
    }

    private static boolean requiresFinalReview(AnalysisReport report) {
        return !report.hypotheses().isEmpty() || !report.actions().isEmpty() || !report.prevention().isEmpty();
    }

    private boolean active(Claim claim) {
        if (Thread.currentThread().isInterrupted()) {
            executions.fail(claim, new ApiError("INTERRUPTED", "조사 실행이 중단되었습니다.", false), clock.instant());
            return false;
        }
        if (!clock.instant().isBefore(claim.deadline())) {
            executions.expire(clock.instant());
            return false;
        }
        return repository.find(claim.investigationId()).map(stored -> stored.investigation().status() == Status.RUNNING).orElse(false);
    }
    private static InvestigationFailure toolFailure() {
        return new InvestigationFailure(new ApiError("TOOL_EXECUTION_FAILED", "필요한 근거 조회를 완료하지 못했습니다.", false));
    }
    private static InvestigationFailure reportFailure() {
        return new InvestigationFailure(new ApiError("REPORT_VALIDATION_FAILED", "보고서 형식 또는 근거 참조 검증에 실패했습니다.", false));
    }
    private static InvestigationFailure limitFailure() {
        return new InvestigationFailure(new ApiError("INVESTIGATION_BUDGET_EXCEEDED", "조사 모델·도구 호출 한도를 초과했습니다.", false));
    }
}
