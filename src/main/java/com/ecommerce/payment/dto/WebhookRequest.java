package com.ecommerce.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record WebhookRequest(
        @NotBlank String providerEventId,
        @NotBlank String transactionId,
        @NotBlank String status,
        @NotBlank String type
) {
}
