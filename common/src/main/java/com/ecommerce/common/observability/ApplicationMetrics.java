package com.ecommerce.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The business signals doc 08 §4 asks for, in one place (Phase 8, ADR-021).
 *
 * <p>Metric names are an API — dashboards and alert rules are written against
 * them — so they are declared here rather than spelled out at each call site.
 * A typo in a metric name does not fail a build; it silently produces a second
 * series that nothing queries.
 *
 * <p><b>Do not end a metric name with a reserved Prometheus suffix.</b> The
 * registry delegates to the Prometheus client's {@code prometheusName}, which
 * strips the suffixes the exposition format reserves for itself —
 * {@code created}, {@code total}, {@code sum}, {@code count}, {@code bucket},
 * {@code info} — before re-adding its own. So {@code orders.created} was
 * exported as {@code orders_total}: the word "created" vanished, with no error
 * and no warning, and only a dashboard query that returned nothing would have
 * revealed it. Hence {@code orders.placed} below. (Found in Phase 8; recorded in
 * PROGRESS.md.)
 *
 * <p>Why these exist alongside the HTTP metrics Boot already provides:
 * {@code http.server.requests} answers "was the request slow or an error", but
 * it cannot tell a *declined payment* from a *malformed request* — both are 4xx.
 * For this system the difference is the whole point: a rising decline rate is a
 * business event worth seeing, and it must not be confused with a rising error
 * rate or it will be alerted on wrongly.
 *
 * <p>Deliberately no saga latency timer: {@code http.server.requests} on
 * checkout-service already measures exactly that (the saga *is* the request),
 * and inventing a second series for the same quantity is how dashboards end up
 * disagreeing with each other.
 */
public class ApplicationMetrics {

    /** Checkout outcomes. {@code PAID} is success; the rest are business rejections. */
    public static final String CHECKOUT_PAID = "paid";
    public static final String CHECKOUT_PAYMENT_DECLINED = "payment_declined";
    public static final String CHECKOUT_INSUFFICIENT_STOCK = "insufficient_stock";
    public static final String CHECKOUT_UNAVAILABLE = "dependency_unavailable";
    public static final String CHECKOUT_BUDGET_EXHAUSTED = "budget_exhausted";

    /** Reservation outcomes. */
    public static final String RESERVATION_RESERVED = "reserved";
    public static final String RESERVATION_INSUFFICIENT = "insufficient_stock";
    public static final String RESERVATION_RELEASED = "released";
    public static final String RESERVATION_COMMITTED = "committed";
    public static final String RESERVATION_EXPIRED = "expired";

    /** Reconciliation outcomes, per pass. */
    public static final String RECONCILIATION_COMPLETED = "completed";
    public static final String RECONCILIATION_COMPENSATED = "compensated";
    public static final String RECONCILIATION_DEFERRED = "deferred";
    public static final String RECONCILIATION_NEEDS_ATTENTION = "needs_attention";

    private final MeterRegistry registry;

    public ApplicationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void checkoutOutcome(String outcome) {
        count("checkout.saga.outcomes", "outcome", outcome);
    }

    public void orderCreated() {
        // `placed`, not `created`: see the class comment on reserved suffixes.
        count("orders.placed", null, null);
    }

    /** Payment outcomes, including the mock provider's declines. */
    public void paymentOutcome(String status) {
        count("payments.outcomes", "status", status);
    }

    public void reservationOutcome(String outcome) {
        count("inventory.reservations", "outcome", outcome);
    }

    public void reconciliationOutcome(String outcome) {
        count("reconciliation.orders", "outcome", outcome);
    }

    /**
     * How long a saga pass took to reconcile, so a job that is running but
     * making no progress is visible (the count alone cannot show that).
     */
    public void reconciliationPass(int claimed) {
        registry.counter("reconciliation.passes").increment();
        registry.counter("reconciliation.claimed").increment(claimed);
    }

    private void count(String name, String tagKey, String tagValue) {
        Counter.Builder builder = Counter.builder(name);
        if (tagKey != null) {
            builder.tag(tagKey, tagValue);
        }
        builder.register(registry).increment();
    }
}
