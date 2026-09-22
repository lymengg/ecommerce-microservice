package com.ecommerce.inventory.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.observability.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.ReservationResponse;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.model.InventoryReservation;
import com.ecommerce.inventory.model.MovementType;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryItemRepository;
import com.ecommerce.inventory.repository.InventoryMovementRepository;
import com.ecommerce.inventory.repository.InventoryReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private final InventoryItemRepository itemRepository = mock(InventoryItemRepository.class);
    private final InventoryReservationRepository reservationRepository = mock(InventoryReservationRepository.class);
    private final InventoryMovementRepository movementRepository = mock(InventoryMovementRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final InventoryService inventoryService =
            new InventoryService(itemRepository, reservationRepository, movementRepository, outboxService,
                    new ApplicationMetrics(new SimpleMeterRegistry()), Duration.ofMinutes(30));

    private InventoryItem item;

    @BeforeEach
    void setUp() {
        item = new InventoryItem(1L, "SKU-1", 10);
        when(itemRepository.findByProductId(1L)).thenReturn(Optional.of(item));
    }

    @Test
    void initializeStockCreatesNewItem() {
        when(itemRepository.findByProductId(2L)).thenReturn(Optional.empty());
        when(itemRepository.save(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = inventoryService.initializeStock(new StockRequest(2L, "SKU-2", 5));

        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.availableQuantity()).isEqualTo(5);
        verify(movementRepository).save(any());
    }

    @Test
    void reserveDeductsAvailableQuantity() {
        when(itemRepository.reserve(1L, 4)).thenReturn(1);
        when(reservationRepository.save(any(InventoryReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationResponse response = inventoryService.reserve(new ReservationRequest(1L, 4, UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED.name());
        verify(outboxService).record(any(), any(), org.mockito.ArgumentMatchers.eq("InventoryReserved"), any());
    }

    @Test
    void reserveRejectsWhenStockInsufficient() {
        when(itemRepository.reserve(1L, 99)).thenReturn(0);

        assertThatThrownBy(() -> inventoryService.reserve(new ReservationRequest(1L, 99, UUID.randomUUID())))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void releaseReturnsStockAndMarksReleased() {
        InventoryReservation reservation = new InventoryReservation(1L, 3, UUID.randomUUID(), Instant.now().plusSeconds(60));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(itemRepository.release(1L, 3)).thenReturn(1);

        ReservationResponse response = inventoryService.release(reservation.getId());

        assertThat(response.status()).isEqualTo(ReservationStatus.RELEASED.name());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void releaseRejectsAlreadyCommittedReservation() {
        InventoryReservation reservation = new InventoryReservation(1L, 3, UUID.randomUUID(), Instant.now().plusSeconds(60));
        reservation.commit();
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> inventoryService.release(reservation.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void commitMarksReservationCommitted() {
        InventoryReservation reservation = new InventoryReservation(1L, 3, UUID.randomUUID(), Instant.now().plusSeconds(60));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(itemRepository.commit(1L, 3)).thenReturn(1);

        ReservationResponse response = inventoryService.commit(reservation.getId());

        assertThat(response.status()).isEqualTo(ReservationStatus.COMMITTED.name());
    }

    @Test
    void releaseByOrderReleasesAllReservations() {
        InventoryReservation reservation = new InventoryReservation(1L, 2, UUID.randomUUID(), Instant.now().plusSeconds(60));
        when(reservationRepository.findByOrderIdAndStatus(reservation.getOrderId(), ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation));
        when(itemRepository.release(1L, 2)).thenReturn(1);
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        inventoryService.releaseByOrder(reservation.getOrderId());

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void expireOverdueReleasesExpiredReservations() {
        InventoryReservation reservation = new InventoryReservation(1L, 2, UUID.randomUUID(), Instant.now().minusSeconds(60));
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));
        when(itemRepository.release(1L, 2)).thenReturn(1);

        inventoryService.expireOverdueReservations();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void getStockThrowsWhenNoRecord() {
        when(itemRepository.findByProductId(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(9L))
                .isInstanceOf(NotFoundException.class);
    }
}
