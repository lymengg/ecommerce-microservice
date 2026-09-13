# AGENTS.md - Project Instructions for AI-Assisted Development

## Project Overview

E-Commerce Microservices - A production-grade e-commerce system built with Java, Spring Boot, and PostgreSQL.

## Architecture Principles

### Clean Architecture
- Domain logic must not depend on Spring, JPA, HTTP, or other infrastructure
- Use dependency inversion: domain defines interfaces, infrastructure implements them
- Separate concerns: domain, application, infrastructure, presentation layers

### Domain-Oriented Packaging
- Organize code around business capabilities, not technical layers
- Example: `product/domain/`, `product/application/`, `product/infrastructure/`, `product/presentation/`
- Avoid global technical-layer structure (controller/, service/, repository/, entity/)

### SOLID Principles
- **Single Responsibility**: Each class has one reason to change
- **Open/Closed**: Open for extension, closed for modification
- **Liskov Substitution**: Subtypes must be substitutable for base types
- **Interface Segregation**: Many specific interfaces over general ones
- **Dependency Inversion**: Depend on abstractions, not concretions

## Code Quality Standards

### Naming Conventions
- Use descriptive, intention-revealing names
- Avoid abbreviations unless widely understood
- Classes: PascalCase (e.g., `ProductRepository`)
- Methods: camelCase (e.g., `findById`)
- Constants: UPPER_SNAKE_CASE (e.g., `MAX_RETRY_COUNT`)

### Class Design
- Keep classes small and focused
- Use constructor injection (no field injection)
- Make classes immutable where practical
- Prefer composition over inheritance
- Avoid unnecessary static state

### Method Design
- Keep methods small and focused
- Limit method parameters
- Return early to avoid deep nesting
- Use descriptive method names

## Security Practices

### OWASP Top 10
- Never hardcode secrets or credentials
- Validate all input
- Use parameterized database access
- Avoid unsafe deserialization
- Do not expose sensitive actuator endpoints
- Use secure HTTP/security configuration

### Data Protection
- Do not log passwords, tokens, or sensitive data
- Use environment variables for configuration
- Never commit secrets to version control

## Testing Standards

### Unit Tests
- Test domain and application logic without Spring context
- Use Mockito for mocking dependencies
- Focus on business rules and edge cases
- Test one thing per test method

### Integration Tests
- Use Testcontainers for database tests
- Test repository implementations against real database
- Verify migrations and constraints

### API Tests
- Test REST API behavior end-to-end
- Verify validation, error responses, and pagination
- Use MockMvc for controller tests

## Database Migration Discipline

- All schema changes must go through Flyway
- Never modify production database manually
- Use descriptive migration filenames
- Test migrations before applying to production
- Never modify applied migrations

## API Design

### RESTful Conventions
- Use proper HTTP methods (GET, POST, PUT, PATCH, DELETE)
- Return appropriate status codes
- Use DTOs for API contracts
- Support pagination for list endpoints
- Version APIs (e.g., /api/v1/)

### Error Handling
- Use RFC 7807 Problem Details format
- Never expose stack traces or internal errors
- Provide meaningful error messages
- Handle validation errors consistently

## Documentation

- Document architectural decisions
- Explain complex business logic
- Keep README.md up to date
- Document API endpoints and contracts

## Development Workflow

1. Understand the requirements
2. Review existing code and conventions
3. Design the solution
4. Implement with tests
5. Verify with lint and type checks
6. Document significant decisions

## Common Pitfalls to Avoid

- Do not create unnecessary abstractions
- Do not introduce frameworks without clear reason
- Do not change conventions without justification
- Do not create empty microservices prematurely
- Do not over-engineer solutions
- Do not skip tests for coverage numbers

## When Modifying Code

1. First, understand the existing code structure
2. Follow existing conventions and patterns
3. Maintain backward compatibility
4. Add tests for new functionality
5. Update documentation if needed
6. Run tests to verify changes

## Important Reminders

- This is a learning project focused on architecture
- Implement incrementally, not all at once
- Document decisions and trade-offs
- Keep the codebase clean and maintainable
- Prioritize simplicity over complexity
