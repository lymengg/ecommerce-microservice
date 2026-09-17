package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CartItemUpdateRequest(
        @Min(1) @Max(99) int quantity
) {
}
