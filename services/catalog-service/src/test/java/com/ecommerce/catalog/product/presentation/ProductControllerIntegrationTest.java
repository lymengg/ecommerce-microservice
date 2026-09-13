package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.CatalogApplication;
import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductStatus;
import com.ecommerce.catalog.product.infrastructure.persistence.ProductJpaEntity;
import com.ecommerce.catalog.product.infrastructure.persistence.ProductJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CatalogApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class ProductControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_catalog_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @BeforeEach
    void setUp() {
        productJpaRepository.deleteAll();
    }

    @Test
    void shouldCreateProduct() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                "TEST-SKU",
                "Test Product",
                "Test Description",
                BigDecimal.valueOf(99.99),
                "USD"
        );

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku", is("TEST-SKU")))
                .andExpect(jsonPath("$.name", is("Test Product")))
                .andExpect(jsonPath("$.description", is("Test Description")))
                .andExpect(jsonPath("$.price", is(99.99)))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void shouldReturn400WhenRequestIsInvalid() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                "",
                "",
                "Test Description",
                BigDecimal.valueOf(-10.00),
                "US"
        );

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").isString())
                .andExpect(jsonPath("$.title", is("Validation Error")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.errors", hasSize(greaterThan(0))));
    }

    @Test
    void shouldGetProductById() throws Exception {
        Product product = createTestProduct("GET-SKU", "Get Product");

        mockMvc.perform(get("/api/v1/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(product.getId().intValue())))
                .andExpect(jsonPath("$.sku", is("GET-SKU")))
                .andExpect(jsonPath("$.name", is("Get Product")));
    }

    @Test
    void shouldReturn404WhenProductNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn400WhenDuplicateSku() throws Exception {
        createTestProduct("DUPLICATE-SKU", "First Product");

        CreateProductRequest request = new CreateProductRequest(
                "DUPLICATE-SKU",
                "Second Product",
                "Description",
                BigDecimal.valueOf(49.99),
                "EUR"
        );

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Product Domain Error")));
    }

    @Test
    void shouldListProductsWithPagination() throws Exception {
        createTestProduct("SKU-1", "Product 1");
        createTestProduct("SKU-2", "Product 2");
        createTestProduct("SKU-3", "Product 3");

        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)));
    }

    @Test
    void shouldUpdateProduct() throws Exception {
        Product product = createTestProduct("UPDATE-SKU", "Original Name");

        UpdateProductRequest request = new UpdateProductRequest(
                "Updated Name",
                "Updated Description",
                BigDecimal.valueOf(149.99),
                "EUR"
        );

        mockMvc.perform(put("/api/v1/products/{id}", product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Name")))
                .andExpect(jsonPath("$.description", is("Updated Description")))
                .andExpect(jsonPath("$.price", is(149.99)))
                .andExpect(jsonPath("$.currency", is("EUR")));
    }

    @Test
    void shouldArchiveProduct() throws Exception {
        Product product = createTestProduct("ARCHIVE-SKU", "Archive Product");

        mockMvc.perform(patch("/api/v1/products/{id}/archive", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ARCHIVED")));
    }

    private Product createTestProduct(String sku, String name) {
        ProductJpaEntity entity = new ProductJpaEntity();
        entity.setSku(sku);
        entity.setName(name);
        entity.setPrice(BigDecimal.valueOf(99.99));
        entity.setCurrency("USD");
        entity.setStatus(ProductStatus.ACTIVE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        ProductJpaEntity savedEntity = productJpaRepository.save(entity);
        
        return Product.builder()
                .id(savedEntity.getId())
                .sku(savedEntity.getSku())
                .name(savedEntity.getName())
                .price(savedEntity.getPrice())
                .currency(savedEntity.getCurrency())
                .status(savedEntity.getStatus())
                .createdAt(savedEntity.getCreatedAt())
                .updatedAt(savedEntity.getUpdatedAt())
                .build();
    }
}
