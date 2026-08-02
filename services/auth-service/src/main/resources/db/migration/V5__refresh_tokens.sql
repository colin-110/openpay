-- Lets a session be renewed instead of forcing a fresh email/password login every time the access
-- token expires.
--
-- Stored the same way api_keys stores its secret: a SHA-256 hash, never the plaintext. A refresh
-- token is exactly as sensitive as a password — anyone holding one can mint fresh sessions
-- indefinitely until it expires or is revoked — so it gets the same treatment.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    -- Rotation chain. Every use of a refresh token revokes it and issues a new one, and the new
    -- one's id is recorded here. That is what makes it possible to tell "an old token was used
    -- again" (theft, the token was already rotated once by its rightful owner) apart from "a
    -- fresh token was used" (normal) — the presented hash resolves to a row that is revoked AND
    -- has something in replaced_by, which is the signal that revokes the entire chain.
    replaced_by UUID REFERENCES refresh_tokens (id)
);

-- The lookup on every refresh: hash in, row out. UNIQUE above already backs this, but naming the
-- index makes the query plan legible rather than incidental.
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);

-- Revoking every active session for a user (a password reset, "sign out everywhere") needs this.
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
