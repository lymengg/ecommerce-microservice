package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.model.InventoryReservation;
import com.ecommerce.inventory.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    List<InventoryReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant expiresAt);

    /** Authoritative read for the order reconciliation job (ADR-020). */
    List<InventoryReservation> findByOrderId(UUID orderId);

    /**
     * The idempotency lookup for {@code reserve} (ADR-019): the live reservation
     * for one order line, if one already exists. Backed by the partial unique
     * index {@code uk_reservations_order_product_active}.
     */
    Optional<InventoryReservation> findByOrderIdAndProductIdAndStatus(UUID orderId,
                                                                      Long productId,
                                                                      ReservationStatus status);
}
