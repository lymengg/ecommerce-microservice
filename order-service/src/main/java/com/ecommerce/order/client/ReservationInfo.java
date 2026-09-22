package com.ecommerce.order.client;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an inventory reservation as returned by inventory-service's
 * internal endpoint.
 */
public record ReservationInfo(
        UUID reservationId,
        Long productId,
        int quantity,
        UUID orderId,
        String status,
        Instant expiresAt
) {
    public boolean isReserved() {
        return "RESERVED".equals(status);
    }
}
