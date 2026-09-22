package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal service-to-service endpoints. SERVICE tokens only, and never routed
 * through the gateway.
 *
 * <p>Added in Phase 7 for the order reconciliation job (ADR-020): the job needs
 * the authoritative payment state for a stuck order and must ask the owning
 * service rather than read its database (ADR-003).
 */
@RestController
@RequestMapping("/internal/api/v1/payments")
@PreAuthorize("hasRole('SERVICE')")
public class PaymentInternalController {

    private final PaymentService paymentService;

    public PaymentInternalController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/by-order/{orderId}")
    public PaymentResponse byOrder(@PathVariable UUID orderId) {
        return paymentService.getByOrder(orderId);
    }
}
