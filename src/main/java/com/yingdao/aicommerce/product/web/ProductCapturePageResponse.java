package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.product.application.ProductCaptureService;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProductCapturePageResponse(
        List<Summary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<String, Long> statusCounts
) {
    public static ProductCapturePageResponse from(ProductCaptureService.CapturePage page) {
        return new ProductCapturePageResponse(
                page.content().stream().map(Summary::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages(),
                page.statusCounts()
        );
    }

    public record Summary(
            String id,
            String sourcePlatform,
            String sourceShopId,
            String sourceProductId,
            String status,
            String title,
            String currency,
            BigDecimal priceMin,
            BigDecimal priceMax,
            String thumbnailUrl,
            String thumbnailStatus,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        static Summary from(ProductCaptureService.CaptureSummary summary) {
            ProductCapture capture = summary.capture();
            ProductCaptureImage thumbnail = summary.thumbnail();
            String thumbnailUrl = thumbnail == null || thumbnail.getProcessedUrl() == null
                    || thumbnail.getProcessedUrl().isBlank()
                    ? thumbnail == null ? null : thumbnail.getOriginalUrl()
                    : thumbnail.getProcessedUrl();
            return new Summary(
                    capture.getId(), capture.getSourcePlatform(), capture.getSourceShopId(),
                    capture.getSourceProductId(), capture.getStatus(), capture.getTitle(),
                    capture.getCurrency(), capture.getPriceMin(), capture.getPriceMax(),
                    thumbnailUrl, thumbnail == null ? null : thumbnail.getStatus(),
                    capture.getCreatedAt(), capture.getUpdatedAt(), capture.getVersion()
            );
        }
    }
}
