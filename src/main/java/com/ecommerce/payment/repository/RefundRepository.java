package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.Refund;
import com.ecommerce.payment.model.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentId(UUID paymentId);

    List<Refund> findByPaymentIdAndStatus(UUID paymentId, RefundStatus status);
}
