package com.ecommerce.payment.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.observability.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.integration.TestSecurity;
import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderInfo;
import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.RefundRequest;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.gateway.PaymentGateway;
import com.ecommerce.payment.gateway.PaymentRequest;
import com.ecommerce.payment.gateway.PaymentResult;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentAttempt;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.model.ProviderTransaction;
import com.ecommerce.payment.model.Refund;
import com.ecommerce.payment.model.RefundStatus;
import com.ecommerce.payment.model.WebhookEvent;
import com.ecommerce.payment.repository.PaymentAttemptRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.repository.ProviderTransactionRepository;
import com.ecommerce.payment.repository.RefundRepository;
import com.ecommerce.payment.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentAttemptRepository attemptRepository = mock(PaymentAttemptRepository.class);
    private final ProviderTransactionRepository transactionRepository = mock(ProviderTransactionRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final WebhookEventRepository webhookEventRepository = mock(WebhookEventRepository.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final OutboxService outboxService = mock(OutboxService.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PaymentService paymentService = new PaymentService(
            paymentRepository, attemptRepository, transactionRepository, refundRepository,
            webhookEventRepository, orderClient, paymentGateway, outboxService,
            new ApplicationMetrics(meterRegistry));

    private final UUID orderId = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private final AtomicReference<Payment> savedPayment = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TestSecurity.asUser(CUSTOMER_ID, "CUSTOMER");
        when(orderClient.getOrder(orderId)).thenReturn(new OrderInfo(
                orderId, CUSTOMER_ID, "PENDING", "USD",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO,
                new BigDecimal("110.00"), Instant.now(), List.of()
        ));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment payment = inv.getArgument(0);
            savedPayment.set(payment);
            return payment;
        });
        when(attemptRepository.save(any(PaymentAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(ProviderTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
    }

    @Test
    void initiateSucceedsWhenGatewayApproves() {
        when(paymentGateway.process(any(PaymentRequest.class)))
                .thenReturn(new PaymentResult(PaymentStatus.SUCCEEDED, "txn-1"));

        PaymentResponse response = paymentService.initiate(new PaymentInitiateRequest(orderId), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED.name());
        assertThat(response.amount()).isEqualByComparingTo("110.00");
        verify(outboxService).record(any(), any(), org.mockito.ArgumentMatchers.eq("PaymentSucceeded"), any());
    }

    @Test
    void initiateFailsWhenGatewayDeclines() {
        when(paymentGateway.process(any(PaymentRequest.class)))
                .thenReturn(new PaymentResult(PaymentStatus.FAILED, "txn-2"));

        PaymentResponse response = paymentService.initiate(new PaymentInitiateRequest(orderId), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED.name());
        verify(outboxService).record(any(), any(), org.mockito.ArgumentMatchers.eq("PaymentFailed"), any());
    }

    @Test
    void initiateIsIdempotentPerKey() {
        when(paymentGateway.process(any(PaymentRequest.class)))
                .thenReturn(new PaymentResult(PaymentStatus.SUCCEEDED, "txn-1"));

        PaymentResponse first = paymentService.initiate(new PaymentInitiateRequest(orderId), "key-1");
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(savedPayment.get()));

        PaymentResponse second = paymentService.initiate(new PaymentInitiateRequest(orderId), "key-1");

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        verify(paymentRepository, org.mockito.Mockito.times(1)).save(any(Payment.class));
    }

    @Test
    void initiateRejectsCancelledOrder() {
        when(orderClient.getOrder(orderId)).thenReturn(new OrderInfo(
                orderId, CUSTOMER_ID, "CANCELLED", "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), List.of()
        ));

        assertThatThrownBy(() -> paymentService.initiate(new PaymentInitiateRequest(orderId), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void webhookIsIdempotentForDuplicateEvent() {
        Payment payment = new Payment(orderId, new BigDecimal("110.00"), "USD", null);
        payment.moveTo(PaymentStatus.SUCCEEDED);
        savedPayment.set(payment);
        ProviderTransaction txn = new ProviderTransaction("mock", "txn-1", payment.getId(), payment.getAmount(), "USD", PaymentStatus.SUCCEEDED);
        when(transactionRepository.findByProviderAndProviderTransactionId("mock", "txn-1")).thenReturn(Optional.of(txn));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(webhookEventRepository.findByProviderAndProviderEventId("mock", "evt-1")).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookRequest request = new WebhookRequest("evt-1", "txn-1", "SUCCEEDED", "payment.succeeded");
        PaymentResponse first = paymentService.handleWebhook("mock", request);

        when(webhookEventRepository.findByProviderAndProviderEventId("mock", "evt-1"))
                .thenReturn(Optional.of(new WebhookEvent("mock", "evt-1", "payment.succeeded", "{}")));
        PaymentResponse second = paymentService.handleWebhook("mock", request);

        assertThat(first.paymentId()).isEqualTo(second.paymentId());
        assertThat(first.status()).isEqualTo(PaymentStatus.SUCCEEDED.name());
        verify(webhookEventRepository, org.mockito.Mockito.times(1)).save(any(WebhookEvent.class));
    }

    @Test
    void refundMarksPaymentRefundedForFullAmount() {
        Payment payment = new Payment(orderId, new BigDecimal("110.00"), "USD", null);
        payment.moveTo(PaymentStatus.SUCCEEDED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.SUCCEEDED)).thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.refund(payment.getId(), null);

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED.name());
    }

    @Test
    void refundMarksPaymentPartiallyRefundedForPartialAmount() {
        Payment payment = new Payment(orderId, new BigDecimal("110.00"), "USD", null);
        payment.moveTo(PaymentStatus.SUCCEEDED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.SUCCEEDED)).thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.refund(payment.getId(), new RefundRequest(new BigDecimal("50.00"), "partial"));

        assertThat(response.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED.name());
    }

    @Test
    void refundRejectsOverRefund() {
        Payment payment = new Payment(orderId, new BigDecimal("110.00"), "USD", null);
        payment.moveTo(PaymentStatus.SUCCEEDED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentIdAndStatus(payment.getId(), RefundStatus.SUCCEEDED)).thenReturn(List.of());

        assertThatThrownBy(() -> paymentService.refund(payment.getId(), new RefundRequest(new BigDecimal("200.00"), "too much")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void refundRejectsFailedPayment() {
        Payment payment = new Payment(orderId, new BigDecimal("110.00"), "USD", null);
        payment.moveTo(PaymentStatus.FAILED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.refund(payment.getId(), null))
                .isInstanceOf(ConflictException.class);
    }
}
