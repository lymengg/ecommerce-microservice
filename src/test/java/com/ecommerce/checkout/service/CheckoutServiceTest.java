package com.ecommerce.checkout.service;

import com.ecommerce.cart.dto.CartLine;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.inventory.service.InventoryService;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutServiceTest {

    private final CartService cartService = mock(CartService.class);
    private final OrderService orderService = mock(OrderService.class);
    private final InventoryService inventoryService = mock(InventoryService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final CheckoutService checkoutService =
            new CheckoutService(cartService, orderService, inventoryService, paymentService);

    private final UUID cartId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(cartService.linesForCheckout(cartId)).thenReturn(
                List.of(new CartLine(cartId, 1L, "SKU-1", 2)));
        when(orderService.create(any(), any())).thenReturn(new OrderResponse(
                orderId, null, "DRAFT", "USD", new BigDecimal("20.00"), BigDecimal.ZERO,
                new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("22.00"), Instant.now(), List.of()
        ));
        when(orderService.markPending(orderId)).thenReturn(orderResponse("PENDING"));
        when(orderService.markPaymentPending(orderId)).thenReturn(orderResponse("PAYMENT_PENDING"));
        when(orderService.markPaid(orderId)).thenReturn(orderResponse("PAID"));
        when(paymentService.initiate(any(), any())).thenReturn(new PaymentResponse(
                paymentId, orderId, "SUCCEEDED", new BigDecimal("22.00"), "USD", Instant.now()
        ));
    }

    private OrderResponse orderResponse(String status) {
        return new OrderResponse(orderId, null, status, "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), List.of());
    }

    @Test
    void checkoutMarksOrderPaidOnSuccess() {
        CheckoutResponse response = checkoutService.checkout(new CheckoutRequest(cartId, null, null), null);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.orderStatus()).isEqualTo("PAID");
        assertThat(response.paymentStatus()).isEqualTo("SUCCEEDED");
        verify(inventoryService).commitByOrder(orderId);
        verify(orderService).markPaid(orderId);
        verify(cartService).markCheckedOut(cartId);
    }

    @Test
    void checkoutCompensatesWhenPaymentFails() {
        when(paymentService.initiate(any(), any())).thenReturn(new PaymentResponse(
                paymentId, orderId, "FAILED", new BigDecimal("22.00"), "USD", Instant.now()
        ));

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("payment declined");

        verify(inventoryService).releaseByOrder(orderId);
        verify(orderService).cancel(eq(orderId), any());
        verify(orderService, never()).markPaid(orderId);
    }

    @Test
    void checkoutCompensatesWhenStockInsufficient() {
        org.mockito.Mockito.doThrow(new InsufficientStockException("Insufficient stock for product 1"))
                .when(inventoryService).reserve(any());

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), null))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryService).releaseByOrder(orderId);
        verify(orderService).cancel(eq(orderId), any());
        verify(paymentService, never()).initiate(any(), any());
    }

    @Test
    void checkoutReusesExistingOrderOnIdempotentRetry() {
        when(orderService.create(any(), any())).thenReturn(orderResponse("PAID"));

        CheckoutResponse response = checkoutService.checkout(new CheckoutRequest(cartId, null, null), "retry-key");

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.orderStatus()).isEqualTo("PAID");
        verify(orderService, never()).markPending(orderId);
        verify(orderService, never()).markPaymentPending(orderId);
        verify(orderService, never()).markPaid(orderId);
        verify(inventoryService, never()).commitByOrder(orderId);
    }
}
