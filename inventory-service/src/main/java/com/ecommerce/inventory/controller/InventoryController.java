package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.OrderActionRequest;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.dto.ReservationResponse;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.dto.StockResponse;
import com.ecommerce.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/v1/inventory")
@PreAuthorize("hasRole('SERVICE')")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/stock")
    @ResponseStatus(HttpStatus.CREATED)
    public StockResponse initializeStock(@Valid @RequestBody StockRequest request) {
        return inventoryService.initializeStock(request);
    }

    @GetMapping("/stock")
    public StockResponse getStock(@RequestParam Long productId) {
        return inventoryService.getStock(productId);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
        return inventoryService.reserve(request);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public ReservationResponse release(@PathVariable UUID reservationId) {
        return inventoryService.release(reservationId);
    }

    @PostMapping("/reservations/{reservationId}/commit")
    public ReservationResponse commit(@PathVariable UUID reservationId) {
        return inventoryService.commit(reservationId);
    }

    @PostMapping("/reservations/commit-by-order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void commitByOrder(@Valid @RequestBody OrderActionRequest request) {
        inventoryService.commitByOrder(request.orderId());
    }

    @PostMapping("/reservations/release-by-order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseByOrder(@Valid @RequestBody OrderActionRequest request) {
        inventoryService.releaseByOrder(request.orderId());
    }

    /**
     * Authoritative reservation state for an order, for the order reconciliation
     * job (ADR-020). Read-only and SERVICE-only; reconciliation asks the owning
     * service rather than reading its tables (ADR-003).
     */
    @GetMapping("/reservations")
    public List<ReservationResponse> reservationsByOrder(@RequestParam UUID orderId) {
        return inventoryService.getReservations(orderId);
    }
}
