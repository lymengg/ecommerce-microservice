package com.ecommerce.integration;

import com.ecommerce.catalog.dto.ProductRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the internal active-product lookup consumed by cart and order
 * services over REST.
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
        mockMvc.perform(post("/api/v1/products/{id}/activate", productId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.price").value(10.00));
    }

    @Test
    void inactiveProductIsNotFound() throws Exception {
        long productId = createProduct("SKU-INT-2", "5.00");

        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/internal/api/v1/catalog/products/{id}", 999))
                .andExpect(status().isNotFound());
    }

    private long createProduct(String sku, String price) throws Exception {
        String body = objectMapper.writeValueAsString(new ProductRequest(sku, sku + " product", null, new BigDecimal(price)));
        String response = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(response, JsonNode.class).get("id").asLong();
    }
}
