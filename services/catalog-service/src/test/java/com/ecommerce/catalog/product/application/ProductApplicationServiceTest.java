package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductDomainException;
import com.ecommerce.catalog.product.domain.ProductRepository;
import com.ecommerce.catalog.product.domain.ProductStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductApplicationServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductApplicationService productApplicationService;

    @Test
    void shouldCreateProductSuccessfully() {
        CreateProductCommand command = new CreateProductCommand(
                "TEST-SKU",
                "Test Product",
                "Test Description",
                BigDecimal.valueOf(99.99),
                "USD"
        );

        Product savedProduct = Product.builder()
                .id(1L)
                .sku("TEST-SKU")
                .name("Test Product")
                .description("Test Description")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(productRepository.existsBySku("TEST-SKU")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        Product result = productApplicationService.execute(command);

        assertNotNull(result);
        assertEquals("TEST-SKU", result.getSku());
        assertEquals("Test Product", result.getName());
        assertEquals(ProductStatus.ACTIVE, result.getStatus());
        verify(productRepository).existsBySku("TEST-SKU");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void shouldThrowExceptionWhenSkuAlreadyExists() {
        CreateProductCommand command = new CreateProductCommand(
                "EXISTING-SKU",
                "Test Product",
                "Test Description",
                BigDecimal.valueOf(99.99),
                "USD"
        );

        when(productRepository.existsBySku("EXISTING-SKU")).thenReturn(true);

        assertThrows(ProductDomainException.class, () ->
                productApplicationService.execute(command)
        );

        verify(productRepository).existsBySku("EXISTING-SKU");
        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldGetProductById() {
        Long productId = 1L;
        Product product = Product.builder()
                .id(productId)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        Optional<Product> result = productApplicationService.execute(productId);

        assertTrue(result.isPresent());
        assertEquals(productId, result.get().getId());
        verify(productRepository).findById(productId);
    }

    @Test
    void shouldReturnEmptyWhenProductNotFound() {
        Long productId = 999L;
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        Optional<Product> result = productApplicationService.execute(productId);

        assertTrue(result.isEmpty());
        verify(productRepository).findById(productId);
    }

    @Test
    void shouldArchiveProduct() {
        Long productId = 1L;
        Product product = Product.builder()
                .id(productId)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Product archivedProduct = Product.builder()
                .id(productId)
                .sku("TEST-SKU")
                .name("Test Product")
                .price(BigDecimal.valueOf(99.99))
                .currency("USD")
                .status(ProductStatus.ARCHIVED)
                .createdAt(product.getCreatedAt())
                .updatedAt(Instant.now())
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenReturn(archivedProduct);

        Optional<Product> result = productApplicationService.archive(productId);

        assertTrue(result.isPresent());
        assertEquals(ProductStatus.ARCHIVED, result.get().getStatus());
        verify(productRepository).findById(productId);
        verify(productRepository).save(any(Product.class));
    }
}
