-- The same transactional outbox the other publishing services use. The implementation is shared in
-- libs/common-outbox; the table is per-service because each service owns its own schema.
--
-- It matters more here than elsewhere: the decision row and the event announcing it must commit
-- together, or a payment can be held for review with nothing left to release it.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    topic VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
