package com.ecommerce.catalog.product.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(Long id);

    Page<Product> findAll(Pageable pageable);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);
}
