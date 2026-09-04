package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds the exact, bounded image set that watermark processing and publishing use. */
final class ProductCaptureImagePlan {
    static final int MAX_IMAGES = 30;
    private static final Map<String, Integer> PRIORITY = Map.of(
            "MAIN", 0, "VARIANT", 1, "GALLERY", 2, "DETAIL", 3
    );

    private ProductCaptureImagePlan() {}

    static Plan prepare(Object rawImages, List<Map<String, Object>> skus,
                        SafeProductImageUrl safeImageUrl) {
        if (rawImages != null && !(rawImages instanceof List<?>)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "图片必须是数组");
        }
        List<?> supplied = rawImages instanceof List<?> list ? list : List.of();
        Map<String, Candidate> byUrl = new LinkedHashMap<>();
        int sequence = 0;
        for (Object raw : supplied) {
            Map<String, Object> source = imageMap(raw);
            String url = safeImageUrl.validate(text(source, "url", "originalUrl", "src", "imageUrl"));
            String role = role(text(source, "role", "type"), sequence == 0 ? "MAIN" : "GALLERY");
            add(byUrl, source, url, role, sequence++);
        }

        Set<String> requiredSkuUrls = new LinkedHashSet<>();
        for (Map<String, Object> sku : skus) {
            String rawUrl = text(sku, "imageUrl", "skuImage", "image");
            if (rawUrl == null || rawUrl.isBlank()) continue;
            String url = safeImageUrl.validate(rawUrl);
            requiredSkuUrls.add(url);
            String source = text(sku, "imageSource");
            boolean inherited = Boolean.TRUE.equals(sku.get("imageInherited"))
                    || source != null && (source.equalsIgnoreCase("PRODUCT_MAIN")
                    || source.equalsIgnoreCase("PRODUCT_MAIN_PAGE"));
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("url", url);
            image.put("alt", text(sku, "variationName", "name"));
            add(byUrl, image, url, inherited ? "MAIN" : "VARIANT", sequence++);
        }

        List<Candidate> candidates = new ArrayList<>(byUrl.values());
        Candidate primary = candidates.stream()
                .filter(candidate -> candidate.roles.contains("MAIN"))
                .min(Comparator.comparingInt(candidate -> candidate.sequence))
                .orElse(candidates.stream().min(Comparator.comparingInt(candidate -> candidate.sequence)).orElse(null));
        for (Candidate candidate : candidates) {
            candidate.finalRole = candidate == primary ? "MAIN"
                    : candidate.roles.contains("VARIANT") ? "VARIANT"
                    : candidate.roles.contains("GALLERY") || candidate.roles.contains("MAIN") ? "GALLERY"
                    : "DETAIL";
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> PRIORITY.get(candidate.finalRole))
                .thenComparingInt(candidate -> candidate.sequence));

        List<Candidate> selectedCandidates = candidates.stream().limit(MAX_IMAGES).toList();
        Set<String> selectedUrls = selectedCandidates.stream()
                .map(candidate -> candidate.url).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> selected = new ArrayList<>();
        for (int index = 0; index < selectedCandidates.size(); index++) {
            Candidate candidate = selectedCandidates.get(index);
            Map<String, Object> value = new LinkedHashMap<>(candidate.source);
            value.put("url", candidate.url);
            value.put("originalUrl", candidate.url);
            value.put("role", candidate.finalRole);
            value.put("position", index);
            selected.add(value);
        }

        Map<String, Integer> roleCounts = counts(candidates);
        Map<String, Integer> selectedRoleCounts = counts(selectedCandidates);
        List<String> issues = new ArrayList<>();
        if (candidates.isEmpty()) issues.add("IMAGES_MISSING");
        if (candidates.size() > selected.size()) {
            issues.add("IMAGE_LIMIT_EXCEEDED");
            if (selectedRoleCounts.get("VARIANT") < roleCounts.get("VARIANT")) {
                issues.add("VARIANT_IMAGE_COVERAGE_TRUNCATED");
            }
            if (selectedRoleCounts.get("GALLERY") < roleCounts.get("GALLERY")) {
                issues.add("GALLERY_IMAGE_COVERAGE_TRUNCATED");
            }
            if (selectedRoleCounts.get("DETAIL") < roleCounts.get("DETAIL")) {
                issues.add("DETAIL_IMAGE_COVERAGE_TRUNCATED");
            }
        }
        long coveredSkuImages = requiredSkuUrls.stream().filter(selectedUrls::contains).count();
        if (coveredSkuImages != requiredSkuUrls.size()
                && !issues.contains("VARIANT_IMAGE_COVERAGE_TRUNCATED")) {
            issues.add("VARIANT_IMAGE_COVERAGE_TRUNCATED");
        }
        Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("expectedCount", candidates.size());
        completeness.put("capturedCount", selected.size());
        completeness.put("requiredSkuImageCount", requiredSkuUrls.size());
        completeness.put("coveredSkuImageCount", coveredSkuImages);
        completeness.put("complete", issues.isEmpty());
        completeness.put("roleCounts", roleCounts);
        completeness.put("selectedRoleCounts", selectedRoleCounts);
        completeness.put("issues", List.copyOf(issues));
        return new Plan(List.copyOf(selected), Map.copyOf(completeness));
    }

    private static void add(Map<String, Candidate> byUrl, Map<String, Object> source,
                            String url, String role, int sequence) {
        Candidate existing = byUrl.get(url);
        if (existing == null) {
            byUrl.put(url, new Candidate(new LinkedHashMap<>(source), url, sequence, role));
        } else {
            existing.roles.add(role);
            if ((text(existing.source, "alt", "altText") == null
                    || text(existing.source, "alt", "altText").isBlank())
                    && text(source, "alt", "altText") != null) {
                existing.source.put("alt", text(source, "alt", "altText"));
            }
        }
    }

    private static Map<String, Object> imageMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (raw instanceof String value && !value.isBlank()) {
            return new LinkedHashMap<>(Map.of("url", value));
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "图片列表格式不正确");
    }

    private static String role(String value, String fallback) {
        String normalized = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MAIN" -> "MAIN";
            case "VARIANT", "SKU", "MODEL", "VARIANT_OPTION" -> "VARIANT";
            case "DETAIL", "DESCRIPTION" -> "DETAIL";
            default -> "GALLERY";
        };
    }

    private static Map<String, Integer> counts(List<Candidate> candidates) {
        Map<String, Integer> counts = zeroCounts();
        candidates.forEach(candidate -> counts.compute(candidate.finalRole, (ignored, count) -> count + 1));
        return Map.copyOf(counts);
    }

    private static Map<String, Integer> zeroCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("MAIN", 0);
        counts.put("GALLERY", 0);
        counts.put("VARIANT", 0);
        counts.put("DETAIL", 0);
        return counts;
    }

    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) return String.valueOf(map.get(key)).trim();
        }
        return null;
    }

    record Plan(List<Map<String, Object>> images, Map<String, Object> completeness) {}

    private static final class Candidate {
        private final Map<String, Object> source;
        private final String url;
        private final int sequence;
        private final Set<String> roles = new LinkedHashSet<>();
        private String finalRole;

        private Candidate(Map<String, Object> source, String url, int sequence, String role) {
            this.source = source;
            this.url = url;
            this.sequence = sequence;
            this.roles.add(role);
        }
    }
}
