package com.ecommerce.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record that a consumer has already handled an event (doc 06 §5).
 *
 * <p>Kafka delivers at-least-once, so a consumer <em>will</em> see the same
 * event twice — after a rebalance, a crash between processing and committing
 * the offset, or a producer retry. The dedup key is the event id, which is
 * generated once when the outbox row is written and therefore stable across
 * redeliveries.
 *
 * <p>Crucially the insert happens <em>in the same local transaction</em> as the
 * business change. Either both commit or neither does, which turns
 * at-least-once delivery into exactly-once <em>effects</em> — the only kind of
 * exactly-once that actually exists without distributed transactions.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false, columnDefinition = "timestamptz")
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
