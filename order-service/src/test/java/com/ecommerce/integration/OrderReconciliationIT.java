package com.ecommerce.integration;

import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.OrderLineRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.reconciliation.OrderReconciliationService;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static com.ecommerce.integration.TestSecurity.asUser;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7d — the reconciliation job (ADR-020). This is the part the roadmap
 * says "everyone skips and production punishes": a circuit breaker without
 * reconciliation just fails faster, because after a partial saga failure
 * something still has to <em>find</em> the inconsistent orders and fix them.
 *
 * <p>Each test constructs a deliberately inconsistent order — the state a
 * partial failure actually leaves behind — and proves the job repairs it. The
 * authoritative payment and reservation state is served by WireMock, so the
 * job's decisions are driven by what the owning services report.
 */
class OrderReconciliationIT extends AbstractIntegrationTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderReconciliationService reconciliationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubCatalog() {
        asUser(UUID.randomUUID(), "CUSTOMER");
        WIRE_MOCK.stubFor(get(urlEqualTo("/internal/api/v1/catalog/products/" + PRODUCT_ID))
                .willReturn(okJson("""
                        {"id":1,"sku":"SKU-REC-1","name":"Monitor","description":"27 inch",
                         "price":250.00,"status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}
                        """)));
    }

    /**
     * The failure this whole sub-step exists for: stock was reserved, no payment
     * ever happened, and the process died. Nothing else in the system would ever
     * notice — the order would sit in PENDING forever.
     */
    @Test
    void compensatesAnOrderStrandedInPendingWithNoPayment() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        stubPayment(orderId, null);
        stubReservations(orderId, "RESERVED");
        stubRelease();

        assertThat(reconciliationService.reconcile()).isEqualTo(1);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
        assertThat(latestReason(orderId)).contains("RECONCILED_NO_SUCCESSFUL_PAYMENT");
    }

    /**
     * The mirror case: the payment did succeed but the order never converged
     * (the process died between payment and the PaymentSucceeded event).
     * Completing it must reuse the normal path, so the inventory commit still
     * happens via OrderConfirmed rather than by a second mechanism.
     */
    @Test
    void completesAnOrderWhosePaymentActuallySucceeded() {
        UUID orderId = strandedOrder(OrderStatus.PAYMENT_PENDING);
        stubPayment(orderId, "SUCCEEDED");
        stubReservations(orderId, "RESERVED");

        assertThat(reconciliationService.reconcile()).isEqualTo(1);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        assertThat(countOutbox("OrderConfirmed", orderId))
                .as("completing re-emits OrderConfirmed, which is what commits the reservations")
                .isEqualTo(1);
        // A succeeded payment is not compensated.
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
    }

    @Test
    void leavesFreshOrdersAlone() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        stubPayment(orderId, null);
        stubReservations(orderId, "RESERVED");
        stubRelease();

        // Not backdated: an order that has just entered PENDING is not stuck.
        jdbcTemplate.update("UPDATE orders SET updated_at = now() WHERE id = ?", orderId);

        assertThat(reconciliationService.reconcile()).isZero();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void isIdempotentAndSafeToRunWhileTheNormalFlowIsRunning() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        stubPayment(orderId, null);
        stubReservations(orderId, "RESERVED");
        stubRelease();

        assertThat(reconciliationService.reconcile()).isEqualTo(1);
        assertThat(reconciliationService.reconcile())
                .as("a repaired order is no longer a candidate")
                .isZero();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
    }

    /**
     * Two instances must not work the same order. Claiming is
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} and then takes a lease, because
     * the repair makes REST calls and cannot hold a row lock while it does.
     */
    @Test
    void aFailedRepairIsHeldByTheLeaseSoASecondPassDoesNotImmediatelyRetryIt() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        // payment-service is down: the repair cannot decide anything.
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/api/v1/payments/by-order/" + orderId))
                .willReturn(serverError()));

        assertThat(reconciliationService.reconcile()).isEqualTo(1);
        assertThat(statusOf(orderId)).as("a transient outage must not cancel an order").isEqualTo(OrderStatus.PENDING);
        assertThat(attemptsOf(orderId)).isEqualTo(1);

        assertThat(reconciliationService.reconcile())
                .as("the lease keeps the order out of the next pass")
                .isZero();
    }

    /**
     * Bounded, with a terminal state: an order that can never be repaired must
     * stop consuming attempts rather than loop forever.
     */
    @Test
    void givesUpIntoATerminalNeedsAttentionStateAfterTheAttemptBudget() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/api/v1/payments/by-order/" + orderId))
                .willReturn(serverError()));

        for (int pass = 0; pass < 5; pass++) {
            reconciliationService.reconcile();
            // Simulate the backoff elapsing between scheduled passes.
            jdbcTemplate.update("UPDATE orders SET next_reconciliation_at = now() - interval '1 minute' WHERE id = ?",
                    orderId);
            if (statusOf(orderId) == OrderStatus.NEEDS_ATTENTION) {
                break;
            }
        }

        assertThat(statusOf(orderId))
                .as("an unrepairable order becomes visible to a human instead of looping forever")
                .isEqualTo(OrderStatus.NEEDS_ATTENTION);
        assertThat(reconciliationService.reconcile()).isZero();
    }

    /**
     * A payment that is still in flight must not be compensated — cancelling
     * could destroy a paid order. The job defers and looks again.
     */
    @Test
    void defersWhileAPaymentIsStillInFlight() {
        UUID orderId = strandedOrder(OrderStatus.PAYMENT_PENDING);
        stubPayment(orderId, "PROCESSING");
        stubReservations(orderId, "RESERVED");
        stubRelease();

        reconciliationService.reconcile();

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAYMENT_PENDING);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
    }

    /**
     * A released reservation is not released twice: compensation only acts on
     * what is still RESERVED.
     */
    @Test
    void doesNotReleaseReservationsThatAreNoLongerHeld() {
        UUID orderId = strandedOrder(OrderStatus.PENDING);
        stubPayment(orderId, null);
        stubReservations(orderId, "EXPIRED");
        stubRelease();

        reconciliationService.reconcile();

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order")));
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Creates an order through the real service and then backdates it, so it
     * looks like one that has been stuck since before the staleness threshold.
     */
    private UUID strandedOrder(OrderStatus status) {
        OrderResponse created = orderService.create(
                new OrderCreateRequest(null, "USD", List.of(new OrderLineRequest(PRODUCT_ID, 2))), null);
        UUID orderId = created.orderId();
        if (status == OrderStatus.PENDING || status == OrderStatus.PAYMENT_PENDING) {
            orderService.markPending(orderId);
        }
        if (status == OrderStatus.PAYMENT_PENDING) {
            orderService.markPaymentPending(orderId);
        }
        jdbcTemplate.update("UPDATE orders SET updated_at = now() - interval '1 hour' WHERE id = ?", orderId);
        return orderId;
    }

    private void stubPayment(UUID orderId, String status) {
        if (status == null) {
            WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/api/v1/payments/by-order/" + orderId))
                    .willReturn(notFound()));
            return;
        }
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/api/v1/payments/by-order/" + orderId))
                .willReturn(okJson("""
                        {"paymentId":"%s","orderId":"%s","status":"%s","amount":550.00,
                         "currency":"USD","createdAt":"2026-01-01T00:00:00Z"}
                        """.formatted(UUID.randomUUID(), orderId, status))));
    }

    private void stubReservations(UUID orderId, String status) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/internal/api/v1/inventory/reservations"))
                .withQueryParam("orderId", equalTo(orderId.toString()))
                .willReturn(okJson("""
                        [{"reservationId":"%s","productId":1,"quantity":2,"orderId":"%s",
                          "status":"%s","expiresAt":"2026-01-01T00:00:00Z"}]
                        """.formatted(UUID.randomUUID(), orderId, status))));
    }

    private void stubRelease() {
        WIRE_MOCK.stubFor(post(urlEqualTo("/internal/api/v1/inventory/reservations/release-by-order"))
                .willReturn(noContent()));
    }

    private OrderStatus statusOf(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private int attemptsOf(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        return order.getReconciliationAttempts();
    }

    private String latestReason(UUID orderId) {
        return jdbcTemplate.queryForObject("""
                SELECT reason FROM order_status_history
                 WHERE order_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, orderId);
    }

    private long countOutbox(String eventType, UUID aggregateId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId.toString());
        return count == null ? 0 : count;
    }
}
