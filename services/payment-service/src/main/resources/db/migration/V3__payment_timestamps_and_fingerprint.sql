-- V2 created these as TIMESTAMP (no time zone) while the entities use OffsetDateTime, so the
-- offset was silently discarded on write. merchants and api_keys already use timestamptz;
-- this brings payments in line. Existing values were written as UTC instants.
ALTER TABLE payments
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMP WITH TIME ZONE USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE payment_events
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'UTC';

-- Request fingerprint backs idempotency conflict detection: replaying a key with a different body
-- is a client error, not a retry. Nullable so pre-existing rows (which have no recorded
-- fingerprint) keep replaying rather than failing closed on a hash we never captured.
ALTER TABLE payments
    ADD COLUMN request_fingerprint VARCHAR(64);

CREATE INDEX idx_payments_merchant_created_at ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payment_events_type ON payment_events (type);
