CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    key_prefix VARCHAR(24) NOT NULL UNIQUE,
    key_hash VARCHAR(128) NOT NULL,
    name VARCHAR(100) NOT NULL,
    scope VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_api_keys_merchant_status ON api_keys (merchant_id, status);
CREATE INDEX idx_api_keys_expires_at ON api_keys (expires_at);
