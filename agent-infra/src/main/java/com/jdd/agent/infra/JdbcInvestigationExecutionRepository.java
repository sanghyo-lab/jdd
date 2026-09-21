package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.Investigation.*;
import com.jdd.agent.domain.InvestigationExecutionRepository;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationRepository.Stored;
import com.jdd.agent.domain.ReportValidator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInvestigationExecutionRepository implements InvestigationExecutionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final ReportValidator validator = new ReportValidator();

    public JdbcInvestigationExecutionRepository(JdbcTemplate jdbc, JsonMapper json,
                                                 PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override public Optional<Claim> claimNext(Instant now, Duration maximumRuntime) {
        if (maximumRuntime.isNegative() || maximumRuntime.isZero()) throw new IllegalArgumentException("Invalid runtime");
        return transaction.execute(ignored -> {
            var candidates = jdbc.query("""
                    SELECT input_json, view_json FROM agent.investigations WHERE execution_status = 'QUEUED'
                    ORDER BY created_at, investigation_id LIMIT 1
                    """, this::read);
            if (candidates.isEmpty()) return Optional.empty();
            var candidate = candidates.getFirst();
            var running = view(candidate.investigation(), Status.RUNNING, now,
                    candidate.investigation().progress(), candidate.investigation().evidence(), null, null);
            String token = UUID.randomUUID().toString();
            Instant deadline = now.plus(maximumRuntime);
            int changed = jdbc.update("""
                    UPDATE agent.investigations SET execution_status = 'RUNNING', execution_token = ?,
                    deadline_at = ?, view_json = ? WHERE investigation_id = ? AND execution_status = 'QUEUED'
                    """, token, timestamp(deadline), json.writeValueAsString(running), running.investigationId());
            return changed == 1 ? Optional.of(new Claim(new Stored(candidate.input(), running), token, deadline))
                    : Optional.empty();
        });
    }

    @Override public Optional<String> beginTool(Claim claim, String toolName, Instant now) {
        requireText(toolName);
        return update(claim, now, current -> {
            String id = UUID.randomUUID().toString();
            var progress = new ArrayList<>(current.progress());
            progress.add(new ToolExecution(id, toolName, ToolStatus.RUNNING, now, null,
                    "도구 조회를 시작했습니다.", List.of(), null));
            progress.sort(Comparator.comparing(ToolExecution::startedAt).thenComparing(ToolExecution::toolExecutionId));
            save(view(current, Status.RUNNING, now, progress, current.evidence(), null, null));
            return id;
        });
    }

    @Override public Optional<List<EvidenceDetail>> completeTool(Claim claim, String toolId, String summary,
                                                               List<Observation> observations, Instant now) {
        requireText(summary);
        return update(claim, now, current -> {
            ToolExecution started = runningTool(current, toolId);
            var details = new ArrayList<EvidenceDetail>();
            var summaries = new ArrayList<>(current.evidence());
            for (var observation : observations) {
                if (observation.type() == null || observation.observedAt() == null || observation.source() == null
                        || observation.content() == null) throw new IllegalArgumentException("Incomplete observation");
                requireText(observation.summary());
                String evidenceId = UUID.randomUUID().toString();
                var detail = new EvidenceDetail(evidenceId, observation.type(), observation.summary(),
                        observation.observedAt(), observation.source(), observation.content(), observation.truncated());
                jdbc.update("""
                        INSERT INTO agent.investigation_evidence (investigation_id, evidence_id, detail_json)
                        VALUES (?, ?, ?)
                        """, claim.investigationId(), evidenceId, json.writeValueAsString(detail));
                details.add(detail);
                summaries.add(new EvidenceSummary(evidenceId, detail.type(), detail.summary(), detail.observedAt(), detail.source()));
            }
            var completed = new ToolExecution(started.toolExecutionId(), started.toolName(), ToolStatus.SUCCEEDED,
                    started.startedAt(), now, summary, details.stream().map(EvidenceDetail::evidenceId).toList(), null);
            var progress = current.progress().stream().map(tool -> tool.toolExecutionId().equals(toolId) ? completed : tool).toList();
            save(view(current, Status.RUNNING, now, progress, summaries, null, null));
            // Only committed observations are returned to the caller, which may then send them to a model.
            return List.copyOf(details);
        });
    }

    @Override public boolean complete(Claim claim, AnalysisReport report, Instant now) {
        return update(claim, now, current -> {
            if (current.progress().stream().anyMatch(tool -> tool.status() != ToolStatus.SUCCEEDED)) {
                throw new IllegalStateException("Cannot finish with incomplete tools");
            }
            var errors = validator.validate(report, current.evidence());
            if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid report: " + String.join(", ", errors));
            var status = report.missingInformation().isEmpty() ? Status.COMPLETED : Status.NEEDS_INPUT;
            save(view(current, status, now, current.progress(), current.evidence(), report, null));
            return true;
        }).orElse(false);
    }

    @Override public boolean fail(Claim claim, ApiError error, Instant now) {
        requireError(error);
        return update(claim, now, current -> { save(failed(current, error, now)); return true; }).orElse(false);
    }

    @Override public int expire(Instant now) {
        return terminateRunning(now, true, new ApiError("INVESTIGATION_TIMEOUT", "조사 제한 시간을 초과했습니다.", false));
    }

    @Override public int recoverInterrupted(Instant now) {
        return terminateRunning(now, false, new ApiError("INTERRUPTED", "Agent 실행이 중단되었습니다. 새 요청 키로 재조사할 수 있습니다.", false));
    }

    private int terminateRunning(Instant now, boolean expiredOnly, ApiError error) {
        return transaction.execute(ignored -> {
            String sql = "SELECT input_json, view_json FROM agent.investigations WHERE execution_status = 'RUNNING'"
                    + (expiredOnly ? " AND deadline_at <= ?" : "") + " ORDER BY investigation_id FOR UPDATE";
            var rows = expiredOnly ? jdbc.query(sql, this::read, timestamp(now)) : jdbc.query(sql, this::read);
            rows.forEach(row -> save(failed(row.investigation(), error, now)));
            return rows.size();
        });
    }

    private <T> Optional<T> update(Claim claim, Instant now, Function<Investigation, T> action) {
        return transaction.execute(ignored -> {
            var rows = jdbc.query("""
                    SELECT input_json, view_json, deadline_at FROM agent.investigations
                    WHERE investigation_id = ? AND execution_token = ? AND execution_status = 'RUNNING' FOR UPDATE
                    """, (rs, index) -> new Active(read(rs, index).investigation(),
                    rs.getObject("deadline_at", OffsetDateTime.class).toInstant()), claim.investigationId(), claim.token());
            if (rows.isEmpty()) return Optional.empty();
            var active = rows.getFirst();
            if (!now.isBefore(active.deadline())) {
                save(failed(active.view(), new ApiError("INVESTIGATION_TIMEOUT", "조사 제한 시간을 초과했습니다.", false), now));
                return Optional.empty();
            }
            return Optional.of(action.apply(active.view()));
        });
    }

    private record Active(Investigation view, Instant deadline) {}

    private void save(Investigation view) {
        jdbc.update("UPDATE agent.investigations SET view_json = ?, execution_status = ? WHERE investigation_id = ?",
                json.writeValueAsString(view), view.status().name(), view.investigationId());
    }

    private static Investigation failed(Investigation current, ApiError error, Instant now) {
        var progress = current.progress().stream().map(tool -> tool.status() == ToolStatus.RUNNING
                ? new ToolExecution(tool.toolExecutionId(), tool.toolName(), ToolStatus.FAILED, tool.startedAt(),
                    now, "도구 실행을 완료하지 못했습니다.", tool.evidenceIds(), error) : tool).toList();
        return view(current, Status.FAILED, now, progress, current.evidence(), null, error);
    }

    private static Investigation view(Investigation old, Status status, Instant now, List<ToolExecution> progress,
                                      List<EvidenceSummary> evidence, AnalysisReport report, ApiError error) {
        return new Investigation(old.schemaVersion(), old.investigationId(), old.ticketId(), old.ticketVersion(),
                status, old.createdAt(), now, List.copyOf(progress), List.copyOf(evidence), report, error);
    }

    private static ToolExecution runningTool(Investigation current, String id) {
        return current.progress().stream().filter(tool -> tool.toolExecutionId().equals(id) && tool.status() == ToolStatus.RUNNING)
                .findFirst().orElseThrow(() -> new IllegalStateException("Tool execution is not running"));
    }

    private Stored read(ResultSet row, int index) throws SQLException {
        return new Stored(json.readValue(row.getString("input_json"), InvestigationInput.class),
                json.readValue(row.getString("view_json"), Investigation.class));
    }

    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Text must not be blank");
    }
    private static void requireError(ApiError error) {
        if (error == null) throw new IllegalArgumentException("Error is required");
        requireText(error.code());
        requireText(error.message());
    }
}
