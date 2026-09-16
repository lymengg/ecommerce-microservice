# E-Commerce Platform — Microservice Architecture Specification

## 1. Architecture Style
Use independently deployable services organized around business capabilities and bounded contexts.

Initial core services:
1. Identity/IAM integration
2. Catalog Service
3. Cart Service
4. Order Service
5. Inventory Service
6. Payment Service

Supporting services:
7. Promotion Service
8. Shipping/Fulfillment Service
9. Review Service
10. Notification Service
11. Audit Service
12. Search Service

Infrastructure:
- Spring Cloud Gateway
- Keycloak
- PostgreSQL
- Redis
- Apache Kafka
- OpenTelemetry
- Prometheus/Grafana
- Loki
- Tempo/Jaeger
- Kubernetes

## 2. Boundary Principles
A service should exist when it has:
- A coherent business capability.
- Independent data ownership.
- Independent scaling needs.
- Independent deployment needs.
- Meaningful failure isolation.
- A clear API/event contract.

Do not create services merely because there are separate database tables.

## 3. Communication
### REST
Use synchronous REST when the caller requires an immediate answer:
- Catalog queries
- Cart operations
- Order queries
- Authorization-sensitive reads
- Checkout validations where immediate decisions are required

### Kafka
Use asynchronous events for:
- Notifications
- Audit propagation
- Search indexing
- Order lifecycle side effects
- Payment status propagation
- Inventory events
- Analytics

## 4. Database-per-Service
Each service owns its PostgreSQL database/schema.

Forbidden:
- Cross-service SQL.
- Foreign keys across service databases.
- Shared mutable domain tables.
- One service modifying another service's data.

## 5. Gateway
Spring Cloud Gateway is the edge entry point.

Responsibilities:
- Routing
- TLS termination integration
- Request correlation
- Coarse-grained rate limiting
- Authentication integration where appropriate
- Header normalization
- CORS policy
- Request size limits

Business authorization remains inside services.

## 6. Identity
Keycloak provides OAuth2/OIDC identity management.

Spring services operate as OAuth2 Resource Servers and validate access tokens.

Do not build a custom authorization server unless there is a real business requirement.

## 7. Service-to-Service Security
Use authenticated service identities. Prefer OAuth2 client credentials for service calls where appropriate.

For highly sensitive internal traffic, mTLS may be introduced at the platform layer.

## 8. Saga
Prefer explicit orchestration for checkout/order workflows when business visibility and recovery are important.

Example:
```text
Order
  -> Reserve Inventory
  -> Initiate Payment
  -> Confirm Order

Failure:
  -> Release Inventory
  -> Cancel Order
```

## 9. Reliability
Use:
- Timeouts
- Limited retries
- Exponential backoff
- Circuit breakers
- Bulkheads
- Rate limits
- Idempotency
- Dead-letter topics
- Reconciliation jobs

Never retry indefinitely.

## 10. Caching
Redis may be used for:
- Catalog read caching
- Rate limiting
- Short-lived state
- Idempotency metadata where durability requirements permit

Do not use cache as the authoritative source of business truth.

## 11. Scalability
Services must be stateless where possible.
Scale independently based on workload.

Kafka consumers scale through consumer groups and partitions.

## 12. Failure Isolation
A failure in Notification or Search must not prevent successful order placement.

A payment provider outage should fail safely and avoid duplicate charging.

## 13. Kubernetes
Each service should eventually have:
- Deployment
- Service
- ConfigMap
- Secret reference
- Readiness probe
- Liveness probe
- Resource requests/limits
- Horizontal scaling policy
- Pod disruption considerations

Use Kubernetes-native service discovery. Do not add Eureka solely because the project is microservices.

## 14. Target Architecture
```text
                    Internet
                       |
                 AWS Load Balancer
                       |
              Spring Cloud Gateway
                       |
       +---------------+----------------+
       |               |                |
    Catalog          Cart             Order
       |               |                |
   PostgreSQL       PostgreSQL      PostgreSQL
                                       |
                         +-------------+-------------+
                         |             |             |
                     Inventory      Payment      Shipping
                         |             |             |
                     PostgreSQL    PostgreSQL    PostgreSQL

                       Kafka
                         |
        +----------------+----------------+
        |                |                |
   Notification        Audit           Search
```
