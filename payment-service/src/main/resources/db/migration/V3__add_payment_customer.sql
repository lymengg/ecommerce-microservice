-- Phase 4: denormalize the order's customer onto the payment so read-side
-- ownership checks do not require a cross-service call per request. Legacy
-- rows stay NULL.
ALTER TABLE payments ADD COLUMN customer_id UUID;

CREATE INDEX idx_payments_customer ON payments (customer_id);
