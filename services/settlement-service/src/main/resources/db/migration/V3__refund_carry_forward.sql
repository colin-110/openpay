-- A refund is a negative payable. It is accrued exactly like a capture, with the sign reversed,
-- so it nets against the merchant's next payout instead of needing a separate mechanism.
--
-- The original CHECK required a positive gross, which was right when only captures existed.
ALTER TABLE settlement_items DROP CONSTRAINT ck_item_amounts;

ALTER TABLE settlement_items
    ADD CONSTRAINT ck_item_amounts CHECK (gross_amount <> 0);

-- Refund items reference the refund, not the payment, so both can coexist for one payment.
ALTER TABLE settlement_items
    ADD COLUMN refund_id UUID,
    ADD COLUMN item_type VARCHAR(20) NOT NULL DEFAULT 'CAPTURE';

-- payment_id alone was unique, which would have stopped a payment from ever being refunded.
ALTER TABLE settlement_items DROP CONSTRAINT settlement_items_payment_id_key;

-- One accrual per capture and one per refund, still enforced, just per source.
CREATE UNIQUE INDEX uq_item_capture ON settlement_items (payment_id)
    WHERE item_type = 'CAPTURE';
CREATE UNIQUE INDEX uq_item_refund ON settlement_items (refund_id)
    WHERE item_type = 'REFUND';

COMMENT ON COLUMN settlement_items.gross_amount IS
    'Positive for a capture, negative for a refund. Netted within a settlement window.';
