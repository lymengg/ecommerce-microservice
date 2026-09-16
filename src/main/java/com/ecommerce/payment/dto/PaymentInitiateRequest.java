package com.ecommerce.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PaymentInitiateRequest(
        @NotNull UUID orderId
) {
}
