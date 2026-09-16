package com.ecommerce.integration;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.outbox.OutboxRepository;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.dto.WebhookRequest;
import com.ecommerce.payment.model.ProviderTransaction;
import com.ecommerce.payment.repository.ProviderTransactionRepository;
import com.ecommerce.payment.repository.WebhookEventRepository;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentServiceIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProviderTransactionRepository transactionRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private OrderResponse createOrder() {
        Product product = productRepository.save(new Product("SKU-PAY-1", "Webcam", "1080p", new BigDecimal("49.99")));
        product.activate();
        productRepository.save(product);
        return orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(product.getId(), 1))),
                null
        );
    }

    @Test
    void initiateSucceedsAndWebhookIsIdempotent() {
        OrderResponse order = createOrder();

        PaymentResponse payment = paymentService.initiate(new PaymentInitiateRequest(order.orderId()), "pay-key-1");
        assertThat(payment.status()).isEqualTo("SUCCEEDED");

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
        OrderResponse order = createOrder();
        PaymentResponse payment = paymentService.initiate(new PaymentInitiateRequest(order.orderId()), null);

        PaymentResponse refunded = paymentService.refund(payment.paymentId(), null);

        assertThat(refunded.status()).isEqualTo("REFUNDED");
    }
}
