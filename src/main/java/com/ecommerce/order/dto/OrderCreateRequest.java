package com.ecommerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public record OrderCreateRequest(
        UUID customerId,
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
        String currency,
        @Valid @NotEmpty List<OrderLineRequest> items
) {
}
