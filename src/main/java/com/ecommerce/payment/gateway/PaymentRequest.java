package com.ecommerce.payment.gateway;

import java.math.BigDecimal;

public record PaymentRequest(
        BigDecimal amount,
        String currency
) {
}
