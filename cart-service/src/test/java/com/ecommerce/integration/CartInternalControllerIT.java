package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.common.security.KeycloakJwtAuthoritiesConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the internal cart endpoints consumed by the checkout orchestrator.
 * Only SERVICE tokens (client credentials) may reach them.
 */
@AutoConfigureMockMvc
class CartInternalControllerIT extends AbstractIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartService cartService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void stubCatalogProduct() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/catalog/products/1"))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-CART-2","name":"Mouse","description":null,
                         "price":19.99,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    @Test
    void linesEndpointReturnsCartLines() throws Exception {
        CartResponse cart = cartService.getOrCreate(CUSTOMER_ID);
        cartService.addItem(CUSTOMER_ID, new CartItemRequest(1L, 2));

        mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", cart.cartId())
                        .with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    void checkoutEndpointClosesCart() throws Exception {
        CartResponse cart = cartService.getOrCreate(CUSTOMER_ID);
        cartService.addItem(CUSTOMER_ID, new CartItemRequest(1L, 1));

        mockMvc.perform(post("/internal/api/v1/cart/{cartId}/checkout", cart.cartId())
                        .with(serviceToken()))
                .andExpect(status().isNoContent());

        assertThat(cartService.getOrCreate(CUSTOMER_ID).cartId()).isNotEqualTo(cart.cartId());
    }

    @Test
    void linesEndpointRejectsCheckedOutCart() throws Exception {
        CartResponse cart = cartService.getOrCreate(CUSTOMER_ID);
        cartService.addItem(CUSTOMER_ID, new CartItemRequest(1L, 1));
        cartService.markCheckedOut(cart.cartId());

        mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", cart.cartId())
                        .with(serviceToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void linesEndpointRejectsUnknownCart() throws Exception {
        String body = mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", UUID.randomUUID())
                        .with(serviceToken()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("title").asText()).isEqualTo("Not Found");
    }

    @Test
    void rejectsCustomerTokens() throws Exception {
        CartResponse cart = cartService.getOrCreate(CUSTOMER_ID);
        cartService.addItem(CUSTOMER_ID, new CartItemRequest(1L, 1));

        mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", cart.cartId())
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                                .authorities(new KeycloakJwtAuthoritiesConverter())))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("SERVICE"))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }
}
