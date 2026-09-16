# E-Commerce Platform

Phase 3 — service extraction. The core domains are independently deployable
Spring Boot services (ADR-003 database-per-service), each owning its own
PostgreSQL database and Flyway migrations. Cross-domain calls happen over
synchronous REST (docs/03-microservice-architecture.md section 3); the checkout
saga is orchestrated by a dedicated stateless service.

Full specifications live in [`docs/`](docs/README.md).

## Modules

```
├── common/             shared library: RFC 9457 error handling (ProblemDetail),
│                       transactional outbox (ADR-009), REST client helpers
├── catalog-service/    products + lifecycle (DRAFT/ACTIVE/ARCHIVED)      :8081
├── cart-service/       carts + cart_items                                :8082
├── inventory-service/  stock + reservations (RESERVED/RELEASED/COMMITTED/EXPIRED),
│                       atomic UPDATE prevents overselling, scheduled expiry :8083
├── order-service/      orders, server-authoritative pricing from catalog,
│                       snapshot items, state machine, idempotency        :8084
├── payment-service/    payments, attempts, mock gateway, webhooks, refunds :8085
├── checkout-service/   checkout saga orchestration over REST (no database) :8086
└── gateway-service/    Spring Cloud Gateway edge: routing, correlation ids,
                        coarse-grained rate limiting (ADR-006, no Eureka)    :8080
```

Each service: `controller -> service -> repository -> model`, plus `dto` and
`client` (REST clients for the services it calls). `common` is the only shared
code; there are no shared domain tables and no cross-service SQL (ADR-011).

## Cross-service calls (REST)

| Caller             | Target     | Endpoint                                          | Purpose |
|---|---|---|---|
| cart-service       | catalog    | `GET /internal/api/v1/catalog/products/{id}`      | validate ACTIVE product, sku, display price |
| order-service      | catalog    | `GET /internal/api/v1/catalog/products/{id}`      | server-authoritative pricing + snapshot |
| payment-service    | order      | `GET /api/v1/orders/{orderId}`                    | authoritative amount, currency, status |
| checkout-service   | cart       | `GET /internal/api/v1/cart/{cartId}/lines`        | checkout lines (no prices) |
| checkout-service   | cart       | `POST /internal/api/v1/cart/{cartId}/checkout`    | close cart after success |
| checkout-service   | order      | `POST /api/v1/orders` (Idempotency-Key)           | create order |
| checkout-service   | order      | `POST /internal/api/v1/orders/{id}/pending`       | advance state machine |
| checkout-service   | order      | `POST /internal/api/v1/orders/{id}/payment-pending` | advance state machine |
| checkout-service   | order      | `POST /internal/api/v1/orders/{id}/paid`          | advance state machine |
| checkout-service   | order      | `POST /api/v1/orders/{id}/cancel`                 | compensate |
| checkout-service   | inventory  | `POST /internal/api/v1/inventory/reservations`    | reserve per line |
| checkout-service   | inventory  | `POST /internal/api/v1/inventory/reservations/commit-by-order` | commit on success |
| checkout-service   | inventory  | `POST /internal/api/v1/inventory/reservations/release-by-order` | release on failure |
| checkout-service   | payment    | `POST /api/v1/payments` (Idempotency-Key)         | initiate payment |

Idempotency keys and server-authoritative pricing are preserved: prices are
never sent by clients; every order line is re-priced from the catalog at
creation time and snapshotted (historical order prices are immutable).

## Business rules implemented

- **Server-authoritative pricing** — clients send product ids + quantities; prices, tax, and totals are computed from the catalog
- **Order state machine** — DRAFT -> PENDING -> PAYMENT_PENDING -> PAID -> ... with validated transitions; cancel allowed only from pre-paid states
- **Inventory reservation** — atomic stock updates prevent overselling; reservations expire after 30 minutes (scheduled job in inventory-service)
- **Payment lifecycle** — idempotent initiation (Idempotency-Key), idempotent webhook processing, refunds (full/partial)
- **Checkout saga** — validate cart -> price order -> reserve inventory -> pay over REST; on failure: release inventory + cancel order (compensation). Structured so a Phase 5 event-driven version can replace the REST calls
- **Idempotency** — order creation and payment initiation via `Idempotency-Key` header
- **Outbox records** — each service with a database has its own `outbox_events` table (ADR-009); the Kafka publisher lands in Phase 5

## Run (full stack locally)

Requirements: Docker (for PostgreSQL), Java 21, Maven.

```bash
# 1. start PostgreSQL with one database per service
docker run --name ecommerce-db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 -d postgres:16-alpine
docker exec -i ecommerce-db psql -U postgres -c "
  CREATE DATABASE ecommerce_catalog;
  CREATE DATABASE ecommerce_cart;
  CREATE DATABASE ecommerce_inventory;
  CREATE DATABASE ecommerce_order;
  CREATE DATABASE ecommerce_payment;"

# 2. build everything
mvn -B package

# 3. start each service (own terminal or background)
mvn -pl gateway-service     spring-boot:run   # :8080
mvn -pl catalog-service     spring-boot:run   # :8081
mvn -pl cart-service        spring-boot:run   # :8082
mvn -pl inventory-service   spring-boot:run   # :8083
mvn -pl order-service       spring-boot:run   # :8084
mvn -pl payment-service     spring-boot:run   # :8085
mvn -pl checkout-service    spring-boot:run   # :8086
```

Default DB credentials are `postgres/postgres`; override with `DB_USER`,
`DB_PASSWORD`, `DB_HOST`, `DB_PORT`, `DB_NAME` (defaults to `ecommerce_<service>`).
Service-to-service URLs are configurable per service, e.g. `CATALOG_SERVICE_URL`,
`ORDER_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`,
`CART_SERVICE_URL`.

Swagger UI per service: `http://localhost:<port>/swagger-ui.html`.

## Manual checkout flow (through the gateway)

```bash
GATEWAY=http://localhost:8080

# create + activate a product
curl -s -X POST $GATEWAY/api/v1/products -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1","name":"Widget","description":"A widget","price":"100.00"}'
# -> note the product id, then:
curl -s -X POST $GATEWAY/api/v1/products/<id>/activate

# set stock
curl -s -X POST http://localhost:8083/internal/api/v1/inventory/stock \
  -H 'Content-Type: application/json' -d '{"productId":<id>,"sku":"SKU-1","totalQuantity":10}'

# add to cart
CART=$(curl -s "$GATEWAY/api/v1/cart" | ...)                # cartId from response
curl -s -X POST $GATEWAY/api/v1/cart/items -H 'Content-Type: application/json' \
  -d "{\"cartId\":\"$CART\",\"productId\":<id>,\"quantity\":2}"

# checkout -> order PAID, stock committed
curl -s -X POST $GATEWAY/api/v1/checkout -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-1' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}"

# a payment above the mock decline threshold (10000) fails and compensates:
# order CANCELLED, stock released
```

## Test

```bash
mvn test        # unit tests only (no Docker needed)
mvn verify      # full reactor: unit + integration tests (Testcontainers, requires Docker)
```

Per-service integration tests boot each service against its own Testcontainers
PostgreSQL and stub downstream services with WireMock. The checkout saga is
covered end-to-end in `checkout-service` (success, payment-failed compensation,
insufficient-stock compensation, idempotent retry). The gateway is covered with
routing, correlation-id propagation and rate-limiting tests.

## Config properties

| Property | Default | Service | Meaning |
|---|---|---|---|
| `ecommerce.tax-rate` | `0.10` | order | tax applied to order subtotals |
| `ecommerce.inventory.reservation-ttl` | `PT30M` | inventory | reservation expiry duration |
| `ecommerce.inventory.expiry-interval-ms` | `60000` | inventory | expiry sweep interval |
| `ecommerce.payment.decline-above` | `10000` | payment | mock provider declines amounts above this |
| `ecommerce.gateway.rate-limit.max-requests-per-minute` | `100` | gateway | per-client-IP edge rate limit |

## Endpoints (base `/api/v1`, all services)

| Method | Path | Service | Purpose |
|---|---|---|---|
| POST/GET | `/products` | catalog | create / list products |
| PATCH | `/products/{id}` | catalog | update product |
| POST | `/products/{id}/activate`, `/archive` | catalog | product lifecycle |
| GET | `/cart?cartId=` | cart | get or create cart |
| POST/PATCH/DELETE | `/cart/items...` | cart | manage cart items |
| POST/GET | `/orders`, `/orders/{id}` | order | create / get order (Idempotency-Key) |
| POST | `/orders/{id}/cancel` | order | cancel order |
| POST/GET | `/payments`, `/payments/{id}` | payment | initiate / get payment (Idempotency-Key) |
| POST | `/payments/{id}/refund` | payment | refund (full or partial) |
| POST | `/payments/webhooks/{provider}` | payment | provider webhook (idempotent) |
| POST | `/checkout` | checkout | full checkout saga (Idempotency-Key) |

Internal service-to-service endpoints (not routed through the gateway):

| Method | Path | Service | Purpose |
|---|---|---|---|
| GET | `/internal/api/v1/catalog/products/{id}` | catalog | ACTIVE product lookup (404 otherwise) |
| GET | `/internal/api/v1/cart/{cartId}/lines` | cart | checkout lines |
| POST | `/internal/api/v1/cart/{cartId}/checkout` | cart | mark cart checked out |
| POST | `/internal/api/v1/inventory/stock` | inventory | set stock levels |
| GET | `/internal/api/v1/inventory/stock?productId=` | inventory | stock levels |
| POST | `/internal/api/v1/inventory/reservations` | inventory | reserve stock |
| POST | `/internal/api/v1/inventory/reservations/{id}/release`, `/commit` | inventory | resolve reservation |
| POST | `/internal/api/v1/inventory/reservations/commit-by-order` | inventory | commit all reservations of an order |
| POST | `/internal/api/v1/inventory/reservations/release-by-order` | inventory | release all reservations of an order |
| POST | `/internal/api/v1/orders/{id}/pending` | order | mark PENDING |
| POST | `/internal/api/v1/orders/{id}/payment-pending` | order | mark PAYMENT_PENDING |
| POST | `/internal/api/v1/orders/{id}/paid` | order | mark PAID |
