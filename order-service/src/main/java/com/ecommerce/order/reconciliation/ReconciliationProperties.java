package com.ecommerce.order.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bounds for the order reconciliation job (Phase 7, ADR-020).
 *
 * <p>Every bound here exists to keep the job finite: a batch cap so one pass
 * cannot run away, a staleness threshold so freshly-created orders are not
 * touched, a lease so two instances cannot work the same order, and an attempt
 * budget so an unrepairable order ends up in {@code NEEDS_ATTENTION} instead of
 * being retried forever.
 */
@Component
@ConfigurationProperties("ecommerce.reconciliation")
public class ReconciliationProperties {

    private boolean enabled = true;

    /** How often a pass runs. */
    private long intervalMs = 60_000;

    /** A first pass is delayed so a cold start does not race the schema. */
    private long initialDelayMs = 30_000;

    /**
     * How long an order may sit in PENDING/PAYMENT_PENDING before it counts as
     * stuck. Must stay below {@code ecommerce.inventory.reservation-ttl}
     * (default PT30M), or the stock would expire before the job could release
     * it deliberately and the repair would lose its meaning.
     */
    private Duration staleAfter = Duration.ofMinutes(10);

    /** Orders examined per pass. */
    private int batchSize = 50;

    /**
     * How long a claimed order is held before another pass (or another
     * instance) may pick it up. Covers the REST calls the repair makes, which
     * cannot happen inside the claiming transaction.
     */
    private Duration lease = Duration.ofMinutes(2);

    /** Attempts before an order is moved to NEEDS_ATTENTION. */
    private int maxAttempts = 5;

    /** Base backoff for an order whose repair failed; grows linearly with attempts. */
    private Duration retryBackoff = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }
}
