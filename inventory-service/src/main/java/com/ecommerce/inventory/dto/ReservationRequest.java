package com.ecommerce.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReservationRequest(
        @NotNull Long productId,
        @Min(1) int quantity,
        @NotNull UUID orderId
) {
}
