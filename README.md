# E-Commerce Microservices

A production-grade e-commerce platform built with Java and Spring Boot, following domain-oriented architecture and microservices principles.

## Architecture Overview

This project follows a domain-oriented / feature-oriented packaging approach with Clean Architecture and Hexagonal Architecture principles:

```
ecommerce/
├── docs/
├── services/
│   └── catalog-service/
├── infrastructure/
├── scripts/
├── .github/
├── .gitignore
├── README.md
└── AGENTS.md
```

### Key Architectural Decisions

- **Domain-Oriented Packaging**: Code is organized around business capabilities, not technical layers
- **Clean Architecture**: Clear separation between domain, application, infrastructure, and presentation layers
- **Hexagonal Architecture**: Domain logic is isolated from infrastructure concerns
- **SOLID Principles**: Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion

## Technology Stack

- **Language**: Java 21 LTS
- **Framework**: Spring Boot 3.3.5
- **Database**: PostgreSQL 16
- **Migration**: Flyway
- **Testing**: JUnit 5, Mockito, Testcontainers
- **Build**: Maven
- **Containerization**: Docker

## Catalog Service

The Catalog Service manages product information, pricing, and availability.

### Features

- Product CRUD operations
- SKU-based product identification
- Product archival (soft delete)
- Pagination support
- Input validation
- Consistent error responses (RFC 7807 Problem Details)

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/products | Create a new product |
| GET | /api/v1/products/{id} | Get product by ID |
| GET | /api/v1/products | List all products (paginated) |
| PUT | /api/v1/products/{id} | Update product |
| PATCH | /api/v1/products/{id}/archive | Archive product |

### Product Model

```json
{
  "id": "uuid",
  "sku": "SKU-001",
  "name": "Product Name",
  "description": "Product description",
  "price": 99.99,
  "currency": "USD",
  "status": "ACTIVE",
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:00Z"
}
```

## Local Development

### Prerequisites

- Java 21
- Maven
- Docker and Docker Compose
- PostgreSQL (or use Docker)

### Quick Start

1. Start PostgreSQL:

```bash
docker-compose up -d postgres
```

2. Build and run the Catalog Service:

```bash
cd services/catalog-service
./mvnw spring-boot:run
```

3. Access the application:

- Application: http://localhost:8080
- Health Check: http://localhost:8081/actuator/health

### Running with Docker

```bash
docker-compose up -d
```

This will start both PostgreSQL and the Catalog Service.

### Running Tests

```bash
cd services/catalog-service
./mvnw test
```

### Database Migrations

Flyway automatically runs migrations on application startup. Migrations are located in:

```
src/main/resources/db/migration/
```

## Project Structure

```
catalog-service/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/ecommerce/catalog/
│   │   │       ├── CatalogApplication.java
│   │   │       └── product/
│   │   │           ├── domain/
│   │   │           │   ├── Product.java
│   │   │           │   ├── ProductId.java
│   │   │           │   ├── ProductStatus.java
│   │   │           │   ├── ProductRepository.java
│   │   │           │   ├── ProductDomainException.java
│   │   │           │   ├── Page.java
│   │   │           │   └── Pageable.java
│   │   │           ├── application/
│   │   │           │   ├── ProductApplicationService.java
│   │   │           │   ├── CreateProductCommand.java
│   │   │           │   ├── UpdateProductCommand.java
│   │   │           │   ├── ProductNotFoundException.java
│   │   │           │   └── DuplicateSkuException.java
│   │   │           ├── infrastructure/
│   │   │           │   └── persistence/
│   │   │           │       ├── ProductJpaEntity.java
│   │   │           │       ├── ProductJpaRepository.java
│   │   │           │       └── ProductRepositoryAdapter.java
│   │   │           └── presentation/
│   │   │               ├── ProductController.java
│   │   │               ├── CreateProductRequest.java
│   │   │               ├── UpdateProductRequest.java
│   │   │               ├── ProductResponse.java
│   │   │               ├── PagedProductResponse.java
│   │   │               ├── ProblemDetail.java
│   │   │               └── GlobalExceptionHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__create_products.sql
│   └── test/
│       ├── java/
│       │   └── com/ecommerce/catalog/
│       │       └── product/
│       │           ├── domain/
│       │           ├── application/
│       │           ├── infrastructure/
│       │           └── presentation/
│       └── resources/
│           └── application-test.yml
├── Dockerfile
├── pom.xml
└── README.md
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| DB_USERNAME | PostgreSQL username | catalog_user |
| DB_PASSWORD | PostgreSQL password | catalog_password |
| SPRING_DATASOURCE_URL | JDBC URL | jdbc:postgresql://localhost:5432/catalog |

### Application Properties

See `src/main/resources/application.yml` for all configuration options.

## Error Handling

The API uses RFC 7807 Problem Details for error responses:

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

## Security

- Input validation on all endpoints
- No secrets or credentials in code
- Parameterized database queries (prevents SQL injection)
- Non-root Docker container
- Actuator endpoints secured

## Testing

### Test Types

1. **Unit Tests**: Domain and application logic
2. **Integration Tests**: Repository layer with Testcontainers
3. **API Tests**: REST endpoint behavior

### Running Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=ProductTest

# Run with coverage
./mvnw test jacoco:report
```

## Contributing

1. Follow the architecture principles in AGENTS.md
2. Write tests for new functionality
3. Run `./mvnw test` before committing
4. Use meaningful commit messages
5. Do not commit secrets or credentials

## License

This project is proprietary and confidential.
