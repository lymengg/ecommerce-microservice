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

## ADR-015: Event Publication — Spring for Apache Kafka and a Polling Outbox Publisher

**Decision.** Publish domain events with **Spring for Apache Kafka**
(`spring-kafka`), using a **polling publisher** that drains the existing
`outbox_events` tables to Kafka and marks `published_at`. Events are JSON in
the versioned envelope of doc 06 §1. Delivery is **at-least-once**; every
consumer is idempotent via a durable `processed_events` table.

**Context.** ADR-009 already chose the transactional outbox and the tables
exist in all five DB-backed services, but nothing drained them — `published_at`
was NULL on every row. This ADR covers the half of the pattern that was missing:
how rows get to the broker, and with which client.

**Why Spring for Apache Kafka, not Spring Cloud Stream.** Spring Cloud Stream
would hide the broker behind a functional binding abstraction, which is a good
trade when you want binder portability (Kafka today, RabbitMQ tomorrow). This
project has committed to Kafka (ADR-004) and is a *learning* exercise in the
event-driven primitives: partitions, keys, consumer groups, offsets, retry and
dead-lettering. `KafkaTemplate` and `@KafkaListener` expose those directly.
Cloud Stream also makes the dead-letter topic, the partition key and the retry
backoff configuration *conventions* rather than code, which is exactly the part
worth seeing. The abstraction is the right answer for a team that wants binder
independence; it is the wrong answer for a project whose goal is to learn Kafka.

**Why JSON with an explicit envelope, not Schema Registry + Avro/Protobuf.**
A schema registry (Avro or Protobuf plus Confluent Schema Registry) buys
**enforced** compatibility: producers and consumers register schemas, and an
incompatible change is rejected at publish time rather than discovered in
production. It also gives compact binary payloads and generated types. It costs
a registry to run and operate, and it constrains the wire format. For this
project the envelope (doc 06 §1) is small, stable and versioned in code
(`eventVersion`), and doc 06 §9 already prescribes additive-only evolution with
tolerant readers — which the envelope enforces with
`@JsonIgnoreProperties(ignoreUnknown = true)`. Schema Registry is the
production-grade upgrade the moment there are many independent producer/consumer
teams; it is not worth a new infrastructure tier here. Recorded as the known
alternative.

**Publisher strategy: polling vs Debezium CDC.** Both are legitimate industry
answers to "get committed outbox rows into Kafka"; the outbox *table* is the
same, only the drain mechanism differs.

| Dimension | Polling publisher (chosen) | Debezium CDC |
|---|---|---|
| Mechanism | in-app `SELECT … FOR UPDATE SKIP LOCKED`, then `send`, then mark | Kafka Connect tails the Postgres WAL (logical replication) |
| Latency | poll interval (here 1 s) | ~sub-second, log-driven |
| Application code | publisher lives in `common`, shared by every producer | none; a Connect cluster + one connector per database |
| Envelope control | full — we build the doc 06 §1 envelope in code | constrained by Debezium's EventRouter SMT; field names and the JSONB `payload` do not map cleanly |
| Multi-instance safety | `FOR UPDATE SKIP LOCKED` — two publishers never take the same row | Connect owns offsets and partition assignment |
| Kafka down | send fails, transaction rolls back, rows stay unpublished and retry | connector down ⇒ the replication slot retains WAL ⇒ disk growth (a classic hazard) |
| Operational cost | none beyond the app | a Connect tier, 5 replication slots/publications, `wal_level=logical`, slot monitoring |
| Testing | plain Testcontainers | needs Connect + Debezium containers; does not fit the existing IT pattern |

Polling was chosen because it needs no new infrastructure, keeps the wire format
under our control (the envelope is part of the contract), is testable with the
existing Testcontainers setup, and its failure mode (rows retained, retried) is
exactly the outbox guarantee. CDC's near-real-time latency and zero publisher
code are real advantages, but they buy less than they cost at this scale. The
honest summary: **Debezium is the better answer when latency matters, the
envelope can be shaped by the connector, and a Kafka Connect tier is already
operated. None of those hold here.**

**Why polling is safe and does not lose events.** Each cycle runs in one
transaction that locks a batch with `FOR UPDATE SKIP LOCKED`, publishes each
row, marks `published_at`, then commits. A send failure throws, the transaction
rolls back, and the rows remain unpublished for the next cycle — so a broker
outage retains rather than loses. Scaling the publisher out cannot double-publish
because a locked row is skipped by the other instance. Delivery is at-least-once
(a crash between send and commit redelivers), which is why consumers are
idempotent (ADR-009's counterpart, doc 06 §4-5).

**Ordering.** Rows are read oldest-first and keyed by aggregate id, so events
for one aggregate share a partition and keep their order. A failed send stops
the cycle instead of skipping ahead. Kafka ordering is partition-local
(doc 06 §12); no business logic assumes global ordering.

**Trace continuity across the async hop.** The OpenTelemetry agent propagates
trace context for Kafka sends made inside an active span, but the publisher runs
on a scheduler thread after the request has finished. The request's W3C
`traceparent` is therefore captured when the event is *recorded* (inside the
request) and stored on the outbox row; the publisher restores it as the current
context just before sending, so the agent injects a child of the original span.
Verified: one checkout is a single Jaeger trace containing both
`payment.events publish` and `payment.events process`.

**Consequences.**
- `spring-kafka` is an *optional* dependency of `common`; only order, payment and
  inventory declare it. Catalog, cart and checkout never open a broker
  connection, and the messaging auto-configuration activates only where the
  starter is present.
- Every DB service gets a `processed_events` table, because the shared
  `ProcessedEvent` entity is scanned by all of them (`ddl-auto: validate`).
- Dead-letter topics use the `<topic>.DLT` naming from doc 06 §6 (Spring Kafka's
  default `-dlt` suffix is overridden), and are declared by `KafkaAdmin`.
- Polling interval, batch size and the publisher on/off switch are configurable
  (`ecommerce.outbox.*`).

**Status:** Accepted, Phase 6.

## ADR-016: Choreography for the PaymentSucceeded → Order → Inventory Leg

**Decision.** Replace the synchronous REST calls the checkout orchestrator made
to *complete* a successful payment — `inventory.commitByOrder` and
`order.markPaid` — with **events**. payment-service already records
`PaymentSucceeded` in its outbox; order-service consumes it, marks the order
PAID and records `OrderConfirmed`; inventory-service consumes `OrderConfirmed`
and commits the reservation. The rest of the saga (cart → order → inventory
reserve → payment initiate, and the failure compensation) stays **orchestrated**
in checkout-service. The checkout request/response contract is unchanged.

**Context.** ADR-013 anticipated this boundary: "Phase 5 replaces these
synchronous calls with events, at which point the same boundary applies to the
producer of the event stream." Doc 03 §8 prefers orchestration for
checkout/order because business visibility and recovery matter; doc 06 §2 and
the roadmap (doc 13 §3, Phase 6c) ask for one flow to be converted so the two
styles can be compared honestly.

**Which was easier to reason about?** *Orchestration*, for the flow as a whole.
The saga is one method: read cart, create order, reserve, pay, complete — and
every failure path is visible in one place. Choreography spreads that flow
across three services and two topics; understanding "what happens after
payment" now requires following `PaymentSucceeded` into order-service and then
`OrderConfirmed` into inventory-service. The orchestrator is the single place a
new engineer reads.

**Which was easier to debug?** *Choreography*, unexpectedly, once tracing was in
place. There is no distributed call stack to reconstruct by hand: the whole
thing is one trace (`payment.events publish` → `payment.events process` →
`order.events publish` → `order.events process`), and each hop's span carries the
same correlation id. With the REST saga, the calls are also one trace, but the
failure handling is interleaved with the happy path in the orchestrator. The
async version separates "did the event arrive" from "what did the handler do",
which the `processed_events` table and the DLT make explicit. Debugging is
easier *provided* the observability is good — which is precisely why Phase 5
(tracing) was pulled ahead of Phase 6.

**Which was easier to compensate on failure?** *Orchestration*, decisively. The
orchestrator can run compensations inline (`releaseByOrder`, `cancel`) the moment
a step fails, and it knows the whole saga's state. Choreography has no owner of
the failure: a compensation must itself be an event-driven reaction, and
deciding "who notices that this payment failed and that reservation must be
released" is genuinely harder. This is why the compensation paths were **left
orchestrated** — moving them to events would trade a clear, testable rollback
for a harder-to-reason-about one, with no benefit here.

**Consequences.**
- Checkout now returns as soon as the payment is accepted; the order and stock
  converge **asynchronously**. `orderStatus: PAID` in the response is the
  checkout *outcome*, not a guarantee the order row is already PAID. The README
  manual flow and the ITs poll for convergence.
- A lost `PaymentSucceeded`/`OrderConfirmed` event leaves the order
  PAYMENT_PENDING and stock reserved until the reservation TTL expires. This is
  the price of eventual consistency; the outbox makes the event loss window
  essentially "Kafka is down and the row is never published", which the outbox
  backlog metric (Phase 8) is designed to surface.
- The `/internal/.../paid` and `commit-by-order` endpoints remain as internal
  APIs but are no longer called by checkout; the unused client methods were
  removed.
- The rule of thumb this produced: **orchestrate flows whose failure you must
  compensate; choreograph broadcast side effects** (notifications, indexing,
  analytics) **and low-coupling propagation** where no one owns the whole flow.

**Status:** Accepted, Phase 6.
