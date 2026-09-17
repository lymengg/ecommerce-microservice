package com.ecommerce.checkout.controller;

import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.checkout.service.CheckoutService;
import com.ecommerce.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("hasRole('CUSTOMER')")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        // The customer id always comes from the token; a mismatching
        // client-supplied value is rejected (broken object-level
        // authorization prevention).
        UUID customerId = SecurityUtils.currentCustomerId();
        if (customerId == null) {
            throw new AccessDeniedException("Customer identity required");
        }
        if (request.customerId() != null && !request.customerId().equals(customerId)) {
            throw new AccessDeniedException("customerId does not match the authenticated caller");
        }
        return checkoutService.checkout(request, customerId, idempotencyKey);
    }
}
