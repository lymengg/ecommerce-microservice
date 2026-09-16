# E-Commerce Platform — Kafka & Event Specification

## 1. Event Model
Events are immutable facts:
- Event ID
- Event type
- Event version
- Aggregate ID
- Occurred timestamp
- Producer/service
- Correlation ID
- Trace ID
- Payload

Example envelope:
```json
{
  "eventId": "...",
  "eventType": "PaymentSucceeded",
  "eventVersion": 1,
  "aggregateId": "...",
  "occurredAt": "...",
  "producer": "payment-service",
  "correlationId": "...",
  "traceId": "...",
  "payload": {}
}
```

## 2. Topics
Prefer domain-oriented topics rather than one topic per table.

Example:
```text
customer.events
catalog.events
order.events
inventory.events
payment.events
shipping.events
promotion.events
notification.events
audit.events
```

## 3. Partitioning
Choose keys based on ordering requirements.

Examples:
- Order events keyed by order ID.
- Payment events keyed by payment ID.
- Inventory events keyed by SKU or inventory aggregate where ordering matters.

## 4. Delivery
Assume at-least-once delivery.

Every consumer must be idempotent.

## 5. Consumer Idempotency
Persist processed event IDs or equivalent durable business deduplication state.

Do not assume Kafka will deliver each event exactly once to the business logic.

## 6. Dead Letter Topics
Failed messages should eventually be routed to a DLQ/DLT with enough metadata for diagnosis and replay.

## 7. Retry
Use bounded retries with backoff.
Do not create infinite retry loops.

## 8. Outbox
Business transaction:
```text
DB state + outbox event
```
Publisher:
```text
outbox -> Kafka
```

## 9. Schema Evolution
- Additive changes preferred.
- Version events where necessary.
- Consumers must tolerate compatible evolution.
- Avoid silently changing the meaning of existing fields.

## 10. Consumer Groups
Each independently functioning application capability should have its own consumer group.

Example:
```text
notification-service
audit-service
search-indexer
analytics-service
```

## 11. Replay
Retain events according to operational needs and design consumers to safely reprocess historical events.

## 12. Ordering
Kafka ordering is partition-local, not globally guaranteed.
Do not design business logic around global ordering.
