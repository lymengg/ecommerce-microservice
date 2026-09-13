package com.ecommerce.catalog.product.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, String> {

    Optional<ProductJpaEntity> findBySku(String sku);

    boolean existsBySku(String sku);

    @Query("SELECT p FROM ProductJpaEntity p WHERE (:sku IS NULL OR p.sku = :sku) AND (:name IS NULL OR p.name LIKE %:name%)")
    Page<ProductJpaEntity> findByFilters(@Param("sku") String sku, @Param("name") String name, Pageable pageable);
}
