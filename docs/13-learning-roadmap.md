# E-Commerce Platform — Learning Roadmap (Revised)

Supersedes `docs/11-implementation-roadmap.md` as the **sequencing and learning
plan**. Doc 11 stays the original capability list ("what exists in a
production-grade platform"); this document is "in what order to learn it, and
how to make it stick".

**Audience:** a solo learner whose goal is to learn microservices with
industry-standard tooling and best practices, deliberately using technologies
never worked with before.

## 1. Learning protocol — "break it first"

Every phase runs the same five steps. **Step 1 is not optional.**

1. **Reproduce the failure** the pattern exists to prevent — naive
   implementation, no pattern.
2. **Measure the damage** — lost/duplicated records, threads stuck, minutes
   spent debugging, requests dropped.
3. **Implement the pattern** — the standard industry solution.
4. **Prove the failure is gone** — re-run the *exact* scenario from step 1.
5. **Record the gotcha** — append to `PROGRESS.md` → "Environment gotchas";
   write an ADR if a real decision was made.

**Why:** Phases 1–4 produced 12 specification documents and ADRs 004–013
*decided before implementation*. That is efficient, but implementing a pattern
you were told to use teaches the shape of the solution, not the problem it
solves. Best practice is at least as much about **knowing when not to** apply a
pattern — which you can only learn by seeing what breaks without it.

**Rule:** if you cannot articulate what breaks in the absence of a pattern, you
have not learned it yet.

**Also:** every phase ends with a new "Environment gotchas" entry. That list in
`PROGRESS.md` is the highest-value artifact in this repository — it is the only
place recording knowledge that tutorials cannot give you.

## 2. Revised sequence

| Revised | Phase | Was | Change | Why |
|---|---|---|---|---|
| ✅ | 1. Foundation | 1 | — | done |
| ✅ | 2. Modular monolith | 2 | — | done |
| ✅ | 3. Service extraction | 3 | — | done |
| ✅ | 4. Security (Keycloak/OAuth2) | 4 | — | done |
| ✅ | 5. Observability — tracing only | 7 | pulled forward, narrowed | you cannot debug async flows without traces |
| ✅ | 6. Kafka & event-driven | 5 | expanded | + CDC alternative, choreography, reconciliation |
| ✅ | 7. Resilience | 6 | reordered after 6 | consumer resilience needs Kafka to exist |
| **8** | **Observability — metrics/logs/alerts** | 7 | remainder | dashboards are only meaningful once there is async load |
| **9** | **Containerization** | 8 | reframed | you already use Docker — this phase is *productionizing* it |
| **10** | **Kubernetes + IaC** | 9 | time-boxed, +Terraform +secrets | add the two things doc 09 asks for but the roadmap omitted |
| **11** | **CI/CD + contract testing** | 10 | expanded | contract testing is named in doc 10 but no phase delivered it |
| **12** | **Production hardening** | 11 | — | unchanged in intent |

### Why tracing moved ahead of Kafka

You already run 7 services plus a REST saga. Phase 6 introduces asynchronous,
out-of-order, at-least-once delivery on top of that. Diagnosing a lost or
duplicated event by grepping logs across seven services is genuinely
infeasible — the failure mode is invisible without trace context. Tracing is
cheap to add now (doc 08 §2 already specifies the propagation rules) and pays
for itself in every phase that follows.

### Why observability is split in two

Doc 08's three pillars have very different dependencies. **Traces** are needed
*before* Kafka. **Metrics, dashboards and alerts** (doc 08 §4, §6, §7) only
become meaningful once there is asynchronous load and consumer lag to watch —
dashboards over a synchronous REST system teach you Grafana, not observability.
Splitting avoids building dashboards twice.

## 3. Phase detail

### Phase 5 — Observability: tracing

**Break it first.** Introduce a failure in one service in the middle of the
checkout saga (e.g. payment returns 500 for one product). Diagnose it using
only `docker` logs / log files. **Time yourself.** Record the number.

**Learn.** W3C Trace Context, OpenTelemetry SDK vs agent, spans and parent/child
relationships, trace propagation over HTTP headers, MDC log correlation,
sampling, OTLP.

**Implement.** OTel SDK (or Java agent) in every service + gateway; OTLP export
to Tempo/Jaeger; structured JSON logs (doc 08 §5) with `traceId`/`spanId`;
propagate the gateway's existing correlation id alongside trace context; never
log tokens (already a Phase 4 rule).

**Verify.** Re-run step 1 and find the failing service from a single trace ID.
Compare the time. A trace must span gateway → checkout → order → inventory →
payment.

**Gotcha to capture.** Whatever surprises you about agent-vs-SDK, or trace
context loss across a client library.

### Phase 6 — Kafka & event-driven

This is the largest phase. Run it as four sub-steps, each with its own
break-it-first cycle.

**6a — Dual-write (the core lesson).**
*Break:* write a naive publisher that commits to Postgres and *then* publishes
to Kafka, as two separate operations. Kill the process between the two. Observe
the lost event. Run it again with the publish first — observe a phantom event
for a transaction that rolled back. Publish twice — observe duplicates.
*Implement:* transactional outbox (ADR-009) — business state + outbox row in one
transaction; a separate publisher drains the outbox. Tables already exist in all
five DB-backed services.
*Verify:* the same kill-the-process scenario now loses nothing. Outbox backlog
returns to zero after recovery.
*Then:* implement the same flow with **Debezium CDC** on the outbox table and
compare — polling vs log-tailing, latency, operational cost. Knowing both is the
real lesson; the industry uses both.

**6b — Consumers.**
*Break:* consume the same event twice; observe double-processing (double stock
commit). Deliver events out of order; observe a state machine violation.
Send a poison message; observe the consumer wedged in an infinite retry loop.
*Implement:* durable consumer idempotency (persist processed event ids, doc 06
§5); domain-oriented topics (doc 06 §2); partition keys chosen for ordering
(order id, payment id, SKU — doc 06 §3); consumer groups; bounded retry with
backoff; dead-letter topic with enough metadata to replay (doc 06 §6–7).
*Verify:* duplicate delivery is a no-op; poison message lands in the DLT and the
consumer keeps running; killing a consumer mid-batch reprocesses safely.
*Note:* doc 06 §12 — Kafka ordering is partition-local. Do not design business
logic around global ordering.

**6c — Replace part of the saga.**
You have an orchestrated REST saga (checkout-service). Convert one flow to
events — for example `PaymentSucceeded` → order → inventory — and run it as
**choreography**. Then compare honestly: which was easier to reason about,
which was easier to debug, which was easier to compensate on failure? Write the
answer as an ADR. This is the orchestration-vs-choreography lesson, and it is
only learnable by doing both.
*Note:* ADR-013 already anticipates this — "Phase 5 replaces these synchronous
calls with events, at which point the same boundary applies to the producer of
the event stream."

**6d — Schema evolution.**
*Break:* add a required field to an event and watch an old consumer fail.
*Implement:* additive-only changes, versioned events, tolerant readers (doc 06
§9).

**Testcontainers Kafka** per doc 10 §3 — do not mock the broker.

**Gotcha to capture.** Everything: consumer group rebalancing, offset
semantics, serialization, DLT metadata.

### Phase 7 — Resilience ✅ DONE

> **Implemented (2026-09-22).** Timeouts with a saga-wide deadline (ADR-018),
> per-dependency Resilience4j circuit breakers, bounded jittered retries with an
> explicit per-call retry policy (ADR-019), bulkheads + bounded pools
> (ADR-017), and the reconciliation job (ADR-020). The break-first baseline was
> skipped by request; the failure modes are instead pinned by
> `ResilientRestClientTest` (common), `CheckoutFailureInjectionIT`,
> `OrderReconciliationIT`, `PaymentFailureIT` and `DatabaseOutageIT`. See
> `PROGRESS.md` for the gotchas found on the way.

**Break it first.** Inject latency into one downstream service (add a sleep).
Observe threads piling up in the caller, connection pools exhausting, and the
failure cascading upstream to unrelated endpoints. Then make it fail
intermittently and observe retry storms.

**Learn.** Timeout budgets, retry with exponential backoff + jitter, circuit
breaker states, bulkheads, fallback/degradation, when *not* to retry (non-
idempotent operations).

**Implement.** Resilience4j across `RestClients` in `common`; connect/read
timeouts on the Apache HttpClient 5 clients; circuit breakers on cross-service
calls; bulkheads so one slow dependency cannot exhaust the pool; idempotency
keys already exist for order/payment creation — use them to make retries safe.

**Verify.** Failure-injection tests from doc 10 §7: payment timeout, payment
provider failure, duplicate webhook, database outage, Kafka outage, service
restart, network timeout, partial saga failure. Each must degrade predictably,
not cascade.

**Do not skip this:** **reconciliation**. After a partial saga failure, how do
you *find* the inconsistent orders and *fix* them? Design the reconciliation
job. This is the part everyone skips and production punishes.

**Gotcha to capture.** Timeout interactions (a 3s read timeout inside a 5s saga
budget), retry amplification.

### Phase 8 — Observability: metrics, logs, alerts

**Break it first.** Define an SLO for checkout (e.g. p95 < 2s, error rate
< 1%). Then degrade the system and watch it burn — with no alert configured.

**Implement.** Micrometer → Prometheus; the metric set in doc 08 §4 (RED for
services, plus JVM, DB pool, **Kafka consumer lag** and **outbox backlog** —
the two that matter most now); Grafana dashboards per doc 08 §6; Loki for
logs; alert rules on *symptoms* not exceptions (doc 08 §7); liveness/readiness
distinguishing (doc 08 §8).

**Note.** Doc 08 §9 — audit logs are business/security records, operational
logs are for diagnosis. Do not substitute one for the other.

**Verify.** Break something and let the alert fire. An alert that has never
fired is untested.

### Phase 9 — Containerization (productionize, not learn Docker)

You have used Docker Compose and Testcontainers since Phase 1. This phase is
about **production-grade images**, not about learning Docker.

**Break it first.** Run the current services in a naive container: root user,
full JDK base image, no memory limit. Observe the image size, and observe the
JVM ignoring the container memory limit.

**Implement.** Multi-stage builds; minimal/distroless base; non-root;
read-only filesystem; dropped capabilities; resource limits; JVM container
awareness (`-XX:MaxRAMPercentage`); image scanning with Trivy; SBOM
generation; a full `docker-compose` local environment including Kafka, Redis
and Keycloak (doc 09 §1, §14).

**Verify.** Image size before/after; `trivy` clean; container runs as non-root;
JVM heap respects the limit.

### Phase 10 — Kubernetes + IaC (time-boxed)

**Time-box this explicitly.** The goal is to *understand what Kubernetes
solves*, not to become a platform engineer. If your goal is understanding
microservices, this phase has the lowest learning-per-hour of anything on the
list. Cap it, and treat the remainder as a survey.

**Break it first.** Scale a service to 3 replicas with an in-memory or
non-shared resource and watch state diverge. Kill a pod mid-request and watch
traffic drop. Observe a service that never becomes ready.

**Implement.** Helm charts per service (doc 09 §3): Deployment, Service,
ConfigMap, Secret references, ServiceAccount, probes, requests/limits, HPA.
Ingress / load balancer per doc 09 §5. Local cluster (kind or minikube).

**Two things the original roadmap omitted but doc 09 asks for:**
- **Secrets management** (doc 09 §4) — Vault or External Secrets Operator.
  "Don't hardcode secrets" is not a strategy; this is the phase where it
  becomes one.
- **IaC / Terraform** (doc 09 §13) — even a minimal module for one cloud
  resource teaches the discipline.

**Deferred from doc 09 §6:** mTLS / zero-trust internal transport. Note it as
a known gap rather than pretending the current token-relay model is
zero-trust.

### Phase 11 — CI/CD + contract testing

**Break it first.** Change a REST response field that a caller depends on and
let it reach the main branch. Observe the breakage discovered at integration
time (or in production).

**Implement — pipeline** (doc 09 §11): build → unit → integration → SAST +
dependency scan → image build → container scan → registry → staging deploy →
smoke tests → production approval → production deploy. You already have the
unit/integration split in `.github/workflows/ci.yml`.

**Implement — contract testing** (doc 10 §5, currently unassigned to any
phase): consumer-driven contracts for **REST and Kafka events** with Pact or
Spring Cloud Contract. Breaking contract changes must fail CI. This is a
genuinely valued industry skill and a real gap in the original roadmap.

**Then:** rolling deployments first, blue/green or canary only when justified
(doc 09 §12).

### Phase 12 — Production hardening

Load tests with k6 (doc 10 §8) measuring p50/p95/p99, throughput, error rate,
resource utilization. Chaos/failure tests. Security assessment: DAST with
OWASP ZAP, SonarQube, dependency and secret scanning (doc 10 §9). Backup and
**restore drills** — doc 09 §15: "Backups are not considered valid until
restore procedures are tested." Runbooks, SLOs/SLIs, cost review.

## 4. Coverage vs the original roadmap

Kept as-is: Kafka, resilience, observability, containerization, Kubernetes,
CI/CD, hardening.

Added because industry values them and doc 10 / doc 09 already ask for them:

| Added | Fits | Source |
|---|---|---|
| Contract testing (REST + Kafka) | Phase 11 | doc 10 §5 |
| CDC / Debezium (outbox alternative) | Phase 6a | — |
| Choreography vs orchestration | Phase 6c | ADR-013 |
| Reconciliation after partial failure | Phase 7 | — |
| Secrets management | Phase 10 | doc 09 §4 |
| Terraform / IaC | Phase 10 | doc 09 §13 |
| Trace-context propagation over Kafka | Phases 5, 6 | doc 08 §2 |
| SBOM + container scanning | Phase 9 | doc 09 §14 |

Deliberately deferred: mTLS / zero-trust internal transport (doc 09 §6).

## 5. Awareness track — know these exist, don't build them

Not required for the goal. Read one article each so you can hold a
conversation; skip the implementation.

- **gRPC** — internal service-to-service. This project is REST-only internally;
  many shops use gRPC internally and REST at the edge.
- **RabbitMQ / cloud messaging (SQS, SNS, Pub/Sub)** — the real lesson is
  *when not to use Kafka*. Kafka is not the default answer to "I need async".
- **CQRS / read models** — the consequence of database-per-service that nobody
  warns you about until reporting gets hard.
- **Event sourcing** — distinct from event-driven; different trade-offs.
- **Service mesh (Istio/Linkerd)** — where mTLS and traffic policy usually
  actually live.

Also name the limit honestly: this is **one coherent vertical stack** (Java /
Spring / Kafka / K8s). That is the right call for depth, but industry is
messier — Go and Node services, RabbitMQ, serverless. You are learning a slice,
not the space.

## 6. Definition of done, per phase

A phase is done when all five hold:

1. The step-1 failure has been reproduced and its damage measured.
2. The pattern is implemented using the standard industry tool.
3. The step-1 scenario re-run passes.
4. A failure-mode test exists and is in CI.
5. `PROGRESS.md` has a new gotcha entry, and any real decision has an ADR.

## 7. Expectations

Eleven remaining phases solo is a long road. Momentum typically dies around the
middle, when the work shifts from "new tool" to operational drudgery (Helm
charts, Grafana dashboards). Plan for that: Phases 5–8 are the high-learning
core; Phases 9–12 are professionalization. If time gets short, depth on
Kafka + resilience + observability is worth more than a complete-but-shallow
Kubernetes phase.
