package com.beauty.ecommerce.product.application.port.in;

import com.beauty.ecommerce.product.adapter.in.web.response.ProductResponseDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface VisualSearchUseCase {
    List<ProductResponseDTO> searchByImage(MultipartFile imageFile, int topK) throws IOException;
    void indexProductVector(Long productId, String imageUrl) throws IOException;
    void indexAllProducts() throws IOException;
}
