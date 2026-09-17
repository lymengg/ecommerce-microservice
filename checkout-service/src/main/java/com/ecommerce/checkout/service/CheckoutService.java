package com.ecommerce.checkout.service;

import com.ecommerce.checkout.client.CartClient;
import com.ecommerce.checkout.client.CartLineInfo;
import com.ecommerce.checkout.client.InventoryClient;
import com.ecommerce.checkout.client.OrderClient;
import com.ecommerce.checkout.client.OrderCreateRequest;
import com.ecommerce.checkout.client.OrderInfo;
import com.ecommerce.checkout.client.PaymentClient;
import com.ecommerce.checkout.client.PaymentInfo;
import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Checkout orchestration (saga). Validates the cart, prices the order from the
 * catalog, reserves inventory, initiates the payment, and compensates (release
 * inventory + cancel order) when the payment fails. Every step is a synchronous
 * REST call to its owning service; there is no cross-service transaction. The
 * class is deliberately structured so a Phase 5 event-driven version can
 * replace the REST calls without changing the checkout contract.
 */
@Service
public class CheckoutService {

    private final CartClient cartClient;
    private final OrderClient orderClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;

    public CheckoutService(CartClient cartClient,
                           OrderClient orderClient,
                           InventoryClient inventoryClient,
                           PaymentClient paymentClient) {
        this.cartClient = cartClient;
        this.orderClient = orderClient;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
    }

    public CheckoutResponse checkout(CheckoutRequest request, UUID customerId, String idempotencyKey) {
        List<CartLineInfo> lines = cartClient.getLines(request.cartId());
        OrderCreateRequest orderRequest = new OrderCreateRequest(
                customerId,
                request.currency(),
                lines.stream().map(line -> new OrderCreateRequest.OrderLineRequest(line.productId(), line.quantity())).toList()
        );
        OrderInfo order = orderClient.createOrder(orderRequest, idempotencyKey);

        if ("DRAFT".equals(order.status())) {
            order = orderClient.markPending(order.orderId());
            reserveOrCompensate(lines, order.orderId());
            order = orderClient.markPaymentPending(order.orderId());
        }

        PaymentInfo payment = paymentClient.initiate(order.orderId(), "checkout:" + order.orderId());

        if ("SUCCEEDED".equals(payment.status())) {
            if ("PAYMENT_PENDING".equals(order.status())) {
                inventoryClient.commitByOrder(order.orderId());
                orderClient.markPaid(order.orderId());
                cartClient.markCheckedOut(request.cartId());
            }
            return new CheckoutResponse(order.orderId(), payment.paymentId(), "PAID", payment.status());
        }

        if ("PAYMENT_PENDING".equals(order.status())) {
            inventoryClient.releaseByOrder(order.orderId());
            orderClient.cancel(order.orderId(), "PAYMENT_FAILED");
        }
        throw new ConflictException("Checkout failed: payment declined");
    }

    private void reserveOrCompensate(List<CartLineInfo> lines, UUID orderId) {
        try {
            for (CartLineInfo line : lines) {
                inventoryClient.reserve(line.productId(), line.quantity(), orderId);
            }
        } catch (InsufficientStockException ex) {
            inventoryClient.releaseByOrder(orderId);
            orderClient.cancel(orderId, "INSUFFICIENT_STOCK");
            throw ex;
        }
    }
}
