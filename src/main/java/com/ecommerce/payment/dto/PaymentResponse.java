package com.ecommerce.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID paymentId,
        UUID orderId,
        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
