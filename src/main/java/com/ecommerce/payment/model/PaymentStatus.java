package com.ecommerce.payment.model;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED,
    PARTIALLY_REFUNDED,
    REFUND_FAILED
}
