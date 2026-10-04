CREATE TABLE payments (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    idempotency_key UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    source_wallet_id UUID NOT NULL,
    target_wallet_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    description VARCHAR(200) NOT NULL,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('TRANSFER', 'MERCHANT')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    reason VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE(owner_id, idempotency_key),
    CHECK (source_wallet_id <> target_wallet_id)
);
CREATE INDEX payment_history ON payments(owner_id, created_at DESC, id);
