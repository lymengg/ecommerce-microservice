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

## ADR-017: Resilience4j in `common`, Per Dependency, Not at the Edge

**Decision.** Implement timeouts, circuit breakers, retries and bulkheads with
**Resilience4j** (`resilience4j-circuitbreaker`, `-retry`, `-bulkhead`) wired by
an **auto-configuration in `common`**, and applied to every cross-service client
through `RestClients`. One breaker, retry and bulkhead instance **per logical
dependency** (`catalog`, `cart`, `inventory`, `order`, `payment`), resolved from
`ecommerce.resilience.*`. The **gateway gets timeouts only** — no retry filter
and no circuit breaker.

**Why Resilience4j, not Spring Retry.** Spring Retry does retry and backoff well
and nothing else; this phase needs a circuit breaker and a bulkhead as well, and
mixing two libraries for one policy is worse than one library for three.
Resilience4j is the industry standard for the whole set, is the implementation
behind Spring Cloud CircuitBreaker, and exposes its state as Micrometer metrics —
which is exactly the signal Phase 8 is asked to surface (doc 08 §4: breaker
state, retry counts).

**Why not `resilience4j-spring-boot3` (or Spring Cloud CircuitBreaker).** Both
bind a *global* `resilience4j.*` namespace and build one registry per type. This
project needs per-dependency instances driven from `ecommerce.resilience.*`, and
it needs them to exist in **checkout-service**, which scans only
`com.ecommerce.checkout` plus two `common` packages. The starter's annotations
(`@CircuitBreaker`) are AOP-based and would be silently inert wherever the scan
does not reach — the same trap that left checkout unsecured before Phase 4. So
the core modules are used directly and the registries are built explicitly. The
starter remains a reasonable choice for a codebase that already has Actuator and
a conventional package layout; it is the wrong fit here.

**Why an auto-configuration, not `@Component`s.** Same reason as
`TracingAutoConfiguration` and `MessagingAutoConfiguration`: whether a service
has circuit breakers must not depend on which packages it happens to scan. The
decorators are applied by `RestClients`, so a service cannot forget them either —
the policy is part of building a client, not something each client opts into.

**Why per dependency, not global.** A single global breaker is the classic
mistake: one failing dependency opens the circuit for *every* dependency, so
payment being down would stop inventory reads. `ResilientRestClientTest` asserts
the separation directly, and the per-dependency name is a shared constant
(`Dependencies`) so the name a client registers under and the name in
configuration cannot drift.

**Why not at the edge.** The gateway is reactive and cannot share `common`'s
servlet configuration, so it could only ever have a *second*, differently
configured copy of the policy. More importantly, retrying at the edge would
double-retry the same request at two layers: a retried checkout is a retried *set*
of saga calls, and the inner layer already retried the idempotent ones. The edge
therefore gets what only it can provide — a bound on how long a client waits
(`response-timeout`, ADR-018) — and the service boundary keeps the
per-dependency policy.

**Failure classification.** Everything that means "the dependency could not
answer" — connection failure, timeout, pool or bulkhead exhaustion, open breaker,
and a 5xx that survived the retries — becomes `ServiceUnavailableException`,
rendered as RFC 9457 `503`. A 4xx is left alone: it is a business answer and still
travels the existing `RemoteExceptionMapper` path. This classification is what
lets the saga distinguish "the system could not answer, clean up" from "the
request was wrong".

**Consequences.**
- `resilience4j-micrometer` is an *optional* dependency of `common`, bound to a
  `MeterRegistry` only if one exists. No service has Actuator yet, so the bean is
  dormant and Phase 8 activates it by adding the dependency — no code change.
- Breakers are per-service instances in the Spring context, so **integration
  tests must reset them between tests**; a test that deliberately drives a
  dependency to 5xx otherwise leaves the breaker OPEN for the next test class.
  Both IT bases do this in `@AfterEach`, and it cost a debugging cycle to learn.
- Retries interact with idempotency, which is its own decision: ADR-019.

**Status:** Accepted, Phase 7.

## ADR-018: The Timeout Budget

**Decision.** Every HTTP call has connect, connection-request and response
timeouts; a checkout saga additionally has a **wall-clock deadline**
(`ecommerce.resilience.saga-budget`, default 10 s) enforced by `Deadline`. Each
request's response timeout is capped at the *remaining* budget, and the saga
refuses to start a step once the budget is spent, compensating instead. The
gateway's response timeout (15 s) is the outermost bound.

**The arithmetic, and where the numbers come from.**

| Bound | Value | Why |
|---|---|---|
| connect timeout | 300 ms | same host, same network; a TCP handshake that takes longer is an outage |
| connection-request timeout | 300 ms | bounds the wait for a pooled connection, which is what turns pool exhaustion into a fast typed failure |
| response timeout (default) | 1.5 s | the slowest legitimate call, plus headroom |
| — `catalog` | 1 s | a single indexed read, called once per order line |
| — `cart` | 1 s | one read, one state write |
| — `inventory` | 1.5 s | an atomic stock update that may contend |
| — `order` | 3 s | creating an order re-prices **every line from catalog**, so catalog's budget nests inside this one |
| — `payment` | 3 s | initiation reads the order and calls the provider |
| retry attempts | 3 | try, retry twice |
| retry backoff | 100 ms → ×2 → 500 ms max, 0.5 jitter | bounded, and jittered so a fleet that failed together does not retry together |
| saga budget | 10 s | ~7 calls; a healthy saga is well under 1 s |
| gateway response timeout | 15 s | must exceed the saga budget so a saga that is legitimately compensating is not cut off at the edge |

The gotcha doc 13 predicts is real and is why this is written down: **a 3 s read
timeout with 3 retries is already 9 s**, and six such calls is 54 s — far outside
any budget a user would tolerate. Two things keep it bounded: the breaker opens
after a handful of failures (so later calls fail fast rather than burning their
full timeout), and the deadline caps each call at whatever is left.

**Why a deadline and not only per-call limits.** Per-call limits cannot bound a
saga — they bound each step independently, and the sum is unbounded. A
`TimeLimiter` would be the idiomatic Resilience4j answer, but it only works on
`CompletionStage`/async work; the saga is a blocking servlet flow, and wrapping it
in a thread pool would move the thread pile-up rather than remove it. So the
budget is an explicit deadline on the request thread, which is the honest
mechanism for blocking code: `Deadline` is set once at the start of the saga,
`ResilienceRequestInterceptor` refuses to start a call when it is spent, and
`BudgetedHttpComponentsClientHttpRequestFactory` caps the response timeout at the
remaining time so the last call cannot overrun by a whole socket timeout.

**Nesting is the subtle part.** Timeouts compose: `checkout → payment` (3 s) must
exceed `payment → order` (3 s), which must exceed `order → catalog` (1 s, once per
line). A caller's timeout shorter than its callee's worst case turns a slow-but-
successful call into a false timeout, which is worse than a slow success — the
saga then compensates an order that was about to be fine. The per-dependency
values above are ordered deliberately for that reason, and this is the place that
ordering is recorded.

**Consequences.**
- Work without a deadline (the reconciliation job, startup calls) gets the
  per-call timeouts only. That is correct: those are not user-facing and should
  not inherit a user's patience.
- The deadline is a `ThreadLocal`, so it does not survive a thread hop. That is
  acceptable today because the saga is synchronous; an async saga would need the
  budget passed explicitly.
- A budget expiry mid-saga triggers **compensation**, not a silent truncation.

**Status:** Accepted, Phase 7.

## ADR-019: Retry Safety, and Making the Inventory Reservation Idempotent

**Decision.** Retries are **opt-in per request**, not per service. The default is
conservative: safe methods (`GET`/`HEAD`/`OPTIONS`) retry, everything else does
not unless the client asserts the operation is idempotent with
`Retryable.yes(...)`. A 4xx is never retried; connection failures, timeouts and
5xx are. And `POST /internal/api/v1/inventory/reservations` **was made
idempotent** rather than exempted from retries.

**Why per request, not per service.** Retry safety is a property of the
*operation*, not of the service it lives on. `GET /api/v1/cart/{id}/lines` is free
to retry; `POST /api/v1/orders/{id}/pending` is a state transition that would be
answered `409` on a repeat, so retrying it converts a slow call into a failure.
Both live in services whose other calls *are* retryable. The HTTP method alone
cannot express the difference either — order creation and payment initiation are
POSTs that must be retried because they carry an `Idempotency-Key`, while the
transition is a POST that must not be.

So the client declares it, at the call site, and the declaration is reviewable:

| Call | Retried? | Why |
|---|---|---|
| `GET` catalog product | yes (default) | pure read |
| `GET` cart lines | yes (default) | pure read |
| `POST` order creation | yes | `Idempotency-Key`; a replay returns the existing order |
| `POST` payment initiation | yes | `Idempotency-Key`; a replay returns the existing payment |
| `POST` inventory reserve | yes | made idempotent — see below |
| `POST` inventory release-by-order | yes | a no-op when nothing is still RESERVED |
| `POST` order pending / payment-pending | no | a state transition; a repeat is a `409` |
| `POST` order cancel | no | a state transition |
| `POST` cart checkout (close cart) | no | a state transition |

**The inventory-reservation question.** Reserving stock was the one call in the
system that could not be retried: it had no idempotency key, so a retry after a
lost response would reserve the same stock twice and hold inventory that no order
would ever release. Two options were open — add an `Idempotency-Key` header, or
exempt it from retries. It was made idempotent, but **not with a client-supplied
key**: the key is the natural one, `(orderId, productId)`, enforced by a partial
unique index while the reservation is `RESERVED`. The reasoning:

- One reservation per order line *is* the business rule, so the natural key needs
  no new header and no client cooperation.
- A client-supplied key can be defeated by the client — send a different key on
  the retry and the guarantee is gone. The natural key cannot be defeated by
  accident.
- It is partial (`WHERE status = 'RESERVED'`) because a later saga attempt, or a
  reconciliation repair, may legitimately reserve the same line again after the
  first was released, committed or expired.

`reserve` therefore looks up the live reservation for the pair first and returns
it unchanged; the unique index is the backstop for two genuinely concurrent
attempts. This is what lets the saga treat a retried reserve as free, which in
turn is what makes the reconciliation job's repair path safe.

**Why 4xx is never retried.** A `400`, `403` or `409` will not fix itself.
Retrying it multiplies load on a dependency that is answering correctly, which is
the retry amplification this phase exists to prevent.

**Why jitter.** Three attempts with a fixed 100 ms backoff, issued by every caller
that failed at the same instant, arrive at the same instant — a self-inflicted
thundering herd. The jitter (0.5) spreads them.

**Consequences.**
- `Retryable` markers are assertions about idempotency. If one is wrong, the
  failure mode is a duplicate side effect, so they deserve review attention; the
  table above is the record.
- Retries sit **outside** the circuit breaker, so each attempt must re-acquire a
  bulkhead permit and pass the breaker. A retry storm therefore cannot bypass the
  guards that exist to stop it — but it does mean one logical call contributes up
  to three results to the breaker's window, which opens it sooner. That is
  intended: a dependency that fails three attempts in a row is failing.

**Status:** Accepted, Phase 7.

## ADR-020: Reconciliation of Orders Stranded by a Partial Saga Failure

**Decision.** A scheduled job in **order-service** — the service that owns order
state — finds orders left in `PENDING` or `PAYMENT_PENDING` past a staleness
threshold, asks **payment-service** and **inventory-service** for the
authoritative state over REST, and then either **completes** the order (payment
succeeded → mark PAID, which re-emits `OrderConfirmed` and commits the
reservations through the normal path) or **compensates** it (release the
reservations that are still RESERVED, cancel the order). It is bounded by a batch
cap, a per-order backoff, and an attempt budget that ends in a terminal
`NEEDS_ATTENTION` state.

**Why this exists at all.** Doc 13 §3 is blunt about it: "This is the part
everyone skips and production punishes." A circuit breaker without reconciliation
just fails faster. Phase 7 adds timeouts and breakers so the saga stops *hanging*
— but the saga can still fail after reserving stock and before a payment exists,
and if the process dies mid-saga (or compensation itself fails because the
dependency is still down), nothing in the system would ever notice. The order sits
in `PENDING` forever and the stock reservation quietly expires at its TTL. Phase
6's outbox guarantees events are not lost; it says nothing about a saga that never
finished.

**Which states are "stuck".** `PENDING` and `PAYMENT_PENDING` only. `DRAFT` is
transient and harmless (no reservation can exist yet). `PAID` and beyond are
terminal for this purpose. The threshold (`stale-after`, default 10 min) is
deliberately **below inventory's reservation TTL** (30 min): if the job could not
release stock before the TTL expired it would be repairing an order whose stock
had already been released by expiry, which is a different and worse situation.

**Where the truth comes from.** Order-service owns the order, but payment state
belongs to payment-service and reservation state to inventory-service. The job
asks each owner over REST (`GET /internal/api/v1/payments/by-order/{id}`,
`GET /internal/api/v1/inventory/reservations?orderId=`) rather than joining across
databases (ADR-003, ADR-011). Both endpoints are new, read-only, and SERVICE-only;
the job runs as a service principal, which is the same trust boundary as the
orchestrator (ADR-013).

**Single-flight, and why not just a lock.** Claiming is
`SELECT ... FOR UPDATE SKIP LOCKED` — each row goes to exactly one claimer and
rows another instance is locking are skipped rather than waited for — and then a
**lease**: the claiming transaction pushes `next_reconciliation_at` into the
future, so two instances cannot work the same order. A plain row lock cannot be
the whole answer, because the repair makes REST calls and must not hold a database
lock for the duration of a network round trip. The lease is written by bulk update
rather than through the entity on purpose: touching the entity fires `@PreUpdate`,
which would move `updated_at` — the staleness clock the claim query reads — and
push the order out of the candidate window for another full threshold after every
pass. (That was a real bug, found by the attempt-budget test.)

**Complete or compensate.** The rule is derived from the payment state:

- payment `SUCCEEDED` → **complete**. Marking PAID re-emits `OrderConfirmed`, so
  the inventory commit happens through the same choreography as the normal flow
  instead of a second, parallel mechanism.
- payment absent, `FAILED`, `CANCELLED`, or refunded → **compensate**: release
  what is still RESERVED, then cancel.
- payment still in flight (`PENDING`, `PROCESSING`, `REFUND_PENDING`) → **defer**.
  Compensating here could destroy a paid order; looking again later costs nothing.
- A dependency that cannot be reached → **defer** with backoff.

**Bounded, and terminal.** Batch size caps one pass; a per-order backoff
(`retry-backoff`, growing linearly with attempts) keeps a struggling dependency
from being hammered; after `max-attempts` (5) the order moves to
`NEEDS_ATTENTION`, which is terminal and therefore *visible* — the alternative is
an order that cycles through the job forever and is noticed by nobody. An operator
resolves it by cancelling it through the normal ADMIN path, which is why
`NEEDS_ATTENTION → CANCELLED` is the one transition out of that state.

**Idempotent, and safe alongside the normal flow.** Completion is a no-op when the
order is already PAID; compensation only releases reservations that are still
RESERVED, and cancelling an already-cancelled order is a no-op. Running the job
while a saga is in progress simply finds nothing to do, because the order is
younger than the threshold.

**A race it accepts.** A payment could succeed *after* the job decided to
compensate — a webhook landing late, say. The reservation TTL bounds the damage
and the stale threshold makes it unlikely, but it is a real distributed-systems
race, not something a lock can remove. The mitigation is that the job only
compensates on a *settled* payment that is not SUCCEEDED, and the race is
documented here rather than discovered later.

**Observability.** Each pass logs a summary line
(`claimed/completed/compensated/deferred`) and each repair logs its outcome, which
is what Loki will pick up in Phase 8; the counters Phase 8 exports come from the
same call sites.

**Status:** Accepted, Phase 7.
