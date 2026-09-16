package com.ecommerce.cart.repository;

import com.ecommerce.cart.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, Long productId);

    Optional<CartItem> findByIdAndCartId(UUID id, UUID cartId);

    void deleteByIdAndCartId(UUID id, UUID cartId);
}
