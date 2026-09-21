package com.jdd.voc.infra;

import com.jdd.voc.domain.AnalysisRepository;
import com.jdd.voc.domain.AnalysisRequest;
import com.jdd.voc.domain.AnalysisService;
import com.jdd.voc.domain.AnalysisProcessor;
import com.jdd.voc.domain.AnalysisWorkRepository;
import com.jdd.voc.domain.TicketRepository;
import com.jdd.voc.domain.VocFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcAnalysisRepository implements AnalysisRepository, AnalysisWorkRepository {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TicketRepository tickets;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public JdbcAnalysisRepository(JdbcTemplate jdbc, TicketRepository tickets, JsonMapper json,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.tickets = tickets; this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override public AnalysisRequest createOrReuse(String ticketId, String key, long version, String previous, Instant now) {
        return transaction.execute(ignored -> {
            // Every intake for this ticket follows this lock order. PATCH's versioned UPDATE
            // uses the same row lock, so the saved input cannot mix two ticket versions.
            if (jdbc.queryForList("SELECT ticket_id FROM voc.tickets WHERE ticket_id=? FOR UPDATE", String.class, ticketId).isEmpty())
                throw VocFailure.notFound();
            var existing = jdbc.query("SELECT * FROM voc.analysis_requests WHERE ticket_id=? AND request_key=? FOR UPDATE",
                    this::read, ticketId, key).stream().findFirst();
            if (existing.isPresent()) {
                AnalysisRequest saved = existing.get();
                if (saved.ticketVersion() != version || !Objects.equals(saved.input().previousInvestigationId(), previous))
                    throw new VocFailure("REQUEST_KEY_CONFLICT", "같은 요청 키로 다른 분석 입력을 요청할 수 없습니다.");
                if (saved.submissionStatus() == AnalysisRequest.SubmissionStatus.FAILED
                        && saved.submissionError() != null && saved.submissionError().retryable()) {
                    jdbc.update("UPDATE voc.analysis_requests SET submission_status='PENDING', submission_error_json=NULL, updated_at=?, "
                                    + "delivery_attempts=0, queue_rejections=0, next_work_at=?, lease_token=NULL, lease_until=NULL "
                                    + "WHERE analysis_request_id=?", Timestamp.from(now), Timestamp.from(now), saved.analysisRequestId());
                    return find(ticketId, saved.analysisRequestId()).orElseThrow();
                }
                return saved;
            }
            var ticket = tickets.find(ticketId).orElseThrow(VocFailure::notFound);
            if (ticket.version() != version) throw VocFailure.versionConflict();
            if (previous != null && jdbc.queryForList("SELECT analysis_request_id FROM voc.analysis_requests "
                    + "WHERE ticket_id=? AND investigation_id=?", String.class, ticketId, previous).isEmpty())
                throw AnalysisService.notFound();
            var input = new AnalysisRequest.Input("1.0", ticketId, version, key, ticket.message(), ticket.context(), previous);
            var result = new AnalysisRequest(UUID.randomUUID().toString(), ticketId, version,
                    AnalysisRequest.SubmissionStatus.PENDING, null, input, null, null, null, null, now, now);
            jdbc.update("INSERT INTO voc.analysis_requests (analysis_request_id, ticket_id, request_key, ticket_version, "
                            + "input_json, submission_status, created_at, updated_at, next_work_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                    result.analysisRequestId(), ticketId, key, version, json.writeValueAsString(input),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            return result;
        });
    }

    @Override public Optional<AnalysisRequest> find(String ticketId, String analysisId) {
        return jdbc.query("SELECT * FROM voc.analysis_requests WHERE ticket_id=? AND analysis_request_id=?",
                this::read, ticketId, analysisId).stream().findFirst();
    }

    @Override public List<AnalysisRequest> list(String ticketId) {
        return jdbc.query("SELECT * FROM voc.analysis_requests WHERE ticket_id=? ORDER BY created_at DESC, analysis_request_id DESC",
                this::read, ticketId);
    }

    @Override public Optional<Work> claim(Instant now, Duration lease) {
        return transaction.execute(ignored -> {
            var candidates = jdbc.queryForList("SELECT analysis_request_id FROM voc.analysis_requests "
                    + "WHERE submission_status IN ('PENDING','SUBMITTED') AND next_work_at<=? "
                    + "AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_work_at, analysis_request_id LIMIT 16",
                    String.class, timestamp(now), timestamp(now));
            for (String id : candidates) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("UPDATE voc.analysis_requests SET lease_token=?, lease_until=?, "
                        + "delivery_attempts=delivery_attempts + CASE WHEN submission_status='PENDING' THEN 1 ELSE 0 END "
                        + "WHERE analysis_request_id=? AND next_work_at<=? AND (lease_until IS NULL OR lease_until<=?) "
                        + "AND submission_status IN ('PENDING','SUBMITTED')", token, timestamp(now.plus(lease)), id,
                        timestamp(now), timestamp(now));
                if (changed == 0) continue;
                Work work = jdbc.queryForObject("SELECT * FROM voc.analysis_requests WHERE analysis_request_id=?",
                        (row, index) -> new Work(read(row, index), token, row.getInt("delivery_attempts"), row.getInt("queue_rejections"),
                                instant(row.getTimestamp("observe_until")), row.getBoolean("manual_refresh_requested")), id);
                jdbc.update("UPDATE voc.analysis_requests SET manual_refresh_requested=FALSE WHERE analysis_request_id=?", id);
                return Optional.of(work);
            }
            return Optional.empty();
        });
    }

    @Override public boolean submitted(Work work, String investigationId, Instant now, Instant observeUntil) {
        return jdbc.update("UPDATE voc.analysis_requests SET submission_status='SUBMITTED', investigation_id=?, "
                + "submission_error_json=NULL, next_work_at=?, observe_until=?, updated_at=?, lease_token=NULL, lease_until=NULL "
                + "WHERE analysis_request_id=? AND lease_token=? AND lease_until>?", investigationId, timestamp(now),
                timestamp(observeUntil), timestamp(now), work.analysis().analysisRequestId(), work.token(), timestamp(now)) == 1;
    }

    @Override public boolean deliveryFailed(Work work, AnalysisRequest.Error error, Instant next, int queueRejections, Instant now) {
        return jdbc.update("UPDATE voc.analysis_requests SET submission_status=?, submission_error_json=?, next_work_at=?, "
                + "queue_rejections=?, updated_at=?, lease_token=NULL, lease_until=NULL "
                + "WHERE analysis_request_id=? AND lease_token=? AND lease_until>?",
                next == null ? "FAILED" : "PENDING", json.writeValueAsString(error), timestamp(next), queueRejections, timestamp(now),
                work.analysis().analysisRequestId(), work.token(), timestamp(now)) == 1;
    }

    @Override public boolean polled(Work work, Map<String, Object> investigation, AnalysisRequest.Error error, Instant next, Instant now) {
        boolean terminal = AnalysisProcessor.terminal(investigation == null ? work.analysis().investigation() : investigation);
        return jdbc.update("UPDATE voc.analysis_requests SET investigation_json=COALESCE(?, investigation_json), "
                + "last_synced_at=CASE WHEN ? THEN ? ELSE last_synced_at END, sync_error_json=?, updated_at=?, "
                + "next_work_at=CASE WHEN manual_refresh_requested AND NOT ? THEN CAST(? AS TIMESTAMP WITH TIME ZONE) "
                + "ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END, "
                + "manual_refresh_requested=CASE WHEN ? THEN FALSE ELSE manual_refresh_requested END, "
                + "lease_token=NULL, lease_until=NULL WHERE analysis_request_id=? AND lease_token=? AND lease_until>?",
                investigation == null ? null : json.writeValueAsString(investigation), investigation != null, timestamp(now),
                error == null ? null : json.writeValueAsString(error), timestamp(now), terminal, timestamp(now), timestamp(next), terminal,
                work.analysis().analysisRequestId(), work.token(), timestamp(now)) == 1;
    }

    @Override public void requestRefresh(String ticketId, String analysisId, Instant now) {
        jdbc.update("UPDATE voc.analysis_requests SET manual_refresh_requested=TRUE, "
                + "next_work_at=CASE WHEN next_work_at IS NULL OR next_work_at>? THEN ? ELSE next_work_at END "
                + "WHERE ticket_id=? AND analysis_request_id=? AND submission_status='SUBMITTED'",
                timestamp(now), timestamp(now), ticketId, analysisId);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private AnalysisRequest read(ResultSet row, int index) throws SQLException {
        String investigation = row.getString("investigation_json");
        String submissionError = row.getString("submission_error_json");
        String syncError = row.getString("sync_error_json");
        Timestamp synced = row.getTimestamp("last_synced_at");
        return new AnalysisRequest(row.getString("analysis_request_id"), row.getString("ticket_id"), row.getLong("ticket_version"),
                AnalysisRequest.SubmissionStatus.valueOf(row.getString("submission_status")), row.getString("investigation_id"),
                json.readValue(row.getString("input_json"), AnalysisRequest.Input.class),
                investigation == null ? null : json.readValue(investigation, OBJECT),
                submissionError == null ? null : json.readValue(submissionError, AnalysisRequest.Error.class),
                syncError == null ? null : json.readValue(syncError, AnalysisRequest.Error.class),
                synced == null ? null : synced.toInstant(), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
