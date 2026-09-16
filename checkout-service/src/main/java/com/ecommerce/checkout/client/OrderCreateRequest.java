package com.ecommerce.checkout.client;

import java.util.List;
import java.util.UUID;

/**
 * Request body for order-service order creation. Mirrors the order-service
 * contract; prices are never sent (server-authoritative pricing).
 */
public record OrderCreateRequest(
        UUID customerId,
        String currency,
        List<OrderLineRequest> items
) {
    public record OrderLineRequest(
            Long productId,
            int quantity
    ) {
    }
}
