package com.beauty.ecommerce.product.application.service;

import com.beauty.ecommerce.product.adapter.in.web.response.ProductResponseDTO;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductImageVectorJpaEntity;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductImageVectorRepository;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductJpaEntity;
import com.beauty.ecommerce.product.adapter.out.persistence.ProductRepository;
import com.beauty.ecommerce.product.application.port.in.VisualSearchUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisualSearchService implements VisualSearchUseCase {

    private final ImageEmbeddingService embeddingService;
    private final ProductImageVectorRepository vectorRepository;
    private final ProductRepository productRepository;

    @Override
    public List<ProductResponseDTO> searchByImage(MultipartFile imageFile, int topK) throws IOException {
        log.info("Visual search started for file: {}, topK={}", imageFile.getOriginalFilename(), topK);

        // 1. Extract embedding from uploaded image
        float[] queryVector = embeddingService.extractEmbedding(imageFile);

        // 2. Load all indexed product vectors
        List<ProductImageVectorJpaEntity> allVectors = vectorRepository.findAll();
        if (allVectors.isEmpty()) {
            log.warn("No product vectors indexed yet. Please run indexing first.");
            return Collections.emptyList();
        }

        // 3. Compute cosine similarity for each product
        List<ScoredProduct> scored = new ArrayList<>();
        for (ProductImageVectorJpaEntity pv : allVectors) {
            try {
                float[] productVector = embeddingService.jsonToVector(pv.getVectorData());
                double similarity = embeddingService.cosineSimilarity(queryVector, productVector);
                scored.add(new ScoredProduct(pv.getProductId(), similarity));
            } catch (Exception e) {
                log.warn("Could not parse vector for product {}: {}", pv.getProductId(), e.getMessage());
            }
        }

        // 4. Sort by similarity descending, take topK
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Long> topProductIds = scored.stream()
                .limit(topK)
                .map(ScoredProduct::productId)
                .collect(Collectors.toList());

        // 5. Load product details
        List<ProductJpaEntity> products = productRepository.findAllById(topProductIds);

        // Sort products in same order as similarity scores
        Map<Long, Double> scoreMap = scored.stream()
                .collect(Collectors.toMap(ScoredProduct::productId, ScoredProduct::score));
        products.sort((a, b) -> Double.compare(
                scoreMap.getOrDefault(b.getId(), 0.0),
                scoreMap.getOrDefault(a.getId(), 0.0)
        ));

        log.info("Visual search found {} results", products.size());
        return products.stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void indexProductVector(Long productId, String imageUrl) throws IOException {
        log.info("Indexing vector for product {} with image {}", productId, imageUrl);
        float[] vector = embeddingService.extractEmbeddingFromUrl(imageUrl);
        String vectorJson = embeddingService.vectorToJson(vector);

        ProductImageVectorJpaEntity entity = vectorRepository.findByProductId(productId)
                .orElse(ProductImageVectorJpaEntity.builder()
                        .productId(productId)
                        .modelName("MobileNetV2")
                        .build());
        entity.setImageUrl(imageUrl);
        entity.setVectorData(vectorJson);
        vectorRepository.save(entity);
        log.info("Vector indexed for product {}", productId);
    }

    @Override
    @Async
    @Transactional
    public void indexAllProducts() throws IOException {
        log.info("Starting bulk indexing of all product images...");
        List<ProductJpaEntity> products = productRepository.findAll();
        int success = 0, failed = 0;

        for (ProductJpaEntity product : products) {
            try {
                String imageUrl = getFirstImageUrl(product);
                if (imageUrl != null && !imageUrl.isBlank()) {
                    indexProductVector(product.getId(), imageUrl);
                    success++;
                }
            } catch (Exception e) {
                log.warn("Failed to index product {}: {}", product.getId(), e.getMessage());
                failed++;
            }
        }
        log.info("Bulk indexing complete. Success: {}, Failed: {}", success, failed);
    }

    private String getFirstImageUrl(ProductJpaEntity product) {
        if (product == null) {
            return null;
        }
        if (product.getImages() != null) {
          for (var image : product.getImages()) {
            if (image == null) {
              continue;
            }
            String imageUrl = image.getImageUrl();
            if (imageUrl != null && !imageUrl.isBlank()) {
              return imageUrl;
            }
          }
        }
        return product.getImageUrl();
    }

    private ProductResponseDTO toResponseDTO(ProductJpaEntity product) {
        List<String> imageUrls = product.getImages() != null
                ? product.getImages().stream()
                        .map(img -> img.getImageUrl())
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return ProductResponseDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .currentPrice(product.getCurrentPrice())
                .imageUrl(product.getImageUrl())
                .images(imageUrls)
                .build();
    }

    private record ScoredProduct(Long productId, double score) {}
}
