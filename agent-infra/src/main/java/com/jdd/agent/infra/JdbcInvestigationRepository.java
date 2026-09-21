package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationRepository;
import com.jdd.agent.domain.InvestigationException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcInvestigationRepository implements InvestigationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Duration maximumQueueWait;
    private final int queueCapacity;
    private final TransactionTemplate admission;

    public JdbcInvestigationRepository(JdbcTemplate jdbc, JsonMapper json, Duration maximumQueueWait) {
        this(jdbc, json, maximumQueueWait, 20);
    }

    @Autowired
    public JdbcInvestigationRepository(JdbcTemplate jdbc, JsonMapper json,
                                      @Value("${jdd.agent.queue.maximum-wait:PT10M}") Duration maximumQueueWait,
                                      @Value("${jdd.agent.queue.capacity:20}") int queueCapacity) {
        if (maximumQueueWait.compareTo(Duration.ofSeconds(1)) < 0 || maximumQueueWait.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Queue maximum wait must be between one second and one hour");
        if (queueCapacity < 1 || queueCapacity > 100)
            throw new IllegalArgumentException("Queue capacity must be between one and one hundred");
        this.jdbc = jdbc;
        this.json = json;
        this.maximumQueueWait = maximumQueueWait;
        this.queueCapacity = queueCapacity;
        this.admission = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        // Count using a fresh snapshot after waiting for the admission lock.
        this.admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        // Serialize admission across connections/processes. Recheck the key after obtaining
        // the lock so the winner of a same-key race is returned even when it fills the queue.
        return admission.execute(transaction -> {
            jdbc.queryForObject("SELECT singleton FROM agent.queue_admission WHERE singleton=1 FOR UPDATE", Integer.class);
            var existing = findByRequest(candidate.input().ticketId(), candidate.input().requestKey());
            if (existing.isPresent()) return existing.get();
            int queued = jdbc.queryForObject("SELECT count(*) FROM agent.investigations WHERE execution_status='QUEUED'", Integer.class);
            if (queued >= queueCapacity) throw InvestigationException.queueFull();
            jdbc.update("""
                    INSERT INTO agent.investigations
                    (investigation_id, ticket_id, request_key, input_json, view_json, created_at, queued_deadline_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, view.investigationId(), view.ticketId(), candidate.input().requestKey(),
                    json.writeValueAsString(candidate.input()), json.writeValueAsString(view),
                    OffsetDateTime.ofInstant(view.createdAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(view.createdAt().plus(maximumQueueWait), ZoneOffset.UTC));
            return candidate;
        });
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
