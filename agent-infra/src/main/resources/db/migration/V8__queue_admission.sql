-- A shared admission lock makes count + new request insertion atomic across connections.
-- Existing investigations remain unchanged, including any queue above the configured limit.
CREATE TABLE agent.queue_admission (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1)
);
INSERT INTO agent.queue_admission (singleton) VALUES (1);
