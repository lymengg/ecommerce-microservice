# E-Commerce Platform — Database Specification

## 1. Principles
- PostgreSQL per service.
- Flyway migrations.
- UUID/UUIDv7-style identifiers where supported by the implementation strategy.
- UTC timestamps.
- Optimistic locking where suitable.
- Database constraints for invariants.
- No cross-service foreign keys.

## 2. Catalog Database
Core tables:
- products
- product_variants
- categories
- product_categories
- product_media
- product_attributes

Important constraints:
- SKU unique.
- Product status constrained.
- Prices represented with exact numeric types.
- Soft/archive lifecycle for historical products.

## 3. Cart Database
Core tables:
- carts
- cart_items

Cart items reference product/SKU identifiers but do not become authoritative product data.

## 4. Order Database
Core tables:
- orders
- order_items
- order_addresses
- order_status_history
- order_idempotency_records
- outbox_events

Order items must snapshot:
- Product/SKU identifier
- Product name at purchase
- Unit price
- Quantity
- Discount
- Tax
- Currency

## 5. Inventory Database
Core tables:
- inventory_items
- inventory_reservations
- inventory_movements
- outbox_events

Reservation records must have ownership, quantity, status, expiration, and timestamps.

## 6. Payment Database
Core tables:
- payments
- payment_attempts
- provider_transactions
- refunds
- webhook_events
- outbox_events

Provider transaction identifiers must be unique.

## 7. Promotion Database
Core tables:
- promotions
- promotion_rules
- coupon_codes
- coupon_redemptions

Enforce usage limits transactionally.

## 8. Shipping Database
Core tables:
- shipments
- shipment_items
- tracking_events
- shipping_addresses

## 9. Review Database
Core tables:
- reviews
- review_moderation
- review_votes

Prevent unauthorized review submission.

## 10. Notification Database
Core tables:
- notifications
- delivery_attempts
- notification_preferences

## 11. Audit Database
Core tables:
- audit_events

Audit entries should capture actor, action, target, timestamp, request/correlation identifiers, and safe metadata.

## 12. Outbox
Recommended columns:
- id
- aggregate_type
- aggregate_id
- event_type
- event_version
- payload
- occurred_at
- published_at
- attempt_count

Use indexes supporting unpublished-event polling.

## 13. Indexing
Indexes should be driven by actual query patterns. Avoid indexing every column.

Use:
- Unique indexes for business identifiers.
- Composite indexes for common filters.
- Partial indexes where useful.
- EXPLAIN/ANALYZE during optimization.

## 14. Migration Rules
- Every schema change is versioned.
- Never modify an already-applied migration in production.
- Prefer backward-compatible expand/contract migrations.
- Destructive changes require staged deployment.
