CREATE TABLE notifications (
    payment_id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_owner ON notifications(owner_id, created_at DESC);
