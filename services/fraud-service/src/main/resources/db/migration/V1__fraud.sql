-- Rules are data, not code.
--
-- The alternative was a set of hard-coded checks, which is faster to write and impossible to change
-- without a deployment. Risk thresholds are exactly the thing that needs changing at 2am when a
-- card-testing run is in progress, so they live in a table an operator can edit.

CREATE TABLE fraud_rules (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    rule_type VARCHAR(40) NOT NULL,       -- AMOUNT_OVER | VELOCITY_COUNT | REPEATED_AMOUNT
    -- What the rule compares against. Its meaning depends on rule_type, which is why the column is
    -- named for the role it plays rather than for any one rule: an amount in minor units for
    -- AMOUNT_OVER, a count for the two velocity rules.
    threshold BIGINT NOT NULL,
    -- Look-back for the velocity rules, in seconds. Ignored by AMOUNT_OVER, and nullable rather
    -- than defaulted so a rule that does not use it says so.
    window_seconds INTEGER,
    -- NULL means the rule applies to every currency. A threshold in minor units is meaningless
    -- across currencies, so a real AMOUNT_OVER rule should always name one.
    currency CHAR(3),
    action VARCHAR(10) NOT NULL,          -- REVIEW | BLOCK
    -- Lowest number wins. Evaluation stops at the first match, so ordering is the whole policy:
    -- put BLOCK rules above the REVIEW rules that would otherwise shadow them.
    priority INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_rule_threshold CHECK (threshold > 0),
    CONSTRAINT ck_rule_window CHECK (window_seconds IS NULL OR window_seconds > 0),
    CONSTRAINT ck_rule_action CHECK (action IN ('REVIEW', 'BLOCK'))
);

CREATE INDEX idx_rules_evaluation ON fraud_rules (priority) WHERE enabled;

-- One decision per payment, enforced. The gate is called inside payment creation, and a retried
-- creation must get the answer it already got rather than a fresh evaluation against a velocity
-- window that has since moved.
CREATE TABLE fraud_decisions (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE,
    merchant_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    payment_method_type VARCHAR(20),
    outcome VARCHAR(10) NOT NULL,         -- ALLOW | REVIEW | BLOCK
    rule_name VARCHAR(100),               -- null when nothing matched
    reason VARCHAR(500),
    -- Set when a human closes a review. The original outcome stays put: how a payment was first
    -- judged and what an operator did about it are two different facts, and overwriting the first
    -- with the second destroys the only record that a review ever happened.
    resolved_outcome VARCHAR(10),
    resolved_by VARCHAR(100),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_decision_outcome CHECK (outcome IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_decision_resolved CHECK (resolved_outcome IS NULL OR resolved_outcome IN ('ALLOW', 'BLOCK')),
    -- A resolution is three facts or none. Half a resolution is a bug, and it should not be
    -- possible to persist one.
    CONSTRAINT ck_decision_resolution_complete CHECK (
        (resolved_outcome IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
        OR (resolved_outcome IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

-- The velocity rules' hot query: what has this merchant done recently.
CREATE INDEX idx_decisions_velocity ON fraud_decisions (merchant_id, created_at DESC);
-- The review queue: small, and read constantly by whoever is working it.
CREATE INDEX idx_decisions_open_reviews ON fraud_decisions (created_at)
    WHERE outcome = 'REVIEW' AND resolved_outcome IS NULL;

-- A starting policy, not a recommendation. Thresholds are in minor units of INR because that is
-- what the demo merchant transacts in; a deployment is expected to replace these outright.
INSERT INTO fraud_rules
    (id, name, rule_type, threshold, window_seconds, currency, action, priority, enabled, created_at, updated_at)
VALUES
    -- Priority 10 before 20: an eight-lakh payment should be refused outright, not queued behind
    -- the review rule that also matches it.
    (gen_random_uuid(), 'extreme-value-payment', 'AMOUNT_OVER', 50000000, NULL, 'INR', 'BLOCK', 10, TRUE, now(), now()),
    (gen_random_uuid(), 'high-value-payment', 'AMOUNT_OVER', 5000000, NULL, 'INR', 'REVIEW', 20, TRUE, now(), now()),
    -- The card-testing signature: the same amount over and over, because the attacker is not
    -- varying anything except the instrument.
    (gen_random_uuid(), 'repeated-identical-amount', 'REPEATED_AMOUNT', 10, 300, NULL, 'BLOCK', 30, TRUE, now(), now()),
    (gen_random_uuid(), 'merchant-velocity-burst', 'VELOCITY_COUNT', 100, 60, NULL, 'REVIEW', 40, TRUE, now(), now());
