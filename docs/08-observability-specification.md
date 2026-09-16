# E-Commerce Platform — Observability Specification

## 1. Three Pillars
- Logs
- Metrics
- Traces

Use OpenTelemetry as the primary instrumentation standard.

## 2. Correlation
Every inbound request should have a correlation/trace context propagated across:
- Gateway
- REST calls
- Kafka messages
- Database operations where useful

## 3. Tracing
Trace critical workflows:
```text
HTTP request
 -> Gateway
 -> Order
 -> Inventory
 -> Payment
 -> Kafka
 -> Notification
```

Capture:
- Trace ID
- Span ID
- Service
- Operation
- Duration
- Error status

Do not place secrets or sensitive payloads in spans.

## 4. Metrics
Track:
- Request rate
- Error rate
- Latency
- Saturation
- JVM metrics
- Database pool utilization
- Kafka consumer lag
- Order creation rate
- Payment success/failure
- Inventory reservation failures
- Outbox backlog

## 5. Logs
Use structured JSON logs.

Recommended fields:
- timestamp
- level
- service
- environment
- traceId
- spanId
- correlationId
- operation
- errorCode

## 6. Dashboards
Create dashboards for:
- Platform health
- API gateway
- Each service
- PostgreSQL
- Redis
- Kafka
- Checkout
- Payments
- Kubernetes

## 7. Alerts
Alert on symptoms:
- High error rate
- High latency
- Kafka lag
- Database saturation
- Outbox backlog
- Payment failure spike
- Inventory reservation failure spike
- Pod crash loops

Avoid alerting on every individual exception.

## 8. Health Checks
Expose appropriate liveness/readiness checks.
Readiness should represent whether the instance can receive traffic.

## 9. Audit vs Observability
Audit logs are business/security records.
Operational logs and traces are for system diagnosis.
Do not substitute one for the other.
