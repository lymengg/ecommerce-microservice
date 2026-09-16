package com.ecommerce.checkout.client;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model of an inventory reservation as returned by inventory-service.
 */
public record ReservationInfo(
        UUID reservationId,
        Long productId,
        int quantity,
        UUID orderId,
        String status,
        Instant expiresAt
) {
}
