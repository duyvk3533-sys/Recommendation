package com.beauty.ecommerce.product.application.service;

import com.beauty.ecommerce.order.adapter.out.persistence.OrderJpaEntity;
import com.beauty.ecommerce.order.adapter.out.persistence.OrderRepository;
import com.beauty.ecommerce.order.domain.entity.OrderStatus;
import com.beauty.ecommerce.order.domain.entity.PaymentStatus;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductJpaEntity;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductRepository;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductViewHistoryRepository;
import com.beauty.ecommerce.product.adapter.out.persistence.WishlistJpaEntity;
import com.beauty.ecommerce.product.adapter.out.persistence.WishlistRepository;
import com.beauty.ecommerce.product.adapter.out.persistence.mapper.ProductMapper;
import com.beauty.ecommerce.product.domain.entity.Product;
import com.beauty.ecommerce.review.adapter.out.persistence.ReviewRepository;
import com.beauty.ecommerce.user.adapter.out.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRecommendationService {

    private static final int MAX_LIMIT = 20;
    private static final int HISTORY_WINDOW_DAYS = 180;

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final WishlistRepository wishlistRepository;
    private final ProductViewHistoryRepository productViewHistoryRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final ProductMapper productMapper;

    public List<Product> getRecommendedProducts(Long seedProductId, int limit) {
        // Log the requesting user for debugging personalization issues
        try {
            String reqEmail = currentUserEmail();
            if (reqEmail != null) {
                userRepository.findByEmail(reqEmail).ifPresent(u ->
                    log.debug("Recommendation request by user email={}, id={}", reqEmail, u.getId())
                );
            } else {
                log.debug("Recommendation request by anonymous user");
            }
        } catch (Exception ex) {
            log.debug("Failed to log requesting user for recommendations", ex);
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        Optional<ProductJpaEntity> seedProduct = seedProductId == null
                ? Optional.empty()
                : productRepository.findById(seedProductId);

        RecommendationProfile profile = buildProfile(seedProduct.orElse(null));
        // Loại bỏ SP đã MUA, đã yêu thích và đã xem khỏi kết quả gợi ý
        // Những SP này vẫn được dùng làm tín hiệu sở thích (profile) nhưng KHÔNG hiển thị trong kết quả
        Set<Long> excludedProductIds = new HashSet<>(profile.purchasedProductIds);
        excludedProductIds.addAll(profile.wishlistProductIds);
        excludedProductIds.addAll(profile.viewedProductIds);
        excludedProductIds.addAll(profile.seedProductIds);
        if (seedProductId != null) {
            excludedProductIds.add(seedProductId);
        }

        List<ProductJpaEntity> candidates = productRepository.findAll().stream()
                .filter(product -> isEligibleCandidate(product, excludedProductIds))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return List.of();
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> ratingMap = new HashMap<>();
        Map<Long, Long> reviewCountMap = new HashMap<>();
        List<Long> candidateIds = candidates.stream().map(ProductJpaEntity::getId).collect(Collectors.toList());
        if (!candidateIds.isEmpty()) {
            reviewRepository.findRatingStatsByProductIds(candidateIds).forEach(row -> {
                Long productId = (Long) row[0];
                Double avgRating = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
                Long reviewCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
                ratingMap.put(productId, avgRating);
                reviewCountMap.put(productId, reviewCount);
            });
        }

        Map<Long, Double> collaborativeScores = buildCollaborativeScores(profile, seedProductId);

        // Compute score map to allow logging and deterministic ordering
        Map<Long, Double> scoreMap = new HashMap<>();
        for (ProductJpaEntity product : candidates) {
            double s = scoreProduct(
                product,
                seedProduct.orElse(null),
                profile,
                collaborativeScores,
                ratingMap.getOrDefault(product.getId(), 0.0),
                reviewCountMap.getOrDefault(product.getId(), 0L)
            );
            scoreMap.put(product.getId(), s);
        }

        // Log profile and candidate stats for debugging
        try {
            log.debug("Recommendation profile: purchased={}, wishlist={}, viewed={}, history={}, seedProducts={}",
                profile.purchasedProductIds.size(),
                profile.wishlistProductIds.size(),
                profile.viewedProductIds.size(),
                profile.historyProductIds.size(),
                profile.seedProductIds.size()
            );

            List<Map.Entry<Long, Double>> top = scoreMap.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(Math.min(10, scoreMap.size()))
                .collect(Collectors.toList());

            log.debug("Top recommendation candidates (id:score): {}", top.stream()
                .map(e -> e.getKey() + ":" + String.format("%.3f", e.getValue()))
                .collect(Collectors.joining(", "))
            );
        } catch (Exception ex) {
            log.debug("Failed to log recommendation debug info", ex);
        }

        // Sort candidates by computed score, then by viewCount and createdAt
        List<ProductJpaEntity> sorted = candidates.stream()
            .sorted((a, b) -> {
                double sa = scoreMap.getOrDefault(a.getId(), 0.0);
                double sb = scoreMap.getOrDefault(b.getId(), 0.0);
                int cmp = Double.compare(sb, sa);
                if (cmp != 0) return cmp;
                Long av = a.getViewCount() != null ? a.getViewCount() : 0L;
                Long bv = b.getViewCount() != null ? b.getViewCount() : 0L;
                cmp = bv.compareTo(av);
                if (cmp != 0) return cmp;
                java.time.LocalDateTime ac = a.getCreatedAt();
                java.time.LocalDateTime bc = b.getCreatedAt();
                if (ac == null && bc == null) return 0;
                if (ac == null) return 1;
                if (bc == null) return -1;
                return bc.compareTo(ac);
            })
            .limit(safeLimit)
            .collect(Collectors.toList());

        return sorted.stream()
            .map(productMapper::mapToDomainEntity)
            .collect(Collectors.toList());
    }

    private RecommendationProfile buildProfile(ProductJpaEntity seedProduct) {
        RecommendationProfile profile = new RecommendationProfile();

        if (seedProduct != null) {
            profile.seedProductIds.add(seedProduct.getId());
            if (seedProduct.getCategoryId() != null) {
                profile.preferredCategoryWeights.merge(seedProduct.getCategoryId(), 4.0, Double::sum);
            }
            addSkinTypeWeight(profile.preferredSkinTypeWeights, seedProduct.getSkinType(), 2.0);
            if (seedProduct.getName() != null) {
                addNameTokenWeights(profile.preferredNameTokenWeights, seedProduct.getName(), 2.0);
            }
        }

        String email = currentUserEmail();
        if (email == null) {
            return profile;
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            List<OrderJpaEntity> orders = orderRepository.findByUserEmailOrderByOrderDateDesc(email);
            LocalDateTime now = LocalDateTime.now();

            for (OrderJpaEntity order : orders) {
                if (!isQualifiedOrder(order)) {
                    continue;
                }

                double recencyWeight = recencyWeight(order.getOrderDate(), now);
                for (var item : order.getItems()) {
                    if (item.getProduct() == null) {
                        continue;
                    }
                    ProductJpaEntity product = item.getProduct();
                    profile.purchasedProductIds.add(product.getId());
                    profile.historyProductIds.add(product.getId());

                    if (product.getCategoryId() != null) {
                        profile.preferredCategoryWeights.merge(product.getCategoryId(), 5.5 * recencyWeight, Double::sum);
                    }
                    addSkinTypeWeight(profile.preferredSkinTypeWeights, product.getSkinType(), 3.4 * recencyWeight);
                    if (product.getName() != null) {
                        addNameTokenWeights(profile.preferredNameTokenWeights, product.getName(), 2.2 * recencyWeight);
                    }
                }
            }

            List<WishlistJpaEntity> wishlistItems = wishlistRepository.findByUserId(user.getId());
            for (WishlistJpaEntity wishlistItem : wishlistItems) {
                profile.wishlistProductIds.add(wishlistItem.getProductId());
                profile.historyProductIds.add(wishlistItem.getProductId());

                productRepository.findById(wishlistItem.getProductId()).ifPresent(product -> {
                    if (product.getCategoryId() != null) {
                        profile.preferredCategoryWeights.merge(product.getCategoryId(), 4.2, Double::sum);
                    }
                    addSkinTypeWeight(profile.preferredSkinTypeWeights, product.getSkinType(), 2.8);
                    if (product.getName() != null) {
                        addNameTokenWeights(profile.preferredNameTokenWeights, product.getName(), 1.8);
                    }
                });
            }

            try {
                productViewHistoryRepository.findTop50ByUserIdOrderByViewedAtDesc(user.getId()).forEach(viewHistory -> {
                    productRepository.findById(viewHistory.getProductId()).ifPresent(product -> {
                        profile.viewedProductIds.add(product.getId());
                        profile.historyProductIds.add(product.getId());

                        double viewWeight = recencyWeight(viewHistory.getViewedAt(), now) * 0.9;
                        if (product.getCategoryId() != null) {
                            // Tăng trọng số category từ view history để gợi ý phản hồi rõ ràng hơn
                            profile.preferredCategoryWeights.merge(product.getCategoryId(), 6.0 * viewWeight, Double::sum);
                        }
                        addSkinTypeWeight(profile.preferredSkinTypeWeights, product.getSkinType(), 3.5 * viewWeight);
                        if (product.getName() != null) {
                            addNameTokenWeights(profile.preferredNameTokenWeights, product.getName(), 2.5 * viewWeight);
                        }
                    });
                });
            } catch (Exception e) {
                log.warn("Could not load view history (table may not exist): {}", e.getMessage());
            }
        });

        return profile;
    }

    private Map<Long, Double> buildCollaborativeScores(RecommendationProfile profile, Long seedProductId) {
        Map<Long, Double> scores = new HashMap<>();
        Set<Long> focusProductIds = new HashSet<>(profile.historyProductIds);
        focusProductIds.addAll(profile.seedProductIds);
        if (seedProductId != null) {
            focusProductIds.add(seedProductId);
        }

        if (focusProductIds.isEmpty()) {
            return scores;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_WINDOW_DAYS);
        for (OrderJpaEntity order : orderRepository.findAllByOrderByOrderDateDesc()) {
            if (order.getOrderDate() == null || order.getOrderDate().isBefore(cutoff) || !isQualifiedOrder(order)) {
                continue;
            }

            Set<Long> orderProductIds = order.getItems().stream()
                    .map(item -> item.getProduct() != null ? item.getProduct().getId() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (orderProductIds.isEmpty()) {
                continue;
            }

            // compute how many focus products are present in this order
            Set<Long> inter = orderProductIds.stream().filter(focusProductIds::contains).collect(Collectors.toSet());
            if (inter.isEmpty()) {
                continue;
            }

            double orderWeight = recencyWeight(order.getOrderDate(), LocalDateTime.now()) + 0.5;
            double interFactor = Math.sqrt(inter.size()); // reduce effect of large intersections

            for (Long candidateId : orderProductIds) {
                if (focusProductIds.contains(candidateId)) continue; // skip focus items themselves
                double delta = orderWeight * interFactor / Math.max(1, orderProductIds.size());
                scores.merge(candidateId, delta, Double::sum);
            }
        }

        return scores;
    }

    // Compute a simple content similarity between profile and product
    private double contentSimilarity(ProductJpaEntity product, RecommendationProfile profile) {
        double sim = 0.0;

        if (product.getCategoryId() != null) {
            sim += profile.preferredCategoryWeights.getOrDefault(product.getCategoryId(), 0.0) * 1.2;
        }

        String normalizedSkinType = normalize(product.getSkinType());
        if (normalizedSkinType != null) {
            sim += profile.preferredSkinTypeWeights.getOrDefault(normalizedSkinType, 0.0) * 1.0;
        }

        // name token similarity
        if (product.getName() != null) {
            String[] tokens = product.getName().toLowerCase(Locale.ROOT).split("\\W+");
            for (String t : tokens) {
                if (t.length() < 3) continue;
                sim += profile.preferredNameTokenWeights.getOrDefault(t, 0.0) * 0.9;
            }
        }

        return sim;
    }

    private double scoreProduct(
            ProductJpaEntity product,
            ProductJpaEntity seedProduct,
            RecommendationProfile profile,
            Map<Long, Double> collaborativeScores,
            Double avgRating,
            Long reviewCount
    ) {
        // Combine content-based similarity, collaborative signal and popularity
        double contentSim = contentSimilarity(product, profile);

        double collab = collaborativeScores.getOrDefault(product.getId(), 0.0);

        // normalize collaborative roughly by log scale to avoid domination
        double collabNorm = Math.log1p(collab);

        double popularity = popularityScore(product, avgRating, reviewCount);

        // SP trong wishlist/viewed đã bị loại khỏi kết quả nên không cần boost nữa

        double seedBoost = 0.0;
        if (seedProduct != null) {
            if (Objects.equals(product.getCategoryId(), seedProduct.getCategoryId())) seedBoost += 1.5;
            if (normalize(seedProduct.getSkinType()) != null && normalize(seedProduct.getSkinType()).equals(normalize(product.getSkinType()))) seedBoost += 0.9;
        }

        // Giảm popularity bias cho user có dữ liệu cá nhân hóa
        boolean hasPersonalData = !profile.viewedProductIds.isEmpty()
                || !profile.wishlistProductIds.isEmpty()
                || !profile.purchasedProductIds.isEmpty();
        double popWeight = hasPersonalData ? 0.4 : 1.0;

        double score = (contentSim * 2.5)
                + (collabNorm * 2.0)
                + (popularity * popWeight)
                + seedBoost;

        return score;
    }

    private double popularityScore(ProductJpaEntity product, Double avgRating, Long reviewCount) {
        double views = product.getViewCount() != null ? product.getViewCount() : 0L;
        double sold = product.getSold() != null ? product.getSold() : 0;
        double rating = avgRating != null ? avgRating : 0.0;
        double reviews = reviewCount != null ? reviewCount : 0L;

        return (Math.log1p(views) * 0.8)
                + (Math.log1p(sold) * 1.2)
                + (rating * 1.9)
                + (Math.log1p(reviews) * 0.4);
    }

    private boolean isQualifiedOrder(OrderJpaEntity order) {
        if (order == null || order.getItems() == null || order.getItems().isEmpty()) {
            return false;
        }

        boolean statusQualified = order.getStatus() != null && (
                OrderStatus.DELIVERED.name().equals(order.getStatus())
                        || OrderStatus.CONFIRMED.name().equals(order.getStatus())
        );
        boolean paymentQualified = order.getPaymentStatus() != null && PaymentStatus.PAID.name().equals(order.getPaymentStatus());

        return statusQualified && paymentQualified;
    }

    private boolean isEligibleCandidate(ProductJpaEntity product, Set<Long> excludedProductIds) {
        if (product == null) {
            return false;
        }
        if (excludedProductIds != null && excludedProductIds.contains(product.getId())) {
            return false;
        }
        if (product.getStatus() != null && !"ACTIVE".equalsIgnoreCase(product.getStatus())) {
            return false;
        }
        return product.getStockQuantity() == null || product.getStockQuantity() > 0;
    }

    private String currentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        if (email == null || email.isBlank() || "anonymousUser".equalsIgnoreCase(email)) {
            return null;
        }
        return email;
    }

    private double recencyWeight(LocalDateTime orderDate, LocalDateTime now) {
        if (orderDate == null) {
            return 0.5;
        }

        long days = Math.max(0, ChronoUnit.DAYS.between(orderDate, now));
        double weight = 1.0 - (Math.min(days, 180) / 240.0);
        return Math.max(0.35, weight);
    }

    private void addSkinTypeWeight(Map<String, Double> map, String skinType, double weight) {
        String normalized = normalize(skinType);
        if (normalized != null) {
            map.merge(normalized, weight, Double::sum);
        }
    }

    private void addNameTokenWeights(Map<String, Double> map, String name, double weight) {
        if (name == null || name.isBlank()) return;
        String[] tokens = name.toLowerCase(Locale.ROOT).split("\\W+");
        for (String t : tokens) {
            if (t.length() < 3) continue;
            map.merge(t, weight, Double::sum);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean intersectionIsEmpty(Set<Long> left, Set<Long> right) {
        for (Long item : left) {
            if (right.contains(item)) {
                return false;
            }
        }
        return true;
    }

    private static class RecommendationProfile {
        private final Set<Long> purchasedProductIds = new HashSet<>();
        private final Set<Long> wishlistProductIds = new HashSet<>();
        private final Set<Long> viewedProductIds = new HashSet<>();
        private final Set<Long> historyProductIds = new HashSet<>();
        private final Set<Long> seedProductIds = new HashSet<>();
        private final Map<Long, Double> preferredCategoryWeights = new HashMap<>();
        private final Map<String, Double> preferredSkinTypeWeights = new HashMap<>();
        private final Map<String, Double> preferredNameTokenWeights = new HashMap<>();
    }
}