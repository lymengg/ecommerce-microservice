package com.ecommerce.cart.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemResponse(
        UUID itemId,
        Long productId,
        String sku,
        String name,
        int quantity,
        BigDecimal unitPrice
) {
}
