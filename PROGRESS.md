# Project Progress — session handoff

Last updated: 2026-09-22 (Phase 8 Observability implemented and verified)

## Status: Phase 8 (Observability) — implemented and verified

Phases 4-7 are DONE and committed. Phase 8 is implemented and verified against a
live stack: every service exposes Prometheus metrics, traces and logs reach the
collector and land in Jaeger and Loki, Grafana has provisioned dashboards over
all three, and **the alert rules were proved by degrading the system and watching
them fire — then watching them resolve.** See "Phase 8 (observability)" below.

Two Phase 7 bugs were found by the Phase 8 baseline and fixed here: a
slow-call circuit-breaker default that converted a working-but-slow dependency
into a 45% error rate, and the `infra/otel/` collector config never having been
committed at all (an unanchored `.gitignore` pattern).

## Phase 7 (Resilience) — implemented and verified

Phases 4, 5 and 6 are DONE and committed. Phase 7 is implemented: timeouts with
a saga-wide deadline, per-dependency Resilience4j circuit breakers, bounded
jittered retries with an explicit per-call retry policy, bulkheads with bounded
connection pools, and the **reconciliation job** that finds and repairs orders a
partial saga failure stranded. See "Phase 7 (resilience)" below for the detail
and the gotchas found on the way. ADR-017…020 record the decisions.

**The "break it first" baseline was skipped by request** (as Phase 5's was), so
there are no before/after measurements. The failure modes are pinned by tests
instead: `ResilientRestClientTest` (common, 12 cases), `CheckoutFailureInjectionIT`
(7), `OrderReconciliationIT` (8), `PaymentFailureIT` (3), `DatabaseOutageIT` (1).

### Phase 4 record

- `4dc3814` — feat: secure the platform with Keycloak/OAuth2, RBAC and
  ownership (Phase 4)
- `980569a` — fix: harden Phase 4 verification findings
- Full reactor `mvn -B verify` green with Docker: 113 tests (the original note
  said 57 unit + 56 integration; the unit count actually measures **62** today,
  so 57 was wrong — integration count unverified since).
- `main` is 3 commits ahead of `origin/main` (unpushed): the two docs commits
  plus the Maven wrapper.

### What Phase 4 delivered
- **Keycloak (ADR-005)** — realm `ecommerce` exported to
  `infra/keycloak/ecommerce-realm.json`: realm roles `CUSTOMER`/`ADMIN`/`SERVICE`,
  users `customer1`/`customer2`/`admin1`, clients `web-app` (SPA),
  `test-client` (password grant for dev/tests), `service-client`
  (client credentials, SERVICE role on its service account). 5-min access
  tokens, refresh rotation, brute-force protection, registration disabled.
  Dev: `docker compose up -d keycloak` (port 8087, `--import-realm`).
- **Shared security module (`common`)** — `ServiceSecurityConfig`
  (stateless OAuth2 resource server, Keycloak `realm_access.roles` →
  `ROLE_*` via `KeycloakJwtAuthoritiesConverter`, JSON 401/403, configurable
  `ecommerce.security.permit-all`, lazy issuer-discovery JwtDecoder so
  services/tests start
  without Keycloak); `SecurityUtils`; `ClientCredentialsTokenProvider`
  (client-credentials SERVICE tokens, cached, `service-client.enabled` flag);
  `RestClients.createWithServiceToken`.
- **Gateway edge security** — reactive `SecurityWebFilterChain`:
  public `GET /api/v1/products/**`, ADMIN product writes, CUSTOMER/ADMIN
  cart/orders/payments/checkout, `denyAll` on `/internal/**` and unknown
  paths, provider webhooks permit-all, OPTIONS preflight permit; token relay
  (downstream services re-validate — defense in depth); explicit CORS
  allowlist (`CORS_ALLOWED_ORIGINS`); stricter per-subject rate limit
  (10/min) for checkout/payment keyed by JWT subject when authenticated.
- **Resource servers** — catalog (public GETs, ADMIN writes, SERVICE
  internal), inventory (SERVICE only), cart (CUSTOMER only), order
  (ownership + CUSTOMER-or-SERVICE, ADMIN all), payment (ownership + SERVICE
  orchestrator, ADMIN refunds, public allowlisted webhooks), checkout
  (CUSTOMER, customerId from token, mismatch → 403).
- **One cart per customer** — cart bound to JWT subject; `cartId` removed
  from cart API DTOs; DB partial unique index
  `uk_carts_active_customer` (V3 migration); a checked-out cart is replaced
  by a fresh one on the next mutation.
- **Trust boundary ADR-013** — endpoints shared by users and the checkout
  orchestrator accept CUSTOMER (identity from `sub`, reject mismatch) or
  SERVICE (trust request customerId). Internal endpoints are SERVICE-only
  and never routed through the gateway.
- **Object-level authorization** — customers reach only their own
  orders/carts/payments (other users' resources 404, no existence leak);
  payment stores denormalized `customer_id` (V3 migration).
- **Tests** — `KeycloakJwtAuthoritiesConverterTest` (common); mocked-JWT
  role/ownership ITs in catalog/cart/inventory/order/checkout + gateway
  (`GatewaySecurityIT` route RBAC, all Docker-free); `GatewayKeycloakIT`
  real-Keycloak end-to-end (issuer/JWKS validation, realm import incl.
  service-account roles, token relay, 401/403s) — **requires Docker**.
  NOTE: mock `jwt()` post-processors ignore `realm_access` by default — all
  mocked-JWT helpers pass `.authorities(new KeycloakJwtAuthoritiesConverter())`.

### Ports / databases (unchanged)
| Service | Port | DB |
|---|---|---|
| gateway-service | 8080 | — |
| catalog-service | 8081 | ecommerce_catalog |
| cart-service | 8082 | ecommerce_cart |
| inventory-service | 8083 | ecommerce_inventory |
| order-service | 8084 | ecommerce_order |
| payment-service | 8085 | ecommerce_payment |
| checkout-service | 8086 | — (no DB) |
| keycloak (compose) | 8087 | — |

## How to resume (fast verification)
```bash
mvn -B verify                      # whole reactor, unit + ITs (needs Docker)
mvn test                           # unit tests only
mvn -pl gateway-service test -Dtest='!GatewayKeycloakIT'   # Docker-free gateway ITs
```

Manual end-to-end flow (documented in README.md "Manual checkout flow"):
1. `docker compose up -d keycloak`, Postgres + 5 databases (README SQL).
2. `mvn -B package -DskipTests`, start services (Keycloak must be reachable
   for token issuance; JWT decoders resolve the issuer lazily on first token
   validation — `LazyIssuerJwtDecoder` — so startup order is flexible).
3. Get tokens via `test-client` password grant (customer1/admin1) and
   `service-client` client-credentials; curl through the gateway with
   `Authorization: Bearer ...` → expect `PAID` + committed stock; a price
   > 10000 declines → expect `CANCELLED` + released stock.

## Phase 5 (tracing) — implemented and verified, UNCOMMITTED

Goal: make one request traceable across all seven services (doc 13 §3, Phase 5).
The implementation works; it has **not been committed** (14 modified files + 2
new paths). The "break it first" baseline in
`docs/phase-5-tracing-baseline.md` was **skipped by request**, so there are no
before/after numbers — the doc is still worth running against the instrumented
system to see the contrast.

### What is implemented

- **Infrastructure** — `docker compose up -d jaeger otel-collector`.
  Jaeger v2 (`jaegertracing/jaeger:2.20.0`, all-in-one, in-memory storage,
  UI `:16686`) behind an OTel Collector
  (`otel/opentelemetry-collector-contrib:0.160.0`), config in
  `infra/otel/otel-collector-config.yaml`. Collector ports are bound to
  `127.0.0.1` so only this machine can inject telemetry. The collector is the
  single ingestion point, which is what lets Phase 8 swap Jaeger for Tempo
  without touching a service.
- **Agent** — OpenTelemetry Java agent **2.31.1**, pinned by
  `otel.agent.version` in the parent pom and fetched into `otel/` (gitignored)
  by a `maven-dependency-plugin` copy bound to `validate` (`inherited=false`,
  so only the root project copies it). Attached via
  `spring-boot-maven-plugin` `jvmArguments` in the parent's `pluginManagement`:
  `spring-boot:run` is instrumented, surefire/failsafe are not, so unit tests
  stay agent-free and fast. `-Dotel.service.name=${project.artifactId}`
  resolves per module.
- **Log correlation** — `logging.pattern.console` in all seven services emits
  `[trace=…,span=…,corr=…]`. The agent's Logback instrumentation supplies
  trace_id/span_id automatically.
- **Correlation id** — kept as the client-facing support id (ADR-014).
  `common` gains `com.ecommerce.common.tracing.Correlation` (shared constants)
  and `CorrelationIdFilter`, registered by `TracingAutoConfiguration` through
  `META-INF/spring/...AutoConfiguration.imports` — deliberately
  auto-configured rather than component-scanned, because checkout-service scans
  selectively and would silently miss it (the trap that left it unsecured
  pre-Phase 4). The filter puts the id in MDC, on the active span
  (`ecommerce.correlation_id`) and echoes it on the response. The gateway sets
  the span attribute in its existing reactive filter; it does **not** depend on
  `common` because that would drag servlet Spring Web into a reactive app, so
  the two header constants are duplicated with a sync comment.
- **Manual span** — `checkout.saga` in `CheckoutService` wraps the whole saga
  in a child span with `checkout.cart_id`, `checkout.currency`,
  `checkout.order_id` and `checkout.outcome`. Deliberately **no customer id**:
  it is a pseudonymous personal identifier (doc 08 §3).
- `opentelemetry-api` added to `common` and `gateway-service` (API only — the
  agent supplies the implementation, and every call is a no-op without it).

### Verified working (2026-09-21, full stack)

- `./mvnw -B test` → BUILD SUCCESS, **65 unit tests**, all 8 modules.
- Synthetic OTLP span → collector → Jaeger query API.
- **Full checkout saga end to end**, through the gateway, with a real Keycloak
  token: `POST /api/v1/checkout` → `orderStatus=PAID`,
  `paymentStatus=SUCCEEDED`. The resulting trace has **201 spans across all
  seven services** (`gateway, catalog, cart, inventory, order, payment,
  checkout`) and carries **exactly one correlation id** — the one supplied at
  the edge — proving end-to-end propagation.
- **Log line → trace id → Jaeger trace**, with `[trace=…,span=…,corr=…]` all
  populated on request-handling lines.
- `ecommerce.correlation_id` present as a span attribute on every service hop.

### Resolved (2026-09-21): the "empty corr=" was a misdiagnosis

`corr=` is **not** broken. The earlier conclusion came from reading
DispatcherServlet *initialisation* lines, which legitimately carry no
correlation id: Tomcat's `StandardWrapperValve` calls `servlet.init()`
**before** the filter chain runs, so those lines are emitted before
`CorrelationIdFilter` executes at all. They still carry trace/span because the
agent's span starts one level higher, at the valve.

Confirmed by temporarily instrumenting the filter: a log line emitted inside it
renders `[trace=…,span=…,corr=corr-probe-777]` — all three populated. The
agent's `LoggingEventInstrumentation` explicitly copies the existing MDC map
(`spanContextData.putAll(contextData)`) before adding trace_id/span_id, so
custom MDC keys survive. The earlier claim in this file that it "drops other
MDC entries" was wrong. Diagnostic logging was removed again after the check.

### Two real bugs found while verifying

Neither was a tracing problem; both blocked the end-to-end run.

1. **Keycloak realm omitted the `basic` client scope, so access tokens carried
   no `sub` claim.** All three clients listed
   `defaultClientScopes: ["web-origins", "acr", "profile", "roles", "email"]`
   with no `basic`, and the realm defined no `defaultDefaultClientScopes`
   fallback. Keycloak's `basic` scope is what supplies `sub`. Every service
   derives the customer from `sub`, so `SecurityUtils.currentCustomerId()`
   returned null and **every authenticated business call returned 403** —
   including `GET /api/v1/cart` with a valid `CUSTOMER` token. Phase 4 never
   caught it because the mocked-JWT ITs inject authorities directly and
   `GatewayKeycloakIT` only exercises the gateway, so **no servlet service was
   ever driven with a real Keycloak token**. Fixed by adding `"basic"` to each
   client's `defaultClientScopes` in `infra/keycloak/ecommerce-realm.json`.
   Re-import needs a **fresh** Keycloak container
   (`docker compose rm -sf keycloak && docker compose up -d keycloak`) —
   `--import-realm` skips a realm that already exists.
2. **The correlation id was not forwarded on service-to-service calls.** The
   gateway sets `X-Correlation-Id`, but `RestClients` had no interceptor for it,
   so each callee generated its own and a user-quoted id found only the first
   hop. Fixed by forwarding `MDC.get(Correlation.MDC_KEY)` in the shared
   `RestClients` builder, so both `create` and `createWithServiceToken` clients
   propagate it. Verified: the same trace went from many distinct correlation
   ids to exactly one.

### Phase 4 authorization: blind spot closed (2026-09-21)

The `sub` bug exposed that Phase 4 was signed off without ever driving a
**servlet service** with a **real Keycloak token**: the mocked-JWT ITs inject
authorities directly, and `GatewayKeycloakIT` only exercises the gateway. So the
whole rule set was re-verified against the live stack with real tokens —
`scripts/verify-authz.sh`, **20/20 checks pass**:

| Area | Checks | Result |
|---|---|---|
| catalog | public GET, CUSTOMER write → 403, ADMIN write → 201 | pass |
| cart | CUSTOMER → 200, ADMIN → 403 (service is CUSTOMER-only), no token → 401 | pass |
| internal `/internal/**` | CUSTOMER → 403, SERVICE → 200/201 (inventory, catalog, order) | pass |
| ADR-013 boundary | mismatched `customerId` on a CUSTOMER token → 403 | pass |
| object ownership | own order → 200; other customer's order/payment → **404, not 403** | pass |
| gateway edge | `/internal/**` via gateway → 403; public GET → 200; no token → 401 | pass |

**Conclusion: the rules were correct all along — the missing `sub` claim was the
only real defect.** Note cart returns 403 for an ADMIN token: the gateway admits
CUSTOMER/ADMIN, but the service is authoritative and CUSTOMER-only (defense in
depth working as intended).

Two follow-ups landed from this:

- **Regression guard.** `GatewayKeycloakIT.realmImportCreatesUsersAndRoles` now
  asserts the access token carries a `sub` claim and that it parses as a UUID.
  Verified by running it against the unfixed realm first: it fails with
  `[access token must carry a sub claim]`, so it genuinely catches the bug.
- **Realm file drift removed.** There were **two** copies of the realm —
  `infra/keycloak/ecommerce-realm.json` (what the dev stack uses) and
  `gateway-service/src/test/resources/keycloak/ecommerce-realm.json` (what the
  IT imported). They had already diverged: the test copy was missing `basic`,
  so **the IT was validating a realm nobody runs**. The duplicate is deleted and
  `gateway-service/pom.xml` now copies the real file into the test classpath at
  `process-test-resources`, so there is exactly one source of truth.

The smoke script is the only thing that exercises real tokens against servlet
services, so run it after any change to the realm, the security config, or an
authorization rule.

### Not done yet (rest of Phase 5)

- Re-run `docs/phase-5-tracing-baseline.md` to fill the "After tracing" columns.
- Collector-side sampling policy and TLS/auth between services and the collector
  (deliberately deferred to Phases 9-10; recorded in ADR-014).

Done since the last revision: ADR-014 written; README run/verify steps corrected
and a Tracing section added; `RestClientsTest` guards the correlation-id
propagation (65 unit tests, up from 62); `scripts/verify-authz.sh` added and
passing 20/20; `GatewayKeycloakIT` guards the `sub` claim; the duplicated realm
file removed.

### Run / verify recipe

```bash
docker compose up -d keycloak jaeger otel-collector
./mvnw install -DskipTests        # see gotcha: -pl <service> alone cannot resolve common
./mvnw -pl checkout-service spring-boot:run
# Jaeger UI: http://localhost:16686
```

## Phase 6 (Kafka & event-driven) — implemented and verified

Goal: make the outbox tables actually publish, add idempotent consumers with a
dead-letter path, convert one saga leg to choreography, and keep the trace
unbroken across the broker (doc 13 §3 Phase 6, doc 06).

### What is implemented

- **Infrastructure** — Kafka in **KRaft mode** (`apache/kafka:3.9.1`, no
  ZooKeeper) in `docker-compose.yml`, with auto-topic-creation disabled so a
  typo fails loudly. Topics and their `.DLT` twins are declared by `KafkaAdmin`.
- **Client** — Spring for Apache Kafka (`spring-kafka`), an *optional*
  dependency of `common` so catalog/cart/checkout never open a broker
  connection. Rationale (vs Spring Cloud Stream) in ADR-015.
- **Envelope** — `com.ecommerce.common.messaging.EventEnvelope`, JSON, doc 06
  §1 shape, with `eventVersion` and a tolerant reader
  (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- **Publisher** — `OutboxPublisher`: a `@Scheduled` poller that locks a batch
  with `SELECT … FOR UPDATE SKIP LOCKED`, publishes to the domain topic keyed by
  aggregate id, and marks `published_at`. Kafka down ⇒ rollback ⇒ rows retained
  and retried; scaling out cannot double-publish (ADR-015).
- **Trace continuity** — the request's W3C `traceparent` is captured into
  `outbox_events.trace_parent` at record time and restored as the current
  context at publish time, so the consumer's span joins the original trace.
  (The agent cannot do this alone: the poller has no request context.)
- **Consumers** — order-service consumes `payment.events` (PaymentSucceeded →
  order PAID, then emits `OrderConfirmed`); inventory-service consumes
  `order.events` (OrderConfirmed → commit reservations). Each is idempotent via
  a durable `processed_events` table written **in the same transaction** as the
  business change.
- **Retry + DLT** — `DefaultErrorHandler` with exponential backoff (500 ms, 1 s,
  2 s) then `<topic>.DLT`, carrying the original record so it can be replayed.
- **6c choreography** — checkout no longer calls `inventory.commitByOrder` /
  `order.markPaid`; the events drive them. Compensation stays orchestrated.
  Compared honestly in ADR-016.
- **Schema evolution (6d)** — additive-only, versioned, tolerant readers; unit
  tests pin unknown-field and missing-field tolerance.

### Verified

- `./mvnw test` → **76 unit tests** green (was 65).
- `./mvnw verify` → whole reactor green with Docker: every existing IT plus
  `OutboxPublisherIT`, `PaymentEventsConsumerIT`, `OrderEventsConsumerIT`
  (duplicate delivery, out-of-order → DLT, poison → DLT, Kafka-down retains and
  drains). Brokers are real Testcontainers Kafka, never mocked.
- **Live stack**: a checkout through the gateway produced one trace
  (`c65eb3177827d953c93e9e5be51548ef`, 443 spans) containing all seven services
  and both `payment.events publish`/`payment.events process` and
  `order.events publish`/`order.events process` — one trace across the broker.
  Order converged to `PAID` and inventory to `COMMITTED` via events; outbox rows
  all `published_at` set; the poison record landed in `payment.events.DLT`.

### Phase 6 gotchas (also in the list below)

- Spring Kafka's `DeadLetterPublishingRecoverer` default DLT suffix is `-dlt`,
  not `.DLT`; with broker auto-create disabled, a wrong suffix wedges the
  consumer in an endless "record in retry" loop. The destination resolver must
  be overridden.
- The publisher serializes `Instant`, so it needs an `ObjectMapper` with the
  JSR-310 module — Boot's is fine; a bare `new ObjectMapper()` in a unit test is
  not.
- `org.testcontainers.kafka.KafkaContainer` works with `apache/kafka:3.9.1`, but
  `getBootstrapServers()` can carry a `PLAINTEXT://` prefix that Kafka clients
  reject; strip it before injecting `spring.kafka.bootstrap-servers`.
- On Windows a running service **locks its fat jar**, so `mvn package` leaves a
  stale jar behind (and `clean` fails with "being used by another process").
  Stop the services before rebuilding — this cost a full debugging cycle here.

## Phase 8 (observability) — implemented and verified

Goal: make the system answer "is it healthy, and if not, why" without reading
logs by hand (doc 13 §3, doc 08; ADR-021, ADR-022).

### The baseline first (doc 13 §3's protocol, this time actually run)

`docs/phase-8-observability-baseline.md` has the numbers. The short version:

| Scenario | p95 | error rate | noticed? |
|---|---|---|---|
| healthy | 333 ms | 0 % | — |
| provider delay 1.2 s | 1391 ms | 0 % | **nothing** (still in SLO, 4× worse) |
| provider delay 2.0 s | 2225 ms | 0 % | **nothing** |
| provider delay 6.0 s | 6568 ms | 75 % | **nothing** |
| 2.0 s, Phase 7 breaker default | 2225 ms | **45 %** | only a stray `WARN` log line |

Then, after the phase, the same degradations: p95 2.48 s with 100 % success
raised `CheckoutLatencyBreach`; 93 % errors raised `CheckoutErrorRateHigh` and
`CircuitBreakerOpen`; restoring the provider cleared all three. The 2.0 s delay
that produced 45 % errors now produces **zero**.

### What is implemented

- **Metrics (8a).** Actuator + `micrometer-registry-prometheus` are dependencies
  of `common`, so every servlet service is instrumented whether or not anyone
  remembered; the gateway declares them itself (it cannot depend on `common`).
  Metrics are **pulled** from `/actuator/prometheus`; the agent's
  `otel.metrics.exporter=none` avoids a second series per measurement.
- **Custom metrics.** `ApplicationMetrics` in `common` owns the business signals
  doc 08 §4 asks for — checkout outcomes, payment outcomes, reservation outcomes,
  orders placed, reconciliation outcomes — because `http.server.requests` cannot
  tell a declined payment (409) from a malformed request. Plus
  `outbox.events.{unpublished,oldest.unpublished.age}` and Kafka consumer lag.
- **Logs (8c).** The agent exports logs over OTLP; the collector forwards them to
  Loki. No file appender, no tailer, no bind mount — which also sidesteps the
  Docker-Desktop inotify problem. Log records carry `trace_id`, so a log line
  links straight to its trace. Labels are constrained to `service_name`,
  `service_instance_id`, `deployment_environment`.
- **Dashboards (8b).** Two provisioned from `infra/grafana/dashboards/`:
  platform overview (SLO, RED, dependency guards, outbox, lag, pool) and
  checkout & payments (business outcomes, reconciliation, plus a Loki panel).
  Prometheus, Loki and Jaeger are all datasources, so a slow checkout is a metric,
  a log line and a trace without changing tabs.
- **Alerts (8e).** Eleven rules in `infra/prometheus/rules/`, all written on
  symptoms. Unit tested with `promtool` (11 tests, including the negative cases) so
  they run on every build. No Alertmanager — nothing to route to in dev.
- **Health (8d).** `liveness`/`readiness` groups; readiness includes the database
  for DB-backed services, liveness deliberately does not. The four observability
  endpoints are unauthenticated (Prometheus and a kubelet need them without a
  token); nothing else under `/actuator` is exposed.
- **The whole environment is one `docker compose up -d`.** Postgres moved into
  compose with `infra/postgres/init-databases.sql`, and Prometheus, Grafana and
  Loki joined the stack.
- **Two tools added** because the phase's protocol needs repeatable drivers rather
  than a session transcript: `scripts/start-services.sh` (start/stop all seven,
  wait for readiness) and `scripts/checkout-load.sh` (SLO probe: p50/p95/p99 and
  error rate).

### Verified

- `./mvnw test` → **94 unit tests** green (was 91; +2 resilience slow-call cases,
  +1 checkout outcome metric).
- `./mvnw verify` → whole reactor green with Docker.
- `promtool test rules` → 11/11.
- Live stack: 7/7 Prometheus targets up; Loki holds logs from all seven services
  with `trace_id` intact; both dashboards provisioned; alerts fired and resolved
  as described above.

### Phase 8 gotchas (also in the list below)

- **A `@ConditionalOnBean(MeterRegistry.class)` in an auto-configuration can be
  evaluated before Boot registers the registry**, so the beans are silently never
  created — no error, just absent metrics. Injecting the registry directly avoids
  the ordering question entirely.
- **A class-level `@ConditionalOnClass` is not enough to keep a bean method's
  parameter types off the classpath.** Once the class passes its condition, Spring
  resolves *every* method signature, so an outbox metric in the same class as a
  Micrometer-only metric broke checkout-service with
  `ClassNotFoundException: JpaRepository`. Split the configuration per dependency.
- **Prometheus strips reserved exposition suffixes from metric names.**
  `orders.created` was exported as `orders_total` — "created" silently vanished.
  Avoid ending a metric name with `created`, `total`, `sum`, `count`, `bucket`
  or `info`.
- **Loki synthesises `service_instance_id` when the resource attribute is
  absent**, and the synthesised value is stable only within a stream — so
  *deleting* the agent's random one is worse than keeping it. Pin it to a stable
  value instead.
- **The `resource` processor, not `attributes`, for resource attributes.**
  `attributes` only touches a record's own attributes; using it on
  `service.instance.id` silently did nothing.
- **PromQL does not allow a line break inside a vector selector's braces**, and
  the error points at the line *after* the one that is wrong.
- **Micrometer exports no histogram buckets by default.** Without
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
  there are no `_bucket` series and every percentile alert silently matches
  nothing.
- **`docker compose up -d <service>` does not reload a bind-mounted config** —
  the container is "Running" so compose leaves it alone. `docker compose restart`
  is needed, and forgetting it means editing a file and testing the old one.
- **Collector 0.160.0 needs an explicit Prometheus reader** for its self-metrics
  (`service.telemetry.metrics.readers`); the old `level: basic` no longer listens
  on 8888, so the scrape target is simply `down`.
- **`otlphttp` is deprecated in collector 0.160.0** — use `otlp_http` (the same
  rename that produced `otlp_grpc` for Jaeger).

## Phase 7 (resilience) — implemented and verified

Goal: stop a slow or failing dependency from taking the caller down, make
retries safe, and **find and repair the orders a partial saga failure leaves
behind** (doc 13 §3, ADR-017…020).

### What is implemented

- **Timeouts (7a, ADR-018).** `RestClients` now builds an Apache HC5 client with
  connect / connection-request / response timeouts and a `PoolingHttpClient
  ConnectionManager` bounded per dependency, instead of the bare
  `new HttpComponentsClientHttpRequestFactory()` that had no timeouts at all.
- **A timeout budget (7a).** `Deadline` is a `ThreadLocal` budget set once at the
  start of the saga. `ResilienceRequestInterceptor` refuses to start a call once
  it is spent; `BudgetedHttpComponentsClientHttpRequestFactory` caps each
  request's response timeout at the time remaining. Budget: 10 s saga, 1–3 s
  per dependency (nested deliberately — see the table in ADR-018), gateway
  response timeout 15 s. The gateway also gets `httpclient` connect/response
  timeouts and a bounded pool.
- **Circuit breakers (7b, ADR-017).** Resilience4j core modules (not the
  starter — it binds one global registry and is AOP-based, which checkout's
  selective scan would silently miss), wired by `ResilienceAutoConfiguration` in
  `common`, **one breaker per dependency** (`catalog`, `cart`, `inventory`,
  `order`, `payment`). A 5xx counts as a failure via `recordResult`; a full
  bulkhead does not. State transitions are logged.
- **Retries (7b, ADR-019).** Exponential backoff with jitter, capped at 3
  attempts, **opt-in per request** via `Retryable.yes/no` — GETs by default,
  state-changing calls only where the client asserts idempotency. 4xx never
  retried.
- **Bulkheads (7c).** A semaphore bulkhead per dependency (fails immediately
  when full, rather than queueing into the caller's thread pool) plus the
  bounded HC5 pool.
- **`reserveOrCompensate` gap closed (7b).** The saga now compensates when a
  dependency is *unreachable*, not just when stock is insufficient. Everything
  that means "could not answer" — timeout, connection failure, pool/bulkhead
  exhaustion, open breaker, and a 5xx that survived the retries — is classified
  as `ServiceUnavailableException` (503) by the interceptor, and the saga
  compensates on it. Payment is deliberately excluded: a payment that cannot be
  reached may in fact have been recorded, so the order is left recoverable for
  reconciliation instead of being cancelled.
- **Inventory reservation idempotency (7b, ADR-019).** `reserve` is idempotent
  on the natural key `(orderId, productId)` while RESERVED (partial unique index
  `uk_reservations_order_product_active`), which is what makes retrying it safe.
  A client-supplied key was rejected as the weaker option — a client can defeat
  its own key on retry.
- **Reconciliation (7d, ADR-020).** `OrderReconciliationService` in
  order-service: claims orders in `PENDING`/`PAYMENT_PENDING` older than
  `stale-after` with `SELECT … FOR UPDATE SKIP LOCKED` **plus a lease**, reads
  the authoritative payment state (new `GET /internal/api/v1/payments/by-order/{id}`)
  and reservation state (new `GET /internal/api/v1/inventory/reservations?orderId=`),
  then **completes** (payment SUCCEEDED → mark PAID, which re-emits
  `OrderConfirmed` and commits stock through the normal path) or **compensates**
  (release what is still RESERVED, cancel). Bounded by batch size, per-order
  backoff and `max-attempts`, ending in the new terminal `NEEDS_ATTENTION`
  state.
- **Metrics hook (8 will finish).** `resilience4j-micrometer` is an *optional*
  dependency of `common`, bound to a `MeterRegistry` only if one exists — dormant
  until Phase 8 adds Actuator, then active with no code change.

### Verified

- `./mvnw test` → **91 unit tests** green (was 76; +12 resilience, +3 checkout
  saga failure handling).
- `./mvnw verify` → whole reactor green with Docker.
- New failure-mode coverage: `ResilientRestClientTest` (timeouts fail fast, the
  deadline refuses a call, retries cap at 3 and never retry 4xx, an unmarked
  POST is not retried, backoff is bounded, the breaker opens and then does not
  call the dependency at all, breakers are independent per dependency, a
  saturated dependency fails fast without starving a healthy one, per-dependency
  config overrides win), `CheckoutFailureInjectionIT` (inventory 5xx and a
  dropped connection compensate; a slow inventory is bounded and compensates; a
  payment timeout leaves the order recoverable; repeated failures open the
  breaker and later checkouts fail fast; a cart read failure has no side
  effects; the saga budget bounds a slow first call),
  `OrderReconciliationIT` (compensates a stranded order, completes a
  paid-but-stuck one, leaves fresh orders alone, is idempotent, a failed repair
  is held by the lease, gives up into `NEEDS_ATTENTION`, defers an in-flight
  payment, does not release already-resolved reservations), `PaymentFailureIT`
  (a declined payment is charged once even when initiation is retried, a
  different key still does not create a second payment, a duplicate webhook is
  still deduplicated), `DatabaseOutageIT` (a paused PostgreSQL container fails
  the request loudly and in bounded time, with no data loss).
- Phase 6 guarantees re-verified: `OutboxPublisherIT` and the consumer ITs still
  pass unchanged (Kafka outage → rows retained; duplicate/out-of-order/poison →
  DLT).

### Phase 7 gotchas (also in the list below)

- **A `NotFoundException` catch clause does not catch a 404.** `RemoteExceptionMapper`
  converts the raw `HttpClientErrorException` *after* the fact, so
  `catch (NotFoundException ex)` never fires — the check has to be on the mapped
  exception. Cost a debugging cycle in the reconciliation IT.
- **Breakers are shared singletons in the shared Spring test context.** An IT
  that drives a dependency to 5xx leaves the breaker OPEN for the next IT class,
  which then sees a fast 503 instead of the behaviour it set up. Both IT bases
  now reset every breaker in `@AfterEach`.
- **Mutating an entity to take the reconciliation lease moves `updated_at`.**
  `@PreUpdate` fires, and `updated_at` is the staleness clock the claim query
  reads — so every pass pushed the order out of the candidate window for another
  full threshold and the attempt budget could never be reached. Fixed by writing
  the lease with a bulk update.
- **`docker stop`/`docker start` on a Testcontainers PostgreSQL reassigns the
  host port on Docker Desktop**, so the pool never recovers. The database-outage
  test pauses the container instead, with a `socketTimeout` on the JDBC URL to
  bound the failure.
- **`-pl <service>` runs against the installed `common`.** After changing
  `common` you must `./mvnw install -DskipTests -pl common` before an IT run, or
  the service silently executes the previous `common`. This made a correct
  5xx-classification change look like it had not been applied.

## Environment gotchas (learned the hard way — read before running)
- **Port 5432 is taken by a local Windows PostgreSQL.** Start the Docker
  Postgres on a different host port and pass it to every service:
  `docker run -p 5433:5432 ...` then start services with `DB_PORT=5433`.
- **Cross-service REST clients use Apache HttpClient 5** (`RestClients` in
  `common`). The JDK HttpClient default aborts keep-alive connections to
  WireMock's Jetty on Windows after a few sequential calls — do not switch
  back without testing the checkout IT repeatedly.
- **WireMock in ITs: always use instance methods** (`WIRE_MOCK.verify(...)`,
  `WIRE_MOCK.stubFor(...)`). The static `WireMock.verify(...)` talks to the
  default server at localhost:8080 and fails.
- **IT base classes must reference the app class**
  (`@SpringBootTest(classes = XxxServiceApplication.class)`) because the ITs
  live in `com.ecommerce.integration`, outside the service's package.
- WireMock servers in IT bases live for the whole JVM (no `@AfterAll stop`),
  like the shared Testcontainers PostgreSQL — stopping them breaks the
  cached Spring context on the next test class. (Gateway ITs are the
  exception: they start/stop WireMock per class, sequentially.)
- **Mocked JWTs need the converter**: `SecurityMockMvcRequestPostProcessors.jwt()`
  and reactive `mockJwt()` ignore `realm_access` and produce `SCOPE_*`
  authorities by default. Always chain
  `.authorities(new KeycloakJwtAuthoritiesConverter())`. Mock `jwt()` also
  defaults the subject to `"user"` — set a UUID subject wherever the service
  parses `sub` as a customer id.
- **Testcontainers dropped the keycloak module** — use
  `com.github.dasniko:testcontainers-keycloak:3.9.1` (4.x needs
  Testcontainers 2.x; the project is on 1.21.4). Package is
  `dasniko.testcontainers.keycloak.KeycloakContainer`.
- **Direct service-call tests need a SecurityContext** — `OrderService`,
  `PaymentService` derive identity from the context; unit/IT tests that call
  them directly set it via `com.ecommerce.integration.TestSecurity.asUser(...)`
  (per-module copy).
- **@Lazy does not defer servlet JwtDecoder creation** — the servlet
  `WebSecurityConfiguration` instantiates it at startup, and
  `NimbusJwtDecoder.withIssuerLocation(...).build()` does OIDC discovery
  eagerly (network at startup). Use the `LazyIssuerJwtDecoder` /
  `LazyIssuerReactiveJwtDecoder` wrappers (discovery on first token
  validation, no I/O at construction).
- **@PreAuthorize denials must not hit the generic @ExceptionHandler** —
  `GlobalExceptionHandler` rethrows `AccessDeniedException` so Spring
  Security's filter maps it to the JSON 403; otherwise it becomes a 500.
- **Services with S2S calls need a token endpoint in ITs** — the IT bases
  (cart/order/payment/checkout) stub POST /token on WireMock and override
  `ecommerce.security.service-client.token-uri` to it (no Keycloak needed
  for service ITs).
- **checkout-service scans selectively** — `CheckoutServiceApplication` scans
  `com.ecommerce.checkout` + `common.error` + `common.security` (NOT the
  whole `com.ecommerce`, to avoid the JPA/outbox beans). If new shared
  components are added to `common`, check whether checkout needs them in the
  scan.
- **Gateway ITs use the test profile** — `GatewayKeycloakIT` needs
  `@ActiveProfiles("test")` so routes point at WireMock 18080; without it the
  main `application.yml` routes to the real service ports (8081-8086) and
  every routed request 500s.
- **Realm JSON must match Keycloak 26 fields** — `refreshTokenLifespan` /
  `refreshTokenMaxReuse` are rejected by the importer in 26.7 (rotation is
  the default); `serviceAccountClientId` users with `realmRoles` import
  correctly (verified by `GatewayKeycloakIT.realmImportCreatesUsersAndRoles`).
- **Clients MUST list `basic` in `defaultClientScopes`, or access tokens have
  no `sub` claim.** Keycloak's `basic` client scope is what supplies `sub`;
  omitting it silently produces tokens that validate fine (401 never fires) but
  carry no subject, so every service that derives identity from `sub` returns
  403. Declaring `defaultClientScopes` on a client overrides the realm default,
  so the scope has to be listed explicitly. Symptom to recognise: token decodes
  with `scope: "email profile"` and no `sub` key.
- **`--import-realm` skips a realm that already exists.** Editing
  `infra/keycloak/ecommerce-realm.json` has no effect until the container is
  recreated: `docker compose rm -sf keycloak && docker compose up -d keycloak`.
- **Keycloak 26 serves health on the management port 9000, not 8080.** The
  compose healthcheck originally probed 8080 and got a 404, so the container sat
  at `unhealthy` indefinitely while Keycloak was serving normally — a
  misleading signal for anyone reading `docker compose ps`. Fixed by probing
  9000 (`--health-enabled=true` makes the endpoint explicit).

### Workstation setup (Windows, cost hours on 2026-09-20 — read first)

- **Docker Desktop is a per-user install**: `%LOCALAPPDATA%\Programs\DockerDesktop`,
  not `C:\Program Files\Docker`. A `where docker` or Program Files check misses
  it entirely and makes it look uninstalled.
- **The real signal for a Docker/WSL start failure is `HypervisorPresent`**, not
  the error text:
  `Get-CimInstance Win32_ComputerSystem | Select HypervisorPresent` must be
  `True`. Docker's "virtualisation support wasn't detected" is misleading —
  firmware VT-x was already enabled (`VirtualizationFirmwareEnabled: True`); the
  missing piece was the WSL2 hypervisor. `wsl --install` also fails on Windows 11
  build 26200 with `Wsl/CallMsi/Install/REGDB_E_CLASSNOTREG` ("Class not
  registered"); the working fixes are the official triage script
  (`https://raw.githubusercontent.com/microsoft/WSL/master/triage/install-latest-wsl.ps1`)
  or installing the WSL MSI directly. Do **not** run the `regsvr32`-every-DLL
  "fix" that circulates in those threads.
- **Maven is not in winget** — `Apache.Maven` does not exist (only unrelated
  Minecraft packages match). Use Scoop: `scoop install main/maven`. Scoop also
  covers the later phases' CLI tools (kubectl, helm, terraform, k6, trivy).
- **Quote `-Dmaven=…` on PowerShell.** `mvn wrapper:wrapper -Dmaven=3.9.16` is
  parsed as `-Dmaven=3`, which writes a broken
  `distributionUrl=…/apache-maven/3/apache-maven-3-bin.zip` into
  `maven-wrapper.properties`. The command exits non-zero but **has already
  written the files**, so it is easy to miss. Inspect the properties file after
  generating the wrapper.
- **`mvnw` needs its executable bit set explicitly on Windows** — this checkout
  has `core.filemode=false`, so Git will not record it and Linux CI fails with
  "Permission denied" while everything works locally. Fix:
  `git update-index --chmod=+x mvnw` (confirm with `git ls-files -s mvnw` →
  `100755`).
- **`dependency:copy` has no `destFileName` or `overWrite` parameter** in
  maven-dependency-plugin 3.8.1 — both are silently ignored (with a WARNING)
  and the artifact lands under its versioned name. Use `<stripVersion>true</stripVersion>`
  to get a stable filename.
- **The OTel Java agent defaults to `http/protobuf`, not gRPC.** With
  `otel.exporter.otlp.endpoint=http://localhost:4317` it warns that 4317 is the
  gRPC port and then **fails to export silently** — no error in the app log and
  nothing in Jaeger. Always set `-Dotel.exporter.otlp.protocol=grpc` (or point
  the endpoint at 4318).
- **Collector 0.160.0 deprecated the bare `otlp` exporter alias** — use
  `otlp_grpc` / `otlp_http`, otherwise the collector logs a deprecation warning
  on every start.
- **`./mvnw -pl <service> spring-boot:run` fails on a clean clone**: the module
  cannot resolve `com.ecommerce:common`, because `mvn package` never installs it.
  Run `./mvnw install -DskipTests` first. The README's run section says
  `mvn -B package`, which is not sufficient.
- **Empty `corr=` on early log lines is not a bug.** Tomcat initialises the
  servlet *before* running the filter chain, so the `DispatcherServlet -
  Initializing Servlet` lines are emitted before any filter — including the
  correlation filter — has run. They still show trace/span, because the agent's
  span starts higher up at the Tomcat valve. Judge MDC by a line emitted during
  request handling, not by servlet initialisation.
- **The OTel agent preserves custom MDC keys.** `LoggingEventInstrumentation`
  copies the event's existing MDC map before adding `trace_id`/`span_id`/
  `trace_flags`, so `%X{correlationId}` works alongside them. (Verified by
  instrumenting the filter: one line rendered trace, span *and* corr together.)
- **Spring Kafka's dead-letter suffix is `-dlt`, not `.DLT`.** The default
  `DeadLetterPublishingRecoverer` destination resolver appends `-dlt`, so a
  message that exhausts its retries goes to `payment.events-dlt`. With
  `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` that topic does not exist, the DLT
  publish fails, and the consumer wedges in an endless "Record in retry and not
  yet recovered" loop — **no later record on that partition is processed**. Fix:
  pass a destination resolver returning `Topics.deadLetter(record.topic())`
  (`<topic>.DLT`, doc 06 §6) and declare those topics via `KafkaAdmin`.
- **The outbox publisher needs a JavaTimeModule-aware ObjectMapper.** The
  envelope carries `occurredAt` as an `Instant`; a bare `new ObjectMapper()`
  throws `InvalidDefinitionException: Java 8 date/time type … not supported`.
  Boot's mapper is fine — only unit tests that build their own mapper hit this.
- **`org.testcontainers.kafka.KafkaContainer` works with the official
  `apache/kafka:3.9.1` image**, but `getBootstrapServers()` may include a
  `PLAINTEXT://` scheme prefix Kafka clients reject; strip it before setting
  `spring.kafka.bootstrap-servers`. (Also: `jps -l` prints the jar path for
  executable jars, not the main class, so grepping for `com.ecommerce` matches
  nothing — kill by PID, not by class name.)
- **On Windows a running service locks its fat jar.** `mvn package` then leaves
  the *previous* jar in place while still reporting SUCCESS, and `mvn clean`
  fails with "The process cannot access the file because it is being used by
  another process". A live-stack run therefore executed stale code and looked
  like a DLT bug. **Stop the services before rebuilding**, and check the jar
  mtime whenever behaviour does not match the source.
- **An unanchored `.gitignore` pattern silently ignores paths at any depth.**
  `otel/` was meant for the fetched agent jar at the repository root, but it also
  matched `infra/otel/`, so **the OpenTelemetry Collector's configuration was
  never committed** — the compose file mounted a file a fresh clone did not have,
  and Phase 5's tracing would not have started for anyone else. Fixed by anchoring
  it to `/otel/`. Worth auditing any ignore pattern that is a bare directory name.
- **`docker compose up -d <service>` does not reload a bind-mounted config.** The
  container is already "Running", so compose leaves it alone and you test the old
  file. Use `docker compose restart <service>` after editing
  `infra/otel/otel-collector-config.yaml`, `infra/loki/…` or
  `infra/prometheus/prometheus.yml` (Prometheus can also `POST /-/reload`, which
  is what `--web.enable-lifecycle` is for).
- **A `@ConditionalOnBean(MeterRegistry.class)` inside an auto-configuration is
  order-sensitive and fails silently.** The registry is registered by Boot's own
  metrics auto-configuration, so a condition evaluated before it sees no bean and
  creates nothing — no error, no warning, just absent metrics. Inject the registry
  directly instead: by instantiation time every definition exists.
- **`@ConditionalOnClass` on the class does not protect bean-method parameter
  types.** Once the class passes its own condition Spring resolves *every* method
  signature, so a metric needing JPA in the same configuration class as one
  needing only Micrometer broke checkout-service with
  `ClassNotFoundException: org.springframework.data.jpa.repository.JpaRepository`.
  One auto-configuration per optional dependency.
- **Prometheus strips reserved exposition suffixes from metric names.**
  `orders.created` was exported as `orders_total`: the word "created" disappeared
  with no error, and only a dashboard query returning nothing would have shown it.
  Do not end a metric name with `created`, `total`, `sum`, `count`, `bucket` or
  `info` — the registry re-adds its own.
- **Loki synthesises `service_instance_id` when the resource attribute is
  missing**, and the synthesised value is stable only within a stream — so each
  new stream brings a new one. Deleting the agent's random per-JVM UUID is
  therefore *worse* than keeping it; pin it to a stable value in the agent's
  `otel.resource.attributes` instead. (Also: the `resource` processor, not
  `attributes`, is what edits resource attributes — `attributes` only touches a
  record's own, and using it there silently does nothing.)
- **PromQL does not accept a line break inside a vector selector's braces**, and
  the parse error points at the line *after* the offending one ("unexpected
  character: '}'"), which sends you looking in the wrong place.
- **Micrometer exports no histogram buckets unless you ask.** Without
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
  there are no `_bucket` series, so `histogram_quantile` has nothing to work with
  and a percentile alert silently matches nothing forever.
- **Collector 0.160.0 needs an explicit Prometheus reader for its own metrics.**
  `service.telemetry.metrics.level: basic` no longer exposes anything on 8888 —
  the config has to declare `readers: [- pull: {exporter: {prometheus: …}}]`, or
  the scrape target is just `down` with no other symptom. (`otlphttp` is also
  deprecated in this version; use `otlp_http`.)
- **`-pl <service>` resolves `common` from the local repository, not the
  reactor.** After changing anything in `common`, run
  `./mvnw install -DskipTests -pl common` before running a single module's ITs,
  or the service executes the *previous* `common` jar. A correct change then
  looks like it had no effect — here it made a 5xx-classification fix appear
  unapplied.
- **A `catch (NotFoundException)` clause never catches a 404.**
  `RemoteExceptionMapper.from(...)` converts the raw `HttpClientErrorException`
  into `NotFoundException` *after* the fact, so the catch has to be on the
  mapped exception (`RuntimeException mapped = from(ex); if (mapped instanceof
  NotFoundException) ...`). A 404 then silently propagates as an error instead
  of being treated as "not found".
- **Resilience4j breakers are singletons in the shared Spring test context.**
  An IT that drives a dependency to 5xx leaves the breaker OPEN for the next IT
  class, which then sees a fast 503 instead of the behaviour it set up — a
  confusing, order-dependent failure. Both IT bases reset every breaker in
  `@AfterEach`.
- **Taking the reconciliation lease by mutating the entity moves `updated_at`.**
  `@PreUpdate` fires on any entity update, and `updated_at` is the staleness
  clock the claim query reads — so each pass pushed the order out of the
  candidate window for another full `stale-after` period and the attempt budget
  could never be reached. Write operational fields with a bulk update.
- **`docker stop`/`docker start` on a Testcontainers PostgreSQL reassigns the
  host port on Docker Desktop**, so the JDBC URL goes stale and the pool never
  recovers. For a database-outage test, `pause`/`unpause` the container instead
  and put `socketTimeout` on the JDBC URL so the failure is bounded rather than
  a hang.
- **`FOR UPDATE SKIP LOCKED` cannot be the whole single-flight story** when the
  work after claiming is a network call: a row lock would be held for the
  duration of the round trip. Claim, then take a *lease* (push
  `next_reconciliation_at` into the future) in the same transaction.
- **The shared `ProcessedEvent` entity is scanned by every DB service**
  (`@EntityScan("com.ecommerce")` + `ddl-auto: validate`), so all five
  `outbox_events` tables gained `correlation_id`/`trace_parent` and every DB
  service gained a `processed_events` table — even catalog/cart, which do not
  consume yet. Forgetting one fails startup on schema validation.

## Next phases (docs/13-learning-roadmap.md)

The sequencing was revised on 2026-09-20 — **`docs/13-learning-roadmap.md`
supersedes `docs/11-implementation-roadmap.md` as the implementation order**
(doc 11 remains the original capability list). Two changes matter when
resuming: tracing moved ahead of Kafka, and observability was split in two.
Each phase runs the "break it first" protocol (reproduce the failure the
pattern prevents, measure it, then implement) — see doc 13 §1.

- **Phase 5 — Observability: tracing** ✅ DONE (pulled forward from old Phase 7).
  OpenTelemetry across all services + gateway, OTLP → Tempo/Jaeger, JSON logs
  with `traceId`/`spanId`, correlation id propagation (doc 08 §2-3, §5).
  Needed *before* Kafka: async failures are invisible without trace context.
- **Phase 6 — Kafka & event-driven** ✅ DONE (was Phase 5, expanded). Four sub-steps:
  6a dual-write → outbox (ADR-009) → then Debezium CDC for comparison;
  6b consumers (idempotency, topics, partition keys, groups, bounded retry,
  DLT); 6c replace one saga flow with choreography and compare against the
  current orchestration (ADR-013 anticipates this); 6d schema evolution.
  The outbox tables exist per service; the checkout orchestrator is
  structured so the REST saga can be replaced by events without changing the
  checkout contract.
- **Phase 7 — Resilience** ✅ DONE (was Phase 6, moved after Kafka). Timeouts
  with a saga deadline, per-dependency circuit breakers, opt-in retries with
  backoff+jitter, bulkheads and bounded pools, the doc 10 §7 failure-injection
  tests, and the reconciliation job for orders stranded by a partial saga
  failure (ADR-017…020).
- **Phase 8 — Observability: metrics/logs/alerts** ✅ DONE (remainder of old
  Phase 7). Actuator + Micrometer → Prometheus (pulled), logs over OTLP → Loki,
  traces → Jaeger, Grafana dashboards provisioned from files, eleven
  symptom-based alert rules unit tested with `promtool` and proved by degrading
  the live system (ADR-021, ADR-022). Kafka consumer lag and outbox backlog are
  both measured. Deliberately not done: Alertmanager (nothing to route to in
  dev), `absent()`/no-data alerts, and a separate management port for the
  actuator endpoints.
- **Phases 9-12** — containerization (productionizing images, not learning
  Docker), Kubernetes + Terraform + secrets management (**time-boxed**),
  CI/CD + contract testing (doc 10 §5, previously unassigned), production
  hardening. Deferred and known: mTLS / zero-trust internal transport
  (doc 09 §6).
