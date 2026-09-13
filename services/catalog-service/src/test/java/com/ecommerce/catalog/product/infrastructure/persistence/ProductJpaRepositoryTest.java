package com.ecommerce.catalog.product.infrastructure.persistence;

import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;
import com.ecommerce.catalog.product.domain.ProductStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class ProductJpaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductJpaRepository productRepository;

    @Test
    void shouldSaveProduct() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        ProductJpaEntity entity = ProductJpaEntity.fromDomain(product);

        ProductJpaEntity savedEntity = entityManager.persistAndFlush(entity);

        assertNotNull(savedEntity.getId());
        assertEquals("SKU-001", savedEntity.getSku());
        assertEquals("Test Product", savedEntity.getName());
        assertEquals(BigDecimal.valueOf(99.99), savedEntity.getPrice());
        assertEquals("USD", savedEntity.getCurrency());
        assertEquals(ProductStatus.ACTIVE, savedEntity.getStatus());
    }

    @Test
    void shouldFindBySku() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        ProductJpaEntity entity = ProductJpaEntity.fromDomain(product);
        entityManager.persistAndFlush(entity);

        Optional<ProductJpaEntity> found = productRepository.findBySku("SKU-001");

        assertTrue(found.isPresent());
        assertEquals("SKU-001", found.get().getSku());
    }

    @Test
    void shouldCheckIfSkuExists() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        ProductJpaEntity entity = ProductJpaEntity.fromDomain(product);
        entityManager.persistAndFlush(entity);

        assertTrue(productRepository.existsBySku("SKU-001"));
        assertFalse(productRepository.existsBySku("SKU-002"));
    }

    @Test
    void shouldThrowExceptionWhenDuplicateSku() {
        Product product1 = Product.create("SKU-001", "Test Product 1", "Description 1", BigDecimal.valueOf(99.99), "USD");
        Product product2 = Product.create("SKU-001", "Test Product 2", "Description 2", BigDecimal.valueOf(149.99), "EUR");

        ProductJpaEntity entity1 = ProductJpaEntity.fromDomain(product1);
        entityManager.persistAndFlush(entity1);

        ProductJpaEntity entity2 = ProductJpaEntity.fromDomain(product2);
        assertThrows(Exception.class, () -> entityManager.persistAndFlush(entity2));
    }

    @Test
    void shouldConvertToDomain() {
        Product product = Product.create("SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD");
        ProductJpaEntity entity = ProductJpaEntity.fromDomain(product);
        entityManager.persistAndFlush(entity);

        Product domainProduct = entity.toDomain();

        assertNotNull(domainProduct);
        assertEquals(entity.getId(), domainProduct.getId().getValue());
        assertEquals(entity.getSku(), domainProduct.getSku());
        assertEquals(entity.getName(), domainProduct.getName());
        assertEquals(entity.getDescription(), domainProduct.getDescription());
        assertEquals(entity.getPrice(), domainProduct.getPrice());
        assertEquals(entity.getCurrency(), domainProduct.getCurrency());
        assertEquals(entity.getStatus(), domainProduct.getStatus());
    }
}
