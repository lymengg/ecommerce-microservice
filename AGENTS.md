# AGENTS.md - Project Instructions for AI-Assisted Development

## Project Overview

This is a production-grade e-commerce microservices project built with Java and Spring Boot. The project follows domain-oriented architecture with Clean Architecture and Hexagonal Architecture principles.

## Architecture Principles

### Domain-Oriented Packaging

Code is organized around business capabilities, not technical layers:

```
product/
├── domain/
├── application/
├── infrastructure/
└── presentation/
```

### Clean Architecture

- **Domain Layer**: Business logic, entities, value objects, repository interfaces
- **Application Layer**: Use cases, application services, DTOs
- **Infrastructure Layer**: Technical implementations (database, messaging, etc.)
- **Presentation Layer**: Controllers, request/response DTOs, error handling

### SOLID Principles

- **Single Responsibility**: Each class has one reason to change
- **Open/Closed**: Open for extension, closed for modification
- **Liskov Substitution**: Subtypes must be substitutable for their base types
- **Interface Segregation**: Many specific interfaces are better than one general-purpose interface
- **Dependency Inversion**: Depend on abstractions, not concretions

## Code Guidelines

### Naming Conventions

- Classes: PascalCase (e.g., `ProductApplicationService`)
- Methods: camelCase (e.g., `createProduct`)
- Constants: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_ATTEMPT`)
- Packages: lowercase (e.g., `com.ecommerce.catalog.product.domain`)

### Code Style

- Use constructor injection, not field injection
- Keep classes small and focused
- Keep methods small and focused
- Use immutable objects where practical
- Prefer composition over inheritance
- Avoid unnecessary static state
- Avoid magic numbers/strings

### Domain Layer

- Domain logic must not depend on Spring, JPA, or infrastructure
- Use value objects for identity (e.g., `ProductId`)
- Keep business rules in domain entities
- Repository interfaces belong to the domain layer

### Application Layer

- Orchestrate use cases
- Enforce application-level rules
- Coordinate repositories
- Manage transactions
- Do not place HTTP-specific logic here

### Infrastructure Layer

- Implement repository interfaces
- Keep persistence concerns separate from domain models
- Use adapters to translate between layers

### Presentation Layer

- Controllers should be thin
- Receive HTTP requests
- Validate input
- Map requests to application commands
- Return appropriate responses
- Business logic must not be in controllers

## Security Guidelines

### OWASP Top 10

- **A01: Broken Access Control**: Implement proper authorization
- **A02: Cryptographic Failures**: Never store secrets in code
- **A03: Injection**: Use parameterized queries
- **A04: Insecure Design**: Follow security-by-design principles
- **A05: Security Misconfiguration**: Use secure defaults
- **A06: Vulnerable Components**: Keep dependencies updated
- **A07: Authentication Failures**: Implement proper authentication
- **A08: Data Integrity Failures**: Validate all inputs
- **A09: Logging Failures**: Log security events appropriately
- **A10: SSRF**: Validate and sanitize URLs

### Secrets Management

- Never commit secrets, API keys, or credentials
- Use environment variables for configuration
- Use secrets management services in production
- Rotate secrets regularly

### Input Validation

- Validate all input on the API boundary
- Use Jakarta Bean Validation for request validation
- Business rules belong in the domain layer
- Never trust client-provided business-critical values

## Testing Guidelines

### Test Types

1. **Unit Tests**: Test domain and application logic in isolation
2. **Integration Tests**: Test repository layer with Testcontainers
3. **API Tests**: Test REST endpoint behavior

### Test Conventions

- Use descriptive test names
- Test one thing per test method
- Use Arrange-Act-Assert pattern
- Mock external dependencies
- Do not test implementation details
- Test behavior, not structure

### Running Tests

```bash
./mvnw test
```

### Test Coverage

- Aim for meaningful coverage, not 100%
- Focus on critical business logic
- Test edge cases and error scenarios
- Test boundary conditions

## Database Guidelines

### Migrations

- Use Flyway for all schema changes
- Never rely on Hibernate auto-generating production schemas
- Use meaningful migration names (e.g., `V1__create_products.sql`)
- Test migrations against a clean database
- Never modify existing migrations

### Schema Design

- Use appropriate constraints
- Add indexes where justified
- Use appropriate data types
- Include timestamps (created_at, updated_at)
- Use soft deletes where appropriate

### Query Optimization

- Use indexes for frequently queried columns
- Avoid N+1 queries
- Use pagination for large result sets
- Profile queries regularly

## API Guidelines

### RESTful Design

- Use nouns, not verbs, for resources
- Use HTTP methods appropriately
- Use plural nouns for collections
- Use consistent naming conventions
- Version APIs (e.g., `/api/v1/products`)

### Request/Response

- Use DTOs for API contracts
- Do not expose JPA entities directly
- Support pagination for list endpoints
- Use consistent error responses (RFC 7807)

### Validation

- Validate input on the API boundary
- Return meaningful error messages
- Do not expose internal implementation details
- Use appropriate HTTP status codes

## Error Handling

### Error Response Format

Use RFC 7807 Problem Details:

```json
{
  "type": "https://api.ecommerce.com/errors/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "Request validation failed",
  "timestamp": "2024-01-01T00:00:00Z",
  "errors": [
    {
      "field": "sku",
      "message": "SKU is required"
    }
  ]
}
```

### Error Types

- 400 Bad Request: Validation errors, malformed requests
- 404 Not Found: Resource not found
- 409 Conflict: Duplicate resources, state conflicts
- 500 Internal Server Error: Unexpected errors

### Logging

- Log errors with context
- Do not log sensitive information
- Use appropriate log levels
- Include request IDs for tracing

## Performance Guidelines

### Caching

- Cache frequently accessed data
- Use appropriate cache invalidation strategies
- Monitor cache hit rates

### Database

- Use connection pooling
- Optimize queries
- Use pagination
- Avoid N+1 queries

### API

- Use compression
- Implement rate limiting
- Use caching headers

## Documentation

### Code Documentation

- Document public APIs
- Explain complex business logic
- Document architectural decisions
- Keep documentation up to date

### API Documentation

- Document all endpoints
- Include request/response examples
- Document error responses
- Use OpenAPI/Swagger where appropriate

## Git Guidelines

### Commit Messages

- Use meaningful commit messages
- Follow conventional commits format
- Keep commits atomic and focused
- Reference issue numbers

### Branching

- Use feature branches
- Keep main branch clean
- Use pull requests for code review
- Delete merged branches

### Code Review

- Review for security issues
- Review for performance issues
- Review for code quality
- Review for test coverage

## Monitoring and Observability

### Logging

- Use structured logging
- Include correlation IDs
- Log appropriate context
- Do not log sensitive information

### Metrics

- Monitor application performance
- Track business metrics
- Set up alerts for anomalies
- Monitor resource usage

### Tracing

- Use distributed tracing
- Track request flows
- Monitor service dependencies
- Identify bottlenecks

## Deployment

### Docker

- Use multi-stage builds
- Run as non-root user
- Minimize image size
- Use specific base image versions

### Environment Management

- Use environment variables
- Separate configuration from code
- Use secrets management
- Monitor environment health

## Common Pitfalls to Avoid

1. **God Classes**: Keep classes small and focused
2. **Anemic Domain Model**: Put behavior in domain objects
3. **N+1 Queries**: Use JOINs or batch fetching
4. **Premature Optimization**: Profile before optimizing
5. **Over-Engineering**: Keep it simple
6. **Magic Numbers**: Use named constants
7. **Field Injection**: Use constructor injection
8. **Static State**: Avoid mutable static state
9. **Deep Inheritance**: Prefer composition
10. **Premature Abstraction**: Abstract only when necessary

## References

- [Domain-Driven Design](https://martinfowler.com/bliki/DDD.html)
- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/clean-architecture.html)
- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
- [SOLID Principles](https://content.pivotal.io/solid-timeless-object-oriented-design-principles)
- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [RFC 7807 Problem Details](https://tools.ietf.org/html/rfc7807)
