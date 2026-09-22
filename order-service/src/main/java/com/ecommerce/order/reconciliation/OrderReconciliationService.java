package com.ecommerce.order.reconciliation;

import com.ecommerce.order.client.InventoryClient;
import com.ecommerce.order.client.PaymentClient;
import com.ecommerce.order.client.PaymentInfo;
import com.ecommerce.common.observability.ApplicationMetrics;
import com.ecommerce.order.client.ReservationInfo;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finds orders a partial saga failure stranded and repairs them (Phase 7,
 * ADR-020).
 *
 * <p>A saga can fail after reserving stock and before a payment exists — a
 * timeout, a crash, a restart — and nothing in the system would ever notice:
 * the order sat in {@code PENDING} or {@code PAYMENT_PENDING} forever while its
 * reservation quietly expired. Compensation in the saga covers the failures it
 * can see; this covers the ones it cannot (the process died mid-saga, or
 * compensation itself failed because the dependency was still down).
 *
 * <p>Design points worth stating:
 *
 * <ul>
 *   <li><b>Authoritative state is asked for, not assumed.</b> Order-service
 *       owns order state, but payment and reservation state belong to
 *       payment-service and inventory-service, so the job reads them over REST
 *       (ADR-003). It never joins across databases.</li>
 *   <li><b>Two instances are safe.</b> Claiming uses
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED} and then takes a lease by
 *       pushing {@code next_reconciliation_at} into the future, because the
 *       repair makes REST calls and must not hold a row lock while it does.</li>
 *   <li><b>It converges, then stops.</b> Each attempt is bounded by a batch
 *       cap, a per-order backoff, and an attempt budget that ends in
 *       {@code NEEDS_ATTENTION}.</li>
 *   <li><b>It is idempotent and safe alongside the normal flow.</b> Completion
 *       reuses the same "mark PAID → emit OrderConfirmed" path the choreography
 *       uses, and compensation only releases reservations that are still
 *       RESERVED. Running the job while a saga is progressing simply finds
 *       nothing to do.</li>
 * </ul>
 */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;
    private final ReconciliationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationMetrics metrics;

    public OrderReconciliationService(OrderRepository orderRepository,
                                      OrderService orderService,
                                      PaymentClient paymentClient,
                                      InventoryClient inventoryClient,
                                      ReconciliationProperties properties,
                                      TransactionTemplate transactionTemplate,
                                      ApplicationMetrics metrics) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    /**
     * Runs one bounded pass. Returns the number of orders claimed, which is what
     * the tests assert on and what Phase 8 will export as a counter.
     */
    public int reconcile() {
        List<UUID> claimed = claim();
        if (claimed.isEmpty()) {
            return 0;
        }
        int completed = 0;
        int compensated = 0;
        int deferred = 0;
        for (UUID orderId : claimed) {
            try {
                switch (repair(orderId)) {
                    case COMPLETED -> {
                        completed++;
                        metrics.reconciliationOutcome(ApplicationMetrics.RECONCILIATION_COMPLETED);
                    }
                    case COMPENSATED -> {
                        compensated++;
                        metrics.reconciliationOutcome(ApplicationMetrics.RECONCILIATION_COMPENSATED);
                    }
                    case DEFERRED -> {
                        deferred++;
                        metrics.reconciliationOutcome(ApplicationMetrics.RECONCILIATION_DEFERRED);
                        defer(orderId, "payment still in flight");
                    }
                }
            } catch (RuntimeException ex) {
                deferred++;
                metrics.reconciliationOutcome(ApplicationMetrics.RECONCILIATION_DEFERRED);
                defer(orderId, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        metrics.reconciliationPass(claimed.size());
        log.info("Reconciliation pass: claimed={} completed={} compensated={} deferred={}",
                claimed.size(), completed, compensated, deferred);
        return claimed.size();
    }

    /**
     * Claims a batch and takes a lease on it. One transaction: the row locks are
     * held only for the claim, and the lease written here is what keeps another
     * instance away while the REST calls below run.
     */
    private List<UUID> claim() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.getStaleAfter());
        return transactionTemplate.execute(status -> {
            List<Order> candidates = orderRepository.claimForReconciliation(
                    staleBefore, now, properties.getBatchSize());
            if (candidates.isEmpty()) {
                return List.of();
            }
            List<UUID> ids = candidates.stream().map(Order::getId).toList();
            orderRepository.holdForReconciliation(ids, Instant.now().plus(properties.getLease()));
            return ids;
        });
    }

    private Outcome repair(UUID orderId) {
        PaymentInfo payment = paymentClient.findByOrder(orderId);

        if (payment != null && payment.isSucceeded()) {
            // Complete. Marking PAID re-emits OrderConfirmed, which commits the
            // reservations through the same path the normal flow uses.
            orderService.reconcileComplete(orderId);
            log.info("Reconciled order {} by COMPLETING it (payment {} succeeded)", orderId, payment.paymentId());
            return Outcome.COMPLETED;
        }

        if (payment != null && !payment.isSettled()) {
            // A payment in flight may still succeed; compensating now could
            // destroy a paid order. Look again after the backoff.
            log.info("Order {} has a {} payment; deferring", orderId, payment.status());
            return Outcome.DEFERRED;
        }

        List<ReservationInfo> reservations = inventoryClient.reservationsByOrder(orderId);
        long stillHeld = reservations.stream().filter(ReservationInfo::isReserved).count();
        if (stillHeld > 0) {
            inventoryClient.releaseByOrder(orderId);
        }
        orderService.reconcileCancel(orderId, "RECONCILED_NO_SUCCESSFUL_PAYMENT");
        log.info("Reconciled order {} by COMPENSATING it (no successful payment; released {} reservations)",
                orderId, stillHeld);
        return Outcome.COMPENSATED;
    }

    /**
     * Records a failed attempt: back off and try later, or stop and hand the
     * order to a human. Without the terminal state the job would retry a
     * permanently broken order forever.
     */
    private void defer(UUID orderId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return;
            }
            int attempts = order.getReconciliationAttempts() + 1;
            if (attempts >= properties.getMaxAttempts()) {
                log.error("Order {} could not be reconciled after {} attempts ({}); marking NEEDS_ATTENTION",
                        orderId, attempts, reason);
                metrics.reconciliationOutcome(ApplicationMetrics.RECONCILIATION_NEEDS_ATTENTION);
                orderRepository.recordReconciliationAttempt(orderId, null);
                orderService.markNeedsAttention(orderId, "RECONCILIATION_FAILED: " + reason);
                return;
            }
            // Linear backoff: the first retry is soon, later ones are spaced out.
            Instant nextAttempt = Instant.now().plus(properties.getRetryBackoff().multipliedBy(attempts));
            orderRepository.recordReconciliationAttempt(orderId, nextAttempt);
            log.warn("Deferred reconciliation of order {} until {} ({})", orderId, nextAttempt, reason);
        });
    }

    private enum Outcome {
        COMPLETED,
        COMPENSATED,
        DEFERRED
    }
}
