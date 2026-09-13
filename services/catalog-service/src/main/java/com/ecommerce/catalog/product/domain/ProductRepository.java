package com.ecommerce.catalog.product.domain;

import java.util.Optional;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(ProductId id);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    Page<Product> findAll(Pageable pageable);
}
