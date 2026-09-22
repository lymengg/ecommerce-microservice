package com.ecommerce.payment.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.common.security.SecurityRoles;
import com.ecommerce.common.security.SecurityUtils;
import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.client.OrderInfo;
import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.RefundRequest;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.gateway.PaymentGateway;
import com.ecommerce.payment.gateway.PaymentRequest;
import com.ecommerce.payment.gateway.PaymentResult;
import com.ecommerce.payment.model.AttemptStatus;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ProviderTransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final OrderClient orderClient;
    private final PaymentGateway paymentGateway;
    private final OutboxService outboxService;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentAttemptRepository attemptRepository,
                          ProviderTransactionRepository transactionRepository,
                          RefundRepository refundRepository,
                          WebhookEventRepository webhookEventRepository,
                          OrderClient orderClient,
                          PaymentGateway paymentGateway,
                          OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.orderClient = orderClient;
        this.paymentGateway = paymentGateway;
        this.outboxService = outboxService;
    }

    @Transactional
    public PaymentResponse initiate(PaymentInitiateRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return toResponse(requireOwnedOrService(existing));
            }
        }
        Payment existingByOrder = paymentRepository.findByOrderId(request.orderId()).orElse(null);
        if (existingByOrder != null) {
            return toResponse(requireOwnedOrService(existingByOrder));
        }

        OrderInfo order = orderClient.getOrder(request.orderId());
        if ("CANCELLED".equals(order.status())) {
            throw new ConflictException("Cannot initiate payment for cancelled order " + request.orderId());
        }
        UUID customerId = resolveCustomerId(order);

        Payment payment = paymentRepository.save(new Payment(
                request.orderId(), customerId, order.total(), order.currency(), idempotencyKey
        ));
        PaymentAttempt attempt = attemptRepository.save(new PaymentAttempt(
                payment.getId(), PaymentGateway.PROVIDER_NAME, 1, AttemptStatus.PROCESSING
        ));

        PaymentResult result = paymentGateway.process(new PaymentRequest(payment.getAmount(), payment.getCurrency()));
        transactionRepository.save(new ProviderTransaction(
                PaymentGateway.PROVIDER_NAME, result.providerTransactionId(), payment.getId(),
                payment.getAmount(), payment.getCurrency(), result.status()
        ));

        payment.moveTo(result.status());
        attempt.mark(result.status() == PaymentStatus.SUCCEEDED ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED);

        outboxService.record("payment", payment.getId().toString(), "PaymentInitiated", Map.of(
                "paymentId", payment.getId().toString(),
                "orderId", request.orderId().toString(),
                "amount", payment.getAmount().toPlainString(),
                "currency", payment.getCurrency()
        ));
        recordOutcome(payment, result.status());
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse handleWebhook(String provider, WebhookRequest request) {
        WebhookEvent event = webhookEventRepository
                .findByProviderAndProviderEventId(provider, request.providerEventId())
                .orElse(null);
        if (event != null) {
            // duplicate delivery: no state changes
            return toResponse(paymentByTransaction(provider, request.transactionId()));
        }
        event = webhookEventRepository.save(new WebhookEvent(provider, request.providerEventId(), request.type(), "{}"));

        ProviderTransaction transaction = transactionRepository
                .findByProviderAndProviderTransactionId(provider, request.transactionId())
                .orElseThrow(() -> new NotFoundException("Unknown provider transaction: " + request.transactionId()));
        Payment payment = paymentRepository.findById(transaction.getPaymentId())
                .orElseThrow(() -> new NotFoundException("Payment not found for transaction"));

        PaymentStatus incoming = PaymentStatus.valueOf(request.status());
        if (!payment.isTerminal()) {
            payment.moveTo(incoming);
            recordOutcome(payment, incoming);
        }
        event.markProcessed();
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse refund(UUID paymentId, RefundRequest request) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new ConflictException("Only a succeeded payment can be refunded");
        }

        BigDecimal refundedSoFar = refundRepository.findByPaymentIdAndStatus(paymentId, RefundStatus.SUCCEEDED).stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundable = payment.getAmount().subtract(refundedSoFar);
        BigDecimal amount = request != null && request.amount() != null ? request.amount() : refundable;

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(refundable) > 0) {
            throw new ConflictException("Refund amount exceeds refundable amount");
        }

        Refund refund = refundRepository.save(new Refund(
                payment.getId(), amount, payment.getCurrency(),
                request != null ? request.reason() : null
        ));
        refund.succeed(); // mock provider always approves the refund

        BigDecimal totalRefunded = refundedSoFar.add(amount);
        payment.moveTo(totalRefunded.compareTo(payment.getAmount()) >= 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED);
        return toResponse(payment);
    }

    /**
     * Authoritative payment state for an order, read by the order reconciliation
     * job (ADR-020). SERVICE-only, and read-only: reconciliation asks the owning
     * service rather than reaching into its tables (ADR-003).
     */
    @Transactional(readOnly = true)
    public PaymentResponse getByOrder(UUID orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("No payment for order: " + orderId));
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
        if (SecurityUtils.isService()) {
            throw new AccessDeniedException("Service callers cannot read payments through this endpoint");
        }
        return toResponse(requireAccessible(payment));
    }

    /**
     * Identity of the payment's customer. For user tokens the order must
     * belong to the caller; SERVICE callers (the checkout orchestrator) are
     * trusted because checkout has already validated the end user.
     */
    private UUID resolveCustomerId(OrderInfo order) {
        if (SecurityUtils.isService()) {
            return order.customerId();
        }
        UUID subject = SecurityUtils.currentCustomerId();
        if (subject == null || !subject.equals(order.customerId())) {
            throw new AccessDeniedException("Order does not belong to the authenticated customer");
        }
        return subject;
    }

    /** Ownership check for idempotent replay: customers only reach their own payments. */
    private Payment requireOwnedOrService(Payment payment) {
        if (SecurityUtils.isService() || SecurityUtils.hasRole(SecurityRoles.ADMIN)) {
            return payment;
        }
        return requireAccessible(payment);
    }

    /**
     * Object-level authorization (OWASP A01): a customer may only read their
     * own payments; other customers' payments are indistinguishable from
     * missing ones (404).
     */
    private Payment requireAccessible(Payment payment) {
        if (SecurityUtils.hasRole(SecurityRoles.ADMIN)) {
            return payment;
        }
        UUID subject = SecurityUtils.currentCustomerId();
        if (subject == null || !subject.equals(payment.getCustomerId())) {
            throw new NotFoundException("Payment not found: " + payment.getId());
        }
        return payment;
    }

    private void recordOutcome(Payment payment, PaymentStatus status) {
        String eventType = status == PaymentStatus.SUCCEEDED ? "PaymentSucceeded" : "PaymentFailed";
        outboxService.record("payment", payment.getId().toString(), eventType, Map.of(
                "paymentId", payment.getId().toString(),
                "orderId", payment.getOrderId().toString(),
                "amount", payment.getAmount().toPlainString(),
                "currency", payment.getCurrency()
        ));
    }

    private Payment paymentByTransaction(String provider, String transactionId) {
        ProviderTransaction transaction = transactionRepository
                .findByProviderAndProviderTransactionId(provider, transactionId)
                .orElseThrow(() -> new NotFoundException("Unknown provider transaction: " + transactionId));
        return paymentRepository.findById(transaction.getPaymentId())
                .orElseThrow(() -> new NotFoundException("Payment not found for transaction"));
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(), payment.getOrderId(), payment.getStatus().name(),
                payment.getAmount(), payment.getCurrency(), payment.getCreatedAt()
        );
    }
}
