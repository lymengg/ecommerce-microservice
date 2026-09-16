package com.ecommerce.catalog.service;

import com.ecommerce.catalog.dto.ProductRequest;
import com.ecommerce.catalog.dto.ProductResponse;
import com.ecommerce.catalog.dto.ProductUpdateRequest;
import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.model.ProductStatus;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.error.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductService productService = new ProductService(productRepository);

    @Test
    void createReturnsDraftProduct() {
        ProductRequest request = new ProductRequest("SKU-001", "Wireless Mouse", "Ergonomic mouse", new BigDecimal("29.99"));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.create(request);

        assertThat(response.sku()).isEqualTo("SKU-001");
        assertThat(response.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void getReturnsProductWhenPresent() {
        Product product = new Product("SKU-001", "Wireless Mouse", "Ergonomic mouse", new BigDecimal("29.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.get(1L);

        assertThat(response.name()).isEqualTo("Wireless Mouse");
    }

    @Test
    void getThrowsNotFoundWhenAbsent() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.get(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void activateMovesProductToActive() {
        Product product = new Product("SKU-001", "Wireless Mouse", null, new BigDecimal("29.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.activate(1L);

        assertThat(response.status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void archiveMovesProductToArchived() {
        Product product = new Product("SKU-001", "Wireless Mouse", null, new BigDecimal("29.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.archive(1L);

        assertThat(response.status()).isEqualTo(ProductStatus.ARCHIVED);
    }

    @Test
    void updateChangesDetailsOnly() {
        Product product = new Product("SKU-001", "Wireless Mouse", "Old description", new BigDecimal("29.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.update(1L, new ProductUpdateRequest("Wireless Mouse Pro", null, new BigDecimal("39.99")));

        assertThat(response.name()).isEqualTo("Wireless Mouse Pro");
        assertThat(response.description()).isEqualTo("Old description");
        assertThat(response.price()).isEqualByComparingTo("39.99");
    }

    @Test
    void getActiveRejectsNonActiveProduct() {
        Product draft = new Product("SKU-001", "Wireless Mouse", null, new BigDecimal("29.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> productService.getActive(1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void getActiveReturnsActiveProduct() {
        Product product = new Product("SKU-001", "Wireless Mouse", null, new BigDecimal("29.99"));
        product.activate();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        Product result = productService.getActive(1L);

        assertThat(result.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }
}
