-- Human logins, as distinct from the machine credentials this service already issues.
--
-- An API key identifies a merchant's server; a user identifies a person acting on that merchant's
-- behalf. They are deliberately separate tables: revoking a departing employee must not break the
-- merchant's integration, and rotating an API key must not lock anyone out of the dashboard.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    -- BCrypt output, never the password. Length 255 because the algorithm and cost live in the
    -- hash itself, so a future cost increase must not need a migration.
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,            -- MERCHANT_ADMIN | MERCHANT_VIEWER
    status VARCHAR(20) NOT NULL,          -- ACTIVE | DISABLED
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Lowercased on write, so uniqueness is not defeated by capitalisation.
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_merchant ON users (merchant_id, status);
