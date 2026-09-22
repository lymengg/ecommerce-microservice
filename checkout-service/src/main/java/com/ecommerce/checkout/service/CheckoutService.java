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
import com.ecommerce.common.error.ServiceUnavailableException;
import com.ecommerce.common.resilience.Deadline;
import com.ecommerce.common.resilience.ResilienceProperties;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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
 *
 * <p>Phase 7 adds two things. First, a saga-wide {@link Deadline} (ADR-018):
 * the saga refuses to start a step it cannot finish rather than spending its
 * whole allowance on one call. Second, compensation for an <em>unreachable</em>
 * dependency, not just a business rejection (ADR-017). Before this, only
 * {@link InsufficientStockException} triggered compensation, so an
 * inventory-service that timed out left the order stranded in {@code PENDING}
 * with stock still held. A dependency that could not answer is now
 * indistinguishable from one that said no, as far as cleanup is concerned.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

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
    private final ResilienceProperties resilienceProperties;

    public CheckoutService(CartClient cartClient,
                           OrderClient orderClient,
                           InventoryClient inventoryClient,
                           PaymentClient paymentClient,
                           ResilienceProperties resilienceProperties) {
        this.cartClient = cartClient;
        this.orderClient = orderClient;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.resilienceProperties = resilienceProperties;
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
        Deadline.start(resilienceProperties.getSagaBudget());
        try (Scope ignored = span.makeCurrent()) {
            return executeSaga(request, customerId, idempotencyKey, span);
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            Deadline.clear();
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
            UUID orderId = order.orderId();
            order = transitionOrCompensate(orderId, "INVENTORY_UNAVAILABLE",
                    () -> orderClient.markPending(orderId));
            reserveOrCompensate(lines, orderId);
            order = transitionOrCompensate(orderId, "INVENTORY_UNAVAILABLE",
                    () -> orderClient.markPaymentPending(orderId));
        }

        // No compensation on a payment failure, deliberately. Initiation is
        // idempotent and already retried by the resilience layer; if it still
        // cannot be reached, the payment may in fact have been recorded, and
        // cancelling the order would then destroy a paid order. The order is
        // left recoverable in PAYMENT_PENDING and the reconciliation job decides
        // from the authoritative payment state (ADR-020).
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
            compensate(order.orderId(), "PAYMENT_FAILED");
        }
        span.setAttribute("checkout.outcome", "PAYMENT_DECLINED");
        throw new ConflictException("Checkout failed: payment declined");
    }

    /**
     * Reserves every line, compensating the whole order if any line cannot be
     * reserved — whether because stock is genuinely insufficient or because
     * inventory-service could not answer at all (ADR-017).
     */
    private void reserveOrCompensate(List<CartLineInfo> lines, UUID orderId) {
        try {
            for (CartLineInfo line : lines) {
                inventoryClient.reserve(line.productId(), line.quantity(), orderId);
            }
        } catch (InsufficientStockException ex) {
            compensate(orderId, "INSUFFICIENT_STOCK");
            throw ex;
        } catch (ServiceUnavailableException ex) {
            // The gap Phase 7 closes: without this the order stayed in PENDING
            // and the reservation was held until its TTL expired.
            compensate(orderId, "INVENTORY_UNAVAILABLE");
            throw ex;
        }
    }

    private OrderInfo transitionOrCompensate(UUID orderId, String reason, Supplier<OrderInfo> transition) {
        if (Deadline.current().filter(Deadline::isExpired).isPresent()) {
            compensate(orderId, "SAGA_BUDGET_EXHAUSTED");
            throw new ServiceUnavailableException("Checkout budget exhausted before advancing order " + orderId);
        }
        try {
            return transition.get();
        } catch (ServiceUnavailableException ex) {
            compensate(orderId, reason);
            throw ex;
        }
    }

    /**
     * Best-effort compensation. Each step is independent and its failure is
     * logged, never rethrown: the original failure is the one the caller must
     * see, and a compensation that cannot run (inventory still unreachable) is
     * picked up by the reservation TTL and by reconciliation (ADR-020).
     */
    private void compensate(UUID orderId, String reason) {
        try {
            inventoryClient.releaseByOrder(orderId);
        } catch (RuntimeException ex) {
            log.warn("Could not release reservations for order {} during compensation: {}",
                    orderId, ex.getMessage());
        }
        try {
            orderClient.cancel(orderId, reason);
        } catch (RuntimeException ex) {
            log.warn("Could not cancel order {} during compensation: {}", orderId, ex.getMessage());
        }
    }
}
