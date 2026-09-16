package com.ecommerce.order.repository;

import com.ecommerce.order.model.OrderIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderIdempotencyRecordRepository extends JpaRepository<OrderIdempotencyRecord, UUID> {

    Optional<OrderIdempotencyRecord> findByIdempotencyKey(String idempotencyKey);
}
