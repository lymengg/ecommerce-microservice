# Phase 8 — Step 1: the observability baseline

**Purpose.** Doc 13 §3 asks for an SLO to be defined *and then the system
degraded, to watch it burn with no alert configured*. This is that measurement.
The "after" column is filled in by re-running the same degradation once the
metrics, dashboards and alert rules exist — the point is to prove that something
notices, not to admire a dashboard.

The measurement was taken on 2026-09-22 against the full local stack, with
`scripts/checkout-load.sh` (added by this phase, because the protocol needs a
repeatable driver rather than a session transcript).

---

## 1. The SLO

| Signal | Objective | Why this one |
|---|---|---|
| checkout latency | **p95 < 2 s** | the saga is ~7 synchronous hops; a healthy one is ~0.2 s, so 2 s is a 10× allowance that still catches a real regression |
| checkout error rate | **< 1 %** | a user-visible failure rate; business rejections (a declined payment, `409`) are *outcomes*, not errors, and are counted separately |

Both are **symptoms**. Nothing in the SLO says "payment-service is slow" — the
alert rules in doc 08 §7 are written the same way, because a rule that fires on
a cause fires on the wrong thing as soon as the architecture changes.

## 2. How the system was degraded

`ecommerce.payment.provider-delay` (added by this phase) makes the mock provider
sleep before answering. It lives in `MockPaymentGateway` because simulating
provider behaviour is what a mock provider is for, and it degrades the system
*realistically* — a slow dependency, not a stopped one. A stopped dependency is
easy; a dependency that answers in 2 s instead of 200 ms is the case that
actually reaches production, and it is invisible to every "is it up?" check.

Measured with `scripts/checkout-load.sh`, 20–30 sequential checkouts per
scenario, gateway checkout rate limit raised to 1000/min so the numbers are the
system's and not the rate limiter's.

## 3. What the damage was

| Scenario | p50 | p95 | Error rate | Inside the SLO? |
|---|---|---|---|---|
| healthy | 202 ms | 333 ms | 0 % | ✅ |
| provider delay 1.2 s | 1360 ms | 1391 ms | 0 % | ✅ but **4× worse** |
| provider delay 2.0 s | 2183 ms | 2225 ms | 0 % | ❌ latency breached |
| provider delay 6.0 s | 89 ms | 6568 ms | 75 % | ❌ both |
| provider delay 2.0 s, Phase 7 breaker defaults | 105 ms | 2225 ms | **45 %** | ❌ both |

Three things are worth reading off that table.

**1. A 4× latency regression is completely silent.** At 1.2 s the system is still
inside its SLO, so no alert *should* fire — but nothing anywhere recorded that
p95 had moved from 333 ms to 1391 ms. That is the difference between "in SLO"
and "healthy", and only a time series can tell you which you are looking at.

**2. At 6 s the failure mode is two-shaped, and p50 lies about it.** p50 is 89 ms
because the circuit breaker opened and was *rejecting* requests immediately,
while the requests that did get through took 6.5 s. A latency dashboard that
plots an average would have shown a system getting *faster* while 75 % of
checkouts failed. This is why the alert rules below are written on percentiles
and rates, never on means.

**3. A Phase 7 bug that only a latency measurement could find.** *(Fixed in this
phase — see §5 and §6.)* The 45 % row is
not a provider delay effect — it is `ecommerce.resilience.circuit-breaker`'s
slow-call detection. `slowCallDurationThreshold` defaults to 2 s and
`slowCallRateThreshold` to 100, and 100 % is *reachable*: when every call is
slower than 2 s the breaker opens, so a dependency that is slow but **answering
correctly** gets converted into an error. A legitimate 2.2 s call is well inside
its own 3 s timeout and well inside the 10 s saga budget, and the breaker trips
anyway. The Phase 7 code comments claimed 100 meant "off" — they were wrong, and
the last row is the proof. Fixed in this phase (see §5).

## 4. What noticed

**Nothing.** Concretely, at the moment of the measurement:

- no service exposed a single metric — none had Actuator or Micrometer on the
  classpath, so there was no `/actuator/prometheus` to scrape and no time series
  of anything;
- there was no Prometheus, Grafana or Loki in the stack, so there was nowhere to
  put a metric even if one existed;
- no alert rule existed anywhere, so nothing could have fired;
- the only evidence available was the 7 log files, read by hand, and the
  correlation-id/trace path from Phase 5.

The 45 % row was diagnosed only because a `WARN` line happened to say
`Circuit breaker 'payment' transitioned CLOSED -> OPEN`. That is a log line
standing in for a metric — which is exactly the gap doc 08 §9 warns about when
it separates operational logs (for diagnosis) from metrics (for detection).
After this phase the same fact is a series, and it has an alert on it.

**Detection time without the probe: unbounded.** There is no mechanism that
would ever have noticed.

## 5. Fixes this baseline forced

- **Slow-call detection no longer mis-fires.** The default
  `slowCallDurationThreshold` is raised above every per-dependency response
  timeout, so a call that *succeeded* is never counted as slow, and the knob is
  documented as something to set deliberately per dependency rather than
  inherited. A latency regression should page a human (a symptom alert), not
  trip a breaker that then denies service to a working dependency.
  `ResilientRestClientTest` pins both behaviours: the old one (a slow-but-
  successful call opening the breaker) and the new default.
- The measurement itself is now a committed tool (`scripts/checkout-load.sh`),
  so the "after" column below is reproducible by anyone.

## 6. Re-run after the phase (step 4) — measured

The same degradations, re-run with the observability stack up. This is the point
of the exercise: the numbers below are the *same* failures as §3, and this time
something noticed.

| Scenario | p95 | Error rate | What fired |
|---|---|---|---|
| healthy (before) | 0.53 s | 0 % | nothing — correct |
| provider delay 2.0 s | **2.48 s** | **0 %** | `CheckoutLatencyBreach` (warning) |
| provider delay 6.0 s | — | **93.4 %** | `CheckoutErrorRateHigh` (critical), `CircuitBreakerOpen` (critical) |
| restored | 0.53 s | 0 % | all resolved |

**The headline is the second row.** A 2.0 s provider delay produces *no errors at
all* — 101 successful checkouts in ten minutes, every one `paid`, and the payment
circuit breaker stayed **closed**. Every error-based signal in the system says
this is fine. The latency SLO says it is not, and it is now the only thing that
does. That is precisely the failure the baseline's §3 identified as invisible, and
it is now the one that pages first.

**The third row shows the failure mode changing shape.** Pushing the delay past
the 3 s payment timeout produces a 93 % error rate, and the payment breaker opens
— so `CircuitBreakerOpen` fires too, because the system has moved from *slow* to
*refusing to try*. Note the alert fired on the breaker being open, not on the
provider being slow: the rule is written on the symptom the caller experiences.

**The Phase 7 fix is confirmed in the live system, not just in a unit test.** In
§3, a 2.0 s delay with Phase 7's slow-call default produced a **45 % error rate**
from a dependency that was answering correctly. In the row above, the same delay
produces **zero** errors and a closed breaker. `resilience4j_circuitbreaker_state`
being a metric rather than a log line is what made that difference visible without
grepping.

**Resolving matters as much as firing.** After the provider was restored, all
three alerts cleared and p95 returned to 0.53 s. An alert that never resolves is
as untested as one that never fires, and a rule with a `for:` that is too short —
or a threshold that a normal day crosses — would have been caught here.

Every rule also has a deterministic `promtool` test
(`infra/prometheus/rules-tests/`), including the negative cases: a healthy
checkout must not fire the latency alert, a declined payment must not fire the
error alert, and a large-but-fresh outbox backlog must not fire the stuck alert.
That is the part that runs on every build; this section is the part that had to
be done once, by hand, to prove the whole path.
