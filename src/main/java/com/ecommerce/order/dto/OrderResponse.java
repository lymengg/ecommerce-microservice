package com.ecommerce.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        UUID customerId,
        String status,
        String currency,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal tax,
        BigDecimal shippingCost,
        BigDecimal total,
        Instant createdAt,
        List<OrderItemResponse> items
) {
}
