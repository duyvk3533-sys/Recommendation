package com.beauty.ecommerce.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesForecastResponse {
    private List<SalesForecastPointDTO> points;
    private String period;
    private BigDecimal totalHistoricalRevenue;
    private Integer totalHistoricalOrders;
    private BigDecimal totalForecastedRevenue;
    private Integer totalForecastedOrders;
    private String revenueTrend; // UPWARD, DOWNWARD, STABLE
    private String ordersTrend;  // UPWARD, DOWNWARD, STABLE
}
