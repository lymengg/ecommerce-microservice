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
| order-service      | payment    | `GET /internal/api/v1/payments/by-order/{id}`     | reconciliation: authoritative payment state (Phase 7) |
| order-service      | inventory  | `GET /internal/api/v1/inventory/reservations?orderId=` | reconciliation: authoritative reservation state (Phase 7) |
| order-service      | inventory  | `POST /internal/api/v1/inventory/reservations/release-by-order` | reconciliation: release on compensation (Phase 7) |

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
- **Resilience** — timeouts with a saga-wide deadline, per-dependency circuit breakers, bounded jittered retries, bulkheads (ADR-017/018/019); retries are opt-in per call, and the inventory reservation is idempotent on `(orderId, productId)` so it can be retried safely
- **Reconciliation** — order-service scans for orders stranded in `PENDING`/`PAYMENT_PENDING` by a partial saga failure, asks payment- and inventory-service for the authoritative state, and completes or compensates them, ending in `NEEDS_ATTENTION` rather than looping (ADR-020)

## Resilience (Phase 7)

Every cross-service call goes through the same policy, built in `common` and
applied by `RestClients`, so a service cannot accidentally skip it.

```text
RestClient call
  └─ retry (only if the call opted in)      ← jittered exponential backoff, 3 attempts
      └─ circuit breaker (per dependency)   ← CLOSED / OPEN / HALF_OPEN
          └─ bulkhead (per dependency)      ← bounded concurrency, fails immediately when full
              └─ Apache HttpClient 5        ← connect / connection-request / response timeouts,
                 (bounded pool per target)     response timeout capped by the remaining saga budget
```

- **Per dependency, never global.** A breaker per target (`catalog`, `cart`,
  `inventory`, `order`, `payment`) — a global one would let payment being down
  stop inventory reads.
- **Fail fast, and typed.** A dependency that cannot answer (timeout, connection
  failure, pool or bulkhead exhaustion, open breaker, 5xx) becomes
  `ServiceUnavailableException` → RFC 9457 `503`. A 4xx stays a 4xx.
- **Retries are opt-in per call.** `GET`s retry by default; a state-changing call
  retries only where the client asserts idempotency (`Retryable.yes`). 4xx is
  never retried. See the table in ADR-019.
- **A saga deadline, not just per-call limits.** `ecommerce.resilience.saga-budget`
  (10 s) is enforced on the request thread; each call's response timeout is capped
  at the time left, and a spent budget triggers compensation rather than a silent
  truncation.
- **The gateway has timeouts only** (`httpclient.connect-timeout`,
  `httpclient.response-timeout`) — no retry filter, because retrying at the edge
  would double-retry the same request at two layers (ADR-017).
- **Compensation covers an unreachable dependency**, not just a business
  rejection. Before Phase 7, an inventory-service that timed out left the order
  stranded in `PENDING` with stock held.

### Reconciliation

A circuit breaker without reconciliation just fails faster. `order-service` runs a
scheduled job (`ecommerce.reconciliation.*`) that claims orders stuck in
`PENDING`/`PAYMENT_PENDING` past `stale-after` using
`SELECT … FOR UPDATE SKIP LOCKED` plus a lease, reads the authoritative payment
and reservation state from their owners, and then:

| Authoritative payment state | Action |
|---|---|
| `SUCCEEDED` | **complete** — mark PAID, which re-emits `OrderConfirmed` and commits the reservations |
| absent / `FAILED` / `CANCELLED` / refunded | **compensate** — release what is still RESERVED, cancel the order |
| still in flight (`PENDING`, `PROCESSING`, …) | **defer** — compensating could destroy a paid order |
| dependency unreachable | **defer** with backoff |

Bounded by a batch cap, a per-order backoff and `max-attempts`; an order that
cannot be repaired moves to the terminal `NEEDS_ATTENTION` state (an operator
cancels it through the normal ADMIN path) instead of being retried forever. See
ADR-020.

## Run (full stack locally)

Requirements: Docker, Java 21. Maven itself is not needed — use the wrapper
(`./mvnw`).

```bash
# 1. the whole environment: PostgreSQL (one database per service, created by
#    infra/postgres/init-databases.sql), Keycloak, Kafka, and the observability
#    stack (Jaeger, OTel Collector, Prometheus, Grafana, Loki).
#    One command as of Phase 8 — this used to be a docker run plus a docker exec
#    psql in this README, which is exactly the kind of setup step that gets
#    skipped or mistyped on a new machine.
docker compose up -d

# 2. build everything, and fetch the pinned OTel agent into otel/
#    `install`, not `package`: with -pl <service> alone the module cannot
#    resolve `common`, which is only ever installed into the local repository
./mvnw install -DskipTests

# 3. start all seven services (each to its own log file, then wait for readiness)
DB_PORT=5433 ./scripts/start-services.sh
#    ...or one at a time, in a terminal each:
#    DB_PORT=5433 ./mvnw -pl gateway-service spring-boot:run     # :8080
#    DB_PORT=5433 ./mvnw -pl catalog-service spring-boot:run     # :8081
#    DB_PORT=5433 ./mvnw -pl cart-service spring-boot:run        # :8082
#    DB_PORT=5433 ./mvnw -pl inventory-service spring-boot:run   # :8083
#    DB_PORT=5433 ./mvnw -pl order-service spring-boot:run       # :8084
#    DB_PORT=5433 ./mvnw -pl payment-service spring-boot:run     # :8085
#    DB_PORT=5433 ./mvnw -pl checkout-service spring-boot:run    # :8086
#
#    stop them again with: ./scripts/start-services.sh --stop
```

**Grafana: http://localhost:3000** — dashboards for the platform and for
checkout/payments, with Prometheus, Loki and Jaeger wired in. **Jaeger:
http://localhost:16686** — a single checkout appears as one trace spanning all
seven services. See [Observability](#observability-phase-8) below.

Postgres is on host port **5433**, because 5432 is often taken by a local
PostgreSQL — hence `DB_PORT=5433` above.

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

## Observability (Phase 8)

Three pillars, one place to look, and one ingestion point (ADR-021).

```text
                    ┌──────────────────────────────────────────┐
  services ──OTLP──►│ otel-collector                           │──► Jaeger   (traces)
  (Java agent)      │  traces + logs; metrics are NOT pushed   │──► Loki     (logs)
                    └──────────────────────────────────────────┘
  Prometheus ──scrape──► each service /actuator/prometheus      (metrics, pulled)
       │
       ├── alert rules  (infra/prometheus/rules)
       └──► Grafana :3000  ── Prometheus + Loki + Jaeger datasources
```

| UI | URL | What it is for |
|---|---|---|
| Grafana | http://localhost:3000 | dashboards, and the entry point to all three pillars |
| Prometheus | http://localhost:9090 | raw queries, `/alerts` for alert state, `/targets` for scrape health |
| Jaeger | http://localhost:16686 | traces |
| Loki | http://localhost:3100 | log queries (via Grafana, or the API) |

**Metrics are pulled, traces and logs are pushed.** A metric is a small fixed-size
fact that is cheap to fetch on a schedule, and the scrape itself is a health
signal (`up`) — the only way to observe a service being *absent*, since a dead
service exports nothing. Logs and traces are large and per-event, so the agent
pushes them over the OTLP connection it already has. Metrics are deliberately
**not** also pushed: two paths would mean two series for every measurement, and a
dashboard would silently disagree with an alert about the same system.

### What is measured

| Source | Examples |
|---|---|
| HTTP (RED) | `http_server_requests_seconds_{count,bucket}` per service, uri, status |
| JVM / process | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage` |
| Database | `hikaricp_connections_{active,idle,pending,max}` |
| Resilience (ADR-017) | `resilience4j_circuitbreaker_state`, `resilience4j_retry_calls_total`, `resilience4j_bulkhead_available_concurrent_calls` |
| Outbox | `outbox_events_unpublished`, `outbox_events_oldest_unpublished_age_seconds` |
| Kafka | `kafka_consumer_lag_aggregate`, `kafka_consumer_lag{topic}` |
| Business | `checkout_saga_outcomes_total{outcome}`, `payments_outcomes_total{status}`, `inventory_reservations_total{outcome}`, `orders_placed_total`, `reconciliation_orders_total{outcome}` |
| Collector | `otelcol_receiver_refused_{spans,log_records}`, `otelcol_exporter_queue_size` |

Business outcomes are separated from errors on purpose: a declined payment is a
`409` and a fact about customers, and merging it into the error rate would make
both signals useless.

### Alerts

Rules live in `infra/prometheus/rules/` and are written on **symptoms**, never on
causes (doc 08 §7). The SLO is checkout **p95 < 2 s, error rate < 1 %**.

| Alert | Fires when |
|---|---|
| `CheckoutLatencyBreach` | checkout p95 over 2 s — the failure with *no errors at all* |
| `CheckoutErrorRateHigh` | >1 % of checkouts return 5xx (business rejections excluded) |
| `CircuitBreakerOpen` | a dependency is being denied without an attempt (ADR-017) |
| `ServiceDown` | a target stops answering scrapes |
| `OutboxBacklogStuck` | the oldest unpublished event is over 5 minutes old |
| `KafkaConsumerLagHigh`, `DatabasePoolSaturated` | consumers behind; requests queueing for connections |
| `PaymentDeclineSpike`, `InventoryReservationFailureSpike` | business signals, labelled `kind: business` |
| `TelemetryDropped`, `TelemetryExporterQueueFilling` | the collector is losing telemetry |

```bash
# check what is firing right now
curl -s localhost:9090/api/v1/alerts | grep -o '"alertname":"[^"]*","state":"[^"]*"'

# test the rules without touching a running system (this runs in CI)
docker run --rm -v "$(pwd)/infra/prometheus:/etc/prometheus" \
  --entrypoint promtool prom/prometheus:v3.1.0 \
  test rules /etc/prometheus/rules-tests/ecommerce-slo_test.yml
```

There is deliberately **no Alertmanager**: in development there is nowhere to
route a notification, and the thing worth testing is the rule. Routing and
silences belong with the production stack (Phase 10).

### Health probes

`/actuator/health` (aggregate), `/actuator/health/liveness` and
`/actuator/health/readiness` on every service and the gateway. Readiness includes
the database for the DB-backed services — doc 08 §8 wants readiness to mean "this
instance can receive traffic", not merely "the context finished starting".
Liveness deliberately does **not** include the database: restarting a service
cannot fix a database outage and would only remove the instances that could
recover.

These four endpoints are unauthenticated (Prometheus and a kubelet both need them
without a user token, and a health check that needs OAuth cannot be relied on
during an identity-provider outage), and nothing else under `/actuator` is
exposed. Moving them to a separate management port is the hardening step, deferred
with the rest of the network work (docs/09 §6, Phase 10).

### Watching it burn

`scripts/checkout-load.sh` drives checkouts and reports p50/p95/p99 and the error
rate, so the SLO can be checked from outside and degradations reproduced:

```bash
# raise the gateway's per-subject limit first, or you measure the rate limiter
RATE_LIMIT=1000 ./scripts/start-services.sh

ITERATIONS=30 ./scripts/checkout-load.sh

# degrade the system on purpose: a slow provider, which is the failure that
# produces no errors at all — and therefore the one only a latency SLO notices
DB_PORT=5433 PAYMENT_PROVIDER_DELAY=2000ms ./mvnw -pl payment-service spring-boot:run
```

The measurements, before and after, are in
`docs/phase-8-observability-baseline.md`.

## Test

```bash
./mvnw test        # unit tests only (no Docker needed)
./mvnw verify      # full reactor: unit + integration tests (Testcontainers, requires Docker)

# alert rules — deterministic, no stack needed, so it belongs in CI alongside the
# unit tests. "An alert that has never fired is untested" (docs/13 §3).
docker run --rm -v "$(pwd)/infra/prometheus:/etc/prometheus" \
  --entrypoint promtool prom/prometheus:v3.1.0 \
  test rules /etc/prometheus/rules-tests/ecommerce-slo_test.yml
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
| `ecommerce.resilience.saga-budget` | `10s` | checkout | wall-clock budget for one checkout saga (ADR-018) |
| `ecommerce.resilience.http.connect-timeout` | `300ms` | all with clients | TCP connect timeout |
| `ecommerce.resilience.http.connection-request-timeout` | `300ms` | all with clients | time to lease a pooled connection |
| `ecommerce.resilience.http.response-timeout` | `1500ms` | all with clients | socket read timeout (capped by the remaining saga budget) |
| `ecommerce.resilience.http.max-connections-per-route` | `20` | all with clients | bounded pool per dependency |
| `ecommerce.resilience.http.max-connections-total` | `100` | all with clients | bounded pool across a client's endpoints |
| `ecommerce.resilience.retry.max-attempts` | `3` | all with clients | attempts including the first |
| `ecommerce.resilience.retry.initial-backoff` | `100ms` | all with clients | first retry delay |
| `ecommerce.resilience.retry.multiplier` | `2.0` | all with clients | exponential factor |
| `ecommerce.resilience.retry.max-backoff` | `500ms` | all with clients | backoff cap |
| `ecommerce.resilience.retry.jitter` | `0.5` | all with clients | randomisation fraction (anti retry-storm) |
| `ecommerce.resilience.retry.retry-on-server-error` | `true` | all with clients | retry a 5xx (4xx is never retried) |
| `ecommerce.resilience.circuit-breaker.sliding-window-size` | `20` | all with clients | breaker window |
| `ecommerce.resilience.circuit-breaker.minimum-number-of-calls` | `10` | all with clients | calls before the rate is evaluated |
| `ecommerce.resilience.circuit-breaker.failure-rate-threshold` | `50` | all with clients | % failures that opens the breaker |
| `ecommerce.resilience.circuit-breaker.wait-duration-in-open-state` | `10s` | all with clients | how long the breaker stays OPEN |
| `ecommerce.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state` | `3` | all with clients | probes before closing |
| `ecommerce.resilience.bulkhead.max-concurrent-calls` | `20` | all with clients | in-flight calls allowed per dependency |
| `ecommerce.resilience.bulkhead.max-wait-duration` | `0` | all with clients | queueing when full (0 = fail immediately) |
| `ecommerce.resilience.dependencies.<name>.response-timeout` | — | per service | per-dependency override (e.g. `order: 3s`, `catalog: 1s`) |
| `ecommerce.resilience.dependencies.<name>.retry-enabled` | `true` | per service | disable retries for a dependency |
| `ecommerce.resilience.dependencies.<name>.max-attempts` | — | per service | per-dependency attempt cap |
| `ecommerce.resilience.dependencies.<name>.max-concurrent-calls` | — | per service | per-dependency bulkhead size |
| `ecommerce.resilience.dependencies.<name>.failure-rate-threshold` | — | per service | per-dependency breaker threshold |
| `ecommerce.resilience.dependencies.<name>.wait-duration-in-open-state` | — | per service | per-dependency breaker open window |
| `ecommerce.reconciliation.enabled` | `true` | order | enable/disable the reconciliation job (ADR-020) |
| `ecommerce.reconciliation.interval-ms` | `60000` | order | how often a reconciliation pass runs |
| `ecommerce.reconciliation.initial-delay-ms` | `30000` | order | delay before the first pass |
| `ecommerce.reconciliation.stale-after` | `10m` | order | how long an order may sit stuck before it is a candidate |
| `ecommerce.reconciliation.batch-size` | `50` | order | orders claimed per pass |
| `ecommerce.reconciliation.lease` | `2m` | order | how long a claimed order is held from other instances |
| `ecommerce.reconciliation.max-attempts` | `5` | order | attempts before `NEEDS_ATTENTION` |
| `ecommerce.reconciliation.retry-backoff` | `5m` | order | base backoff for a failed repair (grows with attempts) |
| `spring.cloud.gateway.server.webflux.httpclient.connect-timeout` | `1000` | gateway | edge connect timeout (ms) |
| `spring.cloud.gateway.server.webflux.httpclient.response-timeout` | `15s` | gateway | edge response timeout; must exceed the saga budget |
| `spring.cloud.gateway.server.webflux.httpclient.pool.max-connections` | `200` | gateway | edge connection pool |
| `spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout` | `1000` | gateway | edge pool acquire timeout (ms) |
| `ecommerce.payment.provider-delay` | `0s` | payment | mock provider latency — the knob used to degrade the system on purpose |
| `ecommerce.metrics.kafka-lag.enabled` | `false` | order/inventory | export consumer lag for this service's group (off where there is no consumer) |
| `ecommerce.metrics.kafka-lag.group` | `spring.application.name` | order/inventory | consumer group to measure |
| `ecommerce.metrics.kafka-lag.interval-ms` | `15000` | order/inventory | how often committed offsets are refreshed |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | all + gateway | the only actuator endpoints exposed |
| `management.endpoint.health.probes.enabled` | `true` | all + gateway | enables `/actuator/health/{liveness,readiness}` |
| `management.endpoint.health.show-details` | `never` | all + gateway | the health endpoint is unauthenticated; do not leak DB/disk detail |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | all + gateway | exports `_bucket` series so p95 can be computed (no buckets ⇒ percentile alerts silently match nothing) |
| `management.metrics.tags.environment` | `${ENVIRONMENT:local}` | all + gateway | common tag on every metric |
| `ENVIRONMENT` | `local` | all + gateway | value of the `environment` tag |
| `PAYMENT_PROVIDER_DELAY` | `0s` | payment | env override for the mock provider delay |

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

Observability endpoints (unauthenticated, not routed through the gateway — see
[Health probes](#health-probes)):

| Method | Path | Service | Purpose |
|---|---|---|---|
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | all + gateway | probes; readiness includes the database for DB-backed services |
| GET | `/actuator/info` | all + gateway | build info |
| GET | `/actuator/prometheus` | all + gateway | Prometheus scrape endpoint |

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
| GET | `/internal/api/v1/inventory/reservations?orderId=` | inventory | authoritative reservation state (reconciliation, Phase 7) |
| GET | `/internal/api/v1/payments/by-order/{orderId}` | payment | authoritative payment state (reconciliation, Phase 7) |
| POST | `/internal/api/v1/orders/{id}/pending` | order | mark PENDING |
| POST | `/internal/api/v1/orders/{id}/payment-pending` | order | mark PAYMENT_PENDING |
| POST | `/internal/api/v1/orders/{id}/paid` | order | mark PAID |
