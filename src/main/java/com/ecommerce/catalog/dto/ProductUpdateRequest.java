package com.ecommerce.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductUpdateRequest(
        @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @DecimalMin("0.01") BigDecimal price
) {
}
