package com.ecommerce.order.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.common.security.SecurityRoles;
import com.ecommerce.common.security.SecurityUtils;
import com.ecommerce.order.client.CatalogClient;
import com.ecommerce.order.client.CatalogProduct;
import com.ecommerce.order.dto.CancelRequest;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderItemResponse;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderIdempotencyRecord;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.model.OrderStatusHistory;
import com.ecommerce.order.repository.OrderIdempotencyRecordRepository;
import com.ecommerce.order.repository.OrderItemRepository;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OrderStatusHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrderService {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(OrderStatus.DRAFT, Set.of(OrderStatus.PENDING, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.PENDING, Set.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED,
                OrderStatus.NEEDS_ATTENTION));
        TRANSITIONS.put(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.PAID, OrderStatus.CANCELLED,
                OrderStatus.NEEDS_ATTENTION));
        TRANSITIONS.put(OrderStatus.PAID, Set.of(OrderStatus.PROCESSING));
        TRANSITIONS.put(OrderStatus.PROCESSING, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED));
        // Terminal, except for the operator escape hatch: an ADMIN may cancel a
        // stuck order through the normal cancel endpoint once a human has looked
        // at it (ADR-020).
        TRANSITIONS.put(OrderStatus.NEEDS_ATTENTION, Set.of(OrderStatus.CANCELLED));
    }

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderIdempotencyRecordRepository idempotencyRepository;
    private final CatalogClient catalogClient;
    private final OutboxService outboxService;
    private final BigDecimal taxRate;

    public OrderService(OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        OrderStatusHistoryRepository historyRepository,
                        OrderIdempotencyRecordRepository idempotencyRepository,
                        CatalogClient catalogClient,
                        OutboxService outboxService,
                        @Value("${ecommerce.tax-rate:0.10}") BigDecimal taxRate) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.catalogClient = catalogClient;
        this.outboxService = outboxService;
        this.taxRate = taxRate;
    }

    @Transactional
    public OrderResponse create(OrderCreateRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            OrderIdempotencyRecord existing = idempotencyRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return get(existing.getOrderId());
            }
        }

        UUID customerId = resolveCustomerId(request.customerId());
        String currency = request.currency() != null ? request.currency() : "USD";
        List<OrderItem> items = request.items().stream()
                .map(line -> buildItem(line, currency))
                .toList();

        BigDecimal subtotal = items.stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal tax = items.stream().map(OrderItem::getTax).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shippingCost = BigDecimal.ZERO;
        BigDecimal total = subtotal.subtract(discount).add(tax).add(shippingCost);

        Order order = orderRepository.save(new Order(
                customerId, currency,
                money(subtotal), money(discount), money(tax), money(shippingCost), money(total)
        ));
        items.forEach(item -> item.assignTo(order.getId()));
        items.forEach(orderItemRepository::save);
        historyRepository.save(new OrderStatusHistory(order.getId(), OrderStatus.DRAFT, OrderStatus.DRAFT, "Order created"));

        if (idempotencyKey != null) {
            idempotencyRepository.save(new OrderIdempotencyRecord(idempotencyKey, order.getId()));
        }
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("customerId", order.getCustomerId() != null ? order.getCustomerId().toString() : "");
        payload.put("total", order.getTotal().toPlainString());
        payload.put("currency", order.getCurrency());
        outboxService.record("order", order.getId().toString(), "OrderCreated", payload);
        return get(order.getId());
    }

    @Transactional
    public OrderResponse markPending(UUID orderId) {
        return transition(orderId, OrderStatus.PENDING, "Inventory reserved");
    }

    @Transactional
    public OrderResponse markPaymentPending(UUID orderId) {
        return transition(orderId, OrderStatus.PAYMENT_PENDING, "Payment initiated");
    }

    @Transactional
    public OrderResponse markPaid(UUID orderId) {
        return applyPaid(orderId);
    }

    /**
     * Event-driven confirmation of a payment (Phase 6c choreography). Called by
     * the {@code PaymentSucceeded} consumer instead of the orchestrator's REST
     * call.
     *
     * <p>Idempotent when the order is already PAID — Kafka is at-least-once, so
     * a redelivery must be a no-op. Any other state is a genuine
     * out-of-order/invalid event and is thrown so the listener's bounded retry
     * and dead-letter handling deal with it rather than corrupting the state
     * machine.
     */
    @Transactional
    public void confirmPayment(UUID orderId) {
        Order order = requireOrder(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            return;
        }
        applyPaid(orderId);
    }

    private OrderResponse applyPaid(UUID orderId) {
        OrderResponse response = transition(orderId, OrderStatus.PAID, "Payment succeeded");
        outboxService.record("order", orderId.toString(), "OrderConfirmed", Map.of(
                "orderId", orderId.toString(),
                "status", "PAID"
        ));
        return response;
    }

    /**
     * Completes an order the normal flow left behind, because the authoritative
     * payment state says it succeeded (ADR-020). Idempotent: a second pass over
     * an already-PAID order is a no-op.
     *
     * <p>Marking PAID re-emits {@code OrderConfirmed}, which is the same path
     * the normal choreography uses to commit the inventory reservations — so
     * repair reuses the flow rather than inventing a second one.
     *
     * <p>Deliberately not authorization-checked: the caller is the scheduled
     * reconciliation job, which has no {@code SecurityContext} and acts for the
     * system rather than for a user. The REST surface is unchanged — a user
     * still cannot reach an order they do not own.
     */
    @Transactional
    public void reconcileComplete(UUID orderId) {
        Order order = requireOrder(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            // Defensive: a payment can only exist once the saga reached
            // PAYMENT_PENDING, but if the transition was the step that failed
            // the order still has to pass through it to be completable.
            transition(order, OrderStatus.PAYMENT_PENDING, "Reconciliation: payment succeeded");
        }
        applyPaid(orderId);
    }

    /**
     * Cancels an order the reconciliation job found no successful payment for
     * (ADR-020). Idempotent, and bypasses object-level authorization for the
     * same reason as {@link #reconcileComplete}.
     */
    @Transactional
    public void reconcileCancel(UUID orderId, String reason) {
        Order order = requireOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        transition(order, OrderStatus.CANCELLED, reason);
        outboxService.record("order", orderId.toString(), "OrderCancelled", Map.of(
                "orderId", orderId.toString(),
                "reason", reason
        ));
    }

    /**
     * Moves an order that could not be repaired to the terminal
     * {@link OrderStatus#NEEDS_ATTENTION} state, so it stops consuming
     * reconciliation attempts and becomes visible to a human (ADR-020).
     */
    @Transactional
    public void markNeedsAttention(UUID orderId, String reason) {
        Order order = requireOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return;
        }
        transition(order, OrderStatus.NEEDS_ATTENTION, reason);
    }

    @Transactional
    public OrderResponse cancel(UUID orderId, CancelRequest request) {
        Order order = requireAccessibleOrder(orderId);
        String reason = request != null && request.reason() != null ? request.reason() : "Order cancelled";
        OrderResponse response = transition(order, OrderStatus.CANCELLED, reason);
        outboxService.record("order", orderId.toString(), "OrderCancelled", Map.of(
                "orderId", orderId.toString(),
                "reason", reason
        ));
        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        return toResponse(requireAccessibleOrder(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list() {
        if (SecurityUtils.hasRole(SecurityRoles.ADMIN)) {
            return orderRepository.findAll().stream()
                    .map(this::toResponse)
                    .toList();
        }
        if (SecurityUtils.isService()) {
            throw new AccessDeniedException("Service callers cannot list orders");
        }
        return orderRepository.findByCustomerId(requireCustomer()).stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderResponse transition(UUID orderId, OrderStatus to, String reason) {
        return transition(requireOrder(orderId), to, reason);
    }

    private OrderResponse transition(Order order, OrderStatus to, String reason) {
        Set<OrderStatus> allowed = TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(to)) {
            throw new ConflictException("Cannot transition order " + order.getId() + " from " + order.getStatus() + " to " + to);
        }
        OrderStatus from = order.getStatus();
        order.moveTo(to);
        historyRepository.save(new OrderStatusHistory(order.getId(), from, to, reason));
        return toResponse(order);
    }

    /**
     * Identity of the order's customer: for user tokens the subject wins and a
     * mismatching request-supplied id is rejected; for SERVICE callers (the
     * checkout orchestrator) the request id is trusted because checkout has
     * already validated the end user.
     */
    private UUID resolveCustomerId(UUID requestCustomerId) {
        if (SecurityUtils.isService()) {
            if (requestCustomerId == null) {
                throw new AccessDeniedException("customerId is required for service-initiated orders");
            }
            return requestCustomerId;
        }
        UUID subject = requireCustomer();
        if (requestCustomerId != null && !requestCustomerId.equals(subject)) {
            throw new AccessDeniedException("customerId does not match the authenticated caller");
        }
        return subject;
    }

    /**
     * Object-level authorization (OWASP A01): a customer may only reach their
     * own orders; other customers' orders are indistinguishable from missing
     * ones (404) to avoid leaking existence. SERVICE and ADMIN bypass the
     * ownership check.
     */
    private Order requireAccessibleOrder(UUID orderId) {
        Order order = requireOrder(orderId);
        if (SecurityUtils.isService() || SecurityUtils.hasRole(SecurityRoles.ADMIN)) {
            return order;
        }
        UUID subject = SecurityUtils.currentCustomerId();
        if (subject == null || !subject.equals(order.getCustomerId())) {
            throw new NotFoundException("Order not found: " + orderId);
        }
        return order;
    }

    private UUID requireCustomer() {
        UUID customerId = SecurityUtils.currentCustomerId();
        if (customerId == null) {
            throw new AccessDeniedException("Customer identity required");
        }
        return customerId;
    }

    private OrderItem buildItem(OrderLineRequest line, String currency) {
        CatalogProduct product = catalogClient.getActiveProduct(line.productId());
        BigDecimal lineSubtotal = product.price().multiply(BigDecimal.valueOf(line.quantity()));
        BigDecimal tax = money(lineSubtotal.multiply(taxRate));
        return new OrderItem(
                null, product.id(), product.sku(), product.name(),
                product.price(), line.quantity(), BigDecimal.ZERO, tax, currency
        );
    }

    private Order requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = orderItemRepository.findByOrderId(order.getId()).stream()
                .map(item -> new OrderItemResponse(
                        item.getId(), item.getProductId(), item.getSku(), item.getName(),
                        item.getUnitPrice(), item.getQuantity(), item.getDiscount(), item.getTax(),
                        money(item.getLineTotal()), item.getCurrency()
                ))
                .toList();
        return new OrderResponse(
                order.getId(), order.getCustomerId(), order.getStatus().name(), order.getCurrency(),
                order.getSubtotal(), order.getDiscount(), order.getTax(), order.getShippingCost(),
                order.getTotal(), order.getCreatedAt(), items
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
