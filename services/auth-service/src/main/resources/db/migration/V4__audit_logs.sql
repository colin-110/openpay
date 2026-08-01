-- Who was given the ability to move money, by whom, and who tried and failed to sign in.
--
-- The table is per-service rather than a central audit database, for the same reason every other
-- table here is: a service owns its schema, and a shared audit table would make auth-service's
-- writes depend on a database it does not control. The implementation is shared in libs/common-audit.
--
-- There is no updated_at and no status column, deliberately. An entry that can change is not
-- evidence. Nothing in the application ever issues an UPDATE or DELETE against this table.

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    -- Free text, not a foreign key. The actor may be someone this service has no row for — an
    -- unknown email at a failed login, an operator holding a token — and an audit log that can
    -- only describe actors it already knows about is missing exactly the entries worth having.
    actor VARCHAR(200) NOT NULL,
    subject VARCHAR(200),
    merchant_id UUID,
    succeeded BOOLEAN NOT NULL,
    detail VARCHAR(500),
    source_ip VARCHAR(45),                -- 45 fits an IPv6 address with an embedded IPv4 suffix
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The two questions this gets asked: "what happened recently" and "what happened to this merchant".
CREATE INDEX idx_audit_recent ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_merchant ON audit_logs (merchant_id, occurred_at DESC);
-- Failed sign-ins against one address, which is the pattern worth alerting on and is invisible
-- if only successes are kept.
CREATE INDEX idx_audit_failures ON audit_logs (actor, occurred_at DESC) WHERE NOT succeeded;
