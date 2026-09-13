package com.ecommerce.catalog.product.infrastructure.persistence;

import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ProductRepositoryAdapter.class, ProductMapper.class})
class ProductRepositoryAdapterTest {

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
    private ProductRepositoryAdapter productRepositoryAdapter;

    @Test
    void shouldSaveProduct() {
        Product product = Product.builder()
                .sku("TEST-SKU")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Product savedProduct = productRepositoryAdapter.save(product);

        assertNotNull(savedProduct);
        assertNotNull(savedProduct.getId());
        assertEquals("TEST-SKU", savedProduct.getSku());
        assertEquals("Test Product", savedProduct.getName());
        assertEquals(ProductStatus.ACTIVE, savedProduct.getStatus());
    }

    @Test
    void shouldFindProductById() {
        Product product = Product.builder()
                .sku("TEST-SKU-2")
                .name("Test Product 2")
                .price(BigDecimal.valueOf(199.99))
                .currency("EUR")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Product savedProduct = productRepositoryAdapter.save(product);

        var foundProduct = productRepositoryAdapter.findById(savedProduct.getId());

        assertTrue(foundProduct.isPresent());
        assertEquals(savedProduct.getId(), foundProduct.get().getId());
        assertEquals("TEST-SKU-2", foundProduct.get().getSku());
    }

    @Test
    void shouldFindAllProductsWithPagination() {
        for (int i = 0; i < 5; i++) {
            Product product = Product.builder()
                    .sku("SKU-" + i)
                    .name("Product " + i)
                    .price(BigDecimal.valueOf(10.00 + i))
                    .currency("USD")
                    .status(ProductStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            productRepositoryAdapter.save(product);
        }

        Page<Product> products = productRepositoryAdapter.findAll(PageRequest.of(0, 3));

        assertNotNull(products);
        assertEquals(3, products.getContent().size());
        assertEquals(5, products.getTotalElements());
        assertEquals(2, products.getTotalPages());
    }

    @Test
    void shouldCheckIfSkuExists() {
        Product product = Product.builder()
                .sku("EXISTING-SKU")
                .name("Existing Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        productRepositoryAdapter.save(product);

        assertTrue(productRepositoryAdapter.existsBySku("EXISTING-SKU"));
        assertFalse(productRepositoryAdapter.existsBySku("NON-EXISTING-SKU"));
    }
}
