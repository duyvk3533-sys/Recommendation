package com.beauty.ecommerce.common.application.service;

import com.beauty.ecommerce.common.dto.SalesForecastPointDTO;
import com.beauty.ecommerce.common.dto.SalesForecastResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SalesForecastingService {

    private final JdbcTemplate jdbcTemplate;

    public SalesForecastResponse forecastSales(String period) {
        int historyDays = 30;
        int forecastDays = 7;

        if ("month".equalsIgnoreCase(period)) {
            historyDays = 60;
            forecastDays = 30;
        } else if ("quarter".equalsIgnoreCase(period)) {
            historyDays = 90;
            forecastDays = 90;
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(historyDays - 1);
        LocalDateTime startDateTime = LocalDateTime.of(startDate, LocalTime.MIN);

        // Fetch daily sales from DB
        String sql = "SELECT DATE(order_date) as order_day, " +
                     "       SUM(total_price) as daily_revenue, " +
                     "       COUNT(id) as daily_orders " +
                     "FROM orders " +
                     "WHERE status != 'CANCELLED' AND order_date >= ? " +
                     "GROUP BY DATE(order_date) " +
                     "ORDER BY DATE(order_date) ASC";

        Map<LocalDate, DailySales> dbSalesMap = new HashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                LocalDate date = rs.getDate("order_day").toLocalDate();
                BigDecimal revenue = rs.getBigDecimal("daily_revenue");
                int orders = rs.getInt("daily_orders");
                dbSalesMap.put(date, new DailySales(revenue, orders));
            }, startDateTime);
        } catch (Exception e) {
            log.error("Lỗi khi truy vấn doanh số lịch sử: {}", e.getMessage());
        }

        // Fill continuous series for history
        double[] revenues = new double[historyDays];
        double[] orders = new double[historyDays];
        List<SalesForecastPointDTO> points = new ArrayList<>();

        BigDecimal totalHistRevenue = BigDecimal.ZERO;
        int totalHistOrders = 0;

        for (int i = 0; i < historyDays; i++) {
            LocalDate currentDate = startDate.plusDays(i);
            DailySales sales = dbSalesMap.get(currentDate);

            if (sales != null) {
                revenues[i] = sales.revenue.doubleValue();
                orders[i] = sales.orders;
                totalHistRevenue = totalHistRevenue.add(sales.revenue);
                totalHistOrders += sales.orders;
            } else {
                revenues[i] = 0.0;
                orders[i] = 0.0;
            }
        }

        // Run Linear Regression for Revenue
        double[] revReg = runLinearRegression(revenues);
        double revSlope = revReg[0];
        double revIntercept = revReg[1];

        // Run Linear Regression for Orders
        double[] ordReg = runLinearRegression(orders);
        double ordSlope = ordReg[0];
        double ordIntercept = ordReg[1];

        // Add history points to response list
        for (int i = 0; i < historyDays; i++) {
            LocalDate currentDate = startDate.plusDays(i);
            BigDecimal trendRev = BigDecimal.valueOf(Math.max(0.0, revSlope * i + revIntercept));
            int trendOrd = (int) Math.max(0.0, Math.round(ordSlope * i + ordIntercept));

            points.add(SalesForecastPointDTO.builder()
                    .date(currentDate)
                    .actualRevenue(BigDecimal.valueOf(revenues[i]).setScale(2, RoundingMode.HALF_UP))
                    .actualOrders((int) orders[i])
                    .trendRevenue(trendRev.setScale(2, RoundingMode.HALF_UP))
                    .trendOrders(trendOrd)
                    .build());
        }

        // Generate Forecast points
        BigDecimal totalForeRevenue = BigDecimal.ZERO;
        int totalForeOrders = 0;

        for (int j = 0; j < forecastDays; j++) {
            LocalDate forecastDate = endDate.plusDays(j + 1);
            int index = historyDays + j;
            BigDecimal trendRev = BigDecimal.valueOf(Math.max(0.0, revSlope * index + revIntercept));
            int trendOrd = (int) Math.max(0.0, Math.round(ordSlope * index + ordIntercept));

            totalForeRevenue = totalForeRevenue.add(trendRev);
            totalForeOrders += trendOrd;

            points.add(SalesForecastPointDTO.builder()
                    .date(forecastDate)
                    .actualRevenue(null)
                    .actualOrders(null)
                    .trendRevenue(trendRev.setScale(2, RoundingMode.HALF_UP))
                    .trendOrders(trendOrd)
                    .build());
        }

        // Determine trend labels
        String revTrend = "STABLE";
        if (revSlope > 1000.0) {
            revTrend = "UPWARD";
        } else if (revSlope < -1000.0) {
            revTrend = "DOWNWARD";
        }

        String ordTrend = "STABLE";
        if (ordSlope > 0.05) {
            ordTrend = "UPWARD";
        } else if (ordSlope < -0.05) {
            ordTrend = "DOWNWARD";
        }

        return SalesForecastResponse.builder()
                .points(points)
                .period(period)
                .totalHistoricalRevenue(totalHistRevenue.setScale(2, RoundingMode.HALF_UP))
                .totalHistoricalOrders(totalHistOrders)
                .totalForecastedRevenue(totalForeRevenue.setScale(2, RoundingMode.HALF_UP))
                .totalForecastedOrders(totalForeOrders)
                .revenueTrend(revTrend)
                .ordersTrend(ordTrend)
                .build();
    }

    private double[] runLinearRegression(double[] y) {
        int n = y.length;
        if (n < 2) {
            return new double[]{0.0, 0.0};
        }
        double sumX = 0;
        double sumY = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += y[i];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (i - meanX) * (y[i] - meanY);
            den += (i - meanX) * (i - meanX);
        }
        double slope = (den != 0) ? num / den : 0.0;
        double intercept = meanY - slope * meanX;
        return new double[]{slope, intercept};
    }

    private static class DailySales {
        BigDecimal revenue;
        int orders;

        DailySales(BigDecimal revenue, int orders) {
            this.revenue = revenue != null ? revenue : BigDecimal.ZERO;
            this.orders = orders;
        }
    }
}
