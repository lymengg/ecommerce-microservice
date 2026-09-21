package com.ecommerce.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Column(name = "published_at", columnDefinition = "timestamptz")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * The edge correlation id at record time (Phase 5, ADR-014). Captured here
     * because the publisher runs later, on a scheduler thread, with no request
     * to read it from.
     */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /**
     * The W3C {@code traceparent} at record time. Restored as the current
     * context when the row is published, so the consumer continues the original
     * trace (doc 08 §2).
     */
    @Column(name = "trace_parent", length = 128)
    private String traceParent;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(String aggregateType,
                       String aggregateId,
                       String eventType,
                       int eventVersion,
                       String payload,
                       String correlationId,
                       String traceParent) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.occurredAt = Instant.now();
        this.correlationId = correlationId;
        this.traceParent = traceParent;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceParent() {
        return traceParent;
    }

    /** Marks the row as delivered. The publisher does this inside its own transaction. */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** Counts a failed publish attempt; the row stays unpublished and is retried. */
    public void recordFailedAttempt() {
        this.attemptCount++;
    }
}
