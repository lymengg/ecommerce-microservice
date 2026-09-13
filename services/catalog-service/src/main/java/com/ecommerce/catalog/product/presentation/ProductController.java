package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.product.application.DuplicateSkuException;
import com.ecommerce.catalog.product.application.ProductApplicationService;
import com.ecommerce.catalog.product.application.ProductNotFoundException;
import com.ecommerce.catalog.product.application.CreateProductCommand;
import com.ecommerce.catalog.product.application.UpdateProductCommand;
import com.ecommerce.catalog.product.domain.Pageable;
import com.ecommerce.catalog.product.domain.ProductId;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductApplicationService productApplicationService;

    public ProductController(ProductApplicationService productApplicationService) {
        this.productApplicationService = productApplicationService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        CreateProductCommand command = new CreateProductCommand(
                request.sku(),
                request.name(),
                request.description(),
                request.price(),
                request.currency()
        );

        var product = productApplicationService.createProduct(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.fromDomain(product));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable String id) {
        var product = productApplicationService.getProductById(ProductId.of(id));
        return ResponseEntity.ok(ProductResponse.fromDomain(product));
    }

    @GetMapping
    public ResponseEntity<PagedProductResponse> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = Pageable.of(page, size, 100);
        var products = productApplicationService.getAllProducts(pageable);
        return ResponseEntity.ok(PagedProductResponse.fromDomain(products));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody UpdateProductRequest request) {
        UpdateProductCommand command = new UpdateProductCommand(
                request.name(),
                request.description(),
                request.price(),
                request.currency()
        );

        var product = productApplicationService.updateProduct(ProductId.of(id), command);
        return ResponseEntity.ok(ProductResponse.fromDomain(product));
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProductResponse> archiveProduct(@PathVariable String id) {
        var product = productApplicationService.archiveProduct(ProductId.of(id));
        return ResponseEntity.ok(ProductResponse.fromDomain(product));
    }
}
