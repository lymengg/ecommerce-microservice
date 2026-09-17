package com.ecommerce.integration;

import com.ecommerce.common.security.KeycloakJwtAuthoritiesConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Object-level authorization (OWASP A01): a customer may only reach their own
 * orders, admins may see everything, and the CUSTOMER-or-SERVICE trust
 * boundary for the checkout orchestrator works.
 */
@AutoConfigureMockMvc
class OrderSecurityIT extends AbstractIntegrationTest {

    private static final UUID CUSTOMER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CUSTOMER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void stubCatalogProduct() {
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlMatching("/internal/api/v1/catalog/products/1"))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-SEC-1","name":"Monitor","description":null,
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerSeesOnlyTheirOwnOrdersInList() throws Exception {
        createOrder(CUSTOMER_A);
        createOrder(CUSTOMER_B);

        mockMvc.perform(get("/api/v1/orders").with(user(CUSTOMER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerId").value(CUSTOMER_A.toString()));

        mockMvc.perform(get("/api/v1/orders").with(user(CUSTOMER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerId").value(CUSTOMER_B.toString()));
    }

    @Test
    void customerCannotReadAnotherCustomersOrder() throws Exception {
        UUID orderOfA = createOrder(CUSTOMER_A);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderOfA).with(user(CUSTOMER_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanReadOwnOrder() throws Exception {
        UUID orderOfA = createOrder(CUSTOMER_A);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderOfA).with(user(CUSTOMER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_A.toString()));
    }

    @Test
    void adminCanReadAnyOrder() throws Exception {
        UUID orderOfA = createOrder(CUSTOMER_A);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderOfA).with(role("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void customerCannotCancelAnotherCustomersOrder() throws Exception {
        UUID orderOfA = createOrder(CUSTOMER_A);

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderOfA).with(user(CUSTOMER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void mismatchingCustomerIdIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/orders").with(user(CUSTOMER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(CUSTOMER_B)))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceCallerCanCreateOrderOnBehalfOfCustomer() throws Exception {
        mockMvc.perform(post("/api/v1/orders").with(role("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(CUSTOMER_A)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_A.toString()));
    }

    @Test
    void serviceCallerCannotListOrders() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(role("SERVICE")))
                .andExpect(status().isForbidden());
    }

    private UUID createOrder(UUID customerId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders").with(user(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readValue(response, JsonNode.class).get("orderId").asText());
    }

    private String orderBody(UUID customerId) {
        return "{\"customerId\":\"" + customerId + "\",\"currency\":\"USD\","
                + "\"items\":[{\"productId\":1,\"quantity\":1}]}";
    }

    private static RequestPostProcessor user(UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString())
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }
}
