package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.product.application.ArchiveProductUseCase;
import com.ecommerce.catalog.product.application.CreateProductCommand;
import com.ecommerce.catalog.product.application.CreateProductUseCase;
import com.ecommerce.catalog.product.application.GetProductUseCase;
import com.ecommerce.catalog.product.application.ListProductsUseCase;
import com.ecommerce.catalog.product.application.UpdateProductCommand;
import com.ecommerce.catalog.product.application.UpdateProductUseCase;
import com.ecommerce.catalog.product.domain.Product;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CreateProductUseCase createProductUseCase;
    private final GetProductUseCase getProductUseCase;
    private final ListProductsUseCase listProductsUseCase;
    private final UpdateProductUseCase updateProductUseCase;
    private final ArchiveProductUseCase archiveProductUseCase;

    public ProductController(
            CreateProductUseCase createProductUseCase,
            GetProductUseCase getProductUseCase,
            ListProductsUseCase listProductsUseCase,
            UpdateProductUseCase updateProductUseCase,
            ArchiveProductUseCase archiveProductUseCase
    ) {
        this.createProductUseCase = createProductUseCase;
        this.getProductUseCase = getProductUseCase;
        this.listProductsUseCase = listProductsUseCase;
        this.updateProductUseCase = updateProductUseCase;
        this.archiveProductUseCase = archiveProductUseCase;
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
        Product product = createProductUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.fromDomain(product));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return getProductUseCase.execute(id)
                .map(ProductResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> listProducts(Pageable pageable) {
        Page<ProductResponse> products = listProductsUseCase.execute(pageable)
                .map(ProductResponse::fromDomain);
        return ResponseEntity.ok(products);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        UpdateProductCommand command = new UpdateProductCommand(
                request.name(),
                request.description(),
                request.price(),
                request.currency()
        );
        return updateProductUseCase.execute(id, command)
                .map(ProductResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<ProductResponse> archiveProduct(@PathVariable Long id) {
        return archiveProductUseCase.archive(id)
                .map(ProductResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
