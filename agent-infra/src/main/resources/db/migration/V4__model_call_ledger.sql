CREATE TABLE agent.demo_budget (
    budget_id INTEGER PRIMARY KEY CHECK (budget_id = 1),
    scope TEXT NOT NULL,
    limit_usd NUMERIC(38, 12) NOT NULL CHECK (limit_usd > 0 AND limit_usd <= 30),
    calls_per_investigation INTEGER NOT NULL CHECK (calls_per_investigation > 0),
    concurrent_calls INTEGER NOT NULL CHECK (concurrent_calls > 0)
);

CREATE TABLE agent.model_calls (
    call_id TEXT PRIMARY KEY,
    investigation_id TEXT NOT NULL REFERENCES agent.investigations(investigation_id),
    request_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'DISPATCHED', 'CONFIRMED', 'UNKNOWN', 'CANCELLED')),
    reserved_usd NUMERIC(38, 12) NOT NULL CHECK (reserved_usd >= 0),
    confirmed_usd NUMERIC(38, 12),
    receipt_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX model_calls_investigation ON agent.model_calls (investigation_id, created_at, call_id);
