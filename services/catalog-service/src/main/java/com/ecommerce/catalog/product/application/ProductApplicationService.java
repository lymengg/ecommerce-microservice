package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Page;
import com.ecommerce.catalog.product.domain.Pageable;
import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;
import com.ecommerce.catalog.product.domain.ProductRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductApplicationService {

    private final ProductRepository productRepository;

    public ProductApplicationService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product createProduct(CreateProductCommand command) {
        if (productRepository.existsBySku(command.sku())) {
            throw new DuplicateSkuException("Product with SKU '" + command.sku() + "' already exists");
        }

        Product product = Product.create(
                command.sku(),
                command.name(),
                command.description(),
                command.price(),
                command.currency()
        );

        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Product getProductById(ProductId productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId.getValue()));
    }

    @Transactional(readOnly = true)
    public Page<Product> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    public Product updateProduct(ProductId productId, UpdateProductCommand command) {
        Product product = getProductById(productId);
        product.update(command.name(), command.description(), command.price(), command.currency());
        return productRepository.save(product);
    }

    public Product archiveProduct(ProductId productId) {
        Product product = getProductById(productId);
        product.archive();
        return productRepository.save(product);
    }
}
