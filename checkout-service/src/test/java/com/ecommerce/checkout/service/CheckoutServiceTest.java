package com.ecommerce.checkout.service;

import com.ecommerce.checkout.client.CartClient;
import com.ecommerce.checkout.client.CartLineInfo;
import com.ecommerce.checkout.client.InventoryClient;
import com.ecommerce.checkout.client.OrderClient;
import com.ecommerce.checkout.client.OrderInfo;
import com.ecommerce.checkout.client.PaymentClient;
import com.ecommerce.checkout.client.PaymentInfo;
import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutServiceTest {

    private final CartClient cartClient = mock(CartClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final InventoryClient inventoryClient = mock(InventoryClient.class);
    private final PaymentClient paymentClient = mock(PaymentClient.class);
    private final CheckoutService checkoutService =
            new CheckoutService(cartClient, orderClient, inventoryClient, paymentClient);

    private final UUID cartId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(cartClient.getLines(cartId)).thenReturn(
                List.of(new CartLineInfo(cartId, 1L, "SKU-1", 2)));
        when(orderClient.createOrder(any(), any())).thenReturn(orderInfo("DRAFT"));
        when(orderClient.markPending(orderId)).thenReturn(orderInfo("PENDING"));
        when(orderClient.markPaymentPending(orderId)).thenReturn(orderInfo("PAYMENT_PENDING"));
        when(paymentClient.initiate(any(), any())).thenReturn(new PaymentInfo(
                paymentId, orderId, "SUCCEEDED", new BigDecimal("22.00"), "USD", Instant.now()
        ));
    }

    private OrderInfo orderInfo(String status) {
        return new OrderInfo(orderId, null, status, "USD", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), List.of());
    }

    @Test
    void checkoutReturnsPaidAndHandsTheRestToEvents() {
        CheckoutResponse response = checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.orderStatus()).isEqualTo("PAID");
        assertThat(response.paymentStatus()).isEqualTo("SUCCEEDED");
        // Phase 6c: the orchestrator no longer commits stock or marks the order
        // paid itself — the PaymentSucceeded -> order -> inventory events do.
        verify(cartClient).markCheckedOut(cartId);
    }

    @Test
    void checkoutCompensatesWhenPaymentFails() {
        when(paymentClient.initiate(any(), any())).thenReturn(new PaymentInfo(
                paymentId, orderId, "FAILED", new BigDecimal("22.00"), "USD", Instant.now()
        ));

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("payment declined");

        verify(inventoryClient).releaseByOrder(orderId);
        verify(orderClient).cancel(eq(orderId), eq("PAYMENT_FAILED"));
    }

    @Test
    void checkoutCompensatesWhenStockInsufficient() {
        doThrow(new InsufficientStockException("Insufficient stock for product 1"))
                .when(inventoryClient).reserve(1L, 2, orderId);

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryClient).releaseByOrder(orderId);
        verify(orderClient).cancel(eq(orderId), eq("INSUFFICIENT_STOCK"));
        verify(paymentClient, never()).initiate(any(), any());
    }

    @Test
    void checkoutReusesExistingOrderOnIdempotentRetry() {
        when(orderClient.createOrder(any(), any())).thenReturn(orderInfo("PAID"));

        CheckoutResponse response = checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, "retry-key");

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.orderStatus()).isEqualTo("PAID");
        verify(orderClient, never()).markPending(orderId);
        verify(orderClient, never()).markPaymentPending(orderId);
    }
}
