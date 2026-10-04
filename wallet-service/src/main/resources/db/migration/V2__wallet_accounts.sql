CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    balance_minor BIGINT NOT NULL DEFAULT 0 CHECK (balance_minor BETWEEN 0 AND 100000000000),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (owner_id, currency)
);
CREATE TABLE wallet_operations (
    id UUID PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- A journal pair encodes two equal entries: debit and credit of the same amount.
CREATE TABLE wallet_journal (
    payment_id UUID PRIMARY KEY REFERENCES wallet_operations(id),
    debit_account UUID NOT NULL,
    credit_account UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (debit_account <> credit_account)
);
CREATE FUNCTION reject_journal_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Financial journal is append-only';
END;
$$;
CREATE TRIGGER immutable_journal BEFORE UPDATE OR DELETE ON wallet_journal
FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();
