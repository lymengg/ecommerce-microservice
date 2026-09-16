package com.ecommerce.cart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cart_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_items_cart_product", columnNames = {"cart_id", "product_id"})
})
public class CartItem {

    @Id
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false, columnDefinition = "timestamptz")
    private Instant addedAt;

    public CartItem() {
        this.id = UUID.randomUUID();
        this.addedAt = Instant.now();
    }

    public CartItem(UUID cartId, Long productId, String sku, int quantity) {
        this();
        this.cartId = cartId;
        this.productId = productId;
        this.sku = sku;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCartId() {
        return cartId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }
}
