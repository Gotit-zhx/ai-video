package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ProductPrintProductionService {
    public static final String DIRECT_LISTING = "printDirectListing";
    private static final String KEY_PREFIX = "print-production-";
    private static final String TEMPLATE_KEY = "printProductionTemplateId";
    private static final String MEASUREMENT_KEY = "printSizeMeasurements";
    private static final String SKU_SUFFIX_KEY = "printSkuSuffix";
    private static final String SKU_ORDER = "STYLE_COLOR_SIZE";
    private static final String SHARED_IMAGE_KEY = "printSharedColorImage";

    public record Settings(String resultId, String templateId, Long templateVersion) {}
    public record Draft(String id, String title, String status, String thumbnailUrl, int skuCount) {}
    private static final int DEFAULT_STOCK = 999;

    private final ProductCaptureService captures;
    private final ProductPrintCaptureService print;
    private final ProductCaptureTemplateService templates;
    private final CommerceImageArchive archive;
    private final ProductCaptureMapper captureMapper;
    private final ProductCaptureImageMapper imageMapper;
    private final ProductCaptureSkuMapper skuMapper;
    private final ObjectMapper json;
    private final ProductSkuNamingService naming;

    public ProductPrintProductionService(ProductCaptureService captures, ProductPrintCaptureService print,
                                         ProductCaptureTemplateService templates, CommerceImageArchive archive,
                                         ProductCaptureMapper captureMapper, ProductCaptureImageMapper imageMapper,
                                         ProductCaptureSkuMapper skuMapper, ObjectMapper json, ProductSkuNamingService naming) {
        this.captures = captures;
        this.print = print;
        this.templates = templates;
        this.archive = archive;
        this.captureMapper = captureMapper;
        this.imageMapper = imageMapper;
        this.skuMapper = skuMapper;
        this.json = json;
        this.naming = naming;
    }

    @Transactional
    public Draft prepare(String sourceId, Settings settings, MultipartFile image, PlatformPrincipal user) {
        if (user == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!user.permissionCodes().containsAll(List.of("product:edit", "product:publish"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "缺少商品制作和上传权限");
        }
        var sourceDetail = captures.printSourceForProduction(sourceId, user);
        var source = sourceDetail.capture();
        var sourceSkus = sourceDetail.skus();
        String description = ProductListingTemplateService.sourceDescription(source, json);
        boolean hasArtwork = print.results(sourceId, user).stream()
                .anyMatch(result -> result.id().equals(settings.resultId()) && result.imageUrl() != null);
        if (!hasArtwork) throw bad("请选择可用的印花提取结果");
        var template = templates.requirePrintBase(settings.templateId(), user);
        if (settings.templateVersion() != null && settings.templateVersion() != template.version()) {
            throw new BusinessException(HttpStatus.CONFLICT, "采集模板已有更新，请刷新后重新制作");
        }
        String templateId = template.id();
        var variant = template.variants().getFirst();
        String style = ProductPublicationSkuNaming.style(variant.styleCode());
        var colors = variant.colors();
        var sizes = ProductCaptureTemplateService.normalizeSizes(variant.sizes(), true);
        int skuCount = sourceSkus.size();
        String title = source.getTitle();
        BigDecimal priceMin = sourceSkus.stream().map(ProductCaptureSku::getPrice).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal priceMax = sourceSkus.stream().map(ProductCaptureSku::getPrice).max(Comparator.naturalOrder()).orElseThrow();
        byte[] bytes = imageBytes(image);
        String org = user.organizationId();
        String hash = CommerceImageArchive.sha(json.writeValueAsBytes(List.of(
                "source-specs", user.userId(), sourceId, source.getId(), source.getVersion(), settings.resultId(), templateId,
                template.version(), CommerceImageArchive.sha(bytes), SKU_ORDER)));
        String key = KEY_PREFIX + hash;
        ProductCapture existing = captureMapper.selectOne(query(org, key));
        if (existing != null) {
            return new Draft(existing.getId(), existing.getTitle(), existing.getStatus(),
                    CommercePublicationDrafts.PREFIX + archive.store(org, bytes).storageKey(), skuCount);
        }

        var archived = archive.store(org, bytes);
        String url = CommercePublicationDrafts.PREFIX + archived.storageKey();
        var normalized = new LinkedHashMap<>(sourceDetail.normalizedData());
        normalized.remove("images");
        normalized.remove("skus");
        normalized.remove("listingTemplate");
        normalized.put("title", title);
        normalized.put("description", description);
        normalized.put("originalDescription", description);
        normalized.put("priceMin", priceMin);
        normalized.put("priceMax", priceMax);
        normalized.put("imageCompleteness", Map.of("complete", true, "expectedCount", 1, "capturedCount", 1,
                "requiredSkuImageCount", 1, "coveredSkuImageCount", 1, "issues", List.of()));
        var measurements = sizes.stream().map(ProductPrintProductionService::measurementLine)
                .filter(line -> !line.isEmpty()).toList();
        var pattern = naming.printArtworkCode(org, settings.resultId());
        String skuStyle = style.isEmpty() ? pattern.style() : style;
        var attributes = sourceSkus.stream()
                .map(sku -> json.readValue(sku.getAttributesJson(), new TypeReference<Map<String, Object>>() {}))
                .toList();
        long distinctColors = attributes.stream().map(row -> ProductSkuNaming.dimension(row, true)).distinct().count();
        String rawData = json.writeValueAsString(Map.of(TEMPLATE_KEY, templateId, MEASUREMENT_KEY, measurements,
                SKU_SUFFIX_KEY, pattern.suffix(), "printSkuOrder", SKU_ORDER, SHARED_IMAGE_KEY, distinctColors > 1,
                "printCaptureId", sourceId, "printResultId", settings.resultId()));
        var draft = new ProductCapture(org, user.userId(), key, hash, source.getSourcePlatform(),
                source.getSourceUrl(), source.getSourceShopId(), source.getSourceProductId(), title, description,
                source.getCategoryName(), source.getBrandName(), source.getCurrency(), priceMin, priceMax,
                json.writeValueAsString(normalized), rawData);
        draft.assignModule(CommerceModule.CAPTURE);
        draft.imagesReady();
        try {
            captureMapper.insert(draft);
        } catch (DuplicateKeyException duplicate) {
            var winner = captureMapper.selectOne(query(org, key).last("FOR UPDATE"));
            if (winner == null) throw duplicate;
            return new Draft(winner.getId(), winner.getTitle(), winner.getStatus(), url, skuCount);
        }
        String originalUrl = url + "?original";
        var main = new ProductCaptureImage(org, draft.getId(), originalUrl, "MAIN", title, 0, archived.mimeType(),
                json.writeValueAsString(Map.of("commerceStorageKey", archived.storageKey())));
        main.processed(url);
        imageMapper.insert(main);
        var used = new HashSet<String>();
        for (int i = 0; i < skuCount; i++) {
            var sourceSku = sourceSkus.get(i);
            String colorName = ProductSkuNaming.dimension(attributes.get(i), true);
            String sizeName = ProductSkuNaming.dimension(attributes.get(i), false);
            String colorCode = colors.stream().filter(color -> colorName.equalsIgnoreCase(color.name()))
                    .map(ProductCaptureTemplateService.Color::colorCode)
                    .filter(code -> code != null && code.matches("[A-Z0-9]{1,16}"))
                    .findFirst().orElseGet(() -> ProductSkuNaming.color(colorName));
            String sizeCode = sizes.stream().filter(size -> sizeName.equalsIgnoreCase(size.name()))
                    .map(ProductCaptureTemplateService.Size::sizeCode).findFirst().orElseGet(() -> ProductPrintImagePlan.sizeToken(sizeName));
            String sku = String.join("-", skuStyle, colorCode, sizeCode, pattern.suffix());
            if (!used.add(sku)) {
                // Other source dimensions can distinguish combinations with the same color and size.
                String variantCode = CommerceImageArchive.sha(sourceSku.getSkuKey().getBytes(StandardCharsets.UTF_8))
                        .substring(0, 8).toUpperCase(Locale.ROOT);
                sku = String.join("-", skuStyle, colorCode, sizeCode.substring(0, Math.min(sizeCode.length(), 8)) + variantCode, pattern.suffix());
                if (!used.add(sku)) throw bad("原商品规格编码重复，请重新采集。");
            }
            skuMapper.insert(new ProductCaptureSku(org, draft.getId(), sku, sourceSku.getExternalSkuId(), sku,
                    sourceSku.getVariationName(), sourceSku.getPrice(), sourceSku.getOriginalPrice(),
                    Objects.requireNonNullElse(sourceSku.getStock(), DEFAULT_STOCK), originalUrl, "COMMERCE_GENERATED",
                    sourceSku.getAttributesJson(), sourceSku.getSourceJson(), i));
        }
        return new Draft(draft.getId(), draft.getTitle(), draft.getStatus(), url, skuCount);
    }

    private static String measurementLine(ProductCaptureTemplateService.Size size) {
        var values = new LinkedHashMap<String, BigDecimal>();
        values.put("Chest", size.chestCm());
        values.put("Length", size.lengthCm());
        values.put("Shoulder", size.shoulderCm());
        values.put("Sleeve", size.sleeveCm());
        var parts = values.entrySet().stream().filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + " " + entry.getValue().toPlainString() + " cm").toList();
        return parts.isEmpty() ? "" : size.name() + "  " + String.join(" / ", parts);
    }

    public static boolean directListing(Map<String, Object> request) {
        return Boolean.TRUE.equals(request.get(DIRECT_LISTING));
    }

    static String templateId(ProductCapture capture, ObjectMapper json) {
        if (capture.getIdempotencyKey() == null || !capture.getIdempotencyKey().startsWith(KEY_PREFIX)) return "";
        var raw = json.readTree(Objects.toString(capture.getRawData(), "{}"));
        return raw.path(TEMPLATE_KEY).asText("");
    }

    public static String skuSuffix(ProductCapture capture, ObjectMapper json) {
        return templateId(capture, json).isEmpty() ? "" : json.readTree(capture.getRawData()).path(SKU_SUFFIX_KEY).asText("");
    }

    static boolean styleFirstSku(ProductCapture capture, ObjectMapper json) {
        return SKU_ORDER.equals(json.readTree(capture.getRawData()).path("printSkuOrder").asText(""));
    }

    public static boolean sharedColorImage(ProductCapture capture, ObjectMapper json) {
        return !templateId(capture, json).isEmpty() && json.readTree(capture.getRawData()).path(SHARED_IMAGE_KEY).asBoolean(false);
    }

    public static List<String> sizeMeasurements(ProductCapture capture, ObjectMapper json) {
        if (templateId(capture, json).isEmpty()) return List.of();
        var lines = json.readTree(capture.getRawData()).path(MEASUREMENT_KEY);
        if (!lines.isArray()) return List.of();
        var result = new ArrayList<String>();
        lines.forEach(line -> { if (line.isString()) result.add(line.asText()); });
        return List.copyOf(result);
    }

    private static LambdaQueryWrapper<ProductCapture> query(String organization, String key) {
        return new LambdaQueryWrapper<ProductCapture>()
                .eq(ProductCapture::getOrganizationId, organization)
                .eq(ProductCapture::getIdempotencyKey, key);
    }

    private static byte[] imageBytes(MultipartFile image) {
        if (image == null || image.getSize() > 8 * 1024 * 1024) throw bad("套版图片最大为 8 MiB");
        try {
            byte[] bytes = image.getBytes();
            if (bytes.length < 12 || bytes[0] != (byte) 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
                throw bad("套版图片须为 PNG");
            }
            int[] size = ProductCaptureTemplateService.dimensions(bytes);
            if (Math.max(size[0], size[1]) > 2048) throw bad("套版图片最长边须在 2048 像素以内");
            return bytes;
        } catch (IOException invalid) {
            throw bad("套版图片读取失败，请重新导出");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
