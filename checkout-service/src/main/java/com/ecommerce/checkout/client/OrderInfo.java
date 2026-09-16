package com.ecommerce.checkout.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of an order as returned by order-service.
 */
public record OrderInfo(
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
        List<OrderItemInfo> items
) {
    public record OrderItemInfo(
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
}
