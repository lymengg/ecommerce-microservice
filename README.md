# E-Commerce Platform

Phase 2 — modular monolith prototype. All core domains live in one Spring Boot app with a simple **controller -> service -> repository** layering inside each domain. The roadmap's Phase 3 will extract these into independent services.

Full specifications live in [`docs/`](docs/README.md).

## Stack

- Java 21 (LTS), Spring Boot 3.5, Maven
- PostgreSQL + Flyway (schema migrations)
- springdoc-openapi (Swagger UI)
- Testcontainers for integration tests
- GitHub Actions for CI

## Layout

```
src/main/java/com/ecommerce/
├── EcommerceApplication.java
├── catalog/       # products + lifecycle (DRAFT/ACTIVE/ARCHIVED)
├── cart/          # carts + cart_items
├── inventory/     # stock, reservations (RESERVED/RELEASED/COMMITTED/EXPIRED)
├── order/         # orders, snapshot pricing, state machine, idempotency
├── payment/       # payments, attempts, webhooks, refunds
├── checkout/      # checkout orchestration (saga compensation)
└── common/
    ├── error/     # RFC 9457 ProblemDetail error handling
    └── outbox/    # transactional outbox (ADR-009) — publisher lands in Phase 5
```

Each domain: `controller -> service -> repository -> model`, plus `dto`.

## Business rules implemented (Phase 2 validation)

- **Server-authoritative pricing** — clients send product ids + quantities; prices, tax, and totals are computed from the catalog
- **Order state machine** — DRAFT -> PENDING -> PAYMENT_PENDING -> PAID -> ... with validated transitions; cancel allowed only from pre-paid states
- **Inventory reservation** — atomic stock updates prevent overselling; reservations expire after 30 minutes (scheduled job)
- **Payment lifecycle** — idempotent initiation (Idempotency-Key), idempotent webhook processing, refunds (full/partial)
- **Checkout saga** — validate cart -> price order -> reserve inventory -> pay; on failure: release inventory + cancel order
- **Idempotency** — order creation and payment initiation via `Idempotency-Key` header
- **Outbox records** — domain events written in the same transaction (OrderCreated, InventoryReserved, PaymentSucceeded, ...)

## Run

```bash
# with a local PostgreSQL running on 5432 (db: ecommerce, user/pass: postgres/postgres)
mvn spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html
```

Or with Docker:

```bash
docker run --name ecommerce-db -e POSTGRES_DB=ecommerce -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres:16-alpine
mvn spring-boot:run
```

## Test

```bash
mvn test        # unit tests only (no Docker needed)
mvn verify      # unit + integration tests (Testcontainers, requires Docker)
```

## Endpoints (base `/api/v1`)

| Method | Path | Purpose |
|---|---|---|
| POST/GET | `/products` | create / list products |
| PATCH | `/products/{id}` | update product |
| POST | `/products/{id}/activate`, `/archive` | product lifecycle |
| GET | `/cart?cartId=` | get or create cart |
| POST/PATCH/DELETE | `/cart/items...` | manage cart items |
| POST/GET | `/orders`, `/orders/{id}` | create / get order (Idempotency-Key) |
| POST | `/orders/{id}/cancel` | cancel order |
| POST/GET | `/payments`, `/payments/{id}` | initiate / get payment (Idempotency-Key) |
| POST | `/payments/{id}/refund` | refund (full or partial) |
| POST | `/payments/webhooks/{provider}` | provider webhook (idempotent) |
| POST | `/checkout` | full checkout saga (Idempotency-Key) |

Internal (no auth yet — Phase 4 adds security):

| Method | Path | Purpose |
|---|---|---|
| POST | `/internal/api/v1/inventory/stock` | set stock levels |
| GET | `/internal/api/v1/inventory/stock?productId=` | stock levels |
| POST | `/internal/api/v1/inventory/reservations` | reserve stock |
| POST | `/internal/api/v1/inventory/reservations/{id}/release`, `/commit` | resolve reservation |

## Config properties

| Property | Default | Meaning |
|---|---|---|
| `ecommerce.tax-rate` | `0.10` | tax applied to order subtotals |
| `ecommerce.inventory.reservation-ttl` | `PT30M` | reservation expiry duration |
| `ecommerce.inventory.expiry-interval-ms` | `60000` | expiry sweep interval |
| `ecommerce.payment.decline-above` | `10000` | mock provider declines amounts above this |
