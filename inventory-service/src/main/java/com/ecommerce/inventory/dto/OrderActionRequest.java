package com.ecommerce.inventory.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for resolving every reservation belonging to an order
 * (commit-by-order / release-by-order), used by the checkout orchestrator.
 */
public record OrderActionRequest(
        @NotNull UUID orderId
) {
}
