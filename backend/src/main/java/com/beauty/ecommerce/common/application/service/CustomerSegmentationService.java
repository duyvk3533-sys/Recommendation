package com.beauty.ecommerce.common.application.service;

import com.beauty.ecommerce.common.dto.CustomerSegmentDTO;
import com.beauty.ecommerce.common.dto.CustomerSegmentationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerSegmentationService {

    @PersistenceContext
    private final EntityManager entityManager;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ClusterPoint {
        private Long userId;
        private String fullName;
        private String email;
        private double totalSpent;
        private double productViews;
        private double xNorm;
        private double yNorm;
        private String segment;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    private static class Centroid {
        private double x;
        private double y;
    }

    @Transactional(readOnly = true)
    public CustomerSegmentationResponse getCustomerSegments() {
        // Query to get user data from Database
        String sql = "SELECT u.id, u.full_name, u.email, " +
                     "  COALESCE((SELECT SUM(o.total_price) FROM orders o WHERE o.user_id = u.id AND o.status != 'CANCELLED'), 0) as total_spent, " +
                     "  COALESCE((SELECT COUNT(*) FROM product_view_history pvh WHERE pvh.user_id = u.id), 0) as product_views " +
                     "FROM users u " +
                     "WHERE u.role = 'USER'";

        List<Object[]> results = entityManager.createNativeQuery(sql).getResultList();
        List<ClusterPoint> points = new ArrayList<>();

        double maxSpent = 0;
        double minSpent = Double.MAX_VALUE;
        double maxViews = 0;
        double minViews = Double.MAX_VALUE;

        for (Object[] row : results) {
            Long userId = ((Number) row[0]).longValue();
            String fullName = (String) row[1];
            String email = (String) row[2];
            double totalSpent = ((Number) row[3]).doubleValue();
            double productViews = ((Number) row[4]).doubleValue();

            ClusterPoint point = ClusterPoint.builder()
                    .userId(userId)
                    .fullName(fullName)
                    .email(email)
                    .totalSpent(totalSpent)
                    .productViews(productViews)
                    .build();

            points.add(point);

            if (totalSpent > maxSpent) maxSpent = totalSpent;
            if (totalSpent < minSpent) minSpent = totalSpent;
            if (productViews > maxViews) maxViews = productViews;
            if (productViews < minViews) minViews = productViews;
        }

        List<CustomerSegmentDTO> customers = new ArrayList<>();
        if (points.isEmpty()) {
            return CustomerSegmentationResponse.builder()
                    .customers(Collections.emptyList())
                    .segmentSizes(Collections.emptyMap())
                    .averageSpent(Collections.emptyMap())
                    .averageViews(Collections.emptyMap())
                    .build();
        }

        // Normalize using Min-Max scaling
        double spentRange = maxSpent - minSpent;
        double viewsRange = maxViews - minViews;

        for (ClusterPoint p : points) {
            p.setXNorm(spentRange == 0 ? 0 : (p.getTotalSpent() - minSpent) / spentRange);
            p.setYNorm(viewsRange == 0 ? 0 : (p.getProductViews() - minViews) / viewsRange);
        }

        int k = 3;
        if (points.size() < k) {
            // Fallback for small datasets
            for (ClusterPoint p : points) {
                if (p.getTotalSpent() > 1000000) {
                    p.setSegment("VIP");
                } else if (p.getProductViews() > 5) {
                    p.setSegment("POTENTIAL");
                } else {
                    p.setSegment("CHEAP");
                }
                customers.add(mapToDTO(p));
            }
        } else {
            // Initialize Centroids systematically from data seeds
            List<Centroid> centroids = new ArrayList<>();
            ClusterPoint vipSeed = points.get(0);
            ClusterPoint cheapSeed = points.get(0);
            ClusterPoint potentialSeed = points.get(0);

            for (ClusterPoint p : points) {
                if (p.getTotalSpent() > vipSeed.getTotalSpent()) vipSeed = p;
                if (p.getTotalSpent() < cheapSeed.getTotalSpent()) cheapSeed = p;
            }
            for (ClusterPoint p : points) {
                if (p.getProductViews() > potentialSeed.getProductViews() && p.getTotalSpent() < vipSeed.getTotalSpent() / 2) {
                    potentialSeed = p;
                }
            }

            centroids.add(new Centroid(cheapSeed.getXNorm(), cheapSeed.getYNorm()));
            centroids.add(new Centroid(vipSeed.getXNorm(), vipSeed.getYNorm()));
            centroids.add(new Centroid(potentialSeed.getXNorm(), potentialSeed.getYNorm()));

            // K-Means loop
            boolean changed = true;
            int maxIterations = 100;
            int iteration = 0;
            int[] assignments = new int[points.size()];

            while (changed && iteration < maxIterations) {
                changed = false;
                iteration++;

                // Assignment step
                for (int i = 0; i < points.size(); i++) {
                    ClusterPoint p = points.get(i);
                    int bestCentroid = 0;
                    double minDist = Double.MAX_VALUE;

                    for (int j = 0; j < k; j++) {
                        Centroid ctr = centroids.get(j);
                        double dist = Math.pow(p.getXNorm() - ctr.getX(), 2) + Math.pow(p.getYNorm() - ctr.getY(), 2);
                        if (dist < minDist) {
                            minDist = dist;
                            bestCentroid = j;
                        }
                    }

                    if (assignments[i] != bestCentroid) {
                        assignments[i] = bestCentroid;
                        changed = true;
                    }
                }

                // Recalculation step
                double[] sumX = new double[k];
                double[] sumY = new double[k];
                int[] counts = new int[k];

                for (int i = 0; i < points.size(); i++) {
                    int clusterIdx = assignments[i];
                    sumX[clusterIdx] += points.get(i).getXNorm();
                    sumY[clusterIdx] += points.get(i).getYNorm();
                    counts[clusterIdx]++;
                }

                for (int j = 0; j < k; j++) {
                    if (counts[j] > 0) {
                        centroids.get(j).setX(sumX[j] / counts[j]);
                        centroids.get(j).setY(sumY[j] / counts[j]);
                    }
                }
            }

            // Cluster labelling logic based on averages
            double[] avgSpent = new double[k];
            double[] avgViews = new double[k];
            int[] counts = new int[k];

            for (int i = 0; i < points.size(); i++) {
                int cluster = assignments[i];
                avgSpent[cluster] += points.get(i).getTotalSpent();
                avgViews[cluster] += points.get(i).getProductViews();
                counts[cluster]++;
            }

            for (int j = 0; j < k; j++) {
                if (counts[j] > 0) {
                    avgSpent[j] /= counts[j];
                    avgViews[j] /= counts[j];
                }
            }

            // Map clusters
            int vipCluster = 0;
            for (int j = 1; j < k; j++) {
                if (avgSpent[j] > avgSpent[vipCluster]) {
                    vipCluster = j;
                }
            }

            int potentialCluster = -1;
            for (int j = 0; j < k; j++) {
                if (j == vipCluster) continue;
                if (potentialCluster == -1) {
                    potentialCluster = j;
                } else {
                    if (avgViews[j] > avgViews[potentialCluster]) {
                        potentialCluster = j;
                    }
                }
            }

            int cheapCluster = -1;
            for (int j = 0; j < k; j++) {
                if (j != vipCluster && j != potentialCluster) {
                    cheapCluster = j;
                    break;
                }
            }

            for (int i = 0; i < points.size(); i++) {
                int cluster = assignments[i];
                ClusterPoint p = points.get(i);
                if (cluster == vipCluster) {
                    p.setSegment("VIP");
                } else if (cluster == potentialCluster) {
                    p.setSegment("POTENTIAL");
                } else {
                    p.setSegment("CHEAP");
                }
                customers.add(mapToDTO(p));
            }
        }

        // Compute summary metrics for each segment
        Map<String, Integer> sizes = new HashMap<>();
        Map<String, Double> spentSums = new HashMap<>();
        Map<String, Double> viewSums = new HashMap<>();

        for (CustomerSegmentDTO c : customers) {
            sizes.put(c.getSegment(), sizes.getOrDefault(c.getSegment(), 0) + 1);
            spentSums.put(c.getSegment(), spentSums.getOrDefault(c.getSegment(), 0.0) + c.getTotalSpent());
            viewSums.put(c.getSegment(), viewSums.getOrDefault(c.getSegment(), 0.0) + c.getProductViews());
        }

        Map<String, Double> averageSpent = new HashMap<>();
        Map<String, Double> averageViews = new HashMap<>();

        for (String seg : sizes.keySet()) {
            int count = sizes.get(seg);
            averageSpent.put(seg, spentSums.get(seg) / count);
            averageViews.put(seg, viewSums.get(seg) / count);
        }

        return CustomerSegmentationResponse.builder()
                .customers(customers)
                .segmentSizes(sizes)
                .averageSpent(averageSpent)
                .averageViews(averageViews)
                .build();
    }

    private CustomerSegmentDTO mapToDTO(ClusterPoint p) {
        return CustomerSegmentDTO.builder()
                .userId(p.getUserId())
                .fullName(p.getFullName())
                .email(p.getEmail())
                .totalSpent(p.getTotalSpent())
                .productViews(p.getProductViews())
                .segment(p.getSegment())
                .build();
    }
}
