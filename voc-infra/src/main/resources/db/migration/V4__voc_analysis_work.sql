ALTER TABLE voc.analysis_requests ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE voc.analysis_requests ADD COLUMN queue_rejections INTEGER NOT NULL DEFAULT 0;
ALTER TABLE voc.analysis_requests ADD COLUMN next_work_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE voc.analysis_requests ADD COLUMN observe_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE voc.analysis_requests ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE voc.analysis_requests ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE voc.analysis_requests ADD COLUMN manual_refresh_requested BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE voc.analysis_requests SET next_work_at=created_at WHERE submission_status='PENDING';
UPDATE voc.analysis_requests SET next_work_at=CURRENT_TIMESTAMP,
    observe_until=CURRENT_TIMESTAMP + INTERVAL '14' MINUTE WHERE submission_status='SUBMITTED';
CREATE INDEX analyses_work_due_idx ON voc.analysis_requests (next_work_at, lease_until);
