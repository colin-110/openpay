-- Indexes matched to the queries that actually run, now that there are enough of them to check.
--
-- An index nobody reads is not free: every insert maintains it, and `payments` is the hottest
-- insert on the platform. Three changes, each tied to a specific repository method.

-- 1. The filtered list. `findByMerchantIdAndStatusOrderByCreatedAtDesc` backs the dashboard's
--    status filter, and until now it had to use (merchant_id, created_at) and discard rows that
--    did not match the status. That is fine at demo scale and wrong for a merchant with a hundred
--    thousand payments filtering to the handful that FAILED: the scan is proportional to their
--    whole history rather than to the answer.
CREATE INDEX idx_payments_merchant_status_created
    ON payments (merchant_id, status, created_at DESC);

-- 2. idx_payments_merchant_id is a strict prefix of idx_payments_merchant_created_at, so every
--    query it could serve is already served. It has been costing an index write per payment for
--    nothing since V3 added the wider one.
DROP INDEX IF EXISTS idx_payments_merchant_id;

-- 3. idx_payment_events_type indexes a column with about six distinct values that no query
--    filters on -- PaymentEventRepository has no finder at all. Low cardinality makes it close to
--    useless even if something did, and payment_events gets a row per transition.
DROP INDEX IF EXISTS idx_payment_events_type;

-- The same gap on refunds, and a worse version of it: idx_refunds_status leads with `status`, so a
-- merchant-scoped query cannot use it at all. Every refund list filtered by status was falling
-- back to the merchant index and discarding rows.
CREATE INDEX idx_refunds_merchant_status_created
    ON refunds (merchant_id, status, created_at DESC);
DROP INDEX IF EXISTS idx_refunds_status;

-- Deliberately not added:
--
--   * an index on payments.status alone. Nothing queries payments across merchants -- every read
--     is scoped by the credential -- so it would serve no query while indexing a column with seven
--     values.
--   * an index on refunds.payment_id + status for sumCommittedAmount. idx_refunds_payment already
--     narrows to the handful of refunds against one payment, and the status filter then applies to
--     a set small enough that a second column buys nothing.
