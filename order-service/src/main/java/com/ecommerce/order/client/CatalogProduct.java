package com.ecommerce.order.client;

import java.math.BigDecimal;

/**
 * Read model of an ACTIVE catalog product used for server-authoritative
 * pricing: clients only send product ids and quantities; price, name and sku
 * are snapshotted from the catalog at order creation time.
 */
public record CatalogProduct(
        Long id,
        String sku,
        String name,
        BigDecimal price
) {
}
