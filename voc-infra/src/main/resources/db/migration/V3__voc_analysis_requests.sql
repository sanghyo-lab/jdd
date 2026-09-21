CREATE TABLE voc.analysis_requests (
    analysis_request_id VARCHAR(36) PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL REFERENCES voc.tickets(ticket_id),
    request_key TEXT NOT NULL,
    ticket_version BIGINT NOT NULL CHECK (ticket_version > 0),
    input_json TEXT NOT NULL,
    submission_status VARCHAR(20) NOT NULL CHECK (submission_status IN ('PENDING', 'SUBMITTED', 'FAILED')),
    investigation_id TEXT UNIQUE,
    investigation_json TEXT,
    submission_error_json TEXT,
    sync_error_json TEXT,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (ticket_id, request_key),
    CHECK ((submission_status = 'SUBMITTED' AND investigation_id IS NOT NULL)
        OR (submission_status <> 'SUBMITTED' AND investigation_id IS NULL))
);
CREATE INDEX analyses_ticket_created_idx ON voc.analysis_requests (ticket_id, created_at DESC, analysis_request_id DESC);
