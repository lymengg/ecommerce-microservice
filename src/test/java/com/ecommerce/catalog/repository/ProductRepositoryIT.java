package com.ecommerce.catalog.repository;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void saveAndFindProduct() {
        Product product = new Product("SKU-IT-001", "USB-C Cable", "1m cable", new BigDecimal("9.99"));

        Product saved = productRepository.save(product);
        Product found = productRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getSku()).isEqualTo("SKU-IT-001");
        assertThat(found.getPrice()).isEqualByComparingTo(new BigDecimal("9.99"));
        assertThat(found.getStatus()).isEqualTo(com.ecommerce.catalog.model.ProductStatus.DRAFT);
    }
}
