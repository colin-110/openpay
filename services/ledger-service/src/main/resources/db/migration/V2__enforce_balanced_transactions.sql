-- Debits must equal credits. Enforced here, not only in application code.
--
-- V1 already argued this case for the append-only rule: the journal is enforced by a trigger
-- "rather than trusted to application code", because "any future service, migration, or console
-- session that tries to rewrite history fails loudly". Every word of that applies just as much to
-- the balance rule, which until now lived only in LedgerService.validate() — one method, in one
-- service, that a second writer or a psql session could walk straight past. An unbalanced journal
-- cannot be repaired by a later correction: every report drawn from it is wrong from that row on,
-- and there is no way to tell which side was the mistake.
--
-- A CONSTRAINT TRIGGER, and DEFERRABLE INITIALLY DEFERRED, because that is the only form that can
-- express this rule at all. Entries are inserted one at a time, so a transaction is *supposed* to
-- be unbalanced in the middle of being written — after the debit and before the credit. A normal
-- row-level trigger would fire on the first entry and refuse every posting ever made. Deferring to
-- COMMIT is what lets the check see the finished transaction rather than a half-written one.
CREATE FUNCTION ledger_transaction_balances() RETURNS trigger AS $$
DECLARE
    total_debits BIGINT;
    total_credits BIGINT;
    entry_count INT;
BEGIN
    SELECT
        COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
        COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
        COUNT(*)
    INTO total_debits, total_credits, entry_count
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    -- Zero entries means the transaction row was rolled back with its entries; nothing to check.
    IF entry_count = 0 THEN
        RETURN NULL;
    END IF;

    -- Covers the one-sided case too: a transaction with only a debit has credits of 0, which is
    -- not equal to its debits, so it is refused here rather than needing its own branch.
    IF total_debits <> total_credits THEN
        RAISE EXCEPTION
            'ledger transaction % does not balance: debits % <> credits %',
            NEW.transaction_id, total_debits, total_credits;
    END IF;

    -- Deliberately no "transaction is for zero" check. It would be unreachable: V1's
    -- ck_entry_amount_positive already refuses any entry with amount <= 0, so with at least one
    -- entry present both sides are necessarily positive. LedgerService.validate() still rejects a
    -- zero posting before it gets this far, and that is the right place for it — a rule the
    -- database cannot express is not made truer by writing it here anyway.
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_balances();
