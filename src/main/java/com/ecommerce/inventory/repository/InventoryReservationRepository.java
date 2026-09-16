package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.model.InventoryReservation;
import com.ecommerce.inventory.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    List<InventoryReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant expiresAt);
}
