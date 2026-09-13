package com.ecommerce.catalog.product.application;

import com.ecommerce.catalog.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ListProductsUseCase {

    Page<Product> execute(Pageable pageable);
}
