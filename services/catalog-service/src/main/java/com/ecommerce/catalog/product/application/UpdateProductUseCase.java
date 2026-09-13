package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;

import java.util.Optional;

public interface UpdateProductUseCase {

    Optional<Product> execute(Long id, UpdateProductCommand command);
}
