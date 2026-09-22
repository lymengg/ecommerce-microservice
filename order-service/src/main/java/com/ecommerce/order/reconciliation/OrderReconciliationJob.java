package com.ecommerce.order.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OrderReconciliationService} on a schedule. Order-service is the
 * natural owner: it owns order state, and the states that get stuck are its own
 * (ADR-020).
 *
 * <p>A failed pass is logged and swallowed so a transient dependency outage
 * cannot stop the scheduler — the next pass retries, and per-order backoff keeps
 * that from becoming a hammer.
 */
@Component
@ConditionalOnProperty(name = "ecommerce.reconciliation.enabled", matchIfMissing = true)
public class OrderReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationJob.class);

    private final OrderReconciliationService reconciliationService;

    public OrderReconciliationJob(OrderReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${ecommerce.reconciliation.interval-ms:60000}",
            initialDelayString = "${ecommerce.reconciliation.initial-delay-ms:30000}")
    public void run() {
        try {
            reconciliationService.reconcile();
        } catch (Exception ex) {
            log.error("Reconciliation pass failed; the next pass will retry", ex);
        }
    }
}
