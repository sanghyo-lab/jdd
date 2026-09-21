package com.jdd.agent.infra;

import com.jdd.agent.domain.ModelCallLedger;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcModelCallLedger implements ModelCallLedger {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public JdbcModelCallLedger(JdbcTemplate jdbc, JsonMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        transaction = new TransactionTemplate(manager);
    }

    @Override public void configure(Budget budget) {
        // A duplicate insert is allowed only when the immutable existing allocation matches.
        try {
            jdbc.update("INSERT INTO agent.demo_budget (budget_id, scope, limit_usd, calls_per_investigation, concurrent_calls, maximum_calls) VALUES (1, ?, ?, ?, ?, ?)",
                    budget.scope(), budget.limitUsd(), budget.callsPerInvestigation(), budget.concurrentCalls(), budget.maximumCalls());
        } catch (DuplicateKeyException exists) {
            Budget previous = jdbc.queryForObject("SELECT * FROM agent.demo_budget WHERE budget_id = 1", this::readBudget);
            if (!budget.equals(previous)) throw new IllegalStateException("Existing demo budget cannot be changed or reset");
        }
    }

    @Override public Optional<Entry> reserve(Request request, Instant now) {
        return transaction.execute(ignored -> {
            Budget budget = lockBudget();
            if (find(request.callId()).isPresent()) throw new IllegalStateException("Model call ID was already used");
            Totals totals = totals(budget.limitUsd());
            if (totals.committedUsd().add(request.maximumCost()).compareTo(budget.limitUsd()) > 0) return Optional.empty();
            if (jdbc.queryForObject("SELECT count(*) FROM agent.model_calls WHERE state = 'UNKNOWN'", Integer.class) > 0)
                return Optional.empty();
            if (budget.maximumCalls() != null && jdbc.queryForObject(
                    "SELECT count(*) FROM agent.model_calls WHERE state <> 'CANCELLED'", Long.class) >= budget.maximumCalls())
                return Optional.empty();
            int investigationCalls = jdbc.queryForObject("SELECT count(*) FROM agent.model_calls WHERE investigation_id = ? AND state <> 'CANCELLED'",
                    Integer.class, request.investigationId());
            int active = jdbc.queryForObject("SELECT count(*) FROM agent.model_calls WHERE state IN ('RESERVED', 'DISPATCHED')", Integer.class);
            if (investigationCalls >= budget.callsPerInvestigation() || active >= budget.concurrentCalls()) return Optional.empty();
            jdbc.update("""
                    INSERT INTO agent.model_calls (call_id, investigation_id, request_json, state, reserved_usd,
                    confirmed_usd, receipt_json, created_at, updated_at) VALUES (?, ?, ?, 'RESERVED', ?, NULL, NULL, ?, ?)
                    """, request.callId(), request.investigationId(), json.writeValueAsString(request), request.maximumCost(),
                    timestamp(now), timestamp(now));
            return find(request.callId());
        });
    }

    @Override public boolean dispatch(String callId, Instant now) {
        return transaction.execute(ignored -> {
            lockBudget();
            return jdbc.update("UPDATE agent.model_calls SET state = 'DISPATCHED', updated_at = ? WHERE call_id = ? AND state = 'RESERVED'",
                    timestamp(now), callId) == 1;
        });
    }

    @Override public Entry settle(String callId, Receipt receipt) {
        return transaction.execute(ignored -> {
            lockBudget();
            Entry existing = find(callId).orElseThrow();
            if (existing.state() == State.CANCELLED || existing.state() == State.RESERVED)
                throw new IllegalStateException("Unsent call cannot receive model usage");
            if (existing.state() == State.CONFIRMED) {
                if (!receipt.equals(existing.receipt())) throw new IllegalStateException("Conflicting model call receipt");
                return existing;
            }
            if (existing.state() == State.UNKNOWN && receipt.equals(existing.receipt())) return existing;
            Optional<BigDecimal> observed = !Boolean.FALSE.equals(receipt.tariffVerified())
                    && existing.request().pricing().model().equals(receipt.actualModel())
                    ? existing.request().pricing().observedCost(receipt.usage()) : Optional.empty();
            String state = observed.isPresent() ? State.CONFIRMED.name() : State.UNKNOWN.name();
            // Actual usage must still be recorded if it exceeds the estimate. Subsequent calls then fail closed.
            jdbc.update("UPDATE agent.model_calls SET state = ?, confirmed_usd = ?, receipt_json = ?, updated_at = ? WHERE call_id = ?",
                    state, observed.orElse(null), json.writeValueAsString(receipt), timestamp(receipt.finishedAt()), callId);
            return find(callId).orElseThrow();
        });
    }

    @Override public Entry cancelUnsent(String callId, Instant now) {
        return transaction.execute(ignored -> {
            lockBudget();
            var existing = find(callId).orElseThrow();
            if (existing.state() != State.RESERVED) throw new IllegalStateException("Only an unsent reservation can be cancelled");
            jdbc.update("UPDATE agent.model_calls SET state = 'CANCELLED', updated_at = ? WHERE call_id = ?", timestamp(now), callId);
            return find(callId).orElseThrow();
        });
    }

    @Override public int recoverUnsettled(Instant now) {
        return transaction.execute(ignored -> {
            // No budget means no paid call could have been reserved.
            if (jdbc.queryForObject("SELECT count(*) FROM agent.demo_budget", Integer.class) == 0) return 0;
            lockBudget();
            var receipt = new Receipt(null, null, null, "INTERRUPTED_USAGE_UNKNOWN", now);
            return jdbc.update("""
                    UPDATE agent.model_calls SET state = 'UNKNOWN', receipt_json = ?, updated_at = ? WHERE state IN ('RESERVED', 'DISPATCHED')
                    """, json.writeValueAsString(receipt), timestamp(now));
        });
    }

    @Override public Optional<Entry> find(String callId) {
        return jdbc.query("SELECT * FROM agent.model_calls WHERE call_id = ?", this::read, callId).stream().findFirst();
    }

    @Override public Totals totals() {
        return transaction.execute(ignored -> totals(lockBudget().limitUsd()));
    }

    private Totals totals(BigDecimal limit) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN state = 'CONFIRMED' THEN confirmed_usd ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN state = 'UNKNOWN' THEN reserved_usd ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN state IN ('RESERVED', 'DISPATCHED') THEN reserved_usd ELSE 0 END), 0) FROM agent.model_calls
                """, (rs, row) -> new Totals(limit, rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
    }

    private Budget lockBudget() {
        var rows = jdbc.query("SELECT * FROM agent.demo_budget WHERE budget_id = 1 FOR UPDATE", this::readBudget);
        if (rows.isEmpty()) throw new IllegalStateException("Demo budget is not configured");
        return rows.getFirst();
    }

    private Budget readBudget(ResultSet rs, int row) throws SQLException {
        return new Budget(rs.getString("scope"), rs.getBigDecimal("limit_usd"),
                rs.getInt("calls_per_investigation"), rs.getInt("concurrent_calls"), rs.getObject("maximum_calls", Integer.class));
    }

    private Entry read(ResultSet rs, int row) throws SQLException {
        String receipt = rs.getString("receipt_json");
        return new Entry(json.readValue(rs.getString("request_json"), Request.class), State.valueOf(rs.getString("state")),
                rs.getBigDecimal("reserved_usd"), rs.getBigDecimal("confirmed_usd"),
                receipt == null ? null : json.readValue(receipt, Receipt.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
