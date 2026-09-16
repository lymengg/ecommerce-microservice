package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CartItemUpdateRequest(
        @NotNull UUID cartId,
        @Min(1) @Max(99) int quantity
) {
}
