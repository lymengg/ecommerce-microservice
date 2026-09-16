package com.ecommerce.integration;

import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the internal order transition endpoints driven by the checkout
 * orchestrator (pending -> payment-pending -> paid) plus state machine guards.
 */
@AutoConfigureMockMvc
class OrderInternalControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void stubCatalogProduct() {
        WIRE_MOCK.stubFor(get(urlMatching("/internal/api/v1/catalog/products/1"))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-ORD-2","name":"Monitor","description":null,
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    private java.util.UUID createOrder() {
        OrderResponse order = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(1L, 1))),
                null
        );
        return order.orderId();
    }

    @Test
    void sagaTransitionsAdvanceStateMachine() throws Exception {
        java.util.UUID orderId = createOrder();

        mockMvc.perform(post("/internal/api/v1/orders/{orderId}/pending", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/internal/api/v1/orders/{orderId}/payment-pending", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        mockMvc.perform(post("/internal/api/v1/orders/{orderId}/paid", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void invalidTransitionIsConflict() throws Exception {
        java.util.UUID orderId = createOrder();

        mockMvc.perform(post("/internal/api/v1/orders/{orderId}/paid", orderId))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(post("/internal/api/v1/orders/{orderId}/pending", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
