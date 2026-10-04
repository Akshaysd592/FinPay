CREATE TABLE ledger_pairs (
    payment_id UUID PRIMARY KEY,
    debit_account UUID NOT NULL,
    credit_account UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK (debit_account <> credit_account)
);
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger projection is append-only';
END;
$$;
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON ledger_pairs
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
