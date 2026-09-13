package com.ecommerce.catalog.product.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class Product {

    private final Long id;
    private final String sku;
    private final String name;
    private final String description;
    private final BigDecimal price;
    private final String currency;
    private final ProductStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Product(Builder builder) {
        this.id = builder.id;
        this.sku = builder.sku;
        this.name = builder.name;
        this.description = builder.description;
        this.price = builder.price;
        this.currency = builder.currency;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Product archive() {
        if (this.status == ProductStatus.ARCHIVED) {
            throw new ProductDomainException("Product is already archived");
        }
        return Product.builder()
                .id(this.id)
                .sku(this.sku)
                .name(this.name)
                .description(this.description)
                .price(this.price)
                .currency(this.currency)
                .status(ProductStatus.ARCHIVED)
                .createdAt(this.createdAt)
                .updatedAt(Instant.now())
                .build();
    }

    public Long getId() {
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

    public static class Builder {
        private Long id;
        private String sku;
        private String name;
        private String description;
        private BigDecimal price;
        private String currency;
        private ProductStatus status;
        private Instant createdAt;
        private Instant updatedAt;

        private Builder() {
        }

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder sku(String sku) {
            this.sku = sku;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder status(ProductStatus status) {
            this.status = status;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Product build() {
            Objects.requireNonNull(sku, "SKU must not be null");
            Objects.requireNonNull(name, "Name must not be null");
            Objects.requireNonNull(price, "Price must not be null");
            Objects.requireNonNull(currency, "Currency must not be null");
            Objects.requireNonNull(status, "Status must not be null");
            return new Product(this);
        }
    }
}
