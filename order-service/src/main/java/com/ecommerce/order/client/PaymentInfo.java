package com.ecommerce.order.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a payment as returned by payment-service's internal endpoint.
 */
public record PaymentInfo(
        UUID paymentId,
        UUID orderId,
        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
    public boolean isSucceeded() {
        return "SUCCEEDED".equals(status);
    }

    /**
     * Whether the payment has stopped moving. Anything still in flight
     * (PENDING, PROCESSING, REFUND_PENDING) must not be compensated — the
     * reconciliation job defers and looks again (ADR-020).
     */
    public boolean isSettled() {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED", "REFUND_FAILED" -> true;
            default -> false;
        };
    }
}
