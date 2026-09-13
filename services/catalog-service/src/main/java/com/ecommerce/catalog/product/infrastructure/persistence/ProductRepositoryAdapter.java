package com.ecommerce.catalog.product.infrastructure.persistence;

import com.ecommerce.catalog.product.domain.Page;
import com.ecommerce.catalog.product.domain.Pageable;
import com.ecommerce.catalog.product.domain.Product;
import com.ecommerce.catalog.product.domain.ProductId;
import com.ecommerce.catalog.product.domain.ProductRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    public ProductRepositoryAdapter(ProductJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Product save(Product product) {
        ProductJpaEntity entity = ProductJpaEntity.fromDomain(product);
        ProductJpaEntity savedEntity = jpaRepository.save(entity);
        return savedEntity.toDomain();
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jpaRepository.findById(id.getValue()).map(ProductJpaEntity::toDomain);
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return jpaRepository.findBySku(sku).map(ProductJpaEntity::toDomain);
    }

    @Override
    public boolean existsBySku(String sku) {
        return jpaRepository.existsBySku(sku);
    }

    @Override
    public Page<Product> findAll(Pageable pageable) {
        PageRequest pageRequest = PageRequest.of(pageable.getPage(), pageable.getSize());
        org.springframework.data.domain.Page<ProductJpaEntity> springPage = jpaRepository.findAll(pageRequest);

        return new Page<>(
                springPage.getContent().stream().map(ProductJpaEntity::toDomain).toList(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages()
        );
    }
}
