package com.ecommerce.catalog.product.presentation;

import java.util.List;

public record PagedProductResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
    public static PagedProductResponse fromDomain(com.ecommerce.catalog.product.domain.Page<com.ecommerce.catalog.product.domain.Product> page) {
        return new PagedProductResponse(
                page.getContent().stream().map(ProductResponse::fromDomain).toList(),
                page.getPage(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}
