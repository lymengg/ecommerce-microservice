package com.ecommerce.common.messaging;

import java.util.UUID;

/**
 * The consumer half of the idempotency contract (doc 06 §4-5): every listener
 * asks {@link #alreadyProcessed} before acting and calls
 * {@link #markProcessed} after.
 *
 * <p>Both calls must run in the listener's transaction, together with the
 * business change — see {@link ProcessedEvent}. The consumer group is recorded
 * so two independent capabilities in the same service can safely consume the
 * same stream without one suppressing the other's work.
 */
public class ConsumerIdempotency {

    private final ProcessedEventRepository repository;

    public ConsumerIdempotency(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    public boolean alreadyProcessed(UUID eventId) {
        return repository.existsById(eventId);
    }

    public void markProcessed(UUID eventId, String consumerGroup) {
        repository.save(new ProcessedEvent(eventId, consumerGroup));
    }
}
