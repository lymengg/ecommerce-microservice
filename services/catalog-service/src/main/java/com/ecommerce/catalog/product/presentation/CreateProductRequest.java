package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.product.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank(message = "SKU is required")
        @Size(min = 1, max = 50, message = "SKU must be between 1 and 50 characters")
        @Pattern(regexp = "^[A-Z0-9\\-]+$", message = "SKU must contain only uppercase letters, numbers, and hyphens")
        String sku,

        @NotBlank(message = "Name is required")
        @Size(min = 1, max = 255, message = "Name must be between 1 and 255 characters")
        String name,

        @Size(max = 2000, message = "Description must not exceed 2000 characters")
        String description,

        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        BigDecimal price,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO currency code")
        String currency
) {
}
