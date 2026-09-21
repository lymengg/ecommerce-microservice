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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Checkout orchestration (saga). Validates the cart, prices the order from the
 * catalog, reserves inventory, initiates the payment, and compensates (release
 * inventory + cancel order) when the payment fails. Every step is a synchronous
 * REST call to its owning service; there is no cross-service transaction.
 *
 * <p>Phase 6c: the success leg is now <em>choreographed</em> rather than
 * orchestrated. The orchestrator stops once the payment is accepted;
 * payment-service publishes {@code PaymentSucceeded}, order-service consumes it
 * and publishes {@code OrderConfirmed}, and inventory-service consumes that to
 * commit the reservations. The checkout contract (request/response shape) is
 * unchanged, but the order and stock converge asynchronously — see ADR-016 for
 * the orchestration-vs-choreography comparison.
 */
@Service
public class CheckoutService {

    /**
     * Resolved through {@link GlobalOpenTelemetry} so the tracer is the one the
     * Java agent installed. With no agent attached this is a no-op tracer and
     * every span call below is inert — the saga still works, it just is not
     * traced, which is what keeps unit tests free of a tracing dependency.
     */
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("com.ecommerce.checkout");

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
        // One span for the whole saga. The agent already creates a server span for
        // the inbound request; this child span is what makes the saga visible as a
        // single unit with the cross-service calls nested beneath it.
        //
        // Deliberately no customer id attribute: it is a pseudonymous personal
        // identifier and doc 08 §3 rules out sensitive data in spans. Cart and
        // order ids are not personal.
        Span span = TRACER.spanBuilder("checkout.saga")
                .setAttribute("checkout.cart_id", request.cartId().toString())
                .setAttribute("checkout.currency", request.currency())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return executeSaga(request, customerId, idempotencyKey, span);
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            span.end();
        }
    }

    private CheckoutResponse executeSaga(CheckoutRequest request,
                                         UUID customerId,
                                         String idempotencyKey,
                                         Span span) {
        List<CartLineInfo> lines = cartClient.getLines(request.cartId());
        OrderCreateRequest orderRequest = new OrderCreateRequest(
                customerId,
                request.currency(),
                lines.stream().map(line -> new OrderCreateRequest.OrderLineRequest(line.productId(), line.quantity())).toList()
        );
        OrderInfo order = orderClient.createOrder(orderRequest, idempotencyKey);
        span.setAttribute("checkout.order_id", order.orderId().toString());

        if ("DRAFT".equals(order.status())) {
            order = orderClient.markPending(order.orderId());
            reserveOrCompensate(lines, order.orderId());
            order = orderClient.markPaymentPending(order.orderId());
        }

        PaymentInfo payment = paymentClient.initiate(order.orderId(), "checkout:" + order.orderId());

        if ("SUCCEEDED".equals(payment.status())) {
            if ("PAYMENT_PENDING".equals(order.status())) {
                // Phase 6c: order confirmation and the inventory commit are no
                // longer synchronous calls from the orchestrator. payment-service
                // publishes PaymentSucceeded; order-service consumes it, marks the
                // order PAID and publishes OrderConfirmed; inventory-service
                // consumes that and commits the reservations. Checkout returns as
                // soon as the payment is accepted and the system converges.
                cartClient.markCheckedOut(request.cartId());
            }
            span.setAttribute("checkout.outcome", "PAID");
            return new CheckoutResponse(order.orderId(), payment.paymentId(), "PAID", payment.status());
        }

        if ("PAYMENT_PENDING".equals(order.status())) {
            inventoryClient.releaseByOrder(order.orderId());
            orderClient.cancel(order.orderId(), "PAYMENT_FAILED");
        }
        span.setAttribute("checkout.outcome", "PAYMENT_DECLINED");
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
