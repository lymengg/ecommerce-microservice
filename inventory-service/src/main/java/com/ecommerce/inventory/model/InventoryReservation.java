package com.ecommerce.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.RESERVED;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "resolved_at", columnDefinition = "timestamptz")
    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public InventoryReservation() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public InventoryReservation(Long productId, int quantity, UUID orderId, Instant expiresAt) {
        this();
        this.productId = productId;
        this.quantity = quantity;
        this.orderId = orderId;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isReserved() {
        return status == ReservationStatus.RESERVED;
    }

    public void release() {
        this.status = ReservationStatus.RELEASED;
        this.resolvedAt = Instant.now();
    }

    public void commit() {
        this.status = ReservationStatus.COMMITTED;
        this.resolvedAt = Instant.now();
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
        this.resolvedAt = Instant.now();
    }
}
