package com.ecommerce.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Locks a batch of unpublished events for this publisher only.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the publisher safe to run
     * in more than one instance: each instance locks the rows it is about to
     * publish and skips rows another instance already holds, so the same event
     * is never published twice by two publishers. Ordering by
     * {@code occurred_at, id} keeps events for a given aggregate in the order
     * they happened.
     *
     * <p>Must be called inside a transaction — the lock lives until commit.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY occurred_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublishedBatch(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();
}
