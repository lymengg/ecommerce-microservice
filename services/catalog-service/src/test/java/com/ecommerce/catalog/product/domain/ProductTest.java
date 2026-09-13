package com.ecommerce.catalog.product.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProductTest {

    @Test
    void shouldCreateProductWithValidData() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");

        assertNotNull(product.getId());
        assertEquals("SKU-001", product.getSku());
        assertEquals("Test Product", product.getName());
        assertEquals("Description", product.getDescription());
        assertEquals(BigDecimal.valueOf(99.99), product.getPrice());
        assertEquals("USD", product.getCurrency());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
        assertNotNull(product.getCreatedAt());
        assertNotNull(product.getUpdatedAt());
    }

    @Test
    void shouldThrowExceptionWhenSkuIsNull() {
        assertThrows(NullPointerException.class, () ->
                Product.create(null, "Test Product", "Description", BigDecimal.valueOf(99.99), "USD"));
    }

    @Test
    void shouldThrowExceptionWhenSkuIsBlank() {
        assertThrows(ProductDomainException.class, () ->
                Product.create("", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD"));
    }

    @Test
    void shouldThrowExceptionWhenNameIsBlank() {
        assertThrows(ProductDomainException.class, () ->
                Product.create("SKU-001", "", "Description", BigDecimal.valueOf(99.99), "USD"));
    }

    @Test
    void shouldThrowExceptionWhenPriceIsZero() {
        assertThrows(ProductDomainException.class, () ->
                Product.create("SKU-001", "Test Product", "Description", BigDecimal.ZERO, "USD"));
    }

    @Test
    void shouldThrowExceptionWhenPriceIsNegative() {
        assertThrows(ProductDomainException.class, () ->
                Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(-10), "USD"));
    }

    @Test
    void shouldThrowExceptionWhenCurrencyIsBlank() {
        assertThrows(ProductDomainException.class, () ->
                Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), ""));
    }

    @Test
    void shouldUpdateProduct() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        Instant beforeUpdate = product.getUpdatedAt();

        product.update("Updated Product", "Updated Description", BigDecimal.valueOf(149.99), "EUR");

        assertEquals("Updated Product", product.getName());
        assertEquals("Updated Description", product.getDescription());
        assertEquals(BigDecimal.valueOf(149.99), product.getPrice());
        assertEquals("EUR", product.getCurrency());
        assertTrue(product.getUpdatedAt().isAfter(beforeUpdate));
    }

    @Test
    void shouldArchiveProduct() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");

        product.archive();

        assertEquals(ProductStatus.ARCHIVED, product.getStatus());
    }

    @Test
    void shouldThrowExceptionWhenArchivingAlreadyArchivedProduct() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        product.archive();

        assertThrows(ProductDomainException.class, product::archive);
    }

    @Test
    void shouldThrowExceptionWhenUpdatingArchivedProduct() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        product.archive();

        assertThrows(ProductDomainException.class, () ->
                product.update("Updated Product", null, null, null));
    }

    @Test
    void shouldEqualSameProduct() {
        Product product1 = Product.restore(
                ProductId.of("123"), "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );
        Product product2 = Product.restore(
                ProductId.of("123"), "SKU-002", "Other Product", "Other Description",
                BigDecimal.valueOf(149.99), "EUR", ProductStatus.ARCHIVED,
                Instant.now(), Instant.now()
        );

        assertEquals(product1, product2);
        assertEquals(product1.hashCode(), product2.hashCode());
    }
}
