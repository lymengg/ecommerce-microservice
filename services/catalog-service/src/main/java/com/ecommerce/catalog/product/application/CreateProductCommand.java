package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;

import java.math.BigDecimal;

public record CreateProductCommand(
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency
) {
}
