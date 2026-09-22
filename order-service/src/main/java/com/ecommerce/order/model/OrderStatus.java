package com.ecommerce.order.model;

public enum OrderStatus {
    DRAFT,
    PENDING,
    PAYMENT_PENDING,
    PAID,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    /**
     * Terminal "a human must look at this" state, entered by the reconciliation
     * job when an order could not be repaired after its attempt budget
     * (ADR-020). It exists so a genuinely unrepairable order stops being
     * retried forever instead of quietly cycling through the job — the
     * "infinite loop" the roadmap warns about. An operator resolves it by
     * cancelling it through the normal ADMIN path.
     */
    NEEDS_ATTENTION
}
