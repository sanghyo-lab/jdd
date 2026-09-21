CREATE TABLE voc.tickets (
    ticket_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version > 0),
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    assignee_id VARCHAR(20) CHECK (assignee_id IN ('sanghyo', 'areum', 'jaehong')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    context_customer_id TEXT,
    context_order_id TEXT,
    context_product_id TEXT,
    context_request_id TEXT,
    context_checkout_key TEXT,
    context_occurred_at TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX tickets_created_idx ON voc.tickets (created_at DESC, ticket_id DESC);
CREATE INDEX tickets_status_created_idx ON voc.tickets (status, created_at DESC, ticket_id DESC);
CREATE INDEX tickets_assignee_created_idx ON voc.tickets (assignee_id, created_at DESC, ticket_id DESC);
