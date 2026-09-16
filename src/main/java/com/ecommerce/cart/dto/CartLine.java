package com.ecommerce.cart.dto;

import java.util.UUID;

/**
 * A cart line prepared for checkout. Prices are intentionally NOT included:
 * the order service re-prices every line from the catalog (server-authoritative).
 */
public record CartLine(
        UUID cartId,
        Long productId,
        String sku,
        int quantity
) {
}
