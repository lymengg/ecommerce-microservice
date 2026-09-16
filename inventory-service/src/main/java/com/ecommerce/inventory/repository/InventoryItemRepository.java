package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.model.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Optional<InventoryItem> findByProductId(Long productId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.reservedQuantity = i.reservedQuantity + :quantity
             WHERE i.productId = :productId
               AND i.totalQuantity - i.reservedQuantity - i.committedQuantity >= :quantity
            """)
    int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.reservedQuantity = i.reservedQuantity - :quantity
             WHERE i.productId = :productId
               AND i.reservedQuantity >= :quantity
            """)
    int release(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.reservedQuantity = i.reservedQuantity - :quantity,
                   i.committedQuantity = i.committedQuantity + :quantity
             WHERE i.productId = :productId
               AND i.reservedQuantity >= :quantity
            """)
    int commit(@Param("productId") Long productId, @Param("quantity") int quantity);
}
