ALTER TABLE agent.investigations ADD COLUMN execution_status TEXT NOT NULL DEFAULT 'QUEUED';
ALTER TABLE agent.investigations ADD COLUMN execution_token TEXT;
ALTER TABLE agent.investigations ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE agent.investigations ADD CONSTRAINT investigation_execution_status
    CHECK (execution_status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'NEEDS_INPUT', 'FAILED'));
CREATE INDEX investigation_queue ON agent.investigations (execution_status, created_at, investigation_id);
