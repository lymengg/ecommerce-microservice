package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductDomainException;
import com.ecommerce.catalog.product.domain.ProductRepository;
import com.ecommerce.catalog.product.domain.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
public class ProductApplicationService implements
        CreateProductUseCase,
        GetProductUseCase,
        ListProductsUseCase,
        UpdateProductUseCase,
        ArchiveProductUseCase {

    private final ProductRepository productRepository;

    public ProductApplicationService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Product execute(CreateProductCommand command) {
        if (productRepository.existsBySku(command.sku())) {
            throw new ProductDomainException("Product with SKU '" + command.sku() + "' already exists");
        }

        Instant now = Instant.now();
        Product product = Product.builder()
                .sku(command.sku())
                .name(command.name())
                .description(command.description())
                .price(command.price())
                .currency(command.currency())
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return productRepository.save(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> execute(Long id) {
        return productRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> execute(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    @Override
    public Optional<Product> execute(Long id, UpdateProductCommand command) {
        return productRepository.findById(id)
                .map(existing -> Product.builder()
                        .id(existing.getId())
                        .sku(existing.getSku())
                        .name(command.name() != null ? command.name() : existing.getName())
                        .description(command.description() != null ? command.description() : existing.getDescription())
                        .price(command.price() != null ? command.price() : existing.getPrice())
                        .currency(command.currency() != null ? command.currency() : existing.getCurrency())
                        .status(existing.getStatus())
                        .createdAt(existing.getCreatedAt())
                        .updatedAt(Instant.now())
                        .build())
                .map(productRepository::save);
    }

    @Override
    public Optional<Product> archive(Long id) {
        return productRepository.findById(id)
                .map(Product::archive)
                .map(productRepository::save);
    }
}
