package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.product.application.ProductCaptureService;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureFieldSource;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.domain.ProductImageProcessingJob;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record ProductCaptureResponse(
        String id,
        String organizationId,
        String sourcePlatform,
        String sourceUrl,
        String sourceShopId,
        String sourceProductId,
        String status,
        String captureMode,
        String completeness,
        String completenessLabel,
        List<String> missingFields,
        SkuCompletenessResponse skuCompleteness,
        String title,
        String description,
        String categoryName,
        String brandName,
        String currency,
        BigDecimal priceMin,
        BigDecimal priceMax,
        Map<String, Object> normalizedData,
        Map<String, Object> rawData,
        List<SkuResponse> skus,
        List<ImageResponse> images,
        List<FieldSourceResponse> fieldSources,
        String approvedBy,
        Instant approvedAt,
        Instant publishedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String originType
) {
    private static final Pattern OPTIONAL_STOCK_ISSUE = Pattern.compile(
            "(?:STOCK_MISSING|SKU_\\d+_STOCK_MISSING)"
    );

    public static ProductCaptureResponse from(ProductCaptureService.CaptureDetail detail,
                                              ObjectMapper objectMapper) {
        ProductCapture capture = detail.capture();
        List<SkuResponse> skus = detail.skus().stream()
                .map(sku -> SkuResponse.from(sku, objectMapper)).toList();
        List<ImageResponse> images = detail.images().stream().map(ImageResponse::from).toList();
        SkuCompletenessResponse skuCompleteness = SkuCompletenessResponse.from(
                detail.normalizedData(), skus
        );
        Map<String, Object> normalizedData = new LinkedHashMap<>(detail.normalizedData());
        normalizedData.put("skuCompleteness", skuCompleteness);
        List<String> missing = missingFields(capture, skus, images, skuCompleteness, normalizedData);
        String completeness = missing.isEmpty() ? "COMPLETE" : "NEEDS_ENRICHMENT";
        return new ProductCaptureResponse(
                capture.getId(), capture.getOrganizationId(), capture.getSourcePlatform(),
                capture.getSourceUrl(), capture.getSourceShopId(), capture.getSourceProductId(),
                capture.getStatus(), "DIRECT_DOM", completeness,
                missing.isEmpty() ? "已完整" : "待补全", missing, skuCompleteness,
                capture.getTitle(), capture.getDescription(), capture.getCategoryName(),
                capture.getBrandName(), capture.getCurrency(), capture.getPriceMin(),
                capture.getPriceMax(), normalizedData, detail.rawData(), skus, images,
                detail.fieldSources().stream()
                        .map(source -> FieldSourceResponse.from(source, objectMapper)).toList(),
                capture.getApprovedBy(), capture.getApprovedAt(), capture.getPublishedAt(),
                capture.getCreatedBy(), capture.getCreatedAt(), capture.getUpdatedAt(),
                capture.getVersion(), detail.originType()
        );
    }

    private static List<String> missingFields(ProductCapture capture, List<SkuResponse> skus,
                                              List<ImageResponse> images,
                                              SkuCompletenessResponse skuCompleteness,
                                              Map<String, Object> normalizedData) {
        List<String> missing = new ArrayList<>();
        if (capture.getTitle() == null || capture.getTitle().isBlank()) missing.add("TITLE");
        if (capture.getDescription() == null || capture.getDescription().isBlank()) missing.add("DESCRIPTION");
        if (capture.getPriceMin() == null) missing.add("PRICE");
        if (skus.isEmpty()) {
            missing.add("SKUS");
        } else {
            if (skus.stream().anyMatch(sku -> sku.price() == null)) missing.add("SKU_PRICE");
            if (skus.stream().anyMatch(sku -> sku.originalPrice() == null
                    || sku.price() != null && sku.originalPrice().compareTo(sku.price()) < 0)) {
                missing.add("SKU_ORIGINAL_PRICE");
            }
            if (skus.stream().anyMatch(sku -> sku.imageUrl() == null || sku.imageUrl().isBlank())) {
                missing.add("SKU_IMAGE");
            }
        }
        if (!skuCompleteness.complete()) missing.add("SKU_INCOMPLETE");
        if (images.isEmpty()) missing.add("IMAGES");
        Set<String> imageUrls = images.stream().map(ImageResponse::originalUrl)
                .filter(url -> url != null && !url.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (skus.stream().map(SkuResponse::imageUrl)
                .filter(url -> url != null && !url.isBlank())
                .anyMatch(url -> !imageUrls.contains(url))) {
            missing.add("SKU_IMAGE_ASSET");
        }
        if (imageCompletenessIncomplete(normalizedData)) missing.add("IMAGE_INCOMPLETE");
        return List.copyOf(missing);
    }

    private static boolean imageCompletenessIncomplete(Map<String, Object> normalizedData) {
        Object raw = normalizedData.get("imageCompleteness");
        if (!(raw instanceof Map<?, ?> metadata) || metadata.isEmpty()) return true;
        Integer expected = imageInteger(metadata.get("expectedCount"));
        Integer captured = imageInteger(metadata.get("capturedCount"));
        Integer requiredSkuImages = imageInteger(metadata.get("requiredSkuImageCount"));
        Integer coveredSkuImages = imageInteger(metadata.get("coveredSkuImageCount"));
        return !Boolean.TRUE.equals(imageBoolean(metadata.get("complete")))
                || expected == null || captured == null || !expected.equals(captured)
                || requiredSkuImages == null || coveredSkuImages == null
                || !requiredSkuImages.equals(coveredSkuImages)
                || !stringList(metadata.get("issues")).isEmpty();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    private static Integer imageInteger(Object value) {
        if (value == null) return null;
        try {
            long number = new BigDecimal(String.valueOf(value).trim()).longValueExact();
            return number < 0 || number > Integer.MAX_VALUE ? null : (int) number;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    private static Boolean imageBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    public record SkuCompletenessResponse(Integer expectedCount, int capturedCount,
                                          boolean complete, String source, boolean hasVariants,
                                          int variantDimensionCount, List<String> issues) {
        static SkuCompletenessResponse from(Map<String, Object> normalized, List<SkuResponse> skus) {
            Object raw = normalized.get("skuCompleteness");
            Map<?, ?> metadata = raw instanceof Map<?, ?> map ? map : Map.of();
            Integer expected = integer(metadata.get("expectedCount"));
            Integer captured = integer(metadata.get("capturedCount"));
            int capturedCount = captured == null ? skus.size() : captured;
            String source = metadata.get("source") == null || String.valueOf(metadata.get("source")).isBlank()
                    ? "DOM" : String.valueOf(metadata.get("source"));
            Integer declaredDimensions = integer(metadata.get("variantDimensionCount"));
            int hintCount = normalized.get("variantHints") instanceof List<?> hints ? hints.size() : 0;
            int variantDimensionCount = Math.max(declaredDimensions == null ? 0 : declaredDimensions,
                    hintCount);
            boolean hasVariants = Boolean.TRUE.equals(booleanValue(metadata.get("hasVariants")))
                    || Boolean.TRUE.equals(booleanValue(normalized.get("hasVariants")))
                    || variantDimensionCount > 0 || skus.size() > 1;
            List<String> declaredIssues = stringList(metadata.get("issues"));
            List<String> issues = declaredIssues.stream()
                    .filter(issue -> !isOptionalStockIssue(issue)).toList();
            boolean claimedComplete = Boolean.TRUE.equals(booleanValue(metadata.get("complete")));
            boolean declarationAllowsCompleteness = claimedComplete
                    || !declaredIssues.isEmpty()
                    && declaredIssues.stream().allMatch(SkuCompletenessResponse::isOptionalStockIssue);
            boolean complete = declarationAllowsCompleteness && expected != null
                    && expected == skus.size() && capturedCount == skus.size()
                    && issues.isEmpty()
                    && completeSkuItems(skus, hasVariants, variantDimensionCount);
            return new SkuCompletenessResponse(expected, capturedCount, complete, source,
                    hasVariants, variantDimensionCount, issues);
        }

        private static boolean completeSkuItems(List<SkuResponse> skus, boolean hasVariants,
                                                int variantDimensionCount) {
            if (skus.isEmpty()) return false;
            Set<String> externalIds = new HashSet<>();
            Set<Map<String, Object>> attributeCombinations = new HashSet<>();
            for (SkuResponse sku : skus) {
                if (sku.externalSkuId() == null || sku.externalSkuId().isBlank()
                        || !externalIds.add(sku.externalSkuId().trim())
                        || sku.price() == null || sku.originalPrice() == null
                        || sku.originalPrice().compareTo(sku.price()) < 0
                        || sku.imageUrl() == null || sku.imageUrl().isBlank()) {
                    return false;
                }
                if (hasVariants && (!completeAttributes(sku.attributes())
                        || variantDimensionCount > 0 && sku.attributes().size() != variantDimensionCount)) {
                    return false;
                }
                if (hasVariants && !attributeCombinations.add(sku.attributes())) return false;
            }
            return true;
        }

        private static boolean isOptionalStockIssue(String issue) {
            return OPTIONAL_STOCK_ISSUE.matcher(issue).matches();
        }

        private static List<String> stringList(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().filter(item -> item != null)
                    .map(String::valueOf).toList();
        }

        private static boolean completeAttributes(Object value) {
            if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return false;
            return map.values().stream().allMatch(SkuCompletenessResponse::completeAttributeValue);
        }

        private static boolean completeAttributeValue(Object value) {
            if (value == null) return false;
            if (value instanceof String text) return !text.isBlank();
            if (value instanceof Map<?, ?> map) {
                return !map.isEmpty() && map.values().stream()
                        .allMatch(SkuCompletenessResponse::completeAttributeValue);
            }
            if (value instanceof List<?> list) {
                return !list.isEmpty() && list.stream()
                        .allMatch(SkuCompletenessResponse::completeAttributeValue);
            }
            return true;
        }

        private static Integer integer(Object value) {
            if (value == null || String.valueOf(value).isBlank()) return null;
            try {
                int result = value instanceof Number number
                        ? number.intValue() : Integer.parseInt(String.valueOf(value));
                return result < 0 ? null : result;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Boolean booleanValue(Object value) {
            if (value instanceof Boolean bool) return bool;
            return value == null ? null : Boolean.valueOf(String.valueOf(value));
        }
    }

    public record SkuResponse(
            String id,
            String skuKey,
            String skuId,
            String externalSkuId,
            String sellerSku,
            String sku,
            String variationName,
            String name,
            BigDecimal price,
            BigDecimal originalPrice,
            Integer stock,
            String imageUrl,
            String imageSource,
            boolean imageInherited,
            String originalPriceSource,
            String status,
            Map<String, Object> attributes,
            Map<String, Object> source,
            int sortOrder
    ) {
        static SkuResponse from(ProductCaptureSku sku, ObjectMapper objectMapper) {
            Map<String, Object> attributes = readMap(objectMapper, sku.getAttributesJson());
            Map<String, Object> source = readMap(objectMapper, sku.getSourceJson());
            return new SkuResponse(
                    sku.getId(), sku.getSkuKey(), sku.getExternalSkuId(), sku.getExternalSkuId(),
                    sku.getSellerSku(), sku.getSellerSku(), sku.getVariationName(),
                    sku.getVariationName(), sku.getPrice(), sku.getOriginalPrice(),
                    sku.getStock(), sku.getImageUrl(), sku.getImageSource(),
                    "PRODUCT_MAIN".equals(sku.getImageSource()), string(source.get("originalPriceSource")),
                    string(source.get("status")), attributes, source, sku.getSortOrder()
            );
        }

        private static String string(Object value) {
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        }
    }

    public record ImageResponse(
            String id,
            String originalUrl,
            String processedUrl,
            String status,
            String role,
            String alt,
            int sortOrder,
            String mimeType,
            String errorMessage,
            boolean dryRun,
            String processingProvider
    ) {
        static ImageResponse from(ProductCaptureImage image) {
            boolean dryRun = "PROCESSED".equals(image.getStatus())
                    && image.getOriginalUrl() != null
                    && image.getOriginalUrl().equals(image.getProcessedUrl());
            return new ImageResponse(
                    image.getId(), image.getOriginalUrl(), image.getProcessedUrl(), image.getStatus(),
                    image.getRoleName(), image.getAltText(), image.getSortOrder(), image.getMimeType(),
                    image.getErrorMessage(), dryRun, dryRun ? "DRY_RUN" : null
            );
        }
    }

    public record FieldSourceResponse(
            String id,
            String field,
            String fieldName,
            String source,
            String sourceType,
            String path,
            Object value,
            BigDecimal confidence,
            Instant createdAt
    ) {
        static FieldSourceResponse from(ProductCaptureFieldSource source, ObjectMapper objectMapper) {
            Object value = readValue(objectMapper, source.getSourceValue());
            return new FieldSourceResponse(
                    source.getId(), source.getFieldName(), source.getFieldName(),
                    source.getSourceType(), source.getSourceType(), source.getSourcePath(),
                    value, source.getConfidence(), source.getCreatedAt()
            );
        }
    }

    public record ImageJobResponse(
            String id,
            String captureId,
            String provider,
            boolean dryRun,
            String status,
            int totalImages,
            int processedImages,
            Object request,
            Object response,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        static ImageJobResponse from(ProductImageProcessingJob job, boolean dryRun,
                                     ObjectMapper objectMapper) {
            return new ImageJobResponse(
                    job.getId(), job.getCaptureId(), job.getProvider(), dryRun, job.getStatus(),
                    job.getTotalImages(), job.getProcessedImages(),
                    readValue(objectMapper, job.getRequestJson()),
                    readValue(objectMapper, job.getResponseJson()), job.getErrorMessage(),
                    job.getCreatedAt(), job.getUpdatedAt(), job.getCompletedAt()
            );
        }
    }

    public record PublishAuditResponse(
            String id,
            String captureId,
            String destinationPlatform,
            String shopId,
            String provider,
            boolean dryRun,
            String status,
            String externalId,
            Object request,
            Object response,
            String errorMessage,
            Instant createdAt,
            Instant completedAt
    ) {
        static PublishAuditResponse from(ProductPublishAudit audit, ObjectMapper objectMapper) {
            return new PublishAuditResponse(
                    audit.getId(), audit.getCaptureId(), audit.getDestinationPlatform(), audit.getShopId(),
                    audit.getProvider(), audit.isDryRun(), audit.getStatus(), audit.getExternalId(),
                    readValue(objectMapper, audit.getRequestJson()),
                    readValue(objectMapper, audit.getResponseJson()), audit.getErrorMessage(),
                    audit.getCreatedAt(), audit.getCompletedAt()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) {
        Object value = readValue(objectMapper, json);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object readValue(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
