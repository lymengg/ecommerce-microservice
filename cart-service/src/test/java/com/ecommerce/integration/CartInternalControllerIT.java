package com.ecommerce.integration;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the internal cart endpoints consumed by the checkout orchestrator.
 */
@AutoConfigureMockMvc
class CartInternalControllerIT extends AbstractIntegrationTest {

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
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), 1L, 2));

        mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", cart.cartId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].quantity").value(2));
    }

    @Test
    void checkoutEndpointClosesCart() throws Exception {
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), 1L, 1));

        mockMvc.perform(post("/internal/api/v1/cart/{cartId}/checkout", cart.cartId()))
                .andExpect(status().isNoContent());

        assertThat(cartService.getOrCreate(cart.cartId()).status()).isEqualTo("CHECKED_OUT");
    }

    @Test
    void linesEndpointRejectsCheckedOutCart() throws Exception {
        CartResponse cart = cartService.getOrCreate(null);
        cartService.addItem(new CartItemRequest(cart.cartId(), 1L, 1));
        cartService.markCheckedOut(cart.cartId());

        mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", cart.cartId()))
                .andExpect(status().isConflict());
    }

    @Test
    void linesEndpointRejectsUnknownCart() throws Exception {
        String body = mockMvc.perform(get("/internal/api/v1/cart/{cartId}/lines", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("title").asText()).isEqualTo("Not Found");
    }
}
