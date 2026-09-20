# Project Progress — session handoff

Last updated: 2026-09-20 (roadmap re-sequenced — see `docs/13-learning-roadmap.md`)

## Status: Phase 4 (Security) — DONE, `mvn -B verify` GREEN

- `4dc3814` — feat: secure the platform with Keycloak/OAuth2, RBAC and
  ownership (Phase 4)
- `980569a` — fix: harden Phase 4 verification findings
- Full reactor `mvn -B verify` green with Docker: 113 tests (57 unit + 56
  integration incl. Testcontainers PostgreSQL + a real Keycloak container).
  `main` is in sync with `origin/main` (pushed).

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
