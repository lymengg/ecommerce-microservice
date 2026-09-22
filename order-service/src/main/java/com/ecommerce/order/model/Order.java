package com.ecommerce.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal discount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(name = "shipping_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingCost;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    /**
     * How many times the reconciliation job has tried to repair this order
     * (ADR-020). Paired with {@link #nextReconciliationAt}, it turns an
     * unrepairable order into a bounded number of attempts followed by a
     * terminal {@code NEEDS_ATTENTION} state rather than an infinite loop.
     */
    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    /**
     * Not before this instant may the reconciliation job pick the order up
     * again. Doubles as the claim lease — the claiming transaction pushes it
     * into the future so a second instance cannot work the same order — and as
     * the per-order backoff after a failed repair.
     *
     * <p>Written by bulk update rather than through this entity
     * ({@code OrderRepository.holdForReconciliation}), because touching the
     * entity would fire {@code @PreUpdate} and move {@code updated_at}, which is
     * the staleness clock the claim query reads.
     */
    @Column(name = "next_reconciliation_at", columnDefinition = "timestamptz")
    private Instant nextReconciliationAt;

    @Version
    @Column(nullable = false)
    private long version;

    public Order() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Order(UUID customerId, String currency, BigDecimal subtotal, BigDecimal discount,
                 BigDecimal tax, BigDecimal shippingCost, BigDecimal total) {
        this();
        this.customerId = customerId;
        this.currency = currency;
        this.subtotal = subtotal;
        this.discount = discount;
        this.tax = tax;
        this.shippingCost = shippingCost;
        this.total = total;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getShippingCost() {
        return shippingCost;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getReconciliationAttempts() {
        return reconciliationAttempts;
    }

    public Instant getNextReconciliationAt() {
        return nextReconciliationAt;
    }

    public void moveTo(OrderStatus newStatus) {
        this.status = newStatus;
    }
}
