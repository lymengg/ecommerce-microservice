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
