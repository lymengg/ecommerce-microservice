# Phase 5 — Step 1: Reproduce the failure (tracing baseline)

**Purpose.** Before adding OpenTelemetry, establish exactly how bad it is to
diagnose a distributed failure in this system *today*. Fill in the measurement
tables. They are the baseline you re-run in step 4 to prove tracing actually
changed something.

Do not skip ahead to installing OTel. The value of this exercise is the
measurement.

---

## 0. Prerequisites

Full stack up, per README. Remember the Windows gotcha from `PROGRESS.md`:
**port 5432 is taken by your local PostgreSQL**, so run the container on 5433
and pass `DB_PORT=5433` to every service.

```bash
docker compose up -d keycloak
docker run --name ecommerce-db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 -d postgres:16-alpine
docker exec -i ecommerce-db psql -U postgres -c "
  CREATE DATABASE ecommerce_catalog;
  CREATE DATABASE ecommerce_cart;
  CREATE DATABASE ecommerce_inventory;
  CREATE DATABASE ecommerce_order;
  CREATE DATABASE ecommerce_payment;"

mvn -B package -DskipTests
```

**Capture logs to files.** This matters — you cannot diagnose across 7 services
by scrolling 7 terminals. Redirect each service to its own file:

```bash
mkdir -p logs
DB_PORT=5433 mvn -pl gateway-service   spring-boot:run > logs/gateway.log   2>&1 &
DB_PORT=5433 mvn -pl catalog-service   spring-boot:run > logs/catalog.log   2>&1 &
DB_PORT=5433 mvn -pl cart-service      spring-boot:run > logs/cart.log      2>&1 &
DB_PORT=5433 mvn -pl inventory-service spring-boot:run > logs/inventory.log 2>&1 &
DB_PORT=5433 mvn -pl order-service     spring-boot:run > logs/order.log     2>&1 &
DB_PORT=5433 mvn -pl payment-service   spring-boot:run > logs/payment.log   2>&1 &
DB_PORT=5433 mvn -pl checkout-service  spring-boot:run > logs/checkout.log  2>&1 &
```

Wait for all 7 to be healthy before continuing.

### Tokens (from README)

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
```

---

## Part A — Try to trace a *successful* checkout

The easy case first. No fault at all.

```bash
# create + activate a product
curl -s -X POST $GATEWAY/api/v1/products -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-TRACE","name":"Trace Widget","description":"tracing baseline","price":"100.00"}'
# -> note the product id

curl -s -X POST $GATEWAY/api/v1/products/<id>/activate -H "$ADMIN"

# stock (SERVICE token — internal endpoint, not routed through the gateway)
SERVICE=$(curl -s -X POST $TOKEN_URL -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=service-client&client_secret=dev-service-client-secret' \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -X POST http://localhost:8083/internal/api/v1/inventory/stock \
  -H "Authorization: Bearer $SERVICE" -H 'Content-Type: application/json' \
  -d '{"productId":<id>,"sku":"SKU-TRACE","totalQuantity":10}'

# add to cart
CART=$(curl -s $GATEWAY/api/v1/cart -H "$CUSTOMER" | sed -E 's/.*"cartId":"([^"]+)".*/\1/')
curl -s -X POST $GATEWAY/api/v1/cart/items -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -d "{\"productId\":<id>,\"quantity\":2}"

# checkout
curl -s -X POST $GATEWAY/api/v1/checkout -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trace-baseline-1' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}"
```

Note the `X-Correlation-Id` on the response:

```bash
curl -si -X POST $GATEWAY/api/v1/checkout -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trace-baseline-2' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}" \
  | grep -i 'x-correlation-id'
```

**Now do the actual task.** Using only `logs/*.log`, answer:

1. How many services touched this checkout?
2. In what order, and how long did each step take?
3. Can you link the lines belonging to *this* request to each other?

Record:

| Metric | Baseline (no tracing) | After tracing |
|---|---|---|
| Log files you had to open to reconstruct one checkout | | |
| Minutes to reconstruct the call sequence | | |
| Could you identify all services involved? (Y/N) | | |
| Could you order the calls correctly? (Y/N) | | |
| Could you get per-step durations? (Y/N) | | |

---

## Part B — Trace a *failed* checkout (deterministic)

A designed decline, not an injected fault — fully repeatable. The payment
service declines any amount above `ecommerce.payment.decline-above` (default
`10000`), which drives the compensation branch in
`CheckoutService.checkout` (release inventory → cancel order).

```bash
# a product priced above the decline threshold
curl -s -X POST $GATEWAY/api/v1/products -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-DECLINE","name":"Too Expensive","description":"decline path","price":"20000.00"}'
# -> note the product id, activate it, set stock, add to cart as in Part A
```

```bash
curl -s -X POST $GATEWAY/api/v1/checkout -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trace-decline-1' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}"
```

Expected: HTTP 409 (`ConflictException` — "Checkout failed: payment declined").

**Now reconstruct from logs alone:**

1. Did the compensation run? Prove it — which log lines show the inventory
   release and the order cancellation?
2. Is stock back to 10? Is the order `CANCELLED`? Check via the APIs, not logs.
3. How long did the *failed* saga take, end to end?
4. Which service was the origin of the failure?

| Metric | Baseline (no tracing) | After tracing |
|---|---|---|
| Minutes to explain the failure path | | |
| Could you prove compensation completed? (Y/N) | | |
| Services opened to answer question 1 | | |
| Could you separate this request's lines from other traffic? (Y/N) | | |

---

## Part C — The discovery (read this after Part B)

There is a **real defect** in the current saga that this exercise is likely to
surface. Read `CheckoutService.reserveOrCompensate`:

```java
private void reserveOrCompensate(List<CartLineInfo> lines, UUID orderId) {
    try {
        for (CartLineInfo line : lines) {
            inventoryClient.reserve(line.productId(), line.quantity(), orderId);
        }
    } catch (InsufficientStockException ex) {
        inventoryClient.releaseByOrder(orderId);
        orderClient.cancel(orderId, "INSUFFICIENT_STOCK");
        throw ex;
    }
}
```

It compensates for **one** exception type: `InsufficientStockException`. That
is a *business* failure — inventory-service answered with an
`urn:problem:insufficient-stock` body. But consider a *connection* failure:
inventory-service is down.

Walk the path:
- `RestClient` throws `ResourceAccessException` (connection refused) — **not**
  `RestClientResponseException`.
- The client's `catch (RestClientResponseException ex)` doesn't match, so
  `RemoteExceptionMapper` never runs.
- `reserveOrCompensate`'s catch doesn't match either.
- The exception propagates as a raw 500.

**Predicted outcome:** the order is left in `PENDING`, stock is not reserved,
and no compensation runs — no release, no cancel. The saga has already
advanced the order state machine (`markPending`) but nothing rolls it back.

**Verify it:**

```bash
# stop inventory-service, then run a fresh checkout
curl -s -o /dev/null -w '%{http_code}\n' -X POST $GATEWAY/api/v1/checkout \
  -H "$CUSTOMER" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: trace-inv-down-1' -d "{\"cartId\":\"$CART\",\"currency\":\"USD\"}"

# then check the order state via the API
curl -s $GATEWAY/api/v1/orders -H "$CUSTOMER"
```

Confirm whether the order is stuck in `PENDING` with no cancellation. Restart
inventory-service afterwards.

If confirmed, **write this down as a finding** — it is the first real
inconsistency in the platform, and it is precisely what Phase 7's
reconciliation job and Phase 6's event-driven compensation exist to fix. Also
note that there are **no timeouts** configured: `RestClients` builds
`new HttpComponentsClientHttpRequestFactory()` with no connect/read timeout, so
a *hung* inventory-service (rather than a dead one) would hang the saga
indefinitely instead of failing fast.

---

## What to expect, and why

Predicted from reading the code — verify each rather than trusting it:

1. **The correlation id dies at the first hop.** The gateway's
   `CorrelationIdFilter` generates `X-Correlation-Id` and forwards it, so
   gateway → checkout carries it. But `RestClients` — the single builder for
   *every* service-to-service client — has no interceptor for it. The only
   interceptor attaches the bearer token. So checkout → cart/order/inventory/
   payment carry nothing.

2. **No service reads it anyway.** `X-Correlation-Id` is set by the gateway and
   then never consumed: there is no servlet filter in any service (the only
   filters in the repo are the gateway's two), no MDC usage anywhere, and no
   `logging` configuration in any `application.yml`.

3. **So correlation is not "hard" — it is currently impossible.** Default Spring
   Boot log lines carry a timestamp, level and logger, but no service name and
   no request identity. All 7 services' logs are format-identical. You will be
   reduced to matching timestamps, and any concurrent traffic makes even that
   ambiguous.

This is the point of the exercise. You are not fixing a slightly-inconvenient
logging setup; you are establishing that a distributed system with 7 services
and a saga currently has **zero** request correlation.

## Step 1 pass criteria

- [ ] You ran a successful checkout and could **not** reconstruct it from logs.
- [ ] You ran a failed checkout and could **not** cleanly prove compensation ran.
- [ ] The "Baseline" columns above are filled in with real numbers.
- [ ] You attempted Part C and recorded the result (confirmed or refuted).
- [ ] You can state in one sentence what is missing: *"there is no trace context
      propagated past the gateway and no service emits it."*

Only then move to step 3: add OpenTelemetry (doc 13 §3, Phase 5) and re-run
Parts A and B to fill in the "After tracing" columns.

## Findings log

Record results here as you go. Anything surprising also belongs in
`PROGRESS.md` → "Environment gotchas" (protocol step 5).

| # | Finding | Evidence |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
