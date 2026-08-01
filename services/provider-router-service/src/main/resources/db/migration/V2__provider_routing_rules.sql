-- Which acquirer gets a payment, as data rather than as a restart.
--
-- Routing lived in application.yml, which meant taking an acquirer out of rotation required a
-- deployment. Acquirers have bad afternoons; a platform that can only respond to one by shipping
-- a config change responds slowly, and the whole point of having two is to be able to move.
--
-- The table is seeded from openpay.router.providers on first start, so an existing deployment
-- comes up routing exactly as it did before. After that the table is the source of truth and the
-- configuration is only a bootstrap — see RoutingRuleSeeder for why that direction, and not the
-- other, is the one that can be got right.

CREATE TABLE provider_routing_rules (
    id UUID PRIMARY KEY,
    provider_name VARCHAR(50) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    -- Lower is tried first, within whichever set of rules applies.
    priority INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    -- The three ways a rule can be narrowed. All nullable, and NULL means "no opinion" rather than
    -- "never": a rule with all three null is the general case, which is what most deployments have.
    --
    -- merchant_id is the override: when a merchant has any enabled rule of its own, those replace
    -- the general ones outright rather than merging with them. Merging would mean an operator
    -- pinning one merchant to one acquirer would still silently fail over to the acquirer they
    -- were trying to avoid.
    merchant_id UUID,
    currency CHAR(3),
    -- Inclusive lower bound, exclusive upper, both in minor units. Half-open so adjacent bands can
    -- be written without a gap or an overlap at the boundary.
    min_amount BIGINT,
    max_amount BIGINT,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT ck_rule_amount_band CHECK (
        min_amount IS NULL OR max_amount IS NULL OR min_amount < max_amount),
    CONSTRAINT ck_rule_amounts_non_negative CHECK (
        (min_amount IS NULL OR min_amount >= 0) AND (max_amount IS NULL OR max_amount > 0)),
    -- One rule per provider per scope. Two rules that differ only in priority are a way to make
    -- routing order depend on row order, which is not something anyone should have to debug.
    --
    -- NULLS NOT DISTINCT because every narrowing column is nullable and the general case is all
    -- three null. Under the default, NULLs never collide, so this constraint would permit any
    -- number of duplicate general rules for the same provider — which is precisely the case it
    -- exists to prevent. Requires PostgreSQL 15, which is what the platform runs.
    CONSTRAINT uq_rule_scope UNIQUE NULLS NOT DISTINCT
        (provider_name, merchant_id, currency, min_amount, max_amount)
);

-- The routing query: enabled rules for this merchant, or the general ones, in priority order.
CREATE INDEX idx_routing_rules_lookup ON provider_routing_rules (merchant_id, priority)
    WHERE enabled;
