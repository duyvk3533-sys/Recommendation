CREATE TABLE product_view_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    viewed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_view_history_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_product_view_history_product FOREIGN KEY (product_id) REFERENCES products(id),
    INDEX idx_product_view_history_user_viewed_at (user_id, viewed_at),
    INDEX idx_product_view_history_user_product (user_id, product_id)
);