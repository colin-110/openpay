-- Double-entry ledger. This is the financial truth of the platform: payments describe intent,
-- the ledger records what the money actually did.

-- An account is identified by its code plus who it belongs to plus its currency. Balances are
-- never mixed across currencies, so USD and EUR payable to one merchant are separate accounts.
CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_code VARCHAR(50) NOT NULL,
    merchant_id UUID,                       -- NULL for platform-owned accounts
    currency CHAR(3) NOT NULL,
    account_type VARCHAR(20) NOT NULL,      -- ASSET | LIABILITY
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- NULLS NOT DISTINCT (PostgreSQL 15+) so the platform account, whose merchant_id is NULL,
    -- can only exist once per code and currency. Default NULL handling would allow duplicates.
    CONSTRAINT uq_ledger_account UNIQUE NULLS NOT DISTINCT (account_code, merchant_id, currency)
);

-- One transaction per posting. event_id is the source event that caused it, and its uniqueness
-- is what makes the consumer idempotent: a redelivered payment event cannot post twice.
CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    reference_type VARCHAR(50) NOT NULL,    -- PAYMENT
    reference_id UUID NOT NULL,             -- payment id
    currency CHAR(3) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ledger_transaction_event UNIQUE (event_id)
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions (id),
    account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    direction VARCHAR(6) NOT NULL,
    amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Sign is carried by direction, never by the amount. A negative debit is not a credit, it is
    -- a bug, and allowing it would let a transaction balance while being nonsense.
    CONSTRAINT ck_entry_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, created_at DESC);
CREATE INDEX idx_ledger_transactions_reference ON ledger_transactions (reference_type, reference_id);

-- The journal is append-only, and that is enforced here rather than trusted to application code.
-- Any future service, migration, or console session that tries to rewrite history fails loudly.
CREATE FUNCTION ledger_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries and ledger_transactions are append-only: % is not permitted',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
