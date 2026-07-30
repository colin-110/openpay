-- Store money in the currency's smallest unit, as the architecture document specifies.
--
-- NUMERIC(19,4) is exact, but every service that touches an amount would have to agree on scale,
-- and the ledger, refunds, and settlements are all about to start carrying amounts. Integer minor
-- units remove the question entirely and match how Stripe, Razorpay, and Adyen model money.
--
-- Existing rows are all 2-decimal currencies, so the conversion is a factor of 100. This is the
-- cheap moment to do it: after the ledger exists it would mean migrating four tables of financial
-- records at once.
ALTER TABLE payments
    ALTER COLUMN amount TYPE BIGINT USING round(amount * 100);

COMMENT ON COLUMN payments.amount IS
    'Amount in the currency''s smallest unit (e.g. cents for USD, paise for INR).';
