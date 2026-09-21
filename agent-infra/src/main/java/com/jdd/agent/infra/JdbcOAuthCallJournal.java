package com.jdd.agent.infra;

import com.jdd.agent.domain.ModelUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcOAuthCallJournal implements OAuthCallJournal {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public JdbcOAuthCallJournal(JdbcTemplate jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }
    public void start(String id, String investigation, int iteration, String model) {
        jdbc.update("INSERT INTO agent.oauth_model_calls (call_id, investigation_id, iteration, requested_model, outcome) VALUES (?, ?, ?, ?, 'DISPATCHED')",
                id, investigation, iteration, model);
    }
    public void finish(String id, String model, ModelUsage usage, String outcome, long elapsed) {
        jdbc.update("UPDATE agent.oauth_model_calls SET actual_model=?, usage_json=?, outcome=?, elapsed_millis=? WHERE call_id=?",
                model, usage == null ? null : json.writeValueAsString(usage), outcome, elapsed, id);
    }
}
