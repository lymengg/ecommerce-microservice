package com.ecommerce.catalog.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public class Product {

    private final ProductId id;
    private final String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String currency;
    private ProductStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    private Product(ProductId id, String sku, String name, String description,
                    BigDecimal price, String currency, ProductStatus status,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Product create(String sku, String name, String description,
                                 BigDecimal price, String currency) {
        Objects.requireNonNull(sku, "SKU must not be null");
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(price, "Price must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");

        if (sku.isBlank()) {
            throw new ProductDomainException("SKU must not be blank");
        }
        if (name.isBlank()) {
            throw new ProductDomainException("Name must not be blank");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProductDomainException("Price must be positive");
        }
        if (currency.isBlank()) {
            throw new ProductDomainException("Currency must not be blank");
        }

        Instant now = Instant.now();
        return new Product(
                ProductId.generate(),
                sku,
                name,
                description,
                price,
                currency.toUpperCase(),
                ProductStatus.ACTIVE,
                now,
                now
        );
    }

    public static Product restore(ProductId id, String sku, String name, String description,
                                  BigDecimal price, String currency, ProductStatus status,
                                  Instant createdAt, Instant updatedAt) {
        return new Product(id, sku, name, description, price, currency, status, createdAt, updatedAt);
    }

    public void update(String name, String description, BigDecimal price, String currency) {
        if (status == ProductStatus.ARCHIVED) {
            throw new ProductDomainException("Cannot update archived product");
        }
        if (name != null) {
            if (name.isBlank()) {
                throw new ProductDomainException("Name must not be blank");
            }
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (price != null) {
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProductDomainException("Price must be positive");
            }
            this.price = price;
        }
        if (currency != null) {
            if (currency.isBlank()) {
                throw new ProductDomainException("Currency must not be blank");
            }
            this.currency = currency.toUpperCase();
        }
        this.updatedAt = Instant.now();
    }

    public void archive() {
        if (status == ProductStatus.ARCHIVED) {
            throw new ProductDomainException("Product is already archived");
        }
        this.status = ProductStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    public ProductId getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Product product = (Product) o;
        return Objects.equals(id, product.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
