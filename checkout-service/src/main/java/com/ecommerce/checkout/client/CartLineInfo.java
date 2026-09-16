package com.ecommerce.checkout.client;

import java.util.UUID;

/**
 * A cart line prepared for checkout, as returned by cart-service. Prices are
 * intentionally absent: order-service re-prices every line from the catalog
 * (server-authoritative pricing).
 */
public record CartLineInfo(
        UUID cartId,
        Long productId,
        String sku,
        int quantity
) {
}
