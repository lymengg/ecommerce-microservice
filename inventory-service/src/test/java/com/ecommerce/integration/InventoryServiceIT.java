package com.ecommerce.integration;

import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.ReservationResponse;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.dto.StockResponse;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryReservationRepository;
import com.ecommerce.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryServiceIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Test
    void reserveCommitAndReleaseRoundTrip() {
        inventoryService.initializeStock(new StockRequest(1L, "SKU-INV-1", 10));

        UUID orderId = UUID.randomUUID();
        ReservationResponse reservation = inventoryService.reserve(new ReservationRequest(1L, 4, orderId));

        StockResponse afterReserve = inventoryService.getStock(1L);
        assertThat(afterReserve.availableQuantity()).isEqualTo(6);
        assertThat(afterReserve.reservedQuantity()).isEqualTo(4);

        inventoryService.commit(reservation.reservationId());
        assertThat(inventoryService.getStock(1L).committedQuantity()).isEqualTo(4);
        assertThat(reservationRepository.findById(reservation.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMMITTED);

        // release the remaining reservation for a second order
        ReservationResponse second = inventoryService.reserve(new ReservationRequest(1L, 2, UUID.randomUUID()));
        inventoryService.release(second.reservationId());
        assertThat(inventoryService.getStock(1L).reservedQuantity()).isEqualTo(0);
        assertThat(inventoryService.getStock(1L).availableQuantity()).isEqualTo(6);
        assertThat(reservationRepository.findById(second.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void oversellingIsPrevented() {
        inventoryService.initializeStock(new StockRequest(2L, "SKU-INV-2", 1));

        inventoryService.reserve(new ReservationRequest(2L, 1, UUID.randomUUID()));

        assertThatThrownBy(() -> inventoryService.reserve(new ReservationRequest(2L, 1, UUID.randomUUID())))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void releaseByOrderReleasesAllReservations() {
        inventoryService.initializeStock(new StockRequest(3L, "SKU-INV-3", 5));
        inventoryService.initializeStock(new StockRequest(4L, "SKU-INV-4", 5));
        UUID orderId = UUID.randomUUID();
        // Two lines of the same order, which is what a cart produces. (Two
        // reservations for the *same* product would now be one — see below.)
        inventoryService.reserve(new ReservationRequest(3L, 2, orderId));
        inventoryService.reserve(new ReservationRequest(4L, 1, orderId));

        inventoryService.releaseByOrder(orderId);

        assertThat(inventoryService.getStock(3L).availableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getStock(4L).availableQuantity()).isEqualTo(5);
        assertThat(inventoryService.getStock(3L).reservedQuantity()).isEqualTo(0);
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RELEASED)).hasSize(2);
    }

    /**
     * Phase 7 (ADR-019): reserving an order line is idempotent on
     * {@code (orderId, productId)} while RESERVED. This is the precondition for
     * the saga being allowed to retry the call at all — without it a retry after
     * a lost response would hold the same stock twice.
     */
    @Test
    void reservingTheSameOrderLineTwiceIsIdempotent() {
        inventoryService.initializeStock(new StockRequest(5L, "SKU-INV-5", 5));
        UUID orderId = UUID.randomUUID();

        ReservationResponse first = inventoryService.reserve(new ReservationRequest(5L, 2, orderId));
        ReservationResponse retry = inventoryService.reserve(new ReservationRequest(5L, 2, orderId));

        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED)).hasSize(1);
        assertThat(inventoryService.getStock(5L).reservedQuantity())
                .as("a retried reserve must not hold the stock twice")
                .isEqualTo(2);
    }

    /**
     * ...but only while RESERVED: once a reservation is released, the same line
     * may legitimately be reserved again (a retried saga, or a reconciliation
     * repair), which is why the unique index is partial.
     */
    @Test
    void aReleasedReservationCanBeReservedAgain() {
        inventoryService.initializeStock(new StockRequest(6L, "SKU-INV-6", 5));
        UUID orderId = UUID.randomUUID();

        ReservationResponse first = inventoryService.reserve(new ReservationRequest(6L, 2, orderId));
        inventoryService.release(first.reservationId());
        ReservationResponse second = inventoryService.reserve(new ReservationRequest(6L, 2, orderId));

        assertThat(second.reservationId()).isNotEqualTo(first.reservationId());
        assertThat(inventoryService.getStock(6L).reservedQuantity()).isEqualTo(2);
    }
}
