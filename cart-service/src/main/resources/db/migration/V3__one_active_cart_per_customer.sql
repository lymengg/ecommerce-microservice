-- Phase 4: one active cart per customer, enforced in the database.
-- The partial index only constrains ACTIVE carts, so a new cart can be
-- created after a previous one was checked out. Legacy rows with a NULL
-- customer_id are unaffected.
CREATE UNIQUE INDEX uk_carts_active_customer ON carts (customer_id) WHERE status = 'ACTIVE';
