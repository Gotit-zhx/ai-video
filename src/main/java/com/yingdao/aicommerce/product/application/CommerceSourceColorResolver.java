package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Resolves captured SKU evidence only; does not infer colors from names, pixels, or AI. */
public final class CommerceSourceColorResolver {
    public static final String DEFAULT_KEY = "default";
    public static final String DEFAULT_LABEL = "原图色";
    private static final Set<String> COLOR_DIMENSIONS = Set.of(
            "color", "colour", "colors", "colours", "warna", "颜色", "顏色", "色");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Color(String key, String label, List<String> skuKeys,
                        String imageId, boolean hasColorReference) {
        public Color { skuKeys = List.copyOf(skuKeys); }
    }

    private CommerceSourceColorResolver() {}

    /** Same dimension aliases and NFKC/case normalization as TikTok sales attributes. */
    public static boolean isColorDimension(String name) {
        return COLOR_DIMENSIONS.contains(normalize(name));
    }

    public static List<Color> resolve(ProductCaptureService.CaptureDetail detail) {
        Objects.requireNonNull(detail, "缺少来源商品");
        Objects.requireNonNull(detail.capture(), "缺少来源商品");
        String capture = detail.capture().getId(), organization = detail.capture().getOrganizationId();
        var images = new LinkedHashMap<String, String>();
        if (detail.images() != null) detail.images().stream().filter(Objects::nonNull)
                .filter(image -> Objects.equals(capture, image.getCaptureId())
                        && Objects.equals(organization, image.getOrganizationId()))
                .sorted(Comparator.comparingInt(ProductCaptureImage::getSortOrder))
                .filter(image -> image.getId() != null && image.getOriginalUrl() != null
                        && !image.getOriginalUrl().isBlank())
                .forEach(image -> images.putIfAbsent(ProductCaptureImagePlan.assetKey(image.getOriginalUrl()), image.getId()));

        var groups = new LinkedHashMap<String, Group>();
        var unclassified = new LinkedHashSet<String>();
        var allSkuKeys = new LinkedHashSet<String>();
        List<ProductCaptureSku> skus = detail.skus() == null ? List.of() : detail.skus();
        var styleColors = repeatedStyleColors(skus, capture, organization);
        for (var sku : skus.stream().filter(Objects::nonNull)
                .filter(sku -> Objects.equals(capture, sku.getCaptureId())
                        && Objects.equals(organization, sku.getOrganizationId()))
                .sorted(Comparator.comparingInt(ProductCaptureSku::getSortOrder)).toList()) {
            String label = colorLabel(sku), skuKey = sku.getSkuKey();
            if (skuKey != null && !skuKey.isBlank()) allSkuKeys.add(skuKey);
            if (label == null) {
                unclassified.add(Objects.toString(skuKey, "未命名 SKU"));
                continue;
            }
            label = variationColorLabel(label, styleColors);
            String groupLabel = label;
            String key = CommerceImageArchive.sha(("sku-color:" + normalize(label)).getBytes(StandardCharsets.UTF_8));
            var group = groups.computeIfAbsent(key, ignored -> new Group(key, groupLabel));
            if (skuKey != null && !skuKey.isBlank()) group.skuKeys.add(skuKey);
            int priority = referencePriority(sku);
            String imageId = priority == 0 ? null : images.get(ProductCaptureImagePlan.assetKey(sku.getImageUrl()));
            if (imageId != null && priority > group.referencePriority) {
                group.imageId = imageId;
                group.referencePriority = priority;
            }
        }
        if (groups.isEmpty()) return List.of(new Color(DEFAULT_KEY, DEFAULT_LABEL,
                List.copyOf(allSkuKeys), null, false));
        if (!unclassified.isEmpty()) throw new IllegalArgumentException(
                "部分 SKU 缺少明确颜色规格，请补齐后再按颜色生成：" + String.join("、", unclassified));
        return groups.values().stream().map(group -> new Color(group.key, group.label,
                List.copyOf(group.skuKeys), group.imageId, group.imageId != null)).toList();
    }

    // A repeated /1,/2,/3 axis shared by multiple colors denotes source styles, which
    // are replaced by the new artwork. Keep every SKU key while sharing its color image.
    private static Set<String> repeatedStyleColors(List<ProductCaptureSku> skus, String capture, String organization) {
        var suffixes = new HashMap<String,Set<String>>();
        for (var sku:skus) {
            if(sku==null||!Objects.equals(capture,sku.getCaptureId())||!Objects.equals(organization,sku.getOrganizationId()))continue;
            String label=colorLabel(sku);if(label==null)continue;
            var matcher=java.util.regex.Pattern.compile("^(.+?)\\s*/\\s*([1-9][0-9]*)$").matcher(normalize(label));
            if(matcher.matches())suffixes.computeIfAbsent(matcher.group(1).strip(),k->new HashSet<>()).add(matcher.group(2));
        }
        var result=new HashSet<String>();
        for(var a:suffixes.entrySet())if(a.getValue().size()>=2)
            for(var b:suffixes.entrySet())if(!a.getKey().equals(b.getKey())&&a.getValue().equals(b.getValue()))result.add(a.getKey());
        return result;
    }

    private static String variationColorLabel(String label, Set<String> styleColors) {
        int slash=label.lastIndexOf('/');
        if(slash>0&&label.substring(slash+1).strip().matches("[1-9][0-9]*")
                &&styleColors.contains(normalize(label.substring(0,slash))))return label.substring(0,slash).strip();
        if(normalize(label).equals("pure white")&&styleColors.contains("white"))return "White";
        return label;
    }

    private static String colorLabel(ProductCaptureSku sku) {
        String label = null;
        for (var attribute : readAttributes(sku.getAttributesJson()).entrySet()) {
            if (!isColorDimension(attribute.getKey())) continue;
            if (!(attribute.getValue() instanceof String value) || normalize(value).isEmpty())
                throw new IllegalArgumentException("SKU “" + sku.getSkuKey() + "” 的颜色值缺失，请补齐后再生成");
            String original = value.strip();
            if (original.codePointCount(0, original.length()) > 160)
                throw new IllegalArgumentException("SKU “" + sku.getSkuKey() + "” 的颜色名称过长，最多支持 160 个字符，请核对后再生成");
            if (label != null && !normalize(label).equals(normalize(value)))
                throw new IllegalArgumentException("SKU “" + sku.getSkuKey() + "” 的颜色规格有冲突，请核对后再生成");
            if (label == null) label = original;
        }
        return label;
    }

    private static int referencePriority(ProductCaptureSku sku) {
        if (sku.getImageUrl() == null || sku.getImageUrl().isBlank()) return 0;
        // Main-photo inheritance is not evidence of the selected color, even if an image ID exists.
        if (Boolean.TRUE.equals(readAttributes(sku.getSourceJson()).get("imageInherited"))) return 0;
        return switch (normalize(sku.getImageSource())) {
            case "sku", "model" -> 2;
            case "variant_option" -> 1;
            default -> 0;
        };
    }

    private static Map<String, Object> readAttributes(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> result = JSON.readValue(value, new TypeReference<>() {});
            return result == null ? Map.of() : result;
        } catch (RuntimeException ignored) { return Map.of(); }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static final class Group {
        final String key, label;
        final LinkedHashSet<String> skuKeys = new LinkedHashSet<>();
        String imageId;
        int referencePriority;
        Group(String key, String label) { this.key = key; this.label = label; }
    }
}
