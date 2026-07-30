-- Inbound provider callbacks.
--
-- Acquirers re-send until acknowledged, so the same outcome arrives more than once. The unique
-- constraint on (provider_name, provider_event_id) is what makes the endpoint idempotent: a
-- duplicate is rejected by the database rather than republished onto Kafka.
CREATE TABLE provider_webhook_events (
    id UUID PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(120) NOT NULL,
    payment_id UUID,
    provider_reference VARCHAR(120),
    outcome VARCHAR(30),
    signature_verified BOOLEAN NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_provider_event UNIQUE (provider_name, provider_event_id)
);

CREATE INDEX idx_webhook_payment ON provider_webhook_events (payment_id);
CREATE INDEX idx_webhook_received_at ON provider_webhook_events (received_at DESC);
