package com.jdd.agent.infra;

import com.jdd.agent.domain.ModelObservationRepository;
import com.jdd.agent.domain.ModelUsage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcModelObservationRepository implements ModelObservationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcModelObservationRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<List<Call>> find(String investigationId) {
        if (jdbc.queryForObject("SELECT count(*) FROM agent.investigations WHERE investigation_id = ?",
                Integer.class, investigationId) == 0) return Optional.empty();
        var calls = new ArrayList<>(jdbc.query("""
                SELECT call_id, investigation_id, requested_model, actual_model, outcome, usage_json, created_at, elapsed_millis
                FROM agent.oauth_model_calls WHERE investigation_id = ?
                """, (rs, row) -> new Call(rs.getString("call_id"), rs.getString("investigation_id"), rs.getString("requested_model"),
                rs.getString("actual_model"), "codex_oauth", rs.getString("outcome"), null,
                rs.getString("usage_json") == null ? null : json.readValue(rs.getString("usage_json"), ModelUsage.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("elapsed_millis", Long.class)),
                investigationId));
        calls.addAll(jdbc.query("""
                SELECT call_id, investigation_id, request_json, state, receipt_json, created_at
                FROM agent.model_calls WHERE investigation_id = ?
                """, (rs, row) -> {
            var request = json.readTree(rs.getString("request_json"));
            String receiptValue = rs.getString("receipt_json");
            JsonNode receipt = receiptValue == null ? null : json.readTree(receiptValue);
            JsonNode usage = receipt == null ? null : receipt.get("usage");
            return new Call(rs.getString("call_id"), rs.getString("investigation_id"), request.path("pricing").path("model").asText(null),
                    receipt == null ? null : receipt.path("actualModel").asText(null), "openai_api",
                    receipt == null ? rs.getString("state") : receipt.path("outcome").asText(null), rs.getString("state"),
                    usage == null || usage.isNull() ? null : json.treeToValue(usage, ModelUsage.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    // Reservation/settlement timestamps are not measured HTTP latency.
                    null);
        }, investigationId));
        calls.sort(Comparator.comparing(Call::createdAt).thenComparing(Call::callId).thenComparing(Call::provider));
        return Optional.of(List.copyOf(calls));
    }
}
