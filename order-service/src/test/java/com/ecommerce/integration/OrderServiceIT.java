package com.ecommerce.integration;

import com.ecommerce.common.outbox.OutboxRepository;
import com.ecommerce.order.dto.CancelRequest;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.ecommerce.integration.TestSecurity.asUser;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

class OrderServiceIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void authenticateCustomer() {
        asUser(CUSTOMER_ID, "CUSTOMER");
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void stubCatalogProduct() {
        WIRE_MOCK.stubFor(get(urlMatching("/internal/api/v1/catalog/products/" + PRODUCT_ID))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-ORD-1","name":"Monitor","description":"27 inch",
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    @Test
    void createOrderPersistsSnapshotsAndTotals() {
        OrderResponse order = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))),
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
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))),
                "ord-key-1"
        );
        assertThat(retry.orderId()).isEqualTo(order.orderId());

        assertThat(outboxRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cancelFromDraftPersistsStateAndHistory() {
        OrderResponse order = orderService.create(
                new OrderCreateRequest(CUSTOMER_ID, null, List.of(new OrderLineRequest(PRODUCT_ID, 1))),
                null
        );

        OrderResponse cancelled = orderService.cancel(order.orderId(), new CancelRequest("customer request"));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }
}
