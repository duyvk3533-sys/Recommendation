package com.beauty.ecommerce.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSegmentDTO {
    private Long userId;
    private String fullName;
    private String email;
    private double totalSpent;
    private double productViews;
    private String segment; // VIP, POTENTIAL, CHEAP
}
