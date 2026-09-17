package com.ecommerce.integration;

import com.ecommerce.catalog.dto.ProductRequest;
import com.ecommerce.common.security.KeycloakJwtAuthoritiesConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the internal active-product lookup consumed by cart and order
 * services over REST (SERVICE tokens only) and the ADMIN-only product
 * lifecycle endpoints.
 */
@AutoConfigureMockMvc
class CatalogInternalControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void activeProductIsReturned() throws Exception {
        long productId = createProduct("SKU-INT-1", "10.00");
        mockMvc.perform(post("/api/v1/products/{id}/activate", productId)
                        .with(adminToken()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", productId)
                        .with(serviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.price").value(10.00));
    }

    @Test
    void inactiveProductIsNotFound() throws Exception {
        long productId = createProduct("SKU-INT-2", "5.00");

        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", productId)
                        .with(serviceToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", 999)
                        .with(serviceToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void productBrowsingIsPublicWithoutToken() throws Exception {
        long productId = createProduct("SKU-INT-PUBLIC", "10.00");

        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId));
    }

    @Test
    void internalLookupRejectsCustomerTokens() throws Exception {
        long productId = createProduct("SKU-INT-3", "5.00");

        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", productId)
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                                .authorities(new KeycloakJwtAuthoritiesConverter())))
                .andExpect(status().isForbidden());
    }

    @Test
    void productWritesRejectCustomerTokens() throws Exception {
        String body = objectMapper.writeValueAsString(new ProductRequest("SKU-INT-4", "sneaky product", null, new BigDecimal("1.00")));
        mockMvc.perform(post("/api/v1/products")
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                                .authorities(new KeycloakJwtAuthoritiesConverter()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private long createProduct(String sku, String price) throws Exception {
        String body = objectMapper.writeValueAsString(new ProductRequest(sku, sku + " product", null, new BigDecimal(price)));
        String response = mockMvc.perform(post("/api/v1/products")
                        .with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(response, JsonNode.class).get("id").asLong();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("SERVICE"))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminToken() {
        return jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("ADMIN"))))
                .authorities(new KeycloakJwtAuthoritiesConverter());
    }
}
