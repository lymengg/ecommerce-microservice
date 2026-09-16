# Project Progress — session handoff

Last updated: 2026-09-16 (Phase 3 complete, committed and pushed)

## Status: Phase 3 (Service Extraction) — DONE

- Commit: `3c7f84f` — `feat: extract catalog, cart, inventory, order, payment into services`
- Pushed to `origin/main`. Working tree clean. Full reactor `mvn -B verify` green:
  57 unit tests + 32 integration tests (Testcontainers + WireMock).

### What Phase 3 delivered
- Multi-module Maven reactor: `common`, `catalog-service`, `cart-service`,
  `inventory-service`, `order-service`, `payment-service`, `checkout-service`,
  `gateway-service` (Spring Cloud Gateway, ADR-006, no Eureka per ADR-010).
- Database-per-service (ADR-003): each service owns a PostgreSQL database
  (`ecommerce_<service>`), its own Flyway migrations, and its own
  `outbox_events` table (ADR-009, publisher still Phase 5).
- Cross-domain calls over synchronous REST (docs/03-microservice-architecture.md
  section 3); checkout is a stateless saga orchestrator with compensation
  (release inventory + cancel order), structured so a Phase 5 event-driven
  version can replace the REST calls.
- Server-authoritative pricing and Idempotency-Key semantics preserved.
- Latent bug fixed: inventory reservation rows now transition to
  COMMITTED/RELEASED when resolved by order (removed
  `clearAutomatically=true` from the bulk stock UPDATEs, which had detached
  the reservation entity).

### Ports / databases
| Service | Port | DB |
|---|---|---|
| gateway-service | 8080 | — |
| catalog-service | 8081 | ecommerce_catalog |
| cart-service | 8082 | ecommerce_cart |
| inventory-service | 8083 | ecommerce_inventory |
| order-service | 8084 | ecommerce_order |
| payment-service | 8085 | ecommerce_payment |
| checkout-service | 8086 | — (no DB) |

## How to resume (fast verification)
```bash
mvn -B verify                      # whole reactor, unit + ITs (needs Docker)
mvn test                           # unit tests only
```

Manual end-to-end flow (documented in README.md "Manual checkout flow"):
1. `docker run` Postgres + create the 5 databases (README shows the SQL).
2. `mvn -B package -DskipTests`, then `java -jar <service>/target/*.jar` per
   service (or `mvn -pl <service> spring-boot:run`).
3. Curl through the gateway: create product → activate → set stock → cart →
   checkout → expect `PAID` + committed stock; a price > 10000 declines →
   expect `CANCELLED` + released stock.

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
  cached Spring context on the next test class.

## Next phases (docs/11-implementation-roadmap.md)
- **Phase 4 — Security**: Keycloak, OAuth2/OIDC resource servers, RBAC,
  rate limiting at the edge. Gateway is ready to attach auth filters.
- **Phase 5 — Kafka**: event contracts, outbox publisher, consumers with
  idempotency, DLT. The outbox tables already exist per service; the
  checkout orchestrator is structured so the REST saga can be replaced by
  events without changing the checkout contract.
- **Phase 6 — Resilience**: timeouts, circuit breakers, retries (the
  RestClient/Apache HC5 foundation is already in `common`).
