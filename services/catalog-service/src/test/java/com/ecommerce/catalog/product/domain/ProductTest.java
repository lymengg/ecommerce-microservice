package com.ecommerce.catalog.product.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProductTest {

    @Test
    void shouldCreateProductWithBuilder() {
        Instant now = Instant.now();
        Product product = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertNotNull(product);
        assertEquals(1L, product.getId());
        assertEquals("TEST-SKU", product.getSku());
        assertEquals("Test Product", product.getName());
        assertEquals("Test Description", product.getDescription());
        assertEquals(BigDecimal.valueOf(99.99), product.getPrice());
        assertEquals("USD", product.getCurrency());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
        assertEquals(now, product.getCreatedAt());
        assertEquals(now, product.getUpdatedAt());
    }

    @Test
    void shouldThrowExceptionWhenSkuIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.builder()
                        .name("Test Product")
                        .price(BigDecimal.valueOf(99.99))
                        .currency("USD")
                        .status(ProductStatus.ACTIVE)
                        .build()
        );
    }

    @Test
    void shouldThrowExceptionWhenNameIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.builder()
                        .sku("TEST-SKU")
                        .price(BigDecimal.valueOf(99.99))
                        .currency("USD")
                        .status(ProductStatus.ACTIVE)
                        .build()
        );
    }

    @Test
    void shouldThrowExceptionWhenPriceIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.builder()
                        .sku("TEST-SKU")
                        .name("Test Product")
                        .currency("USD")
                        .status(ProductStatus.ACTIVE)
                        .build()
        );
    }

    @Test
    void shouldThrowExceptionWhenCurrencyIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.builder()
                        .sku("TEST-SKU")
                        .name("Test Product")
                        .price(BigDecimal.valueOf(99.99))
                        .status(ProductStatus.ACTIVE)
                        .build()
        );
    }

    @Test
    void shouldThrowExceptionWhenStatusIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.builder()
                        .sku("TEST-SKU")
                        .name("Test Product")
                        .price(BigDecimal.valueOf(99.99))
                        .currency("USD")
                        .build()
        );
    }

    @Test
    void shouldArchiveProduct() {
        Instant now = Instant.now();
        Product product = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Product archivedProduct = product.archive();

        assertNotNull(archivedProduct);
        assertEquals(ProductStatus.ARCHIVED, archivedProduct.getStatus());
        assertNotEquals(now, archivedProduct.getUpdatedAt());
    }

    @Test
    void shouldThrowExceptionWhenArchivingAlreadyArchivedProduct() {
        Instant now = Instant.now();
        Product product = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ARCHIVED)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThrows(ProductDomainException.class, product::archive);
    }

    @Test
    void shouldHaveEqualProductsWithSameId() {
        Instant now = Instant.now();
        Product product1 = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Product product2 = Product.builder()
                .id(1L)
                .sku("TEST-SKU-2")
                .name("Test Product 2")
                .price(BigDecimal.valueOf(199.99))
                .currency("EUR")
                .status(ProductStatus.INACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertEquals(product1, product2);
        assertEquals(product1.hashCode(), product2.hashCode());
    }

    @Test
    void shouldNotEqualProductsWithDifferentIds() {
        Instant now = Instant.now();
        Product product1 = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        Product product2 = Product.builder()
                .id(2L)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertNotEquals(product1, product2);
    }
}
