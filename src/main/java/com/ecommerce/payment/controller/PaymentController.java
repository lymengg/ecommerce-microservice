package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.RefundRequest;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse initiate(@Valid @RequestBody PaymentInitiateRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return paymentService.initiate(request, idempotencyKey);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return paymentService.get(paymentId);
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResponse refund(@PathVariable UUID paymentId,
                                  @Valid @RequestBody(required = false) RefundRequest request) {
        return paymentService.refund(paymentId, request);
    }

    @PostMapping("/webhooks/{provider}")
    public PaymentResponse webhook(@PathVariable String provider,
                                   @Valid @RequestBody WebhookRequest request) {
        return paymentService.handleWebhook(provider, request);
    }
}
