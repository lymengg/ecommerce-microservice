package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal service-to-service endpoints used by the checkout orchestrator to
 * advance the order state machine as the saga progresses. SERVICE tokens only.
 */
@RestController
@RequestMapping("/internal/api/v1/orders")
@PreAuthorize("hasRole('SERVICE')")
public class OrderInternalController {

    private final OrderService orderService;

    public OrderInternalController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{orderId}/pending")
    public OrderResponse markPending(@PathVariable UUID orderId) {
        return orderService.markPending(orderId);
    }

    @PostMapping("/{orderId}/payment-pending")
    public OrderResponse markPaymentPending(@PathVariable UUID orderId) {
        return orderService.markPaymentPending(orderId);
    }

    @PostMapping("/{orderId}/paid")
    public OrderResponse markPaid(@PathVariable UUID orderId) {
        return orderService.markPaid(orderId);
    }
}
