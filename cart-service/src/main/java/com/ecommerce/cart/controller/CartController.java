package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartItemUpdateRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customer cart API (Phase 4): the cart belongs to the authenticated customer
 * (JWT {@code sub}) and is resolved server-side — clients no longer pass a
 * cart id. CUSTOMER role only.
 */
@RestController
@RequestMapping("/api/v1/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse get() {
        return cartService.getOrCreate(requireCustomer());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(@Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(requireCustomer(), request);
    }

    @PatchMapping("/items/{itemId}")
    public CartResponse updateItem(@PathVariable UUID itemId,
                                   @Valid @RequestBody CartItemUpdateRequest request) {
        return cartService.updateQuantity(requireCustomer(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse removeItem(@PathVariable UUID itemId) {
        return cartService.removeItem(requireCustomer(), itemId);
    }

    private UUID requireCustomer() {
        UUID customerId = SecurityUtils.currentCustomerId();
        if (customerId == null) {
            throw new AccessDeniedException("Customer identity required");
        }
        return customerId;
    }
}
