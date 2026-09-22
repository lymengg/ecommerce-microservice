package com.ecommerce.order.repository;

import com.ecommerce.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByCustomerId(UUID customerId);

    /**
     * Claims a batch of orders for reconciliation (ADR-020).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the job safe to run on
     * two instances: each row is handed to exactly one claimer, and a row
     * another instance is already locking is skipped rather than waited for.
     * The caller then pushes {@code next_reconciliation_at} into the future in
     * the same transaction (a lease), because the repair itself makes REST calls
     * and must not hold a database lock while it does.
     *
     * <p>Only the genuinely stuck states are candidates, only orders that have
     * been sitting in them past the staleness threshold, and only those whose
     * backoff has elapsed.
     */
    @Query(value = """
            SELECT * FROM orders
             WHERE status IN ('PENDING', 'PAYMENT_PENDING')
               AND updated_at < :staleBefore
               AND (next_reconciliation_at IS NULL OR next_reconciliation_at <= :now)
             ORDER BY updated_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Order> claimForReconciliation(@Param("staleBefore") Instant staleBefore,
                                       @Param("now") Instant now,
                                       @Param("limit") int limit);

    /**
     * Takes the lease on a claimed batch.
     *
     * <p>A bulk update rather than mutating the loaded entities on purpose:
     * {@code updated_at} means "last business change" and is the staleness clock
     * the claim query reads, so an operational write must not move it. Going
     * through the entity would fire {@code @PreUpdate} and push the order out of
     * the candidate window for another full staleness period after every pass.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Order o SET o.nextReconciliationAt = :until WHERE o.id IN :ids")
    int holdForReconciliation(@Param("ids") List<UUID> ids, @Param("until") Instant until);

    /** Records one failed attempt and when it may be retried. Same reasoning as above. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.reconciliationAttempts = o.reconciliationAttempts + 1,
                   o.nextReconciliationAt = :next
             WHERE o.id = :id
            """)
    int recordReconciliationAttempt(@Param("id") UUID id, @Param("next") Instant next);
}
