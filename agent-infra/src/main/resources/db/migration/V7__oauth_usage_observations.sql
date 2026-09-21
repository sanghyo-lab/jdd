-- Independent of the paid API ledger. No USD amount or inferred zero usage.
CREATE TABLE agent.oauth_model_calls (
    call_id TEXT PRIMARY KEY,
    investigation_id TEXT NOT NULL,
    iteration INTEGER NOT NULL,
    requested_model TEXT NOT NULL,
    actual_model TEXT,
    usage_json TEXT,
    outcome TEXT NOT NULL,
    elapsed_millis BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX oauth_model_calls_investigation ON agent.oauth_model_calls (investigation_id, created_at);
