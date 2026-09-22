package com.ecommerce.common.observability;

import com.ecommerce.common.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox backlog as metrics (Phase 8, ADR-021) — doc 08 §4 lists it, and docs/13
 * calls it one of the two that matter most now.
 *
 * <p>The outbox is what makes a Kafka outage survivable: rows are retained and
 * drained on recovery. That design has a blind spot, and it is this metric's
 * job to cover it — a publisher that stops draining (Kafka unreachable, a
 * poisoned batch, a scheduler that died) looks exactly like a healthy system
 * from the outside, because every business request still succeeds. The only
 * symptom is that the backlog stops shrinking.
 *
 * <p>Two series, because neither alone is enough:
 * <ul>
 *   <li>{@code outbox.events.unpublished} — the count. Normal under load.</li>
 *   <li>{@code outbox.events.oldest.unpublished.age} — seconds since the oldest
 *       unpublished event occurred. This is the one that distinguishes "busy"
 *       from "stuck": a backlog of one row that is an hour old is an incident, a
 *       backlog of fifty that is two seconds old is a Tuesday.</li>
 * </ul>
 *
 * <p>Values are polled at scrape time, which costs one indexed count per scrape.
 * The last known value is kept and returned if the query fails, so a database
 * outage produces a stale metric rather than a failed scrape — a scrape that
 * errors is indistinguishable from a service that is down, and that is the wrong
 * signal.
 */
public class OutboxBacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

    private final OutboxRepository outboxRepository;
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxBacklogMetrics(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.events.unpublished", this, OutboxBacklogMetrics::unpublishedCount)
                .description("Events written but not yet published to Kafka")
                .register(registry);
        Gauge.builder("outbox.events.oldest.unpublished.age", this, OutboxBacklogMetrics::oldestAgeSeconds)
                .description("Seconds since the oldest unpublished event occurred")
                .baseUnit("seconds")
                .register(registry);
    }

    private double unpublishedCount() {
        try {
            unpublished.set(outboxRepository.countByPublishedAtIsNull());
        } catch (RuntimeException ex) {
            // Keep the last known value: see the class comment.
            log.debug("Could not read the outbox backlog; reporting the last known value", ex);
        }
        return unpublished.get();
    }

    private double oldestAgeSeconds() {
        try {
            Instant oldest = outboxRepository.oldestUnpublishedOccurredAt();
            oldestAgeSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds()));
        } catch (RuntimeException ex) {
            log.debug("Could not read the oldest unpublished event; reporting the last known value", ex);
        }
        return oldestAgeSeconds.get();
    }
}
