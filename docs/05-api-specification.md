# E-Commerce Platform — REST API Specification

## 1. General
Base path:
```text
/api/v1
```

Use resource-oriented REST APIs.

## 2. Response Rules
Successful responses should have predictable representations.

Errors should use RFC 9457-style Problem Details where appropriate:
```json
{
  "type": "https://example.com/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/orders",
  "traceId": "..."
}
```

Never expose stack traces or internal implementation details.

## 3. Catalog
```text
GET    /api/v1/products
GET    /api/v1/products/{productId}
POST   /api/v1/products
PATCH  /api/v1/products/{productId}
POST   /api/v1/products/{productId}/activate
POST   /api/v1/products/{productId}/archive
```

## 4. Cart
```text
GET    /api/v1/cart
POST   /api/v1/cart/items
PATCH  /api/v1/cart/items/{itemId}
DELETE /api/v1/cart/items/{itemId}
```

## 5. Orders
```text
POST /api/v1/orders
GET  /api/v1/orders
GET  /api/v1/orders/{orderId}
POST /api/v1/orders/{orderId}/cancel
```

Object-level authorization is mandatory.

## 6. Inventory
Inventory endpoints should primarily support internal authenticated clients and administrative workflows.

```text
POST /internal/api/v1/inventory/reservations
POST /internal/api/v1/inventory/reservations/{reservationId}/release
POST /internal/api/v1/inventory/reservations/{reservationId}/commit
```

## 7. Payments
```text
POST /api/v1/payments
GET  /api/v1/payments/{paymentId}
POST /api/v1/payments/{paymentId}/refund
POST /api/v1/payments/webhooks/{provider}
```

Webhook endpoints must verify provider authenticity and implement idempotency.

## 8. Idempotency
For retryable state-changing operations:
```text
Idempotency-Key: <client-generated-stable-key>
```

The server must persist the result sufficiently to return the same logical result for a repeated request.

## 9. Pagination
Prefer cursor pagination for large or frequently changing collections.

Example:
```text
GET /api/v1/orders?limit=20&cursor=...
```

Enforce maximum page sizes.

## 10. Filtering and Sorting
Whitelist supported fields. Never interpolate arbitrary client input into SQL or query expressions.

## 11. Versioning
Use `/api/v1` and evolve contracts compatibly.
Breaking changes require a new version or migration strategy.

## 12. Security
- Validate all input.
- Authenticate protected endpoints.
- Authorize every protected object.
- Rate limit sensitive operations.
- Avoid sensitive data in URLs.
- Do not return tokens or secrets in normal API responses.
