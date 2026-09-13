package com.ecommerce.catalog.product.presentation;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        String id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse fromDomain(com.ecommerce.catalog.product.domain.Product product) {
        return new ProductResponse(
                product.getId().getValue(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getCurrency(),
                product.getStatus().name(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
