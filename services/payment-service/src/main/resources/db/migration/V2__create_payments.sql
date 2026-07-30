CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(50) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_payments_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS payment_events (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    payload JSONB,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_payment_events_payment_id FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE
);

CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
CREATE INDEX idx_payment_events_payment_id ON payment_events (payment_id);
