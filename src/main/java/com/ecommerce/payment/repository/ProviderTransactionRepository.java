package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.ProviderTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderTransactionRepository extends JpaRepository<ProviderTransaction, UUID> {

    Optional<ProviderTransaction> findByProviderAndProviderTransactionId(String provider, String providerTransactionId);
}
