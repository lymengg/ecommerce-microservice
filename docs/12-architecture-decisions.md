# E-Commerce Platform — Architecture Decision Records

## ADR-001: Spring Boot
**Decision:** Use Spring Boot for backend services.

**Reason:** Mature ecosystem, production adoption, strong integration with security, data, messaging, testing, and observability.

## ADR-002: PostgreSQL
**Decision:** Use PostgreSQL as the primary relational database.

**Reason:** Strong transactional guarantees, mature indexing/querying, JSON support, reliability, and broad industry adoption.

## ADR-003: Database Per Service
**Decision:** Each business service owns its database.

**Reason:** Preserves service autonomy and prevents tight coupling.

## ADR-004: Kafka
**Decision:** Use Apache Kafka for domain event distribution.

**Reason:** Durable event streams, replay, partitioning, consumer groups, and strong ecosystem fit for event-driven architecture.

## ADR-005: Keycloak
**Decision:** Use Keycloak rather than implementing a custom identity provider.

**Reason:** Avoids unnecessary security-critical custom authentication infrastructure while providing standards-based OIDC/OAuth2.

## ADR-006: Spring Cloud Gateway
**Decision:** Use Spring Cloud Gateway as the API gateway.

**Reason:** Strong Spring ecosystem integration and sufficient gateway capabilities without introducing unnecessary platform complexity.

## ADR-007: Kubernetes
**Decision:** Target Kubernetes for production orchestration.

**Reason:** Standardized container orchestration, declarative deployment, scaling, health management, and broad ecosystem.

## ADR-008: OpenTelemetry
**Decision:** Use OpenTelemetry for distributed telemetry.

**Reason:** Vendor-neutral instrumentation and portability across observability backends.

## ADR-009: Transactional Outbox
**Decision:** Use the Outbox pattern for reliable database-to-event publication.

**Reason:** Prevents the dual-write problem between local transactions and Kafka publication.

## ADR-010: No Eureka
**Decision:** Do not introduce Eureka.

**Reason:** Kubernetes already provides service discovery. Adding another registry increases operational complexity without a current requirement.

## ADR-011: No Shared Domain Database
**Decision:** Services must not directly query each other's databases.

**Reason:** Direct database coupling destroys service ownership boundaries.

## ADR-012: No Elasticsearch Initially
**Decision:** Do not introduce a dedicated search engine until product requirements justify it.

**Reason:** Avoid infrastructure complexity before search scale/features require it.

## ADR-013: CUSTOMER-or-SERVICE Trust Boundary
**Decision:** Endpoints used both by end users and by the checkout orchestrator
(`POST /api/v1/orders`, `POST /api/v1/orders/{id}/cancel`, `POST /api/v1/payments`)
accept either a user token or a service token:

- **CUSTOMER / ADMIN principal** — identity is derived from the JWT `sub`
  claim; a client-supplied `customerId` that does not match the subject is
  rejected (403). Object-level authorization is enforced in the owning service.
- **SERVICE principal** (client credentials) — the request-supplied
  `customerId` is trusted because the orchestrator already validated the end
  user's token before starting the saga.

**Reason:** The checkout saga must act on behalf of a user without holding the
user's token. Routing the orchestrator's calls through user impersonation
would require token exchange and weaken auditability; trusting the
orchestrator keeps the trust boundary small and explicit. Phase 5 replaces
these synchronous calls with events, at which point the same boundary applies
to the producer of the event stream.

**Status:** Accepted, Phase 4.

## ADR-014: Distributed Tracing and the Correlation Id

**Decision:** Instrument every service with the OpenTelemetry Java agent
(bytecode instrumentation), exporting OTLP to an OpenTelemetry Collector which
forwards to Jaeger. Keep the edge `X-Correlation-Id` as a client-facing support
id, propagated on service-to-service calls; do not replace it with the trace id.

**Context.** A request crosses seven services and a saga. Before this, request
correlation was impossible rather than merely difficult: the gateway set
`X-Correlation-Id`, but `RestClients` did not forward it and no service read it
or logged it.

**Why the Java agent, not the Spring Boot starter or Micrometer Tracing.**
OpenTelemetry's own guidance is that the Java agent is the default choice for
Spring Boot: widest out-of-the-box coverage, no application code. That matters
concretely here — it instruments Apache HttpClient 5 (so W3C `traceparent`
propagates across `RestClients` for free) and Logback (so `trace_id`/`span_id`
reach every log line), which are exactly the two gaps this phase had to close.
Micrometer Tracing would be the more idiomatic Spring choice and unifies better
with Micrometer metrics in Phase 8; it is the fallback if the agent's startup
cost or an interaction with another agent becomes a problem. Manual
instrumentation is used only for the business span (`checkout.saga`), so the
mechanism is understood rather than merely trusted.

**Why a collector tier.** Applications talk to the collector, never to a
tracing backend. That keeps the backend swappable (Phase 8 replaces Jaeger with
Tempo without touching a service) and puts central concerns — sampling,
redaction, batching, retry — in one place.

**Why the correlation id survives.** A trace id is internal and unusable in a
support conversation; a correlation id is quotable and already echoed to
clients. The two are complementary: `traceparent` does propagation, while the
correlation id is attached to the MDC and as a span attribute
(`ecommerce.correlation_id`) so both logs and traces are searchable by it. That
requires `RestClients` to forward the header — the fix that made one id cover
the whole saga.

**Consequences.**
- `spring-boot:run` is instrumented; unit and integration tests are not, so
  tests stay fast and need no collector.
- The agent version is pinned in the parent POM and the jar fetched into a
  gitignored `otel/` directory; a clean clone needs a root build before running
  a single service with `-pl`.
- The gateway does not depend on `common` (servlet Spring Web must not reach a
  reactive app), so the two correlation header constants are duplicated with a
  sync comment.
- Development OTLP is plaintext and the collector binds to localhost. TLS and
  authentication between services and the collector belong to Phases 9-10.
- No customer id is placed on spans: it is a pseudonymous personal identifier
  (doc 08 §3).

**Status:** Accepted, Phase 5.
