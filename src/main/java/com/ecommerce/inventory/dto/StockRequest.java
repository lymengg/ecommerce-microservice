package com.ecommerce.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StockRequest(
        @NotNull Long productId,
        @NotBlank String sku,
        @Min(0) int totalQuantity
) {
}
