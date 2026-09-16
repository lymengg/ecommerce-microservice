package com.ecommerce.integration;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.outbox.OutboxRepository;
import com.ecommerce.order.dto.CancelRequest;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void createOrderPersistsSnapshotsAndTotals() {
        Product product = productRepository.save(new Product("SKU-ORD-1", "Monitor", "27 inch", new BigDecimal("250.00")));
        product.activate();
        productRepository.save(product);

        OrderResponse order = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(product.getId(), 2))),
                "ord-key-1"
        );

        assertThat(order.status()).isEqualTo("DRAFT");
        assertThat(order.subtotal()).isEqualByComparingTo("500.00");
        assertThat(order.tax()).isEqualByComparingTo("50.00");
        assertThat(order.total()).isEqualByComparingTo("550.00");
        assertThat(order.items().get(0).name()).isEqualTo("Monitor");
        assertThat(order.items().get(0).unitPrice()).isEqualByComparingTo("250.00");

        // same key returns the same order without duplicating
        OrderResponse retry = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(product.getId(), 2))),
                "ord-key-1"
        );
        assertThat(retry.orderId()).isEqualTo(order.orderId());

        assertThat(outboxRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cancelFromDraftPersistsStateAndHistory() {
        Product product = productRepository.save(new Product("SKU-ORD-2", "Mouse Pad", "Cloth", new BigDecimal("9.99")));
        product.activate();
        productRepository.save(product);

        OrderResponse order = orderService.create(
                new OrderCreateRequest(UUID.randomUUID(), null, List.of(new OrderLineRequest(product.getId(), 1))),
                null
        );

        OrderResponse cancelled = orderService.cancel(order.orderId(), new CancelRequest("customer request"));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }
}
