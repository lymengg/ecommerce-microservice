package com.ecommerce.cart.repository;

import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByCustomerIdAndStatus(UUID customerId, CartStatus status);
}
