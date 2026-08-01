-- What the customer paid with.
--
-- Flat columns rather than a JSON blob: these are read on every list query and shown in every
-- table, and a handful of short strings is cheaper to index and impossible to get wrong at the
-- binding layer. It also makes "how much of my volume is UPI" an ordinary query later.
--
-- Nothing here can move money. The instrument token a merchant sends is deliberately not stored:
-- a card number, a CVV, or a reusable token has no business in a payment row, and the only reason
-- to keep one would be to do something this platform does not do. What is kept is the minimum
-- needed to recognise the payment on a statement — a network and last four digits, or a VPA with
-- its local part masked.
ALTER TABLE payments
    ADD COLUMN payment_method_type VARCHAR(20),
    ADD COLUMN payment_method_network VARCHAR(20),
    ADD COLUMN payment_method_last4 CHAR(4),
    ADD COLUMN payment_method_vpa VARCHAR(120),
    ADD COLUMN payment_method_bank VARCHAR(60);

-- Nullable on purpose: payments taken before this column existed genuinely do not know, and
-- inventing a default would be worse than an honest blank.
COMMENT ON COLUMN payments.payment_method_type IS 'card | upi | netbanking | wallet, or null for payments taken before this was captured';

CREATE INDEX idx_payments_method_type ON payments (merchant_id, payment_method_type);
