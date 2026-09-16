package com.ecommerce.checkout.dto;

import java.util.UUID;

public record CheckoutResponse(
        UUID orderId,
        UUID paymentId,
        String orderStatus,
        String paymentStatus
) {
}
