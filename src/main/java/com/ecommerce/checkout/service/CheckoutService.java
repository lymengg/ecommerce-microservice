package com.ecommerce.checkout.service;

import com.ecommerce.cart.dto.CartLine;
import com.ecommerce.cart.service.CartService;
import com.ecommerce.checkout.dto.CheckoutRequest;
import com.ecommerce.checkout.dto.CheckoutResponse;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.inventory.dto.ReservationRequest;
import com.ecommerce.inventory.service.InventoryService;
import com.ecommerce.order.dto.CancelRequest;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.service.OrderService;
import com.ecommerce.payment.dto.PaymentInitiateRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * In-process checkout orchestration for the modular monolith. Validates the
 * cart, prices the order from the catalog, reserves inventory, initiates the
 * payment, and compensates (release inventory + cancel order) when the payment
 * fails. In Phase 3+ this orchestration moves across services via events.
 */
@Service
public class CheckoutService {

    private final CartService cartService;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    public CheckoutService(CartService cartService,
                           OrderService orderService,
                           InventoryService inventoryService,
                           PaymentService paymentService) {
        this.cartService = cartService;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
    }

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request, String idempotencyKey) {
        List<CartLine> lines = cartService.linesForCheckout(request.cartId());
        OrderCreateRequest orderRequest = new OrderCreateRequest(
                request.customerId(),
                request.currency(),
                lines.stream().map(line -> new OrderLineRequest(line.productId(), line.quantity())).toList()
        );
        OrderResponse order = orderService.create(orderRequest, idempotencyKey);

        if (order.status().equals("DRAFT")) {
            order = orderService.markPending(order.orderId());
            reserveOrCompensate(lines, order.orderId());
            order = orderService.markPaymentPending(order.orderId());
        }

        PaymentResponse payment = paymentService.initiate(
                new PaymentInitiateRequest(order.orderId()), "checkout:" + order.orderId()
        );

        if (payment.status().equals("SUCCEEDED")) {
            if (order.status().equals("PAYMENT_PENDING")) {
                inventoryService.commitByOrder(order.orderId());
                orderService.markPaid(order.orderId());
                cartService.markCheckedOut(request.cartId());
            }
            return new CheckoutResponse(order.orderId(), payment.paymentId(), "PAID", payment.status());
        }

        if (order.status().equals("PAYMENT_PENDING")) {
            inventoryService.releaseByOrder(order.orderId());
            orderService.cancel(order.orderId(), new CancelRequest("PAYMENT_FAILED"));
        }
        throw new ConflictException("Checkout failed: payment declined");
    }

    private void reserveOrCompensate(List<CartLine> lines, java.util.UUID orderId) {
        try {
            for (CartLine line : lines) {
                inventoryService.reserve(new ReservationRequest(line.productId(), line.quantity(), orderId));
            }
        } catch (InsufficientStockException ex) {
            inventoryService.releaseByOrder(orderId);
            orderService.cancel(orderId, new CancelRequest("INSUFFICIENT_STOCK"));
            throw ex;
        }
    }
}
