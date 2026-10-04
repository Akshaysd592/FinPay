CREATE TABLE spending (
    payment_id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    category VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX spending_month ON spending(owner_id, occurred_at);
