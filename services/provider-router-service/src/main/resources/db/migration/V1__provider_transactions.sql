-- One row per attempt against a provider, so a payment that failed over to a second acquirer
-- keeps a full record of what was tried, in what order, and why each attempt ended.
CREATE TABLE provider_transactions (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    provider_name VARCHAR(50) NOT NULL,
    provider_reference VARCHAR(120),
    attempt_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- One attempt number per payment: makes a redelivered payment.created event unable to
    -- double-dispatch to the same provider.
    CONSTRAINT uq_provider_txn_attempt UNIQUE (payment_id, attempt_no)
);

CREATE INDEX idx_provider_txn_payment ON provider_transactions (payment_id);
CREATE INDEX idx_provider_txn_provider_status ON provider_transactions (provider_name, status, created_at DESC);
