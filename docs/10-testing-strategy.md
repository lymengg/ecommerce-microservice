# E-Commerce Platform — Testing Strategy

## 1. Testing Pyramid
- Unit tests
- Component/service tests
- Integration tests
- Contract tests
- End-to-end tests
- Performance tests
- Security tests

## 2. Unit Tests
Test domain rules independently:
- Order transitions
- Pricing
- Promotion validation
- Inventory reservation rules
- Payment state transitions

## 3. Integration Tests
Use Testcontainers for:
- PostgreSQL
- Kafka
- Redis
- Keycloak where practical

Avoid replacing infrastructure behavior with excessive mocks.

## 4. API Tests
Use REST Assured or equivalent.

Validate:
- Authentication
- Authorization
- Validation
- Error responses
- Pagination
- Idempotency
- Security headers

## 5. Contract Testing
Protect service contracts for:
- REST
- Kafka events

Breaking contract changes should fail CI.

## 6. End-to-End
Critical flows:
1. Register/login.
2. Browse product.
3. Add to cart.
4. Checkout.
5. Reserve inventory.
6. Pay.
7. Confirm order.
8. Ship.
9. Deliver.
10. Review.

## 7. Failure Tests
Explicitly test:
- Payment timeout
- Payment provider failure
- Duplicate webhook
- Duplicate Kafka event
- Inventory race
- Database outage
- Kafka outage
- Service restart
- Network timeout
- Partial Saga failure

## 8. Performance
Use k6 for:
- Catalog reads
- Cart operations
- Checkout
- Order queries

Measure:
- p50
- p95
- p99
- throughput
- error rate
- resource utilization

## 9. Security
Automate:
- Dependency scanning
- SAST
- Secret scanning
- Container scanning
- DAST with OWASP ZAP

## 10. Test Data
Never use real customer/payment data.
Generate deterministic, anonymized test fixtures.

## 11. CI Quality Gates
A production build should fail when:
- Unit/integration tests fail.
- Critical vulnerabilities are detected according to policy.
- Contract tests fail.
- Required quality checks fail.
