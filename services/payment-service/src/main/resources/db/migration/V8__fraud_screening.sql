-- Where a payment stands with risk screening, kept separately from its lifecycle status.
--
-- Folding this into PaymentStatus was the obvious alternative and it is wrong: a held payment is
-- still CREATED, and a released one is still CREATED, so a HELD status would have to be unwound
-- into the state it interrupted. Two orthogonal facts, two columns.
--
-- ALLOWED for existing rows because they were created before screening existed and were, in fact,
-- allowed through. Recording them as unscreened would be more literal and less true: nothing is
-- ever going to screen them now, and a NULL here would make every consumer handle a case that
-- means the same thing as ALLOWED.
ALTER TABLE payments
    ADD COLUMN fraud_status VARCHAR(20) NOT NULL DEFAULT 'ALLOWED';

-- The queue of payments waiting on a human. Small, and read whenever the release consumer starts.
CREATE INDEX idx_payments_held ON payments (created_at) WHERE fraud_status = 'HELD';
