ALTER TABLE agent.investigations ADD COLUMN queued_deadline_at TIMESTAMP WITH TIME ZONE;
-- Legacy requests keep a deadline based on their original acceptance, not this deployment.
UPDATE agent.investigations SET queued_deadline_at = created_at + INTERVAL '10' MINUTE;
ALTER TABLE agent.investigations ALTER COLUMN queued_deadline_at SET NOT NULL;
CREATE INDEX investigation_queue_deadline ON agent.investigations (execution_status, queued_deadline_at);
