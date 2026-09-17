package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.CartLine;
import com.ecommerce.cart.service.CartService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal service-to-service endpoints for the checkout orchestrator: exposes
 * the cart lines prepared for checkout and closes the cart once checkout
 * succeeds.
 */
@RestController
@RequestMapping("/internal/api/v1/cart")
@PreAuthorize("hasRole('SERVICE')")
public class CartInternalController {

    private final CartService cartService;

    public CartInternalController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/{cartId}/lines")
    public List<CartLine> getLines(@PathVariable UUID cartId) {
        return cartService.linesForCheckout(cartId);
    }

    @PostMapping("/{cartId}/checkout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markCheckedOut(@PathVariable UUID cartId) {
        cartService.markCheckedOut(cartId);
    }
}
