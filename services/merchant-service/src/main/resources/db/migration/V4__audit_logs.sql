-- Merchant-service's own audit log: who onboarded a merchant, and who rotated a signing secret.
--
-- Identical in shape to auth-service's, and deliberately a second table rather than a shared one.
-- A central audit database would make every service's writes depend on a schema none of them owns,
-- and would be the one table whose outage stops the whole platform recording anything. The code
-- behind it is shared in libs/common-audit; only the storage is per-service.
--
-- Nothing in the application ever issues an UPDATE or DELETE against this table.

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    subject VARCHAR(200),
    merchant_id UUID,
    succeeded BOOLEAN NOT NULL,
    detail VARCHAR(500),
    source_ip VARCHAR(45),
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_recent ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_merchant ON audit_logs (merchant_id, occurred_at DESC);
