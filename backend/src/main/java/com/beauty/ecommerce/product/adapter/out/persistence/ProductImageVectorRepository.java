package com.beauty.ecommerce.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductImageVectorRepository extends JpaRepository<ProductImageVectorJpaEntity, Long> {

    Optional<ProductImageVectorJpaEntity> findByProductId(Long productId);

    List<ProductImageVectorJpaEntity> findAll();

    @Query("SELECT p FROM ProductImageVectorJpaEntity p WHERE p.productId IN :productIds")
    List<ProductImageVectorJpaEntity> findByProductIdIn(List<Long> productIds);

    void deleteByProductId(Long productId);
}