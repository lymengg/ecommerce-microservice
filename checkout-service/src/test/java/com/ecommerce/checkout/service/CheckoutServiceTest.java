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
import com.ecommerce.common.error.ServiceUnavailableException;
import com.ecommerce.common.observability.ApplicationMetrics;
import com.ecommerce.common.resilience.ResilienceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CheckoutService checkoutService = new CheckoutService(
            cartClient, orderClient, inventoryClient, paymentClient,
            new ResilienceProperties(), new ApplicationMetrics(meterRegistry));

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
        assertThat(counter("paid")).isEqualTo(1.0);
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

    /**
     * The gap Phase 7 closes (ADR-017). Before this, only a business rejection
     * compensated; an inventory-service that could not answer left the order in
     * PENDING with stock still held.
     */
    @Test
    void checkoutCompensatesWhenInventoryIsUnreachable() {
        doThrow(new ServiceUnavailableException("Could not reach inventory within its timeout budget"))
                .when(inventoryClient).reserve(1L, 2, orderId);

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(inventoryClient).releaseByOrder(orderId);
        verify(orderClient).cancel(eq(orderId), eq("INVENTORY_UNAVAILABLE"));
        verify(paymentClient, never()).initiate(any(), any());
    }

    @Test
    void checkoutCompensatesWhenAnOrderTransitionCannotBeReached() {
        doThrow(new ServiceUnavailableException("Could not reach order within its timeout budget"))
                .when(orderClient).markPaymentPending(orderId);

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(inventoryClient).releaseByOrder(orderId);
        verify(orderClient).cancel(eq(orderId), eq("INVENTORY_UNAVAILABLE"));
    }

    /**
     * A payment that cannot be reached is deliberately <em>not</em> compensated:
     * initiation is idempotent and already retried, the payment may in fact have
     * been recorded, and cancelling would then destroy a paid order. The order
     * stays recoverable and the reconciliation job decides (ADR-020).
     */
    @Test
    void checkoutLeavesTheOrderRecoverableWhenPaymentIsUnreachable() {
        when(paymentClient.initiate(any(), any()))
                .thenThrow(new ServiceUnavailableException("Could not reach payment within its timeout budget"));

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(inventoryClient, never()).releaseByOrder(orderId);
        verify(orderClient, never()).cancel(any(), any());
        verify(cartClient, never()).markCheckedOut(cartId);
    }

    /**
     * Phase 8 (ADR-021): the outcome series is what distinguishes a business
     * decline from a system error. Both are 4xx/5xx on the wire, and only one of
     * them should ever page anyone.
     */
    @Test
    void recordsTheBusinessOutcomeSeparatelyFromTheHttpStatus() {
        when(paymentClient.initiate(any(), any())).thenReturn(new PaymentInfo(
                paymentId, orderId, "FAILED", new BigDecimal("22.00"), "USD", Instant.now()));

        assertThatThrownBy(() -> checkoutService.checkout(new CheckoutRequest(cartId, null, null), customerId, null))
                .isInstanceOf(ConflictException.class);

        assertThat(counter("payment_declined")).isEqualTo(1.0);
        assertThat(counter("paid")).isZero();
    }

    /** Zero when the outcome never happened: no meter is registered until it does. */
    private double counter(String outcome) {
        Counter counter = meterRegistry.find("checkout.saga.outcomes").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
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
