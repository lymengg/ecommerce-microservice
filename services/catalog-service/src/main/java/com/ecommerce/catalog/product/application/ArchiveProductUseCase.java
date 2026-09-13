package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;

import java.util.Optional;

public interface ArchiveProductUseCase {

    Optional<Product> archive(Long id);
}
