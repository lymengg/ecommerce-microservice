-- Phase 7 (ADR-019): make reserving stock idempotent per (order, product).
--
-- Reserving was the one call in the system that could not be retried: unlike
-- order creation and payment initiation it carried no Idempotency-Key, so a
-- retry after a lost response would reserve the same stock twice and hold
-- inventory that no order would ever release. Since the checkout saga's
-- recovery story depends on being able to retry, the endpoint is made
-- idempotent instead of being exempted from retries.
--
-- The natural key is (order_id, product_id): one reservation per order line is
-- exactly the business rule, so no client-supplied key is needed and a caller
-- cannot accidentally defeat the guarantee by sending a different key on retry.
--
-- Partial, not plain: once a reservation is RELEASED/COMMITTED/EXPIRED, a later
-- saga attempt (or a reconciliation repair) may legitimately reserve the same
-- line again, and the index must not block that.
CREATE UNIQUE INDEX uk_reservations_order_product_active
    ON inventory_reservations (order_id, product_id)
    WHERE status = 'RESERVED';
