package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInvestigationRepository implements InvestigationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Duration maximumQueueWait;

    public JdbcInvestigationRepository(JdbcTemplate jdbc, JsonMapper json,
                                      @Value("${jdd.agent.queue.maximum-wait:PT10M}") Duration maximumQueueWait) {
        if (maximumQueueWait.compareTo(Duration.ofSeconds(1)) < 0 || maximumQueueWait.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Queue maximum wait must be between one second and one hour");
        this.jdbc = jdbc;
        this.json = json;
        this.maximumQueueWait = maximumQueueWait;
    }

    @Override public Optional<Stored> find(String id) {
        return jdbc.query("SELECT input_json, view_json FROM agent.investigations WHERE investigation_id = ?",
                this::read, id).stream().findFirst();
    }

    @Override public Optional<Stored> findByRequest(String ticketId, String requestKey) {
        return jdbc.query("SELECT input_json, view_json FROM agent.investigations WHERE ticket_id = ? AND request_key = ?",
                this::read, ticketId, requestKey).stream().findFirst();
    }

    @Override public Stored insertOrFind(Stored candidate) {
        var view = candidate.investigation();
        // Each statement commits separately. A failed unique insert must not poison a PostgreSQL
        // transaction before we read the committed winner of a simultaneous request.
        try {
            jdbc.update("""
                    INSERT INTO agent.investigations
                    (investigation_id, ticket_id, request_key, input_json, view_json, created_at, queued_deadline_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, view.investigationId(), view.ticketId(), candidate.input().requestKey(),
                    json.writeValueAsString(candidate.input()), json.writeValueAsString(view),
                    OffsetDateTime.ofInstant(view.createdAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(view.createdAt().plus(maximumQueueWait), ZoneOffset.UTC));
            return candidate;
        } catch (DuplicateKeyException conflict) {
            return findByRequest(candidate.input().ticketId(), candidate.input().requestKey()).orElseThrow(() -> conflict);
        }
    }

    @Override public Optional<Investigation.EvidenceDetail> findEvidence(String investigationId, String evidenceId) {
        return jdbc.query("""
                SELECT detail_json FROM agent.investigation_evidence
                WHERE investigation_id = ? AND evidence_id = ?
                """, (rs, row) -> json.readValue(rs.getString(1), Investigation.EvidenceDetail.class),
                investigationId, evidenceId).stream().findFirst();
    }

    private Stored read(ResultSet row, int index) throws SQLException {
        return new Stored(json.readValue(row.getString("input_json"), InvestigationInput.class),
                json.readValue(row.getString("view_json"), Investigation.class));
    }
}
