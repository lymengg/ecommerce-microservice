# E-Commerce Platform

Phase 4 — security (Keycloak/OAuth2, ADR-005). The core domains are
independently deployable Spring Boot services (ADR-003 database-per-service),
each owning its own PostgreSQL database and Flyway migrations. Every service is
an OAuth2 resource server validating Keycloak JWTs; the gateway enforces
route-level authorization at the edge; service-to-service calls use
client-credentials tokens (SERVICE role); object-level authorization is
enforced in the owning service. Cross-domain calls happen over synchronous
REST (docs/03-microservice-architecture.md section 3); the checkout saga is
orchestrated by a dedicated stateless service.

Full specifications live in [`docs/`](docs/README.md).

## Modules

```
├── common/             shared library: RFC 9457 error handling (ProblemDetail),
│                       transactional outbox (ADR-009) + Kafka publisher and
│                       event envelope/consumers (ADR-015), REST client helpers,
│                       OAuth2 resource-server security config, Keycloak JWT
│                       role mapping, client-credentials token provider
├── catalog-service/    products + lifecycle (DRAFT/ACTIVE/ARCHIVED)      :8081
│                       public GETs, ADMIN writes, SERVICE internal
├── cart-service/       one active cart per customer (JWT subject),       :8082
│                       CUSTOMER only
├── inventory-service/  stock + reservations (RESERVED/RELEASED/COMMITTED/EXPIRED),
│                       atomic UPDATE prevents overselling, scheduled expiry,
│                       SERVICE only (internal)                            :8083
├── order-service/      orders, server-authoritative pricing from catalog,
│                       snapshot items, state machine, idempotency,
│                       ownership checks (CUSTOMER sees own, ADMIN all)    :8084
├── payment-service/    payments, attempts, mock gateway, webhooks, refunds,
│                       ownership checks; refunds ADMIN; webhooks public    :8085
├── checkout-service/   checkout saga orchestration over REST (no database),
│                       CUSTOMER only, customerId from token               :8086
└── gateway-service/    Spring Cloud Gateway edge: routing, correlation ids,
                        JWT validation, route RBAC, CORS allowlist, rate
                        limiting, /internal/** denied (ADR-006, no Eureka)  :8080
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
| checkout-service   | order      | `POST /api/v1/orders/{id}/cancel`                 | compensate |
| checkout-service   | inventory  | `POST /internal/api/v1/inventory/reservations`    | reserve per line |
| checkout-service   | inventory  | `POST /internal/api/v1/inventory/reservations/release-by-order` | release on failure |
| checkout-service   | payment    | `POST /api/v1/payments` (Idempotency-Key)         | initiate payment |

Every service-to-service call carries a Keycloak client-credentials token with
the SERVICE realm role (see `ecommerce.security.service-client.*`); internal
endpoints (`/internal/**`) accept SERVICE tokens only and are never routed
through the gateway. Endpoints used both by end users and by the checkout
orchestrator accept either a CUSTOMER token (identity from the JWT `sub`,
client-supplied customer ids are rejected on mismatch) or a SERVICE token
(checkout has already validated the end user) — ADR-013.

Idempotency keys and server-authoritative pricing are preserved: prices are
never sent by clients; every order line is re-priced from the catalog at
creation time and snapshotted (historical order prices are immutable).

**Phase 6c:** the *completion* of a successful payment is no longer a REST call
from the orchestrator. `inventory.commitByOrder` and `order.markPaid` were
replaced by events (see [Events](#events-kafka) below); those internal endpoints
remain available but checkout no longer calls them.

## Events (Kafka)

Services also communicate **asynchronously** (doc 03 §3): side effects that do
not need an immediate answer travel as domain events. This is what lets a
consumer be down and catch up later, and stops a slow subscriber from failing a
checkout.

### The outbox → publisher → consumer path

Business state and the event are written in **one local transaction** (the
transactional outbox, ADR-009). A scheduled publisher then drains
`outbox_events` to Kafka and marks `published_at` — the request path never
talks to the broker, so a Kafka outage cannot fail a checkout. Consumers are
**idempotent** (a durable `processed_events` table written in the same
transaction as the business change), so at-least-once delivery has exactly-once
effects.

```text
service tx ──► outbox_events row ──► OutboxPublisher ──► <domain>.events
                                    (poll + SKIP LOCKED)       │
                                                               ▼
                                @KafkaListener (idempotent; bounded retry with
                                backoff; failures → <domain>.events.DLT)
```

### Topics and events

| Topic | Events | Producer | Consumer (group) |
|---|---|---|---|
| `payment.events` | `PaymentInitiated`, `PaymentSucceeded`, `PaymentFailed` | payment-service | order-service |
| `order.events` | `OrderCreated`, `OrderConfirmed`, `OrderCancelled` | order-service | inventory-service |
| `inventory.events` | `InventoryReserved`, `InventoryReleased`, `InventoryCommitted`, `InventoryExpired` | inventory-service | — |
| `catalog.events`, `cart.events` | (reserved for later phases) | — | — |

Each topic has a `<topic>.DLT` dead-letter twin: a record that exhausts its
retries (3, exponential backoff) is published there with its original payload
and headers so it can be replayed once the bug is fixed.

### The envelope (doc 06 §1)

```json
{ "eventId": "...", "eventType": "PaymentSucceeded", "eventVersion": 1,
  "aggregateId": "...", "occurredAt": "...", "producer": "payment-service",
  "correlationId": "...", "traceId": "...", "payload": { "orderId": "..." } }
```

The partition key is the aggregate id (order id / payment id / reservation id),
so events for one aggregate keep their order. Kafka ordering is
**partition-local** — no business logic assumes global ordering (doc 06 §12).
Schema changes are additive-only and versioned; consumers ignore unknown fields
(tolerant readers, doc 06 §9).

### Choreography (Phase 6c)

The success leg of the saga is **choreographed** rather than orchestrated:
payment-service publishes `PaymentSucceeded`; order-service consumes it, marks
the order PAID and publishes `OrderConfirmed`; inventory-service consumes that
and commits the reservation. Checkout returns as soon as the payment is
accepted, so the order and stock converge **asynchronously**. The rest of the
saga — and all compensation — stays orchestrated. The reasoning is in ADR-016.

## Business rules implemented

- **Server-authoritative pricing** — clients send product ids + quantities; prices, tax, and totals are computed from the catalog
- **Order state machine** — DRAFT -> PENDING -> PAYMENT_PENDING -> PAID -> ... with validated transitions; cancel allowed only from pre-paid states
- **Inventory reservation** — atomic stock updates prevent overselling; reservations expire after 30 minutes (scheduled job in inventory-service)
- **Payment lifecycle** — idempotent initiation (Idempotency-Key), idempotent webhook processing, refunds (full/partial)
- **Checkout saga** — validate cart -> price order -> reserve inventory -> pay over REST; on failure: release inventory + cancel order (compensation). On success, order confirmation and the stock commit are driven by events (Phase 6c, ADR-016)
- **Idempotency** — order creation and payment initiation via `Idempotency-Key` header
- **Outbox records** — each service with a database has its own `outbox_events` table (ADR-009); a polling publisher drains them to Kafka and consumers are idempotent (ADR-015)
- **Authentication** — OAuth2/OIDC via Keycloak (ADR-005); short-lived access tokens (5 min), refresh-token rotation; services validate issuer + signature (JWKS) + expiry
- **Authorization** — gateway route RBAC (public product GETs, ADMIN writes, CUSTOMER cart/orders/payments/checkout); method security in services; object-level authorization in the owning service (a customer only reaches their own orders/carts/payments, other users' resources are 404)
- **Service-to-service** — client-credentials tokens with the SERVICE role; `/internal/**` endpoints are SERVICE-only and unreachable through the gateway
- **Edge hardening** — explicit CORS allowlist (no wildcards), stricter per-user rate limits on checkout/payment, provider webhooks allowlisted, JWT/decoder lazy init
- **Token hygiene** — the `realm_access.roles` claim maps to `ROLE_*` authorities; tokens are never logged

## Run (full stack locally)

Requirements: Docker (PostgreSQL + Keycloak + Jaeger + OTel Collector + Kafka),
Java 21. Maven itself is not needed — use the wrapper (`./mvnw`).

```bash
# 1. start Keycloak (:8087), Jaeger (:16686), the OTel Collector (:4317)
#    and Kafka (:9092, KRaft mode — no ZooKeeper)
docker compose up -d keycloak jaeger otel-collector kafka

# 2. start PostgreSQL with one database per service
#    host port 5433, because 5432 is often taken by a local PostgreSQL —
#    pass DB_PORT=5433 to every service, as in step 4
docker run --name ecommerce-db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 -d postgres:16-alpine
# one -c per statement: CREATE DATABASE cannot run inside a transaction block,
# and several statements in a single -c are wrapped in one
docker exec -i ecommerce-db psql -U postgres \
  -c "CREATE DATABASE ecommerce_catalog" \
  -c "CREATE DATABASE ecommerce_cart" \
  -c "CREATE DATABASE ecommerce_inventory" \
  -c "CREATE DATABASE ecommerce_order" \
  -c "CREATE DATABASE ecommerce_payment"

# 3. build everything, and fetch the pinned OTel agent into otel/
#    `install`, not `package`: with -pl <service> alone the module cannot
#    resolve `common`, which is only ever installed into the local repository
./mvnw install -DskipTests

# 4. start each service (own terminal or background)
DB_PORT=5433 ./mvnw -pl gateway-service   spring-boot:run   # :8080
DB_PORT=5433 ./mvnw -pl catalog-service   spring-boot:run   # :8081
DB_PORT=5433 ./mvnw -pl cart-service      spring-boot:run   # :8082
DB_PORT=5433 ./mvnw -pl inventory-service spring-boot:run   # :8083
DB_PORT=5433 ./mvnw -pl order-service     spring-boot:run   # :8084
DB_PORT=5433 ./mvnw -pl payment-service   spring-boot:run   # :8085
DB_PORT=5433 ./mvnw -pl checkout-service  spring-boot:run   # :8086
```

**Jaeger UI: http://localhost:16686** — a single checkout appears as one trace
spanning all seven services. See [Tracing](#tracing) below.

Keycloak note: `--import-realm` skips a realm that already exists, so after
editing `infra/keycloak/ecommerce-realm.json` recreate the container
(`docker compose rm -sf keycloak && docker compose up -d keycloak`).

Default DB credentials are `postgres/postgres`; override with `DB_USER`,
`DB_PASSWORD`, `DB_HOST`, `DB_PORT`, `DB_NAME` (defaults to `ecommerce_<service>`).
Service-to-service URLs are configurable per service, e.g. `CATALOG_SERVICE_URL`,
`ORDER_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`,
`CART_SERVICE_URL`. If port 5432 is taken (e.g. by a local Windows
PostgreSQL), run Postgres on another host port and set `DB_PORT` for every
service.

Keycloak defaults: `KEYCLOAK_ISSUER_URI=http://localhost:8087/realms/ecommerce`
(used by every service and the gateway) and the service-to-service client
`SERVICE_CLIENT_ID=service-client` / `SERVICE_CLIENT_SECRET=dev-service-client-secret`
(used by cart, order, payment, checkout). In production these come from a
secret manager (never commit real secrets).

Realm contents (dev): roles `CUSTOMER`, `ADMIN`, `SERVICE`; users
`customer1` / `customer2` / `admin1` (passwords `<username>-password`);
clients `web-app` (SPA), `test-client` (password grant for curl/tests),
`service-client` (client credentials, SERVICE role on its service account).
Access tokens live 5 minutes.

Swagger UI per service: `http://localhost:<port>/swagger-ui.html`.

## Manual checkout flow (through the gateway)

Get tokens with the dev `test-client` (password grant), then call the APIs
with `Authorization: Bearer <token>`. Roles: `customer1` (CUSTOMER),
`admin1` (ADMIN).

```bash
GATEWAY=http://localhost:8080
TOKEN_URL=http://localhost:8087/realms/ecommerce/protocol/openid-connect/token

admin_token() { curl -s -X POST $TOKEN_URL -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password&client_id=test-client&client_secret=dev-test-client-secret&username=admin1&password=admin1-password' \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
customer_token() { curl -s -X POST $TOKEN_URL -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password&client_id=test-client&client_secret=dev-test-client-secret&username=customer1&password=customer1-password' \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
ADMIN="Authorization: Bearer $(admin_token)"
CUSTOMER="Authorization: Bearer $(customer_token)"

# create + activate a product (ADMIN)
curl -s -X POST $GATEWAY/api/v1/products -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1","name":"Widget","description":"A widget","price":"100.00"}'
# -> note the product id, then:
curl -s -X POST $GATEWAY/api/v1/products/<id>/activate -H "$ADMIN"

# set stock (SERVICE token: use the service-client client-credentials grant)
SERVICE=$(curl -s -X POST $TOKEN_URL -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=service-client&client_secret=dev-service-client-secret' \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -X POST http://localhost:8083/internal/api/v1/inventory/stock \
  -H "Authorization: Bearer $SERVICE" -H 'Content-Type: application/json' \
  -d '{"productId":<id>,"sku":"SKU-1","totalQuantity":10}'

# add to cart (customer-scoped; the cart is resolved from the token)
CART=$(curl -s $GATEWAY/api/v1/cart -H "$CUSTOMER" | sed -E 's/.*"cartId":"([^"]+)".*/\1/')
curl -s -X POST $GATEWAY/api/v1/cart/items -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -d "{\"productId\":<id>,\"quantity\":2}"

# checkout -> the response reports PAID/SUCCEEDED immediately; the order row and
# stock converge a moment later via the PaymentSucceeded -> OrderConfirmed events
curl -s -X POST $GATEWAY/api/v1/checkout -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-1' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}"

# a payment above the mock decline threshold (10000) fails and compensates:
# order CANCELLED, stock released
```

## Tracing

Every service is instrumented with the **OpenTelemetry Java agent** and exports
OTLP to a collector, which forwards to Jaeger (ADR-014). No application code is
needed for HTTP propagation or log correlation — the agent instruments Apache
HttpClient 5 and Logback directly.

- **Traces** — <http://localhost:16686>. A checkout produces a single trace
  across all seven services, with a `checkout.saga` span wrapping the saga
  (`checkout.cart_id`, `checkout.currency`, `checkout.order_id`,
  `checkout.outcome`).
- **Logs** — every service emits `[trace=…,span=…,corr=…]`, so a log line can be
  joined to its trace and back.
- **Correlation id** — `X-Correlation-Id` is minted at the gateway, echoed to the
  client and forwarded on service-to-service calls, so one id covers the whole
  saga. It is attached to the MDC and to each span as
  `ecommerce.correlation_id`, making it searchable in both logs and Jaeger.
  Quote it in a bug report and the whole request can be reconstructed.

The agent is pinned by `otel.agent.version` in the parent POM and fetched into
`otel/` (gitignored) by a `validate`-phase `dependency:copy`. It is attached
through `spring-boot-maven-plugin` `jvmArguments`, so **only `spring-boot:run`
is instrumented** — unit and integration tests run without it and need no
collector.

Deliberately not done yet: TLS/authentication between services and the
collector, and a collector-side sampling policy. Both are Phase 9-10 concerns
(see ADR-014). No customer id is placed on spans — it is a pseudonymous
personal identifier (doc 08 §3).

## Test

```bash
./mvnw test        # unit tests only (no Docker needed)
./mvnw verify      # full reactor: unit + integration tests (Testcontainers, requires Docker)
```

### Authorization smoke test

```bash
./scripts/verify-authz.sh    # against a running stack; exits non-zero on failure
```

Drives **real Keycloak tokens** against every servlet service and checks role
rules, the `/internal/**` SERVICE-only boundary, object-level ownership
(other customers' resources must be 404, never 403), the ADR-013
`customerId`-mismatch rule, and the gateway edge. It exists because the
mocked-JWT ITs inject authorities directly and `GatewayKeycloakIT` only covers
the gateway — so "real token → servlet service → business endpoint" is not
covered by any automated test. A missing `basic` client scope once made every
authenticated business call return 403 for a whole phase without a test
noticing. Run it after any change to the realm, the security config, or an
authorization rule.

Per-service integration tests boot each service against its own Testcontainers
PostgreSQL (plus a Testcontainers Kafka broker for order and inventory) and stub
downstream services with WireMock. Kafka is never mocked (doc 10 §3): the event
tests run against a real broker — duplicate delivery, out-of-order delivery,
poison message → DLT, and Kafka-down (outbox retains, then drains on recovery). Security behavior is
covered at three levels: unit tests for the JWT claim mapping (`common`),
mocked-JWT MockMvc/WebTestClient tests for role rules and object-level
authorization in every service plus the gateway's route RBAC (no Docker
needed), and `GatewayKeycloakIT` — a real Keycloak container exercising issuer
metadata resolution, JWKS signature validation, realm import (users, roles,
service-account role mapping) and token relay end-to-end (requires Docker).

## Config properties

| Property | Default | Service | Meaning |
|---|---|---|---|
| `ecommerce.tax-rate` | `0.10` | order | tax applied to order subtotals |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | order/payment/inventory | Kafka broker (KRaft) |
| `ecommerce.outbox.poll-interval-ms` | `1000` | order/payment/inventory | outbox publisher poll interval |
| `ecommerce.outbox.batch-size` | `100` | order/payment/inventory | rows locked and published per cycle |
| `ecommerce.outbox.enabled` | `true` | order/payment/inventory | enable/disable the scheduled publisher |
| `ecommerce.inventory.reservation-ttl` | `PT30M` | inventory | reservation expiry duration |
| `ecommerce.inventory.expiry-interval-ms` | `60000` | inventory | expiry sweep interval |
| `ecommerce.payment.decline-above` | `10000` | payment | mock provider declines amounts above this |
| `ecommerce.gateway.rate-limit.max-requests-per-minute` | `100` | gateway | per-client-IP edge rate limit |
| `ecommerce.gateway.rate-limit.strict.max-requests-per-minute` | `10` | gateway | per-subject limit for checkout/payment |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `http://localhost:8087/realms/ecommerce` | all + gateway | Keycloak issuer (JWKS resolved from it) |
| `ecommerce.security.permit-all` | — | per service | unauthenticated matchers, e.g. `GET:/api/v1/products/**` |
| `ecommerce.security.service-client.enabled` | `false` | cart/order/payment/checkout | enables client-credentials S2S tokens |
| `ecommerce.security.service-client.token-uri` | `.../token` | cart/order/payment/checkout | Keycloak token endpoint |
| `ecommerce.security.service-client.client-id` | `service-client` | cart/order/payment/checkout | confidential client id |
| `ecommerce.security.service-client.client-secret` | `dev-service-client-secret` | cart/order/payment/checkout | confidential client secret (env in prod) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | gateway | explicit CORS origin allowlist |

## Endpoints (base `/api/v1`, all services)

Role key: public = no token; CUSTOMER = any user token; ADMIN = admin token;
SERVICE = client-credentials token. `/internal/**` endpoints are SERVICE-only.

| Method | Path | Service | Role | Purpose |
|---|---|---|---|---|
| GET | `/products`, `/products/{id}` | catalog | public | browse products |
| POST | `/products` | catalog | ADMIN | create product |
| PATCH | `/products/{id}` | catalog | ADMIN | update product |
| POST | `/products/{id}/activate`, `/archive` | catalog | ADMIN | product lifecycle |
| GET | `/cart` | cart | CUSTOMER | get (or create) the caller's active cart |
| POST | `/cart/items` | cart | CUSTOMER | add item to the caller's cart |
| PATCH/DELETE | `/cart/items/{itemId}` | cart | CUSTOMER | update/remove cart item |
| POST/GET | `/orders`, `/orders/{id}` | order | CUSTOMER (own) / ADMIN | create / get order (Idempotency-Key) |
| POST | `/orders/{id}/cancel` | order | CUSTOMER (own) / ADMIN | cancel order |
| POST/GET | `/payments`, `/payments/{id}` | payment | CUSTOMER (own) / ADMIN | initiate / get payment (Idempotency-Key) |
| POST | `/payments/{id}/refund` | payment | ADMIN | refund (full or partial) |
| POST | `/payments/webhooks/{provider}` | payment | public | provider webhook (idempotent, allowlisted) |
| POST | `/checkout` | checkout | CUSTOMER | full checkout saga (Idempotency-Key) |

Internal service-to-service endpoints (SERVICE role, not routed through the
gateway):

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
