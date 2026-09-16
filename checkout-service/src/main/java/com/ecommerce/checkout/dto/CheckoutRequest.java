package com.ecommerce.checkout.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CheckoutRequest(
        @NotNull UUID cartId,
        UUID customerId,
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
        String currency
) {
}
