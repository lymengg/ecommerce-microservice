# Project Progress — session handoff

Last updated: 2026-09-21 (Phase 5 tracing verified end-to-end; uncommitted)

## Status: Phase 5 (Observability/tracing) — verified end-to-end, uncommitted

Phase 4 is DONE. Phase 5 tracing is implemented and now verified against a live
Jaeger with a real Keycloak token: one checkout produces a single trace across
all seven services carrying one correlation id. The work is **uncommitted** —
see "Phase 5 (tracing)" below for the state, the two bugs found on the way, and
what remains.

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

### Not done yet (rest of Phase 5)

- Re-run `docs/phase-5-tracing-baseline.md` to fill the "After tracing" columns.
- Collector-side sampling policy and TLS/auth between services and the collector
  (deliberately deferred to Phases 9-10; recorded in ADR-014).

Done since the last revision: ADR-014 written, README run/verify steps corrected
and a Tracing section added, and `RestClientsTest` guards the correlation-id
propagation (65 unit tests now, up from 62).

### Run / verify recipe

```bash
docker compose up -d keycloak jaeger otel-collector
./mvnw install -DskipTests        # see gotcha: -pl <service> alone cannot resolve common
./mvnw -pl checkout-service spring-boot:run
# Jaeger UI: http://localhost:16686
```

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

## Next phases (docs/13-learning-roadmap.md)

The sequencing was revised on 2026-09-20 — **`docs/13-learning-roadmap.md`
supersedes `docs/11-implementation-roadmap.md` as the implementation order**
(doc 11 remains the original capability list). Two changes matter when
resuming: tracing moved ahead of Kafka, and observability was split in two.
Each phase runs the "break it first" protocol (reproduce the failure the
pattern prevents, measure it, then implement) — see doc 13 §1.

- **Phase 5 — Observability: tracing** (pulled forward from old Phase 7).
  OpenTelemetry across all services + gateway, OTLP → Tempo/Jaeger, JSON logs
  with `traceId`/`spanId`, correlation id propagation (doc 08 §2-3, §5).
  Needed *before* Kafka: async failures are invisible without trace context.
- **Phase 6 — Kafka & event-driven** (was Phase 5, expanded). Four sub-steps:
  6a dual-write → outbox (ADR-009) → then Debezium CDC for comparison;
  6b consumers (idempotency, topics, partition keys, groups, bounded retry,
  DLT); 6c replace one saga flow with choreography and compare against the
  current orchestration (ADR-013 anticipates this); 6d schema evolution.
  The outbox tables exist per service; the checkout orchestrator is
  structured so the REST saga can be replaced by events without changing the
  checkout contract.
- **Phase 7 — Resilience** (was Phase 6, moved after Kafka). Timeouts, circuit
  breakers, retries, bulkheads (the RestClient/Apache HC5 foundation is
  already in `common`), failure-injection tests (doc 10 §7), plus the
  reconciliation job for inconsistent orders after a partial saga failure.
- **Phase 8 — Observability: metrics/logs/alerts** (remainder of old Phase 7).
  Micrometer → Prometheus, Grafana dashboards, Loki, alerts on symptoms
  (doc 08 §4, §6-7) — including Kafka consumer lag and outbox backlog.
- **Phases 9-12** — containerization (productionizing images, not learning
  Docker), Kubernetes + Terraform + secrets management (**time-boxed**),
  CI/CD + contract testing (doc 10 §5, previously unassigned), production
  hardening. Deferred and known: mTLS / zero-trust internal transport
  (doc 09 §6).
