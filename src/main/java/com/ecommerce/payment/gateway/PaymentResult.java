package com.ecommerce.payment.gateway;

import com.ecommerce.payment.model.PaymentStatus;

public record PaymentResult(
        PaymentStatus status,
        String providerTransactionId
) {
}
