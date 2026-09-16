package com.ecommerce.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundRequest(
        @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 500) String reason
) {
}
