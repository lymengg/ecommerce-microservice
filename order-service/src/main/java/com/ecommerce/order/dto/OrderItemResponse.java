package com.ecommerce.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID itemId,
        Long productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal discount,
        BigDecimal tax,
        BigDecimal lineTotal,
        String currency
) {
}
