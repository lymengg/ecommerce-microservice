package com.ecommerce.integration;

import com.ecommerce.common.outbox.OutboxRepository;
import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.model.ProviderTransaction;
import com.ecommerce.payment.repository.ProviderTransactionRepository;
import com.ecommerce.payment.repository.WebhookEventRepository;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static com.ecommerce.integration.TestSecurity.asUser;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentServiceIT extends AbstractIntegrationTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ProviderTransactionRepository transactionRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void stubOrder() {
        asUser(CUSTOMER_ID, "CUSTOMER");
        WIRE_MOCK.stubFor(get(urlMatching("/api/v1/orders/" + ORDER_ID))
                .willReturn(okJson("""
                        {"orderId":"%s","customerId":"%s","status":"PENDING","currency":"USD",
                         "subtotal":100.00,"discount":0,"tax":10.00,"shippingCost":0,"total":110.00,
                         "createdAt":"2026-01-01T00:00:00Z","items":[]}
                        """.formatted(ORDER_ID, CUSTOMER_ID))));
    }

    @Test
    void initiateSucceedsAndWebhookIsIdempotent() {
        PaymentResponse payment = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-1");
        assertThat(payment.status()).isEqualTo("SUCCEEDED");
        assertThat(payment.amount()).isEqualByComparingTo("110.00");

        ProviderTransaction txn = transactionRepository.findAll().get(0);
        WebhookRequest webhook = new WebhookRequest("evt-1", txn.getProviderTransactionId(), "SUCCEEDED", "payment.succeeded");

        PaymentResponse first = paymentService.handleWebhook("mock", webhook);
        PaymentResponse second = paymentService.handleWebhook("mock", webhook);

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isGreaterThanOrEqualTo(2); // PaymentInitiated + PaymentSucceeded
    }

    @Test
    void refundAfterSuccess() {
        PaymentResponse payment = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), null);

        PaymentResponse refunded = paymentService.refund(payment.paymentId(), null);

        assertThat(refunded.status()).isEqualTo("REFUNDED");
    }
}
