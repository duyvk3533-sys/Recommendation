package com.beauty.ecommerce.common.config;

import com.beauty.ecommerce.user.adapter.out.persistence.UserJpaEntity;
import com.beauty.ecommerce.user.adapter.out.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            log.info("Bắt đầu khởi tạo dữ liệu mẫu...");
            initializeUsers();
            initializeCategoriesAndProducts();
            fixActivityLogGroups();
            initializeProductViewHistory();
            log.info("Hoàn tất khởi tạo dữ liệu mẫu.");
        } catch (Exception e) {
            log.error("Lỗi khi khởi tạo dữ liệu mẫu: {}. Ứng dụng vẫn sẽ tiếp tục chạy.", e.getMessage());
        }
    }

    private void initializeProductViewHistory() {
        log.info("Khởi tạo lịch sử xem sản phẩm ngẫu nhiên để phục vụ phân cụm khách hàng...");
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_view_history", Integer.class);
            if (count != null && count > 0) {
                log.info("Lịch sử xem sản phẩm đã có dữ liệu. Bỏ qua khởi tạo.");
                return;
            }

            List<Long> userIds = jdbcTemplate.queryForList("SELECT id FROM users WHERE role = 'USER'", Long.class);
            if (userIds.isEmpty()) {
                log.info("Không có người dùng nào để tạo lịch sử xem.");
                return;
            }

            List<Long> productIds = jdbcTemplate.queryForList("SELECT id FROM products WHERE status = 'ACTIVE'", Long.class);
            if (productIds.isEmpty()) {
                log.info("Không có sản phẩm nào để tạo lịch sử xem.");
                return;
            }

            Random rand = new Random();
            for (Long userId : userIds) {
                Double totalSpent = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(total_price), 0) FROM orders WHERE user_id = ? AND status != 'CANCELLED'",
                        Double.class, userId
                );

                int viewsCount;
                if (totalSpent > 10000000) {
                    viewsCount = 15 + rand.nextInt(16);
                } else if (totalSpent > 100000) {
                    viewsCount = 3 + rand.nextInt(8);
                } else {
                    if (rand.nextDouble() < 0.7) {
                        viewsCount = 25 + rand.nextInt(26);
                    } else {
                        viewsCount = 1 + rand.nextInt(5);
                    }
                }

                for (int i = 0; i < viewsCount; i++) {
                    Long productId = productIds.get(rand.nextInt(productIds.size()));
                    jdbcTemplate.update(
                            "INSERT INTO product_view_history (user_id, product_id, viewed_at) VALUES (?, ?, NOW())",
                            userId, productId
                    );
                }
            }
            log.info("Đã khởi tạo thành công lịch sử xem sản phẩm cho phân cụm.");
        } catch (Exception e) {
            log.warn("Không thể khởi tạo lịch sử xem sản phẩm: {}", e.getMessage());
        }
    }

    private void fixActivityLogGroups() {
        log.info("Đang chuẩn hóa phân nhóm cho nhật ký hoạt động cũ...");
        try {
            // Chuyển các log mua sắm về đúng nhóm
            jdbcTemplate.execute("UPDATE activity_logs SET action_group = 'SHOPPING' " +
                    "WHERE action_group IN ('MUA SẮM', 'SYSTEM') " +
                    "AND action_type IN ('ADD_TO_CART', 'REMOVE_FROM_CART', 'CLEAR_CART', 'PLACE_ORDER', 'CANCEL_ORDER_REQUEST', 'APPROVE_CANCELLATION', 'REJECT_CANCELLATION')");
            
            // Chuyển các log tài khoản về đúng nhóm
            jdbcTemplate.execute("UPDATE activity_logs SET action_group = 'ACCOUNT' " +
                    "WHERE action_group IN ('TÀI KHOẢN', 'SYSTEM') " +
                    "AND action_type IN ('LOGIN', 'LOGIN_GOOGLE', 'REGISTER', 'CHANGE_PASSWORD', 'LOCK_USER', 'UNLOCK_USER')");
            
            // Chuẩn hóa nhóm hệ thống
            jdbcTemplate.execute("UPDATE activity_logs SET action_group = 'SYSTEM' " +
                    "WHERE action_group = 'HỆ THỐNG'");
            
            log.info("Đã chuẩn hóa dữ liệu nhật ký thành công.");
        } catch (Exception e) {
            log.warn("Không thể chuẩn hóa nhật ký hoạt động (có thể bảng chưa tồn tại): {}", e.getMessage());
        }
    }

    private void initializeUsers() {
        String adminEmail = "admin@beauty.com";
        userRepository.findByEmail(adminEmail).ifPresentOrElse(
            admin -> {
                log.info("Cập nhật lại mật khẩu cho tài khoản Admin: {}...", adminEmail);
                admin.setPassword(passwordEncoder.encode("admin123"));
                admin.setRole("ADMIN");
                userRepository.save(admin);
            },
            () -> {
                log.info("Khởi tạo tài khoản quản trị mặc định: {}...", adminEmail);
                UserJpaEntity admin = UserJpaEntity.builder()
                        .email(adminEmail)
                        .password(passwordEncoder.encode("admin123"))
                        .fullName("Quản Trị Viên")
                        .role("ADMIN")
                        .createdAt(LocalDateTime.now())
                        .build();
                userRepository.save(admin);
                log.info("Đã tạo tài khoản Admin thành công: admin@beauty.com / admin123");
            }
        );

        if (userRepository.findByEmail("user@beauty.com").isEmpty()) {
            UserJpaEntity user = UserJpaEntity.builder()
                    .email("user@beauty.com")
                    .password(passwordEncoder.encode("user123"))
                    .fullName("Khách Hàng Mẫu")
                    .role("USER")
                    .createdAt(LocalDateTime.now())
                    .build();

            userRepository.save(user);
            log.info("Đã tạo tài khoản User thành công: user@beauty.com / user123");
        }
    }

    private void initializeCategoriesAndProducts() {
        log.info("Bỏ qua khởi tạo danh mục tự động (để tránh trùng lặp dữ liệu)...");
    }
}
