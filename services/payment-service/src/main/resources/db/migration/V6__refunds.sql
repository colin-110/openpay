-- Refunds are their own resource, not a payment status.
--
-- A payment can be refunded in parts, so "refunded" is not a single point on the payment's
-- lifecycle; it is a running total against it. Modelling refunds separately is also how Stripe and
-- Razorpay expose them, and it keeps partial refunds from turning the payment state machine into a
-- combinatorial mess.
CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,           -- PENDING | SUCCEEDED | FAILED
    reason VARCHAR(255),
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64),
    failure_reason VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_refund_amount_positive CHECK (amount > 0),
    -- Same defence as payments: a retried request must not become a second refund.
    CONSTRAINT uq_refunds_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_merchant_created ON refunds (merchant_id, created_at DESC);
CREATE INDEX idx_refunds_status ON refunds (status, created_at);
