package com.ecommerce.integration;

import com.ecommerce.common.security.KeycloakJwtAuthoritiesConverter;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import com.ecommerce.checkout.CheckoutServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the checkout saga at the checkout-service boundary:
 * the full flow (cart lines -> order creation -> reservation -> payment ->
 * commit/compensate) runs through the real REST stack against WireMock-stubbed
 * cart/order/inventory/payment services.
 */
@SpringBootTest(classes = CheckoutServiceApplication.class)
@AutoConfigureMockMvc
abstract class AbstractCheckoutIT {

    static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static final UUID CART_ID = UUID.randomUUID();
    static final UUID ORDER_ID = UUID.randomUUID();
    static final UUID PAYMENT_ID = UUID.randomUUID();
    static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    static {
        WIRE_MOCK.start();
    }

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void clientProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + WIRE_MOCK.port();
        registry.add("ecommerce.cart.base-url", () -> baseUrl);
        registry.add("ecommerce.order.base-url", () -> baseUrl);
        registry.add("ecommerce.inventory.base-url", () -> baseUrl);
        registry.add("ecommerce.payment.base-url", () -> baseUrl);
        registry.add("ecommerce.security.service-client.token-uri",
                () -> "http://localhost:" + WIRE_MOCK.port() + "/token");
    }

    @BeforeEach
    void stubServiceToken() {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/token"))
                .willReturn(okJson("""
                        {"access_token":"test-service-token","expires_in":300,"token_type":"Bearer"}
                        """)));
    }

    @AfterEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    ResultActions checkout(String idempotencyKey) throws Exception {
        var request = post("/api/v1/checkout")
                .with(jwt().jwt(j -> j.subject(CUSTOMER_ID.toString())
                                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                        .authorities(new KeycloakJwtAuthoritiesConverter()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartId\":\"" + CART_ID + "\",\"currency\":\"USD\"}");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    static String cartLinesJson() {
        return "[{\"cartId\":\"" + CART_ID + "\",\"productId\":1,\"sku\":\"SKU-1\",\"quantity\":2}]";
    }

    static String orderJson(String status) {
        return "{\"orderId\":\"" + ORDER_ID + "\",\"customerId\":null,\"status\":\"" + status
                + "\",\"currency\":\"USD\",\"subtotal\":20.00,\"discount\":0,\"tax\":2.00,"
                + "\"shippingCost\":0,\"total\":22.00,\"createdAt\":\"2026-01-01T00:00:00Z\",\"items\":[]}";
    }

    static String paymentJson(String status) {
        return "{\"paymentId\":\"" + PAYMENT_ID + "\",\"orderId\":\"" + ORDER_ID
                + "\",\"status\":\"" + status + "\",\"amount\":22.00,\"currency\":\"USD\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\"}";
    }

    static String insufficientStockProblemJson() {
        return "{\"type\":\"urn:problem:insufficient-stock\",\"title\":\"Insufficient Stock\","
                + "\"status\":409,\"detail\":\"Insufficient stock for product 1\"}";
    }

    void assertConflictSaga(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("payment declined")));
    }
}
