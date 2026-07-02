package com.beauty.ecommerce.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductViewHistoryRepository extends JpaRepository<ProductViewHistoryJpaEntity, Long> {
    List<ProductViewHistoryJpaEntity> findTop50ByUserIdOrderByViewedAtDesc(Long userId);
}