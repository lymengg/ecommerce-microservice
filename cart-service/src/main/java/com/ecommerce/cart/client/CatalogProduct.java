package com.ecommerce.cart.client;

import java.math.BigDecimal;

/**
 * Read model of an ACTIVE catalog product, as returned by the catalog
 * service's internal endpoint. Pricing remains server-authoritative: the cart
 * never stores prices, it only displays them.
 */
public record CatalogProduct(
        Long id,
        String sku,
        String name,
        BigDecimal price
) {
}
