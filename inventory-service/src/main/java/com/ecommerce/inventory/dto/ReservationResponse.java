package com.ecommerce.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        Long productId,
        int quantity,
        UUID orderId,
        String status,
        Instant expiresAt
) {
}
