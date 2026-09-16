package com.ecommerce.checkout.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a payment as returned by payment-service.
 */
public record PaymentInfo(
        UUID paymentId,
        UUID orderId,
        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
