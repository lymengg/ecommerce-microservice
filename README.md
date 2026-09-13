# E-Commerce Microservices

A production-grade e-commerce microservices project built with Java, Spring Boot, and PostgreSQL.

## Project Purpose

This project establishes the foundation for a scalable e-commerce system using microservices architecture. It implements domain-oriented design with Clean Architecture principles.

## Architecture Overview

- **Domain-Oriented Packaging**: Code is organized around business capabilities, not technical layers
- **Clean Architecture**: Clear separation between domain, application, infrastructure, and presentation layers
- **Hexagonal Architecture**: Domain logic is isolated from external concerns
- **SOLID Principles**: Single responsibility, dependency inversion, and explicit dependencies

## Technology Stack

- Java 17 LTS
- Spring Boot 3.2.5
- Spring Data JPA
- PostgreSQL 15
- Flyway (database migrations)
- Maven
- JUnit 5 + Mockito
- Testcontainers
- Docker

## Repository Structure

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

## Catalog Service Structure

```
catalog-service/
├── src/
│   ├── main/
│   │   ├── java/com/ecommerce/catalog/
│   │   │   ├── CatalogApplication.java
│   │   │   ├── product/
│   │   │   │   ├── domain/
│   │   │   │   ├── application/
│   │   │   │   ├── infrastructure/
│   │   │   │   └── presentation/
│   │   │   └── shared/
│   │   │       ├── config/
│   │   │       └── error/
│   │   └── resources/
│   │       ├── db/migration/
│   │       └── application.yml
│   └── test/
├── Dockerfile
├── pom.xml
└── README.md
```

## Local Setup

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker and Docker Compose

### Start PostgreSQL

```bash
docker-compose up -d
```

### Run Catalog Service

```bash
cd services/catalog-service
./mvnw spring-boot:run
```

The service will start on port 8080.

### Run Tests

```bash
cd services/catalog-service
./mvnw test
```

### Build Docker Image

```bash
cd services/catalog-service
docker build -t catalog-service .
```

### Run Docker Container

```bash
docker run -p 8080:8080 \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/catalog_db \
  catalog-service
```

## API Overview

### Products API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/products | Create a new product |
| GET | /api/v1/products/{id} | Get product by ID |
| GET | /api/v1/products | List products (paginated) |
| PUT | /api/v1/products/{id} | Update a product |
| PATCH | /api/v1/products/{id}/archive | Archive a product |

### Request Examples

**Create Product:**
```json
POST /api/v1/products
{
  "sku": "PROD-001",
  "name": "Wireless Headphones",
  "description": "High-quality wireless headphones",
  "price": 99.99,
  "currency": "USD"
}
```

**Update Product:**
```json
PUT /api/v1/products/1
{
  "name": "Updated Product Name",
  "price": 149.99
}
```

### Response Format

All responses follow RFC 7807 Problem Details format for errors:

```json
{
  "type": "https://api.ecommerce.com/errors/validation",
  "title": "Validation Error",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/products",
  "timestamp": "2024-01-01T00:00:00Z",
  "errors": [
    {
      "field": "sku",
      "message": "SKU is required"
    }
  ]
}
```

## Health Checks

- Health: `GET /actuator/health`
- Info: `GET /actuator/info`

## Database

PostgreSQL with Flyway migrations. Schema is managed through versioned SQL files in `src/main/resources/db/migration/`.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_USERNAME | postgres | Database username |
| DB_PASSWORD | postgres | Database password |
| DB_URL | jdbc:postgresql://localhost:5432/catalog_db | Database URL |
| SERVER_PORT | 8080 | Application port |
| MANAGEMENT_PORT | 8081 | Actuator port |
