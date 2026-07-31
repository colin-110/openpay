-- Outbound merchant webhooks.
--
-- Every delivery is a row before it is an HTTP request. A webhook that was attempted and failed
-- has to be visible and retryable; if the only record were a log line, a merchant asking "did you
-- ever tell me about this payment" would be unanswerable.
CREATE TABLE merchant_webhook_deliveries (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    -- The source event. Unique, so one domain event produces exactly one delivery no matter how
    -- many times Kafka hands it to us.
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    target_url TEXT,
    status VARCHAR(20) NOT NULL,          -- PENDING | DELIVERED | FAILED | ABANDONED
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    response_status INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_delivery_event UNIQUE (event_id)
);

-- The dispatcher's hot query: what is due, oldest first.
CREATE INDEX idx_deliveries_due ON merchant_webhook_deliveries (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_deliveries_merchant ON merchant_webhook_deliveries (merchant_id, created_at DESC);
