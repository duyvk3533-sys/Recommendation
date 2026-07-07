package com.beauty.ecommerce.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSegmentationResponse {
    private List<CustomerSegmentDTO> customers;
    private Map<String, Integer> segmentSizes;
    private Map<String, Double> averageSpent;
    private Map<String, Double> averageViews;
}
