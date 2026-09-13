package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Page;
import com.ecommerce.catalog.product.domain.Pageable;
import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;
import com.ecommerce.catalog.product.domain.ProductRepository;
import com.ecommerce.catalog.product.domain.ProductStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductApplicationServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductApplicationService productApplicationService;

    @BeforeEach
    void setUp() {
        productApplicationService = new ProductApplicationService(productRepository);
    }

    @Test
    void shouldCreateProduct() {
        CreateProductCommand command = new CreateProductCommand(
                "SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD"
        );

        when(productRepository.existsBySku("SKU-001")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            return Product.restore(
                    product.getId(), product.getSku(), product.getName(), product.getDescription(),
                    product.getPrice(), product.getCurrency(), product.getStatus(),
                    product.getCreatedAt(), product.getUpdatedAt()
            );
        });

        Product result = productApplicationService.createProduct(command);

        assertNotNull(result);
        assertEquals("SKU-001", result.getSku());
        assertEquals("Test Product", result.getName());
        assertEquals(BigDecimal.valueOf(99.99), result.getPrice());
        assertEquals(ProductStatus.ACTIVE, result.getStatus());
        verify(productRepository).existsBySku("SKU-001");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void shouldThrowExceptionWhenSkuAlreadyExists() {
        CreateProductCommand command = new CreateProductCommand(
                "SKU-001", "Test Product", "Description", BigDecimal.valueOf(99.99), "USD"
        );

        when(productRepository.existsBySku("SKU-001")).thenReturn(true);

        assertThrows(DuplicateSkuException.class, () -> productApplicationService.createProduct(command));
        verify(productRepository).existsBySku("SKU-001");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void shouldGetProductById() {
        ProductId productId = ProductId.generate();
        Product product = Product.restore(
                productId, "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        Product result = productApplicationService.getProductById(productId);

        assertNotNull(result);
        assertEquals(productId, result.getId());
        verify(productRepository).findById(productId);
    }

    @Test
    void shouldThrowExceptionWhenProductNotFound() {
        ProductId productId = ProductId.generate();

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productApplicationService.getProductById(productId));
        verify(productRepository).findById(productId);
    }

    @Test
    void shouldGetAllProducts() {
        ProductId productId = ProductId.generate();
        Product product = Product.restore(
                productId, "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );

        Page<Product> page = new Page<>(List.of(product), 0, 20, 1, 1);
        Pageable pageable = Pageable.of(0, 20);

        when(productRepository.findAll(pageable)).thenReturn(page);

        Page<Product> result = productApplicationService.getAllProducts(pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(0, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
        verify(productRepository).findAll(pageable);
    }

    @Test
    void shouldUpdateProduct() {
        ProductId productId = ProductId.generate();
        Product product = Product.restore(
                productId, "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );

        UpdateProductCommand command = new UpdateProductCommand(
                "Updated Product", "Updated Description", BigDecimal.valueOf(149.99), "EUR"
        );

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            return Product.restore(
                    p.getId(), p.getSku(), p.getName(), p.getDescription(),
                    p.getPrice(), p.getCurrency(), p.getStatus(),
                    p.getCreatedAt(), p.getUpdatedAt()
            );
        });

        Product result = productApplicationService.updateProduct(productId, command);

        assertNotNull(result);
        assertEquals("Updated Product", result.getName());
        assertEquals("Updated Description", result.getDescription());
        assertEquals(BigDecimal.valueOf(149.99), result.getPrice());
        assertEquals("EUR", result.getCurrency());
        verify(productRepository).findById(productId);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void shouldArchiveProduct() {
        ProductId productId = ProductId.generate();
        Product product = Product.restore(
                productId, "SKU-001", "Test Product", "Description",
                BigDecimal.valueOf(99.99), "USD", ProductStatus.ACTIVE,
                Instant.now(), Instant.now()
        );

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            return Product.restore(
                    p.getId(), p.getSku(), p.getName(), p.getDescription(),
                    p.getPrice(), p.getCurrency(), p.getStatus(),
                    p.getCreatedAt(), p.getUpdatedAt()
            );
        });

        Product result = productApplicationService.archiveProduct(productId);

        assertNotNull(result);
        assertEquals(ProductStatus.ARCHIVED, result.getStatus());
        verify(productRepository).findById(productId);
        verify(productRepository).save(any(Product.class));
    }
}
