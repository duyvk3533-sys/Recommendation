-- V35: Add image_vector column to products table for Visual Search feature
-- image_vector stores JSON array of float values (embedding from DJL/MobileNet)
ALTER TABLE products
    ADD COLUMN image_vector LONGTEXT NULL COMMENT 'JSON array of image embedding vector for visual search';

-- Create a separate table to store indexed product image vectors
CREATE TABLE IF NOT EXISTS product_image_vectors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    image_url VARCHAR(1000) NOT NULL,
    vector_data LONGTEXT NOT NULL COMMENT 'JSON array of float embedding values',
    model_name VARCHAR(100) DEFAULT 'MobileNetV2' COMMENT 'AI model used for embedding',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_piv_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    INDEX idx_piv_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;