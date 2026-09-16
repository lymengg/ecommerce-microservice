package com.ecommerce.catalog.service;

import com.ecommerce.catalog.dto.ProductRequest;
import com.ecommerce.catalog.dto.ProductResponse;
import com.ecommerce.catalog.dto.ProductUpdateRequest;
import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.model.ProductStatus;
import com.ecommerce.catalog.repository.ProductRepository;
import com.ecommerce.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(
                request.sku(),
                request.name(),
                request.description(),
                request.price()
        );
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = requireProduct(id);
        product.updateDetails(
                request.name() != null ? request.name() : product.getName(),
                request.description() != null ? request.description() : product.getDescription(),
                request.price() != null ? request.price() : product.getPrice()
        );
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse activate(Long id) {
        Product product = requireProduct(id);
        product.activate();
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse archive(Long id) {
        Product product = requireProduct(id);
        product.archive();
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(requireProduct(id));
    }

    @Transactional(readOnly = true)
    public Product getActive(Long id) {
        Product product = requireProduct(id);
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new NotFoundException("Product not available: " + id);
        }
        return product;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    private Product requireProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }
}
