# E-Commerce Platform — Implementation Roadmap

## Phase 1 — Foundation
- Repository structure
- Java/Spring Boot baseline
- Coding standards
- Error handling
- OpenAPI
- PostgreSQL/Flyway
- Testcontainers
- CI skeleton

## Phase 2 — Modular Domain Prototype
Build core domains in a modular structure first:
- Catalog
- Cart
- Order
- Inventory
- Payment

Validate business rules before distributed deployment.

## Phase 3 — Service Extraction
Extract:
1. Catalog
2. Cart
3. Inventory
4. Order
5. Payment

Introduce database-per-service.

## Phase 4 — Security ✅ (2026-09-17)
- Keycloak — realm `ecommerce` (roles CUSTOMER/ADMIN/SERVICE, 5-min access
  tokens, refresh rotation, brute-force protection); dev via
  `docker compose up -d keycloak`, tests via a Keycloak container
- OAuth2/OIDC — every service is an OAuth2 resource server (ADR-005);
  gateway validates JWTs at the edge and relays them (defense in depth)
- Resource server validation — issuer-uri/JWKS, lazy decoders, Keycloak
  `realm_access.roles` -> `ROLE_*` authorities
- RBAC — gateway route rules (public product GETs, ADMIN writes, CUSTOMER
  cart/orders/payments/checkout); method security in services; SERVICE role
  for internal endpoints
- Object-level authorization — customer sees only own orders/carts/payments
  (404 for others); cart bound to JWT subject (one active cart per customer);
  client-supplied customerId rejected on mismatch (ADR-013)
- Rate limiting — per-IP fixed window at the edge + stricter per-subject
  limit for checkout/payment
- CORS/CSRF strategy — explicit origin allowlist; bearer-token APIs are
  stateless (CSRF not applicable, documented); webhooks permit-all + provider
  allowlist
- Security testing — unit tests for claim mapping; mocked-JWT role/ownership
  ITs in every service + gateway; real-Keycloak end-to-end IT
  (`GatewayKeycloakIT`); security defaults: no secrets committed, restricted
  actuator exposure planned with Phase 9

## Phase 5 — Kafka
- Event contracts
- Outbox
- Producers
- Consumers
- Consumer idempotency
- DLT
- Retry strategy
- Schema evolution

## Phase 6 — Resilience
- Timeouts
- Circuit breakers
- Bulkheads
- Backoff
- Failure injection
- Reconciliation

## Phase 7 — Observability
- OpenTelemetry
- Prometheus
- Grafana
- Loki
- Tempo/Jaeger
- Dashboards
- Alerts

## Phase 8 — Containerization
- Dockerfiles
- Compose local environment
- Secure images
- Local Kafka/Postgres/Redis/Keycloak

## Phase 9 — Kubernetes
- Helm charts
- Deployments
- Services
- Config
- Secret references
- Probes
- Autoscaling
- Ingress/load balancer

## Phase 10 — CI/CD
- Automated tests
- Security scans
- Image build
- Registry
- Staging deployment
- Smoke tests
- Production deployment

## Phase 11 — Production Hardening
- Load tests
- Chaos/failure tests
- Security assessment
- Backup/restore
- Disaster recovery
- Runbooks
- SLOs/SLIs
- Cost optimization

## Recommended Learning Order
```text
Domain modeling
 -> Spring Boot
 -> PostgreSQL/JPA/Flyway
 -> REST/OpenAPI
 -> Security
 -> Microservices
 -> Kafka
 -> Resilience
 -> Observability
 -> Docker
 -> Kubernetes
 -> CI/CD
 -> AWS
```
