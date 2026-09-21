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
    public interface ReportDecoder { AnalysisReport decode(String candidate); }
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
        for (int iteration = 1; iteration <= limits.modelCalls(); iteration++) {
            if (!active(claim)) return;
            int remainingModels = limits.modelCalls() - iteration + 1;
            int remainingTools = limits.toolCalls() - executedTools;
            // Reserve the final inference for a report; no new evidence can be consumed afterwards.
            var available = remainingModels == 1 || remainingTools == 0 ? List.<ToolDefinition>of() : definitions;
            var reply = model.next(new Request(claim.investigationId(), claim.stored().input(), prompt, iteration,
                    available, List.copyOf(history), new Remaining(remainingModels, remainingTools)));
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
            List<String> errors = List.of();
            try {
                report = reports.decode(reply.text());
            } catch (RuntimeException invalidFormat) {
                errors = List.of("Report JSON does not match the required schema");
            }
            if (errors.isEmpty()) {
                var current = repository.find(claim.investigationId()).orElseThrow().investigation();
                errors = validator.validate(report, current.evidence());
            }
            if (errors.isEmpty()) {
                executions.complete(claim, report, clock.instant());
                return;
            }
            // Validator messages contain only server-defined field names/reasons, never model values.
            errors = errors.stream().distinct().limit(16).toList();
            LOG.log(System.Logger.Level.WARNING, "Report validation rejected: investigationId={0}, iteration={1}, reasons={2}",
                    claim.investigationId(), iteration, errors);
            if (++repairedReports > limits.reportRepairs()) throw reportFailure();
            history.add(Message.feedback("보고서 검증 오류만 수정하세요. 새 근거 ID를 만들지 마세요: " + String.join(", ", errors)));
        }
        throw limitFailure();
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
