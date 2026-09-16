package com.ecommerce.cart.dto;

import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID cartId,
        UUID customerId,
        String status,
        List<CartItemResponse> items
) {
}
