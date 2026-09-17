package com.ecommerce.catalog.controller;

import com.ecommerce.catalog.dto.ProductResponse;
import com.ecommerce.catalog.service.ProductService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service endpoint: returns a product only when it is
 * ACTIVE (404 otherwise), mirroring {@link ProductService#getActive}. Used by
 * cart and order services for validation and server-authoritative pricing.
 * Only callers presenting a SERVICE token (client credentials) may reach it.
 */
@RestController
@RequestMapping("/internal/api/v1/catalog/products")
@PreAuthorize("hasRole('SERVICE')")
public class CatalogInternalController {

    private final ProductService productService;

    public CatalogInternalController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{id}")
    public ProductResponse getActive(@PathVariable Long id) {
        return ProductResponse.from(productService.getActive(id));
    }
}
