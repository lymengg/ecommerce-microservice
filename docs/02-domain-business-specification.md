# E-Commerce Platform — Domain & Business Specification

## 1. Domain Map
Core domains:
- Identity
- Customer
- Catalog
- Cart
- Order
- Inventory
- Payment
- Promotion
- Shipping/Fulfillment
- Review
- Notification
- Audit
- Search

## 2. Domain Ownership
Each service owns its business data. No service reads another service's database directly.

## 3. Authoritative Rules
The server is authoritative for:
- Price
- Discount
- Tax
- Shipping cost
- Inventory
- Permissions
- Payment status
- Order state

Client-provided values are requests, not trusted facts.

## 4. Order Lifecycle
```text
DRAFT
  -> PENDING
  -> PAYMENT_PENDING
  -> PAID
  -> PROCESSING
  -> SHIPPED
  -> DELIVERED
```

Failure/cancellation transitions must be explicit and validated.

## 5. Inventory Reservation
Reservation states:
- RESERVED
- RELEASED
- COMMITTED
- EXPIRED

Inventory must be protected against race conditions using appropriate database locking/atomic update strategies.

## 6. Payment Lifecycle
```text
PENDING
-> PROCESSING
-> SUCCEEDED
-> REFUNDED / PARTIALLY_REFUNDED
```
Failure/cancellation transitions:
- FAILED
- CANCELLED
- REFUND_PENDING
- REFUND_FAILED

Payment provider callbacks are treated as authoritative external events and must be idempotent.

## 7. Checkout
```text
Validate Cart
 -> Calculate Authoritative Price
 -> Validate Promotion
 -> Reserve Inventory
 -> Create Order
 -> Initiate Payment
 -> Await Payment Result
 -> Confirm or Cancel Order
```

## 8. Saga Compensation
Example:
```text
Payment Failed
 -> Release Inventory
 -> Cancel Order
```

## 9. Transactional Outbox
Within one local database transaction:
```text
Business State Change + Outbox Record
             |
             v
      Outbox Publisher
             |
             v
           Kafka
```

## 10. Idempotency
Required for:
- Payment initiation
- Payment webhook processing
- Order commands where retries are possible
- Kafka event consumers
- Inventory reservation commands

Use stable idempotency keys and durable records.

## 11. Events
Examples:
- CustomerRegistered
- CustomerSuspended
- ProductCreated
- ProductUpdated
- ProductActivated
- ProductArchived
- OrderCreated
- OrderCancelled
- OrderConfirmed
- PaymentInitiated
- PaymentSucceeded
- PaymentFailed
- InventoryReserved
- InventoryReleased
- InventoryCommitted
- InventoryExpired
- ShipmentCreated
- ShipmentShipped
- ShipmentDelivered
- ReviewSubmitted
- ReviewPublished

Events describe facts that happened. Commands describe requested actions.

## 12. Money and Time
- Never use binary floating point for monetary values.
- Store amount and currency explicitly.
- Use UTC internally for timestamps.
- Preserve original order pricing, discounts, tax, and currency.

## 13. Deletion
Prefer lifecycle states and archival for business-critical records rather than destructive deletion.

## 14. Reconciliation
Payment, inventory, and order domains should provide reconciliation mechanisms for mismatches caused by external failures, retries, or delayed events.
