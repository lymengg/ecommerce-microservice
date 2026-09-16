package com.ecommerce.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record StockResponse(
        UUID itemId,
        Long productId,
        String sku,
        int totalQuantity,
        int reservedQuantity,
        int committedQuantity,
        int availableQuantity,
        Instant updatedAt
) {
}
