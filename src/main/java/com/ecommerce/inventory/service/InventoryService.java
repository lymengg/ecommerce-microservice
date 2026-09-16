package com.ecommerce.inventory.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.ReservationResponse;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.dto.StockResponse;
import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.model.InventoryMovement;
import com.ecommerce.inventory.model.InventoryReservation;
import com.ecommerce.inventory.model.MovementType;
import com.ecommerce.inventory.model.ReservationStatus;
import com.ecommerce.inventory.repository.InventoryItemRepository;
import com.ecommerce.inventory.repository.InventoryMovementRepository;
import com.ecommerce.inventory.repository.InventoryReservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryMovementRepository movementRepository;
    private final OutboxService outboxService;
    private final Duration reservationTtl;

    public InventoryService(InventoryItemRepository itemRepository,
                            InventoryReservationRepository reservationRepository,
                            InventoryMovementRepository movementRepository,
                            OutboxService outboxService,
                            @Value("${ecommerce.inventory.reservation-ttl:PT30M}") Duration reservationTtl) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.movementRepository = movementRepository;
        this.outboxService = outboxService;
        this.reservationTtl = reservationTtl;
    }

    @Transactional
    public StockResponse initializeStock(StockRequest request) {
        InventoryItem item = itemRepository.findByProductId(request.productId()).orElse(null);
        if (item == null) {
            item = itemRepository.save(new InventoryItem(request.productId(), request.sku(), request.totalQuantity()));
        } else {
            item.adjustTotal(request.totalQuantity());
        }
        recordMovement(request.productId(), MovementType.STOCK_ADJUSTED, request.totalQuantity());
        return toStockResponse(item);
    }

    @Transactional
    public ReservationResponse reserve(ReservationRequest request) {
        requireItem(request.productId());
        int updated = itemRepository.reserve(request.productId(), request.quantity());
        if (updated == 0) {
            throw new InsufficientStockException("Insufficient stock for product " + request.productId());
        }
        InventoryReservation reservation = reservationRepository.save(
                new InventoryReservation(request.productId(), request.quantity(), request.orderId(), Instant.now().plus(reservationTtl))
        );
        recordMovement(request.productId(), MovementType.RESERVED, request.quantity());
        outboxService.record("inventory", reservation.getId().toString(), "InventoryReserved", Map.of(
                "reservationId", reservation.getId().toString(),
                "productId", request.productId(),
                "quantity", request.quantity(),
                "orderId", request.orderId().toString()
        ));
        return toReservationResponse(reservation);
    }

    @Transactional
    public ReservationResponse release(UUID reservationId) {
        InventoryReservation reservation = requireReservation(reservationId);
        if (!reservation.isReserved()) {
            throw new ConflictException("Reservation is not in RESERVED state: " + reservationId);
        }
        itemRepository.release(reservation.getProductId(), reservation.getQuantity());
        reservation.release();
        recordMovement(reservation.getProductId(), MovementType.RELEASED, reservation.getQuantity());
        outboxService.record("inventory", reservation.getId().toString(), "InventoryReleased", Map.of(
                "reservationId", reservation.getId().toString(),
                "productId", reservation.getProductId(),
                "quantity", reservation.getQuantity(),
                "orderId", reservation.getOrderId().toString()
        ));
        return toReservationResponse(reservation);
    }

    @Transactional
    public ReservationResponse commit(UUID reservationId) {
        InventoryReservation reservation = requireReservation(reservationId);
        if (!reservation.isReserved()) {
            throw new ConflictException("Reservation is not in RESERVED state: " + reservationId);
        }
        itemRepository.commit(reservation.getProductId(), reservation.getQuantity());
        reservation.commit();
        recordMovement(reservation.getProductId(), MovementType.COMMITTED, reservation.getQuantity());
        outboxService.record("inventory", reservation.getId().toString(), "InventoryCommitted", Map.of(
                "reservationId", reservation.getId().toString(),
                "productId", reservation.getProductId(),
                "quantity", reservation.getQuantity(),
                "orderId", reservation.getOrderId().toString()
        ));
        return toReservationResponse(reservation);
    }

    @Transactional
    public void releaseByOrder(UUID orderId) {
        reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED)
                .forEach(reservation -> release(reservation.getId()));
    }

    @Transactional
    public void commitByOrder(UUID orderId) {
        reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED)
                .forEach(reservation -> commit(reservation.getId()));
    }

    @Transactional
    public StockResponse getStock(Long productId) {
        return toStockResponse(requireItem(productId));
    }

    @Scheduled(fixedDelayString = "${ecommerce.inventory.expiry-interval-ms:60000}")
    @Transactional
    public void expireOverdueReservations() {
        reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, Instant.now())
                .forEach(reservation -> {
                    itemRepository.release(reservation.getProductId(), reservation.getQuantity());
                    reservation.expire();
                    recordMovement(reservation.getProductId(), MovementType.EXPIRED, reservation.getQuantity());
                    outboxService.record("inventory", reservation.getId().toString(), "InventoryExpired", Map.of(
                            "reservationId", reservation.getId().toString(),
                            "productId", reservation.getProductId(),
                            "quantity", reservation.getQuantity(),
                            "orderId", reservation.getOrderId().toString()
                    ));
                });
    }

    private InventoryItem requireItem(Long productId) {
        return itemRepository.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("No stock record for product " + productId));
    }

    private InventoryReservation requireReservation(UUID reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + reservationId));
    }

    private void recordMovement(Long productId, MovementType type, int quantity) {
        movementRepository.save(new InventoryMovement(productId, type, quantity));
    }

    private ReservationResponse toReservationResponse(InventoryReservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getProductId(),
                reservation.getQuantity(),
                reservation.getOrderId(),
                reservation.getStatus().name(),
                reservation.getExpiresAt()
        );
    }

    private StockResponse toStockResponse(InventoryItem item) {
        return new StockResponse(
                item.getId(),
                item.getProductId(),
                item.getSku(),
                item.getTotalQuantity(),
                item.getReservedQuantity(),
                item.getCommittedQuantity(),
                item.getAvailableQuantity(),
                item.getUpdatedAt()
        );
    }
}
