CREATE TABLE agent.investigations (
    investigation_id TEXT PRIMARY KEY,
    ticket_id TEXT NOT NULL,
    request_key TEXT NOT NULL,
    input_json TEXT NOT NULL,
    view_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT investigation_request_unique UNIQUE (ticket_id, request_key)
);

CREATE TABLE agent.investigation_evidence (
    investigation_id TEXT NOT NULL REFERENCES agent.investigations(investigation_id),
    evidence_id TEXT NOT NULL,
    detail_json TEXT NOT NULL,
    PRIMARY KEY (investigation_id, evidence_id)
);
