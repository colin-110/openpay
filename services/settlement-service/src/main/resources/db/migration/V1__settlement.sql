-- Settlement turns "we owe this merchant" into "we paid this merchant".
--
-- Two levels on purpose. An item is one captured payment becoming payable, accrued the moment the
-- capture lands. A settlement is a batch of those items for one merchant, currency, and date,
-- created when the window closes. Keeping them apart is what lets a payment be accrued immediately
-- but paid out on a schedule, and what makes a payout auditable back to the payments behind it.

CREATE TABLE settlement_items (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    -- One item per payment, enforced rather than checked: a redelivered capture event must not
    -- accrue the same money twice. The ledger uses the same defence for the same reason.
    payment_id UUID NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL,
    gross_amount BIGINT NOT NULL,
    fee_amount BIGINT NOT NULL,
    net_amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,          -- PENDING | SETTLED
    settlement_id UUID,                   -- set when the item joins a batch
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_item_amounts CHECK (gross_amount > 0 AND fee_amount >= 0)
);

CREATE TABLE settlements (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    settlement_date DATE NOT NULL,
    gross_amount BIGINT NOT NULL,
    fee_amount BIGINT NOT NULL,
    net_amount BIGINT NOT NULL,
    item_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,          -- CREATED | COMPLETED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- A merchant gets at most one settlement per currency per day. Running the job twice must not
    -- produce two payouts for the same money.
    CONSTRAINT uq_settlement_window UNIQUE (merchant_id, currency, settlement_date)
);

ALTER TABLE settlement_items
    ADD CONSTRAINT fk_item_settlement FOREIGN KEY (settlement_id) REFERENCES settlements (id);

-- The batching job's hot query: everything still pending, grouped by who it belongs to.
CREATE INDEX idx_items_pending ON settlement_items (merchant_id, currency, captured_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_items_settlement ON settlement_items (settlement_id);
CREATE INDEX idx_settlements_merchant ON settlements (merchant_id, settlement_date DESC);
