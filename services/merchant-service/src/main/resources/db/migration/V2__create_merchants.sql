CREATE TABLE merchants (
    id UUID PRIMARY KEY,
    merchant_code VARCHAR(50) NOT NULL UNIQUE,
    legal_name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    webhook_url TEXT,
    default_currency CHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_merchants_status ON merchants (status);
CREATE INDEX idx_merchants_created_at_desc ON merchants (created_at DESC);
