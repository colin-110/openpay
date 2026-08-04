-- When a payment started waiting for asynchronous screening, and nothing else.
--
-- Both kinds of hold are FraudStatus.HELD, deliberately: it means the release path, the review
-- queue and applyScreeningOutcome work unchanged for either. But the two are not operationally
-- the same thing, and without a way to tell them apart they cannot be monitored apart:
--
--   * held by a rule  — normal, expected, waiting for a human, may sit for hours legitimately
--   * awaiting async screening — waiting for a machine that should answer in milliseconds
--
-- The second one sitting for ten minutes is an incident. The first one sitting for ten minutes is
-- Tuesday. A single "count of HELD payments" alert cannot distinguish those and would either be
-- permanently firing or permanently useless.
--
-- Null for every existing row, which is correct: nothing created before this column existed was
-- ever waiting on asynchronous screening, because the mode did not exist.
ALTER TABLE payments ADD COLUMN screening_requested_at TIMESTAMPTZ;

-- Partial index: the reaper asks "which payments are still waiting, and since when", and the
-- answer is nearly always none. Indexing only the rows that are actually waiting keeps this to a
-- few pages rather than one entry per payment ever taken.
CREATE INDEX idx_payments_awaiting_screening
    ON payments (screening_requested_at)
    WHERE screening_requested_at IS NOT NULL;
