package com.ecommerce.order.service;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.outbox.OutboxService;
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
        TRANSITIONS.put(OrderStatus.PENDING, Set.of(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.PAID, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.PAID, Set.of(OrderStatus.PROCESSING));
        TRANSITIONS.put(OrderStatus.PROCESSING, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED));
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
                request.customerId(), currency,
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
        OrderResponse response = transition(orderId, OrderStatus.PAID, "Payment succeeded");
        outboxService.record("order", orderId.toString(), "OrderConfirmed", Map.of(
                "orderId", orderId.toString(),
                "status", "PAID"
        ));
        return response;
    }

    @Transactional
    public OrderResponse cancel(UUID orderId, CancelRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "Order cancelled";
        OrderResponse response = transition(orderId, OrderStatus.CANCELLED, reason);
        outboxService.record("order", orderId.toString(), "OrderCancelled", Map.of(
                "orderId", orderId.toString(),
                "reason", reason
        ));
        return response;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        Order order = requireOrder(orderId);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list() {
        return orderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderResponse transition(UUID orderId, OrderStatus to, String reason) {
        Order order = requireOrder(orderId);
        Set<OrderStatus> allowed = TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!allowed.contains(to)) {
            throw new ConflictException("Cannot transition order " + orderId + " from " + order.getStatus() + " to " + to);
        }
        OrderStatus from = order.getStatus();
        order.moveTo(to);
        historyRepository.save(new OrderStatusHistory(order.getId(), from, to, reason));
        return toResponse(order);
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
