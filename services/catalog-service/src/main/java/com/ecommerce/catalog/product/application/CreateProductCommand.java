package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;

public record CreateProductCommand(
        String sku,
        String name,
        String description,
        java.math.BigDecimal price,
        String currency
) {
}
