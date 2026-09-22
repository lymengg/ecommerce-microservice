package com.ecommerce.integration;

import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.model.ProviderTransaction;
import com.ecommerce.payment.repository.PaymentRepository;
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

/**
 * Phase 7e — provider failure and duplicate webhook (doc 10 §7). The duplicate
 * webhook case already existed from Phase 4/6; the point of these tests is that
 * it <em>still</em> holds now that the saga retries payment initiation, and
 * that a provider failure cannot be charged twice by a retry.
 */
class PaymentFailureIT extends AbstractIntegrationTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProviderTransactionRepository transactionRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @BeforeEach
    void stubOrder() {
        asUser(CUSTOMER_ID, "CUSTOMER");
        stubOrderTotal("110.00");
    }

    private void stubOrderTotal(String total) {
        WIRE_MOCK.stubFor(get(urlMatching("/api/v1/orders/" + ORDER_ID))
                .willReturn(okJson("""
                        {"orderId":"%s","customerId":"%s","status":"PENDING","currency":"USD",
                         "subtotal":100.00,"discount":0,"tax":10.00,"shippingCost":0,"total":%s,
                         "createdAt":"2026-01-01T00:00:00Z","items":[]}
                        """.formatted(ORDER_ID, CUSTOMER_ID, total))));
    }

    /**
     * The provider declines, so the saga compensates. A retry of initiation
     * (the resilience layer retries it, because it carries an Idempotency-Key)
     * must reuse the same payment and the same provider transaction — a second
     * charge would be the worst possible outcome.
     */
    @Test
    void aDeclinedPaymentIsChargedOnceEvenWhenInitiationIsRetried() {
        stubOrderTotal("20000.00"); // above ecommerce.payment.decline-above

        PaymentResponse first = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-declined");
        assertThat(first.status()).isEqualTo("FAILED");

        PaymentResponse retry = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-declined");

        assertThat(retry.paymentId()).isEqualTo(first.paymentId());
        assertThat(retry.status()).isEqualTo("FAILED");
        assertThat(paymentRepository.count()).as("one payment, not two").isEqualTo(1);
        assertThat(transactionRepository.count()).as("one provider transaction, not two").isEqualTo(1);
    }

    /** A retry that presents a *different* key still finds the payment by order. */
    @Test
    void aDifferentIdempotencyKeyOnRetryStillDoesNotCreateASecondPayment() {
        PaymentResponse first = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-a");
        PaymentResponse retry = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-b");

        assertThat(retry.paymentId()).isEqualTo(first.paymentId());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    /** Phase 4/6 guarantee, re-verified: a redelivered webhook is a no-op. */
    @Test
    void aDuplicateWebhookIsStillProcessedExactlyOnce() {
        PaymentResponse payment = paymentService.initiate(new PaymentInitiateRequest(ORDER_ID), "pay-key-webhook");
        ProviderTransaction txn = transactionRepository.findAll().get(0);
        WebhookRequest webhook = new WebhookRequest("evt-dup", txn.getProviderTransactionId(), "SUCCEEDED",
                "payment.succeeded");

        paymentService.handleWebhook("mock", webhook);
        paymentService.handleWebhook("mock", webhook);
        paymentService.handleWebhook("mock", webhook);

        assertThat(payment.paymentId()).isNotNull();
        assertThat(webhookEventRepository.count()).as("deduplicated by providerEventId").isEqualTo(1);
        assertThat(paymentRepository.findById(payment.paymentId()).orElseThrow().getStatus().name())
                .isEqualTo("SUCCEEDED");
    }
}
