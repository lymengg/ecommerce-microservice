package com.ecommerce.catalog.product.presentation;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @Size(max = 255, message = "Name must not exceed 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @DecimalMin(value = "0.01", message = "Price must be positive")
        BigDecimal price,

        @Size(min = 3, max = 3, message = "Currency must be exactly 3 characters")
        String currency
) {
}
