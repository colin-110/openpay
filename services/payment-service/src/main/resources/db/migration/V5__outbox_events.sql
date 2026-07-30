-- Transactional outbox.
--
-- The payment row and its outbox row are written in one local transaction, so a payment can never
-- be committed without its event, and an event can never describe a payment that rolled back.
-- A relay publishes to Kafka afterwards. Publishing inside the transaction instead would mean a
-- Kafka outage either loses the event or blocks the payment.
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

-- The relay's hot query: oldest unpublished first, so per-aggregate ordering is preserved.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
