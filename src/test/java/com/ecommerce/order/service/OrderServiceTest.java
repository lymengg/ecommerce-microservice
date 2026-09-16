package com.ecommerce.order.service;

import com.ecommerce.catalog.model.Product;
import com.ecommerce.catalog.service.ProductService;
import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.outbox.OutboxService;
import com.ecommerce.order.dto.CancelRequest;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderIdempotencyRecord;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderIdempotencyRecordRepository;
import com.ecommerce.order.repository.OrderItemRepository;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderItemRepository orderItemRepository = mock(OrderItemRepository.class);
    private final OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
    private final OrderIdempotencyRecordRepository idempotencyRepository = mock(OrderIdempotencyRecordRepository.class);
    private final ProductService productService = mock(ProductService.class);
    private final OutboxService outboxService = mock(OutboxService.class);

    private final OrderService orderService = new OrderService(
            orderRepository, orderItemRepository, historyRepository, idempotencyRepository,
            productService, outboxService, new BigDecimal("0.10"));

    private final AtomicReference<Order> savedOrder = new AtomicReference<>();
    private final AtomicReference<OrderItem> savedItem = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Product product = new Product("SKU-1", "Widget", "A widget", new BigDecimal("100.00"));
        product.activate();
        when(productService.getActive(1L)).thenReturn(product);
        when(productService.getActive(9L)).thenThrow(new com.ecommerce.common.error.NotFoundException("Product not available: 9"));

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            savedOrder.set(order);
            return order;
        });
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(inv -> {
            OrderItem item = inv.getArgument(0);
            savedItem.set(item);
            return item;
        });
        when(orderRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.of(savedOrder.get()));
        when(orderItemRepository.findByOrderId(any(UUID.class))).thenAnswer(inv -> List.of(savedItem.get()));
        when(idempotencyRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(idempotencyRepository.save(any(OrderIdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderCreateRequest request() {
        return new OrderCreateRequest(null, null, List.of(new OrderLineRequest(1L, 2)));
    }

    @Test
    void createComputesAuthoritativeTotals() {
        OrderResponse response = orderService.create(request(), null);

        assertThat(response.status()).isEqualTo(OrderStatus.DRAFT.name());
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.subtotal()).isEqualByComparingTo("200.00");
        assertThat(response.tax()).isEqualByComparingTo("20.00");
        assertThat(response.total()).isEqualByComparingTo("220.00");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).name()).isEqualTo("Widget");
        verify(outboxService).record(eq("order"), any(), eq("OrderCreated"), any());
    }

    @Test
    void createIgnoresClientPriceViaServerPricing() {
        // the request carries no prices at all; they are read from the catalog
        OrderResponse response = orderService.create(request(), null);

        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void createReturnsExistingOrderForSameIdempotencyKey() {
        OrderResponse first = orderService.create(request(), "k1");
        OrderIdempotencyRecord record = new OrderIdempotencyRecord("k1", first.orderId());
        when(idempotencyRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(record));

        OrderResponse second = orderService.create(request(), "k1");

        assertThat(second.orderId()).isEqualTo(first.orderId());
        verify(orderRepository, org.mockito.Mockito.times(1)).save(any(Order.class));
    }

    @Test
    void markPendingTransitionsFromDraft() {
        orderService.create(request(), null);

        OrderResponse response = orderService.markPending(savedOrder.get().getId());

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    }

    @Test
    void markPaidAllowedFromPaymentPending() {
        savedOrder.set(new Order(null, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        savedOrder.get().moveTo(OrderStatus.PAYMENT_PENDING);
        savedItem.set(new OrderItem(savedOrder.get().getId(), 1L, "SKU-1", "Widget", new BigDecimal("10.00"), 1, BigDecimal.ZERO, BigDecimal.ZERO, "USD"));

        OrderResponse response = orderService.markPaid(savedOrder.get().getId());

        assertThat(response.status()).isEqualTo(OrderStatus.PAID.name());
        verify(outboxService).record(eq("order"), any(), eq("OrderConfirmed"), any());
    }

    @Test
    void markPaidRejectedFromDraft() {
        orderService.create(request(), null);
        UUID orderId = savedOrder.get().getId();

        assertThatThrownBy(() -> orderService.markPaid(orderId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelAllowedFromDraft() {
        orderService.create(request(), null);

        OrderResponse response = orderService.cancel(savedOrder.get().getId(), new CancelRequest("changed my mind"));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED.name());
        verify(outboxService).record(eq("order"), any(), eq("OrderCancelled"), any());
    }

    @Test
    void cancelRejectedFromPaid() {
        Order paid = new Order(null, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        paid.moveTo(OrderStatus.PAID);
        savedOrder.set(paid);

        assertThatThrownBy(() -> orderService.cancel(paid.getId(), new CancelRequest("late")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createRejectsInactiveProduct() {
        OrderCreateRequest bad = new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(9L, 1)));

        assertThatThrownBy(() -> orderService.create(bad, null))
                .isInstanceOf(com.ecommerce.common.error.NotFoundException.class);
    }
}
