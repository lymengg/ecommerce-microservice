-- Phase 7 (ADR-020): reconciliation of orders stranded by a partial saga
-- failure. Nothing in the system used to *find* an order left in PENDING or
-- PAYMENT_PENDING, so it stayed there forever while its stock reservation
-- quietly expired.
--
-- The job claims work with SELECT ... FOR UPDATE SKIP LOCKED and then makes
-- REST calls, so it cannot hold a row lock for the duration of those calls.
-- Instead it takes a lease: next_reconciliation_at is pushed into the future
-- inside the claiming transaction, which is what stops two instances from
-- working the same order.
ALTER TABLE orders ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN next_reconciliation_at TIMESTAMP WITH TIME ZONE;

-- A terminal "needs a human" state, so an order that cannot be repaired stops
-- being retried instead of looping forever.
ALTER TABLE orders DROP CONSTRAINT chk_orders_status;
ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status IN (
    'DRAFT', 'PENDING', 'PAYMENT_PENDING', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED',
    'CANCELLED', 'NEEDS_ATTENTION'
));

-- Candidate scan: only the stuck states, oldest first, honouring the backoff.
-- updated_at, not created_at: an order that has just moved into
-- PAYMENT_PENDING is not stuck yet.
CREATE INDEX idx_orders_reconciliation
    ON orders (status, updated_at)
    WHERE status IN ('PENDING', 'PAYMENT_PENDING');
