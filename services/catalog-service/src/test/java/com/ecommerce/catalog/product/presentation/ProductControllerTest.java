package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.product.application.ProductApplicationService;
import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;
import com.ecommerce.catalog.product.domain.ProductStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductApplicationService productApplicationService;

    @Test
    void shouldCreateProduct() throws Exception {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");

        when(productApplicationService.createProduct(any())).thenReturn(product);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "SKU-001",
                                    "name": "Test Product",
                                    "description": "Description",
                                    "price": 99.99,
                                    "currency": "USD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.price").value(99.99))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldReturnBadRequestWhenSkuIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Test Product",
                                    "description": "Description",
                                    "price": 99.99,
                                    "currency": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldReturnBadRequestWhenPriceIsNegative() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "SKU-001",
                                    "name": "Test Product",
                                    "description": "Description",
                                    "price": -10.00,
                                    "currency": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    void shouldGetProduct() throws Exception {
        ProductId productId = ProductId.generate();
        Product product = Product.restore(
                productId, "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );

        when(productApplicationService.getProductById(productId)).thenReturn(product);

        mockMvc.perform(get("/api/v1/products/" + productId.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Test Product"));
    }

    @Test
    void shouldReturnNotFoundWhenProductDoesNotExist() throws Exception {
        ProductId productId = ProductId.generate();

        when(productApplicationService.getProductById(productId))
                .thenThrow(new com.ecommerce.catalog.product.application.ProductNotFoundException("Product not found"));

        mockMvc.perform(get("/api/v1/products/" + productId.getValue()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product Not Found"));
    }
}
