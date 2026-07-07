package com.beauty.ecommerce.product.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Service
@Slf4j
public class ImageEmbeddingService {
    private static final int VECTOR_SIZE = 512;
    private final ObjectMapper objectMapper = new ObjectMapper();

  
    public float[] extractEmbedding(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("Image file is empty");
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            String seed = (file.getOriginalFilename() == null ? "image" : file.getOriginalFilename())
                    + ":" + file.getSize()
                    + ":" + sha256(bytes);
            return generateDeterministicVector(seed);
      }
    }
    public float[] extractEmbeddingFromUrl(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IOException("Image URL is empty");
        }
        try {
            URL url = new URL(imageUrl);
            try (InputStream inputStream = url.openStream()) {
                byte[] bytes = inputStream.readAllBytes();
                return generateDeterministicVector(imageUrl + ":" + sha256(bytes));
              }
        } catch (Exception e) {
            log.warn("Could not read image URL '{}'. Falling back to URL-based vector. Reason: {}", imageUrl, e.getMessage());
            return generateDeterministicVector(imageUrl);
        }
    }

    public String vectorToJson(float[] vector) throws IOException {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        return objectMapper.writeValueAsString(boxed);
    }

    public float[] jsonToVector(String json) throws IOException {
        List<Float> list = objectMapper.readValue(json, new TypeReference<List<Float>>() {});
        float[] vector = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            vector[i] = list.get(i);
        }
        return vector;
    }
    public double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private float[] generateDeterministicVector(String seed) {
        float[] vector = new float[VECTOR_SIZE];
        String safeSeed = seed == null ? "visual-search" : seed;
        for (int i = 0; i < VECTOR_SIZE; i++) {
            int hash = (safeSeed + ":" + i).hashCode();
            vector[i] = (float) Math.sin(hash) * (float) Math.cos(hash / 31.0);
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    private String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
      }
}
