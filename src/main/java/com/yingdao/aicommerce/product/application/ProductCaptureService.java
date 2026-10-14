package com.yingdao.aicommerce.product.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.application.ProductSourceUrlParser.SourceIdentity;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentProvider;
import com.yingdao.aicommerce.product.application.provider.ProductEnrichmentRegistry;
import com.yingdao.aicommerce.product.application.provider.ProductPublishingProvider;
import com.yingdao.aicommerce.product.application.provider.ProductPublishingRegistry;
import com.yingdao.aicommerce.product.application.provider.VideoUploadPendingException;
import com.yingdao.aicommerce.product.application.provider.WatermarkRemovalProvider;
import com.yingdao.aicommerce.product.application.provider.WatermarkRemovalRegistry;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureFieldSource;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.domain.ProductImageProcessingJob;
import com.yingdao.aicommerce.product.domain.ProductPublishAudit;
import com.yingdao.aicommerce.product.mapper.ProductCaptureFieldSourceMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.product.mapper.ProductImageProcessingJobMapper;
import com.yingdao.aicommerce.product.mapper.ProductPublishAuditMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Service
public class ProductCaptureService {
    private static final int MAX_RAW_JSON_LENGTH = 2_000_000;
    private static final int MAX_SKUS = 500;
    private static final int MAX_IMAGES = ProductCaptureImagePlan.MAX_IMAGES;
    private static final Pattern OPTIONAL_STOCK_ISSUE = Pattern.compile(
            "(?:STOCK_MISSING|SKU_\\d+_STOCK_MISSING)"
    );
    private static final Set<String> EDITABLE_STATUSES = Set.of("CAPTURED", "READY", "FAILED");
    private static final Set<String> PROTECTED_NORMALIZED_FIELDS = Set.of(
            "skus", "variants", "models", "images", "imageUrls",
            "skuCompleteness", "imageCompleteness", "fieldSources", "rawData",
            "sourcePlatform", "sourceUrl", "sourceProductId", "sourceShopId", "captureMode",
            "originalDescription", "listingTemplate", "originalTitle", "titleAi"
    );

    private final ProductCaptureMapper captureMapper;
    private final ProductCaptureSkuMapper skuMapper;
    private final ProductCaptureImageMapper imageMapper;
    private final ProductCaptureFieldSourceMapper fieldSourceMapper;
    private final ProductImageProcessingJobMapper imageJobMapper;
    private final ProductPublishAuditMapper publishAuditMapper;
    private final ProductSourceUrlParser sourceUrlParser;
    private final SafeProductImageUrl safeImageUrl;
    private final ProductEnrichmentRegistry enrichmentRegistry;
    private final WatermarkRemovalRegistry watermarkRegistry;
    private final ProductPublishingRegistry publishingRegistry;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private ProductWatermarkQueue watermarkQueue;
    @org.springframework.beans.factory.annotation.Autowired
    private ProductListingTemplateService listingTemplates;
    @org.springframework.beans.factory.annotation.Autowired
    private ProductListingTitleService listingTitles;
    @org.springframework.beans.factory.annotation.Autowired
    private ProductListingLogisticsService listingLogistics;

    public ProductCaptureService(
            ProductCaptureMapper captureMapper,
            ProductCaptureSkuMapper skuMapper,
            ProductCaptureImageMapper imageMapper,
            ProductCaptureFieldSourceMapper fieldSourceMapper,
            ProductImageProcessingJobMapper imageJobMapper,
            ProductPublishAuditMapper publishAuditMapper,
            ProductSourceUrlParser sourceUrlParser,
            SafeProductImageUrl safeImageUrl,
            ProductEnrichmentRegistry enrichmentRegistry,
            WatermarkRemovalRegistry watermarkRegistry,
            ProductPublishingRegistry publishingRegistry,
            ObjectMapper objectMapper
    ) {
        this.captureMapper = captureMapper;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.fieldSourceMapper = fieldSourceMapper;
        this.imageJobMapper = imageJobMapper;
        this.publishAuditMapper = publishAuditMapper;
        this.sourceUrlParser = sourceUrlParser;
        this.safeImageUrl = safeImageUrl;
        this.enrichmentRegistry = enrichmentRegistry;
        this.watermarkRegistry = watermarkRegistry;
        this.publishingRegistry = publishingRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CaptureDetail create(Map<String, Object> request, String headerIdempotencyKey,
                                PlatformPrincipal principal) {
        Map<String, Object> body = mutableMap(request);
        String rawRequest = writeJson(body);
        if (rawRequest.length() > MAX_RAW_JSON_LENGTH) {
            throw badRequest("采集数据不能超过 2 MB");
        }
        SourceIdentity source = sourceUrlParser.parse(
                text(body, "sourceUrl", "url", "productUrl"),
                text(body, "sourcePlatform", "platform"),
                text(body, "sourceShopId", "shopId", "sellerId"),
                text(body, "sourceProductId", "productId", "itemId")
        );
        sanitizeCapturedUrls(body, source.canonicalUrl());
        body.put("sourceUrl", source.canonicalUrl());
        if (body.containsKey("url")) body.put("url", source.canonicalUrl());
        String fingerprint = sourceFingerprint(source);
        String idempotencyKey = idempotencyKey(headerIdempotencyKey,
                text(body, "idempotencyKey"), "capture-" + fingerprint.substring(0, 48));
        ProductCapture existing = findByIdempotency(principal.organizationId(), idempotencyKey);
        if (existing != null) {
            verifyFingerprint(existing, fingerprint);
            if (supplementMissingCaptureDetails(existing, body, principal)) return detail(existing);
            ensureRecaptureAllowed(existing, principal);
        }

        String requestedProvider = text(body, "enrichmentProvider");
        ProductEnrichmentProvider.EnrichmentResult enrichment = enrichmentRegistry.enrich(
                requestedProvider == null ? "DOM" : requestedProvider, source, body);
        Map<String, Object> normalized = new LinkedHashMap<>(enrichment.normalizedData());
        normalized.putAll(mapValue(body, "normalizedData", "normalized"));
        normalized.putAll(mapValue(body, "domData", "dom", "fields"));
        copyKnownFields(body, normalized);
        copy(body, normalized, "attributes", "attributes", "productAttributes", "product_attributes", "productProperties");
        normalized.put("sourcePlatform", source.platform());
        normalized.put("sourceUrl", source.canonicalUrl());
        normalized.put("sourceProductId", source.productId());
        if (source.shopId() != null) normalized.put("sourceShopId", source.shopId());
        normalized.put("captureMode", "DIRECT_DOM");
        Object rawCapturedSkus = firstNonNull(value(body, "skus", "variants", "models"),
                value(normalized, "skus", "variants", "models"));
        Object capturedImages = firstNonNull(value(body, "images", "imageUrls"),
                value(normalized, "images", "imageUrls"));
        List<Map<String, Object>> capturedSkus = resolveSkuImages(rawCapturedSkus, capturedImages);
        ProductCaptureImagePlan.Plan imagePlan = ProductCaptureImagePlan.prepare(
                capturedImages, capturedSkus, safeImageUrl
        );
        normalized.put("images", imagePlan.images());
        normalized.put("imageCompleteness", mergeImageCompleteness(
                imagePlan.completeness(), declaredImageCompleteness(body, normalized)
        ));
        normalizeSkuCompleteness(body, normalized, capturedSkus, false, false, Map.of());
        if (existing != null && !validatedSkuCompleteness(normalized, capturedSkus)
                && validatedSkuCompleteness(readMap(existing.getNormalizedData()), skuEntityMaps(existing))) {
            throw conflict("本次未取得完整 SKU，已保留之前完整的商品数据；请等待商品规格加载完成后重试");
        }

        String title = bounded(requiredText(normalized, "title", "name", "productName"), 500);
        String description = boundedNullable(text(normalized, "description", "productDescription"), 100_000);
        String category = boundedNullable(text(normalized, "categoryName", "category"), 255);
        String brand = boundedNullable(text(normalized, "brandName", "brand"), 255);
        String currency = currency(text(normalized, "currency", "currencyCode"));
        BigDecimal priceMin = decimal(value(normalized, "priceMin", "minPrice", "price"), "最低价格");
        BigDecimal priceMax = decimal(value(normalized, "priceMax", "maxPrice", "price"), "最高价格");
        if (priceMin != null && priceMax == null) priceMax = priceMin;
        if (priceMin == null && priceMax != null) priceMin = priceMax;
        validatePriceRange(priceMin, priceMax);
        if (listingTitles != null) {
            normalized.put("originalTitle", title);
            try {
                title = listingTitles.generate(principal.organizationId(), normalized, source.platform());
                normalized.put("titleAi", Map.of("status", "SUCCEEDED", "sdk", "@earendil-works/pi-agent-core",
                        "basis", "MAIN_IMAGE_AND_COLLECTED_FACTS"));
            } catch (BusinessException exception) {
                // A model outage must not discard the successfully captured SKU/images.
                normalized.put("titleAi", Map.of("status", "FAILED", "error", exception.getMessage(),
                        "sdk", "@earendil-works/pi-agent-core"));
            }
        }
        putStandardFields(normalized, title, description, category, brand, currency, priceMin, priceMax);
        if (listingTemplates != null) {
            listingTemplates.applyCapture(principal.organizationId(), source.platform(), normalized);
            description = boundedNullable(text(normalized, "description"), 100_000);
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("captureRequest", body);
        if (!enrichment.rawData().isEmpty()) raw.put("providerResponse", enrichment.rawData());
        String normalizedJson = writeJson(normalized);
        String rawJson = writeJson(raw);
        if (existing != null) {
            existing.recapture(title, description, category, brand, currency, priceMin, priceMax,
                    normalizedJson, rawJson);
            updateCapture(existing);
            deleteSkus(existing);
            deleteImages(existing);
            deleteFieldSources(existing);
            insertSkus(existing, capturedSkus);
            insertImages(existing, imagePlan.images());
            insertFieldSources(existing, body, normalized,
                    enrichment.rawData().isEmpty() ? "DOM" : normalizeFieldSource(requestedProvider));
            return detail(existing);
        }
        ProductCapture capture = new ProductCapture(
                principal.organizationId(), principal.userId(), idempotencyKey, fingerprint,
                source.platform(), source.canonicalUrl(), source.shopId(), source.productId(),
                title, description, category, brand, currency, priceMin, priceMax,
                normalizedJson, rawJson
        );
        try {
            captureMapper.insert(capture);
        } catch (DuplicateKeyException exception) {
            ProductCapture duplicate = findByIdempotency(principal.organizationId(), idempotencyKey);
            if (duplicate != null) return verifyIdempotent(duplicate, fingerprint, principal);
            throw exception;
        }
        insertSkus(capture, capturedSkus);
        insertImages(capture, imagePlan.images());
        insertFieldSources(capture, body, normalized,
                enrichment.rawData().isEmpty() ? "DOM" : normalizeFieldSource(requestedProvider));
        return detail(capture);
    }

    @Transactional(readOnly = true)
    public CapturePage list(String organizationId, String status, String query, int page, int size) {
        List<String> statuses = listStatuses(status);
        String search = query == null || query.isBlank() ? null : query.trim();
        if (search != null && search.length() > 200) throw badRequest("商品搜索关键词不能超过 200 个字符");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));

        QueryWrapper<ProductCapture> criteria = new QueryWrapper<ProductCapture>()
                .eq("organization_id", organizationId)
                .in(!statuses.isEmpty(), "status", statuses)
                .and(search != null, nested -> nested
                        .like("title", search)
                        .or().like("source_product_id", search)
                        .or().like("source_shop_id", search)
                        .or().like("source_platform", search)
                        .or().like("category_name", search)
                        .or().like("brand_name", search)
                        .or().apply("""
                                EXISTS (
                                    SELECT 1
                                    FROM product_capture_skus summary_sku
                                    WHERE summary_sku.organization_id = product_captures.organization_id
                                      AND summary_sku.capture_id = product_captures.id
                                      AND (
                                          summary_sku.external_sku_id LIKE CONCAT('%', {0}, '%')
                                          OR summary_sku.seller_sku LIKE CONCAT('%', {0}, '%')
                                          OR summary_sku.variation_name LIKE CONCAT('%', {0}, '%')
                                      )
                                )
                                """, search))
                .orderByDesc("updated_at", "id");
        IPage<ProductCapture> captures = captureMapper.selectPage(
                new Page<>(safePage + 1L, safeSize), criteria
        );

        List<String> captureIds = captures.getRecords().stream().map(ProductCapture::getId).toList();
        Map<String, ProductCaptureImage> thumbnails = captureIds.isEmpty()
                ? Map.of()
                : imageMapper.selectFirstByCaptureIds(organizationId, captureIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ProductCaptureImage::getCaptureId,
                                image -> image,
                                (first, ignored) -> first,
                                LinkedHashMap::new
                        ));
        Set<String> generatedCaptureIds = captureIds.isEmpty() ? Set.of()
                : new HashSet<>(captureMapper.selectCommerceGeneratedCaptureIds(organizationId, captureIds));
        List<CaptureSummary> content = captures.getRecords().stream()
                .map(capture -> new CaptureSummary(capture, thumbnails.get(capture.getId()),
                        generatedCaptureIds.contains(capture.getId()) ? "COMMERCE_GENERATED" : "CAPTURED"))
                .toList();
        return new CapturePage(
                content,
                Math.toIntExact(captures.getCurrent() - 1),
                Math.toIntExact(captures.getSize()),
                captures.getTotal(),
                Math.toIntExact(captures.getPages()),
                captureStatusCounts(organizationId)
        );
    }

    private List<String> listStatuses(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) return List.of();
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "CAPTURED", "DRAFT" -> List.of("CAPTURED");
            case "IMAGE_PROCESSING", "PROCESSING" -> List.of("IMAGE_PROCESSING");
            case "READY", "REVIEW", "REVIEW_REQUIRED" -> List.of("READY");
            case "APPROVED" -> List.of("APPROVED");
            case "PUBLISHED" -> List.of("PUBLISHED");
            case "FAILED", "REJECTED" -> List.of("FAILED");
            default -> throw badRequest("不支持的商品草稿状态");
        };
    }

    private Map<String, Long> captureStatusCounts(String organizationId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        List.of("CAPTURED", "IMAGE_PROCESSING", "READY", "APPROVED", "PUBLISHED", "FAILED")
                .forEach(status -> counts.put(status, 0L));
        List<Map<String, Object>> rows = captureMapper.selectMaps(new QueryWrapper<ProductCapture>()
                .select("status", "COUNT(*) AS total")
                .eq("organization_id", organizationId)
                .groupBy("status"));
        for (Map<String, Object> row : rows) {
            String status = mapTextIgnoreCase(row, "status");
            Object rawCount = mapValueIgnoreCase(row, "total");
            if (status != null && counts.containsKey(status) && rawCount instanceof Number number) {
                counts.put(status, number.longValue());
            }
        }
        return Map.copyOf(counts);
    }

    private String mapTextIgnoreCase(Map<String, Object> map, String key) {
        Object value = mapValueIgnoreCase(map, key);
        return value == null ? null : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }

    private Object mapValueIgnoreCase(Map<String, Object> map, String key) {
        if (map == null) return null;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public CaptureDetail get(String id, PlatformPrincipal principal) {
        return detail(requireCapture(id, principal.organizationId()));
    }

    @Transactional
    public CaptureDetail patch(String id, Map<String, Object> request, PlatformPrincipal principal) {
        ProductCapture capture = requireCapture(id, principal.organizationId());
        Map<String, Object> body = mutableMap(request);
        long requestedVersion = requiredVersion(body);
        if (requestedVersion != capture.getVersion()) {
            throw conflict("商品草稿已被其他请求更新，请刷新后重试");
        }
        if (!EDITABLE_STATUSES.contains(capture.getStatus())) {
            throw conflict("当前商品草稿状态不能编辑");
        }
        Map<String, Object> normalized = readMap(capture.getNormalizedData());
        Map<String, Object> previousSkuCompleteness = new LinkedHashMap<>(
                mapValue(normalized, "skuCompleteness")
        );
        Map<String, Object> normalizedPatch = mapValue(body, "normalizedData", "normalized");
        rejectProtectedNormalizedPatch(normalizedPatch);
        normalized.putAll(normalizedPatch);
        copyKnownFields(body, normalized);
        boolean skusChanged = bodyContains(body, "skus", "variants", "models");
        boolean imagesChanged = bodyContains(body, "images", "imageUrls");
        boolean skuCompletenessChanged = bodyContains(body, "skuCompleteness")
                || bodyContains(mapValue(body, "normalizedData", "normalized"), "skuCompleteness");
        List<Map<String, Object>> replacementSkus = null;
        boolean skuContentChanged = false;
        boolean manualSkuConfirmation = manualSkuConfirmationRequested(body);
        if (skusChanged) {
            Object skuImages = imagesChanged
                    ? value(body, "images", "imageUrls") : imageEntityMaps(images(capture));
            replacementSkus = resolveSkuImages(value(body, "skus", "variants", "models"), skuImages);
            skuContentChanged = !sameSkuContent(replacementSkus, skuEntityMaps(capture));
            normalizeSkuCompleteness(body, normalized, replacementSkus, skuContentChanged,
                    manualSkuConfirmation, previousSkuCompleteness);
        } else if (skuCompletenessChanged) {
            normalizeSkuCompleteness(body, normalized, skuEntityMaps(capture), false,
                    manualSkuConfirmation, previousSkuCompleteness);
        }
        boolean publicationImagesChanged = imagesChanged || skuContentChanged;
        List<Map<String, Object>> replacementImages = null;
        if (publicationImagesChanged) {
            Object suppliedImages = imagesChanged
                    ? value(body, "images", "imageUrls") : imageEntityMaps(images(capture));
            List<Map<String, Object>> effectiveSkus = skusChanged
                    ? replacementSkus : skuEntityMaps(capture);
            ProductCaptureImagePlan.Plan imagePlan = ProductCaptureImagePlan.prepare(
                    suppliedImages, effectiveSkus, safeImageUrl
            );
            replacementImages = imagePlan.images();
            normalized.put("images", replacementImages);
            normalized.put("imageCompleteness", imagePlan.completeness());
        }

        String title = bodyContains(body, "title", "name", "productName")
                ? bounded(requiredText(body, "title", "name", "productName"), 500) : capture.getTitle();
        String description = bodyContains(body, "description", "productDescription")
                ? boundedNullable(text(body, "description", "productDescription"), 100_000) : capture.getDescription();
        String category = bodyContains(body, "categoryName", "category")
                ? boundedNullable(text(body, "categoryName", "category"), 255) : capture.getCategoryName();
        String brand = bodyContains(body, "brandName", "brand")
                ? boundedNullable(text(body, "brandName", "brand"), 255) : capture.getBrandName();
        String currency = bodyContains(body, "currency", "currencyCode")
                ? currency(text(body, "currency", "currencyCode")) : capture.getCurrency();
        BigDecimal priceMin = bodyContains(body, "priceMin", "minPrice", "price")
                ? decimal(value(body, "priceMin", "minPrice", "price"), "最低价格") : capture.getPriceMin();
        BigDecimal priceMax = bodyContains(body, "priceMax", "maxPrice", "price")
                ? decimal(value(body, "priceMax", "maxPrice", "price"), "最高价格") : capture.getPriceMax();
        if (body.containsKey("price") && priceMin != null && priceMax == null) priceMax = priceMin;
        validatePriceRange(priceMin, priceMax);
        putStandardFields(normalized, title, description, category, brand, currency, priceMin, priceMax);
        normalized.put("captureMode", "DIRECT_DOM");
        capture.updateDraft(title, description, category, brand, currency, priceMin, priceMax, writeJson(normalized));
        updateCapture(capture);

        if (skuContentChanged) {
            deleteSkus(capture);
            insertSkus(capture, replacementSkus);
        }
        if (publicationImagesChanged) {
            deleteImages(capture);
            insertImages(capture, replacementImages);
        }
        // Provenance is server-owned: PATCH records only the fields actually edited by a user.
        // A client-supplied fieldSources collection must never replace or relabel capture history.
        insertChangedFieldSources(capture, body);
        insertStructuralEditSources(capture, body);
        return detail(capture);
    }

    @Transactional
    public ImageProcessResult processImages(String id, Map<String, Object> request,
                                            String headerIdempotencyKey, PlatformPrincipal principal) {
        ProductCapture capture = requireCapture(id, principal.organizationId());
        WatermarkRemovalProvider provider = watermarkRegistry.current();
        if ("AI_WATERMARK_V1".equals(provider.code())) return processAiImageStep(capture,request,headerIdempotencyKey,principal,provider);
        if (!EDITABLE_STATUSES.contains(capture.getStatus())
                && !("AI_WATERMARK_V1".equals(provider.code()) && "APPROVED".equals(capture.getStatus()))) {
            throw conflict("当前商品草稿状态不能处理图片");
        }
        List<ProductCaptureImage> images = images(capture);
        if (images.isEmpty()) throw conflict("草稿没有可处理的商品图片");
        Map<String, Object> body = mutableMap(request);
        String key = idempotencyKey(headerIdempotencyKey, text(body, "idempotencyKey"),
                "images-" + capture.getId());
        ProductImageProcessingJob existing = imageJobMapper.selectOne(
                new LambdaQueryWrapper<ProductImageProcessingJob>()
                        .eq(ProductImageProcessingJob::getOrganizationId, principal.organizationId())
                        .eq(ProductImageProcessingJob::getIdempotencyKey, key));
        if (existing != null) {
            if (!capture.getId().equals(existing.getCaptureId())) throw idempotencyConflict();
            return new ImageProcessResult(existing, detail(requireCapture(id, principal.organizationId())),
                    dryRunProvider(existing.getProvider()), existing.getProvider());
        }

        ProductImageProcessingJob job = new ProductImageProcessingJob(
                principal.organizationId(), capture.getId(), principal.userId(), key,
                provider.code(), images.size(), writeJson(body)
        );
        try {
            imageJobMapper.insert(job);
        } catch (DuplicateKeyException exception) {
            ProductImageProcessingJob duplicate = imageJobMapper.selectOne(
                    new LambdaQueryWrapper<ProductImageProcessingJob>()
                            .eq(ProductImageProcessingJob::getOrganizationId, principal.organizationId())
                            .eq(ProductImageProcessingJob::getIdempotencyKey, key));
            if (duplicate != null && capture.getId().equals(duplicate.getCaptureId())) {
                return new ImageProcessResult(duplicate, detail(capture),
                        dryRunProvider(duplicate.getProvider()), duplicate.getProvider());
            }
            throw exception;
        }
        job.start();
        imageJobMapper.updateById(job);
        capture.startImageProcessing();
        updateCapture(capture);
        List<Map<String, Object>> results = new ArrayList<>();
        String firstError = null;
        for (ProductCaptureImage image : images) {
            if (!"AI_WATERMARK_V1".equals(provider.code()) && "PROCESSED".equals(image.getStatus())
                    && image.getProcessedUrl() != null && !image.getProcessedUrl().isBlank()) {
                // A new workflow may retry only the failed portion of an earlier image job.
                // Preserve successful paid work and keep the replacement job's progress truthful.
                job.incrementProcessed();
                Map<String, Object> reused = new LinkedHashMap<>();
                reused.put("imageId", image.getId());
                reused.put("processedUrl", image.getProcessedUrl());
                reused.put("reused", true);
                results.add(reused);
                continue;
            }
            image.processing();
            imageMapper.updateById(image);
            try {
                WatermarkRemovalProvider.ProcessedImage result = provider.process(capture, image);
                String processedUrl = "AI_WATERMARK_V1".equals(provider.code()) && result.url() != null
                        && result.url().matches("/api/v1/product-watermark/assets/[a-f0-9]{64}")
                        ? result.url() : safeImageUrl.validate(result.url());
                image.processed(processedUrl);
                imageMapper.updateById(image);
                job.incrementProcessed();
                imageJobMapper.updateById(job);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("imageId", image.getId());
                item.put("processedUrl", processedUrl);
                item.put("providerResponse", result.providerResponse());
                results.add(item);
            } catch (Exception exception) {
                String message = readable(exception, "图片处理失败");
                if (firstError == null) firstError = message;
                image.failed(message);
                imageMapper.updateById(image);
                if ("AI_WATERMARK_V1".equals(provider.code())) break;
            }
        }
        if (firstError == null) {
            job.succeed(writeJson(Map.of("dryRun", provider.dryRun(), "images", results)));
            capture.imagesReady();
        } else {
            job.fail(firstError, writeJson(Map.of("dryRun", provider.dryRun(), "images", results)));
            capture.fail(firstError);
        }
        imageJobMapper.updateById(job);
        updateCapture(capture);
        return new ImageProcessResult(job, detail(capture), provider.dryRun(), provider.code());
    }

    private ImageProcessResult processAiImageStep(ProductCapture capture,Map<String,Object> request,String headerKey,
                                                PlatformPrincipal principal,WatermarkRemovalProvider provider) {
        if (!Set.of("CAPTURED","READY","APPROVED","FAILED","IMAGE_PROCESSING").contains(capture.getStatus())) throw conflict("当前商品不能进行水印处理");
        var body=mutableMap(request);String key=idempotencyKey(headerKey,text(body,"idempotencyKey"),"images-"+capture.getId());
        var job=imageJobMapper.selectOne(new LambdaQueryWrapper<ProductImageProcessingJob>()
                .eq(ProductImageProcessingJob::getOrganizationId,principal.organizationId())
                .eq(ProductImageProcessingJob::getIdempotencyKey,key).last("FOR UPDATE"));
        var all=images(capture);if(all.isEmpty())throw conflict("商品没有可处理图片");
        if(job==null){
            if("IMAGE_PROCESSING".equals(capture.getStatus()))throw conflict("另一项图片任务正在处理此商品");
            job=new ProductImageProcessingJob(principal.organizationId(),capture.getId(),principal.userId(),key,provider.code(),all.size(),writeJson(body));
            imageJobMapper.insert(job);job.start();capture.startImageProcessing();updateCapture(capture);
        }else if(!capture.getId().equals(job.getCaptureId())||!provider.code().equals(job.getProvider()))throw idempotencyConflict();
        if(!"RUNNING".equals(job.getStatus()))return new ImageProcessResult(job,detail(capture),false,provider.code());
        watermarkQueue.enqueue(job,all);
        return new ImageProcessResult(job,detail(capture),false,provider.code());
    }

    @Transactional
    public CaptureDetail approve(String id, PlatformPrincipal principal) {
        ProductCapture capture = requireCapture(id, principal.organizationId());
        if ("APPROVED".equals(capture.getStatus())) return detail(capture);
        if (!"READY".equals(capture.getStatus())) {
            throw conflict("请先完成商品图片处理，再审核草稿");
        }
        List<ProductCaptureImage> images = images(capture);
        List<ProductCaptureSku> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductCaptureSku>()
                .eq(ProductCaptureSku::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureSku::getCaptureId, capture.getId()));
        List<String> missing = approvalMissingFields(capture, skus, images);
        if (!missing.isEmpty()) {
            throw conflict("商品资料尚未补全：" + String.join("、", missing));
        }
        capture.approve(principal.userId());
        updateCapture(capture);
        return detail(capture);
    }

    @Transactional
    public PublishResult publish(String id, Map<String, Object> request,
                                 String headerIdempotencyKey, PlatformPrincipal principal) {
        return publish(id, request, headerIdempotencyKey, principal, () -> {});
    }

    @Transactional
    public PublishResult publish(String id, Map<String, Object> request,
                                 String headerIdempotencyKey, PlatformPrincipal principal,
                                 ProductPublishingProvider.ExternalCreateBoundary externalCreateBoundary) {
        ProductCapture capture = requireCapture(id, principal.organizationId());
        Map<String, Object> body = mutableMap(request);
        if (!Boolean.TRUE.equals(booleanValue(value(body, "confirmed")))) {
            throw badRequest("一键发布前必须明确提交 confirmed=true");
        }
        String destination = sourceUrlParser.normalizePlatform(
                text(body, "destinationPlatform", "targetPlatform", "platform") == null
                        ? capture.getSourcePlatform()
                        : text(body, "destinationPlatform", "targetPlatform", "platform"));
        String shopId = boundedNullable(text(body, "shopId", "destinationShopId"), 128);
        if (shopId == null) throw badRequest("一键发布必须指定目标店铺 ID");
        String fallbackKey = "publish-" + sha256(capture.getId() + ":" + destination + ":" + (shopId == null ? "" : shopId))
                .substring(0, 48);
        String key = idempotencyKey(headerIdempotencyKey, text(body, "idempotencyKey"), fallbackKey);
        ProductPublishAudit existing = publishAuditMapper.selectOne(
                new LambdaQueryWrapper<ProductPublishAudit>()
                        .eq(ProductPublishAudit::getOrganizationId, principal.organizationId())
                        .eq(ProductPublishAudit::getIdempotencyKey, key));
        if (listingTemplates != null) {
            var snapshot = existing != null
                    ? ProductListingTemplateService.snapshotFrom(readMap(existing.getRequestJson()))
                    : listingTemplates.publishSnapshot(principal.organizationId(), id, key, destination, capture);
            ProductListingTemplateService.apply(body, snapshot);
        } else body.remove("listingTemplateSnapshot");
        if (listingLogistics != null && "SHOPEE".equals(destination)) {
            var shipping = existing != null
                    ? ProductListingLogisticsService.snapshotFrom(readMap(existing.getRequestJson()))
                    : listingLogistics.publishSnapshot(principal.organizationId(), id, key, shopId);
            ProductListingLogisticsService.apply(body, shipping);
        } else body.remove("listingLogisticsSnapshot");
        if (existing != null) {
            verifyPublishIdempotency(existing, capture, destination, shopId, body);
            return new PublishResult(existing, detail(capture));
        }
        if (!List.of("APPROVED", "PUBLISHED").contains(capture.getStatus())) {
            throw conflict("商品必须先通过人工审核，才能一键发布");
        }
        ProductPublishingProvider provider = publishingRegistry.forPlatform(destination);
        ProductPublishAudit audit = new ProductPublishAudit(
                principal.organizationId(), capture.getId(), principal.userId(), key,
                destination, shopId, provider.code(), provider.dryRun(), writeJson(body)
        );
        try {
            publishAuditMapper.insert(audit);
        } catch (DuplicateKeyException exception) {
            ProductPublishAudit duplicate = publishAuditMapper.selectOne(
                    new LambdaQueryWrapper<ProductPublishAudit>()
                            .eq(ProductPublishAudit::getOrganizationId, principal.organizationId())
                            .eq(ProductPublishAudit::getIdempotencyKey, key));
            if (duplicate != null) {
                verifyPublishIdempotency(duplicate, capture, destination, shopId, body);
                return new PublishResult(duplicate, detail(capture));
            }
            throw exception;
        }
        audit.publishing();
        publishAuditMapper.updateById(audit);
        boolean[] externalCreationEntered = {false};
        try {
            // Providers also use the resolved key for durable media retries. Keep the
            // caller/audit body unchanged so header-only requests retain their fingerprint.
            Map<String, Object> providerRequest = mutableMap(body);
            providerRequest.put("idempotencyKey", key);
            ProductPublishingProvider.PublishResult result = provider.publish(
                    capture, destination, shopId, providerRequest, () -> {
                        externalCreationEntered[0] = true;
                        externalCreateBoundary.enter();
                    });
            Map<String, Object> providerResponse = new LinkedHashMap<>();
            if (result.providerResponse() != null) providerResponse.putAll(result.providerResponse());
            providerResponse.put("dryRun", provider.dryRun());
            audit.completed(provider.dryRun() ? null : result.externalId(), writeJson(providerResponse));
        } catch (Exception exception) {
            // A durable media checkpoint may still be uploading/transcoding. Roll back
            // this provisional audit so the workflow can resume with its existing key.
            // The same exception after ADD_ITEM intent must never enable a create retry.
            if (exception instanceof VideoUploadPendingException pending && !externalCreationEntered[0]) throw pending;
            // Transport failures during media preparation are safe to resume. Roll back
            // the provisional audit, otherwise its FAILED result poisons the same key.
            if (!externalCreationEntered[0] && isRetryableMarketplaceTransport(exception)) {
                throw (RuntimeException) exception;
            }
            // A participating transaction may already have failed. Returning a failed
            // audit cannot commit in that state and would replace the real error with
            // UnexpectedRollbackException. The workflow's separate create boundary
            // remains durable and still quarantines errors after marketplace creation.
            if (publishTransactionRollbackOnly()) {
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(readable(exception, "商品发布失败"), exception);
            }
            audit.failed(readable(exception, "商品发布失败"), writeJson(Map.of("dryRun", provider.dryRun())));
        }
        if ("PUBLISHED".equals(audit.getStatus())) {
            // The confirmed marketplace receipt is independent of this redundant draft
            // status. A second shop publication or user edit can advance its version
            // while the API runs; never replace their changes or erase the receipt.
            // Real database failures still escape and roll back the audit transaction.
            capture.published();
            if (captureMapper.updateById(capture) == 0) {
                capture = currentCaptureAfterPublicationConflict(capture);
            }
        }
        publishAuditMapper.updateById(audit);
        return new PublishResult(audit, detail(capture));
    }

    static boolean isRetryableMarketplaceTransport(Exception failure) {
        if (!(failure instanceof com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException)) return false;
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) return true;
        }
        return false;
    }

    private ProductCapture currentCaptureAfterPublicationConflict(ProductCapture capture) {
        // A plain SELECT could return the old MySQL REPEATABLE READ snapshot. This
        // current read happens only after all marketplace calls, and is held to commit.
        ProductCapture current = captureMapper.selectOne(new QueryWrapper<ProductCapture>()
                .eq("id", capture.getId())
                .eq("organization_id", capture.getOrganizationId())
                .last("FOR SHARE"));
        if (current == null) throw new BusinessException(HttpStatus.NOT_FOUND, "商品采集草稿不存在");
        return current;
    }

    private boolean publishTransactionRollbackOnly() {
        try { return TransactionAspectSupport.currentTransactionStatus().isRollbackOnly(); }
        catch (NoTransactionException ignored) { return false; }
    }

    private void verifyPublishIdempotency(ProductPublishAudit existing, ProductCapture capture,
                                          String destination, String shopId,
                                          Map<String, Object> request) {
        Map<String, Object> storedRequest = new LinkedHashMap<>(readMap(existing.getRequestJson()));
        Map<String, Object> currentRequest = new LinkedHashMap<>(request);
        storedRequest.remove("idempotencyKey");
        currentRequest.remove("idempotencyKey");
        if (!capture.getId().equals(existing.getCaptureId())
                || !destination.equals(existing.getDestinationPlatform())
                || !shopId.equals(existing.getShopId())
                || !writeJson(canonical(storedRequest)).equals(writeJson(canonical(currentRequest)))) {
            throw idempotencyConflict();
        }
    }

    private CaptureDetail verifyIdempotent(ProductCapture existing, String fingerprint,
                                           PlatformPrincipal principal) {
        verifyFingerprint(existing, fingerprint);
        ensureRecaptureAllowed(existing, principal);
        return detail(existing);
    }

    private void verifyFingerprint(ProductCapture existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) throw idempotencyConflict();
    }

    private String sourceFingerprint(SourceIdentity source) {
        if ("TIKTOK".equals(source.platform())) {
            return sha256(String.join("\n", source.platform(), source.productId()));
        }
        return sha256(String.join("\n", source.platform(),
                source.shopId() == null ? "" : source.shopId(), source.productId()));
    }

    private ProductCapture findByIdempotency(String organizationId, String key) {
        return captureMapper.selectOne(new LambdaQueryWrapper<ProductCapture>()
                .eq(ProductCapture::getOrganizationId, organizationId)
                .eq(ProductCapture::getIdempotencyKey, key));
    }

    private void ensureRecaptureAllowed(ProductCapture existing, PlatformPrincipal principal) {
        if (!principal.organizationId().equals(existing.getOrganizationId())
                || !principal.userId().equals(existing.getCreatedBy())
                || !"CAPTURED".equals(existing.getStatus())
                || existing.getApprovedBy() != null
                || existing.getApprovedAt() != null
                || existing.getPublishedAt() != null
                || hasUserEdits(existing)
                || hasProcessedImages(images(existing))) {
            throw conflict("当前商品草稿已被编辑或进入人工处理流程，不能被插件重采覆盖");
        }
    }

    private boolean supplementMissingCaptureDetails(ProductCapture existing, Map<String, Object> body,
                                                     PlatformPrincipal principal) {
        if (!principal.organizationId().equals(existing.getOrganizationId())
                || !principal.userId().equals(existing.getCreatedBy())
                || !EDITABLE_STATUSES.contains(existing.getStatus())
                || existing.getApprovedBy() != null
                || existing.getApprovedAt() != null
                || existing.getPublishedAt() != null) {
            return false;
        }
        boolean userEdits = hasUserEdits(existing);
        List<ProductCaptureImage> currentImages = userEdits ? List.of() : images(existing);
        // An untouched draft still supports full recapture, including newly available SKU and media.
        // A draft reset after a supplemental capture must retain its earlier image processing results.
        if ("CAPTURED".equals(existing.getStatus()) && !userEdits && !hasProcessedImages(currentImages)) {
            return false;
        }
        String category = firstNonBlank(text(body, "categoryName", "category"),
                text(mapValue(body, "domData", "dom", "fields"), "categoryName", "category"));
        if (existing.getCategoryName() != null && !existing.getCategoryName().isBlank()) category = null;
        if (category != null) {
            Long userCategoryEdits = fieldSourceMapper.selectCount(
                    new LambdaQueryWrapper<ProductCaptureFieldSource>()
                            .eq(ProductCaptureFieldSource::getOrganizationId, existing.getOrganizationId())
                            .eq(ProductCaptureFieldSource::getCaptureId, existing.getId())
                            .eq(ProductCaptureFieldSource::getSourceType, "USER")
                            .eq(ProductCaptureFieldSource::getFieldName, "categoryName"));
            category = userCategoryEdits != null && userCategoryEdits > 0 ? null : bounded(category, 255);
        }
        ProductCaptureImagePlan.DetailSupplement imageSupplement = null;
        if (!userEdits) {
            Object rawImages = firstNonNull(value(body, "images", "imageUrls"),
                    firstNonNull(value(mapValue(body, "domData", "dom", "fields"), "images", "imageUrls"),
                            value(mapValue(body, "normalizedData", "normalized"), "images", "imageUrls")));
            imageSupplement = ProductCaptureImagePlan.supplementalDetails(
                    currentImages, rawImages, skuEntityMaps(existing), safeImageUrl);
        }
        boolean newImages = imageSupplement != null && !imageSupplement.additions().isEmpty();
        if (category == null && !newImages) {
            // A repeated supplemental request is a no-op, never a full replacement of processed media.
            return imageSupplement != null && imageSupplement.requestedDetails();
        }

        ProductCaptureFieldSource categoryProvenance = category == null ? null
                : categorySupplementSource(existing, body, category);
        Map<String, Object> normalized = readMap(existing.getNormalizedData());
        if (category != null) normalized.put("categoryName", category);
        if (newImages) {
            normalized.put("images", imageSupplement.images());
            normalized.put("imageCompleteness", supplementImageCompleteness(
                    imageSupplement.completeness(), mapValue(normalized, "imageCompleteness"),
                    declaredImageCompleteness(body, Map.of())));
        }
        existing.supplementCapturedDetails(category == null ? existing.getCategoryName() : category,
                writeJson(normalized), newImages);
        updateCapture(existing);
        if (categoryProvenance != null) fieldSourceMapper.insert(categoryProvenance);
        if (newImages) {
            insertImages(existing, imageSupplement.additions());
            fieldSourceMapper.insert(new ProductCaptureFieldSource(existing.getOrganizationId(), existing.getId(),
                    "images", "DERIVED", "$.detailImageSupplement", writeJson(imageSupplement.additions()), BigDecimal.ONE));
        }
        return true;
    }

    private boolean hasProcessedImages(List<ProductCaptureImage> images) {
        return images.stream().anyMatch(image -> !"PENDING".equals(image.getStatus()));
    }

    private Map<String, Object> supplementImageCompleteness(Map<String, Object> server, Map<String, Object> previous,
                                                            List<Map<String, Object>> declarations) {
        Map<String, Object> result = new LinkedHashMap<>(server);
        int expected = requiredImageCount(server, "expectedCount", "预期图片数量");
        int captured = requiredImageCount(server, "capturedCount", "已采集图片数量");
        Set<String> issues = new LinkedHashSet<>(skuIssues(server.get("issues")));
        // Keep a previously known target, but recompute old deficits from the now-persisted assets.
        // Carrying an old complete=false flag would leave a repaired capture permanently blocked.
        Integer previousExpected = nonNegativeInteger(previous.get("expectedCount"), "之前预期图片数量");
        if (previousExpected != null) expected = Math.max(expected, previousExpected);
        for (Map<String, Object> declaration : declarations) {
            Integer declaredExpected = nonNegativeInteger(declaration.get("expectedCount"), "预期图片数量");
            if (declaredExpected != null) expected = Math.max(expected, declaredExpected);
            issues.addAll(skuIssues(declaration.get("issues")));
            if (Boolean.FALSE.equals(booleanValue(declaration.get("complete")))) issues.add("SOURCE_IMAGES_INCOMPLETE");
        }
        if (expected > captured) issues.add("IMAGE_CAPTURE_INCOMPLETE");
        result.put("expectedCount", expected);
        result.put("issues", List.copyOf(issues));
        result.put("complete", Boolean.TRUE.equals(server.get("complete")) && issues.isEmpty() && expected == captured);
        return Map.copyOf(result);
    }

    private ProductCaptureFieldSource categorySupplementSource(ProductCapture capture,
                                                                Map<String, Object> body, String category) {
        for (Map<String, Object> source : mapList(value(body, "fieldSources"), "字段来源")) {
            if (!"categoryName".equals(text(source, "field", "fieldName"))) continue;
            String sourceType = normalizeFieldSource(text(source, "source", "sourceType"));
            // A collector can describe DOM/structured evidence, but cannot create USER edit history.
            if (!"DOM".equals(sourceType)) sourceType = "DERIVED";
            return new ProductCaptureFieldSource(capture.getOrganizationId(), capture.getId(),
                    "categoryName", sourceType, boundedNullable(text(source, "path", "sourcePath"), 500),
                    writeJson(category), confidence(value(source, "confidence")));
        }
        return new ProductCaptureFieldSource(capture.getOrganizationId(), capture.getId(),
                "categoryName", "DOM", "$.categoryName", writeJson(category), BigDecimal.ONE);
    }

    private boolean hasUserEdits(ProductCapture capture) {
        Long userSourceCount = fieldSourceMapper.selectCount(
                new LambdaQueryWrapper<ProductCaptureFieldSource>()
                        .eq(ProductCaptureFieldSource::getOrganizationId, capture.getOrganizationId())
                        .eq(ProductCaptureFieldSource::getCaptureId, capture.getId())
                        .eq(ProductCaptureFieldSource::getSourceType, "USER")
        );
        if (userSourceCount != null && userSourceCount > 0) return true;
        String skuSource = text(
                mapValue(readMap(capture.getNormalizedData()), "skuCompleteness"),
                "source", "sourceType"
        );
        return "USER".equalsIgnoreCase(skuSource);
    }

    private ProductCapture requireCapture(String id, String organizationId) {
        ProductCapture capture = captureMapper.selectOne(new LambdaQueryWrapper<ProductCapture>()
                .eq(ProductCapture::getId, id)
                .eq(ProductCapture::getOrganizationId, organizationId));
        if (capture == null) throw new BusinessException(HttpStatus.NOT_FOUND, "商品采集草稿不存在");
        return capture;
    }

    private void updateCapture(ProductCapture capture) {
        if (captureMapper.updateById(capture) == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "商品草稿已被其他请求更新，请刷新后重试");
        }
    }

    private long requiredVersion(Map<String, Object> body) {
        Object value = body.get("version");
        if (value == null || String.valueOf(value).isBlank()) {
            throw badRequest("编辑商品草稿必须提交 version");
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value).trim());
            long version = decimal.longValueExact();
            if (version < 0) throw badRequest("version 不能小于 0");
            return version;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw badRequest("version 必须是非负整数");
        }
    }

    private void rejectProtectedNormalizedPatch(Map<String, Object> normalizedPatch) {
        List<String> protectedFields = normalizedPatch.keySet().stream()
                .filter(PROTECTED_NORMALIZED_FIELDS::contains)
                .sorted()
                .toList();
        if (!protectedFields.isEmpty()) {
            throw badRequest("normalizedData 不能修改受保护字段：" + String.join("、", protectedFields));
        }
    }

    private CaptureDetail detail(ProductCapture capture) {
        String organizationId = capture.getOrganizationId();
        List<ProductCaptureSku> skus = skuMapper.selectList(new LambdaQueryWrapper<ProductCaptureSku>()
                .eq(ProductCaptureSku::getOrganizationId, organizationId)
                .eq(ProductCaptureSku::getCaptureId, capture.getId())
                .orderByAsc(ProductCaptureSku::getSortOrder));
        List<ProductCaptureImage> images = imageMapper.selectList(new LambdaQueryWrapper<ProductCaptureImage>()
                .eq(ProductCaptureImage::getOrganizationId, organizationId)
                .eq(ProductCaptureImage::getCaptureId, capture.getId())
                .orderByAsc(ProductCaptureImage::getSortOrder));
        List<ProductCaptureFieldSource> fields = fieldSourceMapper.selectList(
                new LambdaQueryWrapper<ProductCaptureFieldSource>()
                        .eq(ProductCaptureFieldSource::getOrganizationId, organizationId)
                        .eq(ProductCaptureFieldSource::getCaptureId, capture.getId())
                        .orderByAsc(ProductCaptureFieldSource::getCreatedAt));
        return new CaptureDetail(capture, skus, images, fields,
                readMap(capture.getNormalizedData()), readMap(capture.getRawData()),
                captureMapper.selectCommerceGeneratedCaptureIds(organizationId, List.of(capture.getId()))
                        .contains(capture.getId()) ? "COMMERCE_GENERATED" : "CAPTURED");
    }

    private List<ProductCaptureImage> images(ProductCapture capture) {
        return imageMapper.selectList(new LambdaQueryWrapper<ProductCaptureImage>()
                .eq(ProductCaptureImage::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureImage::getCaptureId, capture.getId())
                .orderByAsc(ProductCaptureImage::getSortOrder));
    }

    private void insertSkus(ProductCapture capture, Object raw) {
        List<Map<String, Object>> values = mapList(raw, "SKU");
        if (values.size() > MAX_SKUS) throw badRequest("单个商品最多保存 500 个 SKU");
        Set<String> usedKeys = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> sku = values.get(index);
            String externalId = boundedNullable(text(sku, "externalSkuId", "skuId", "id", "modelId"), 160);
            String sellerSku = boundedNullable(text(sku, "sellerSku", "sku", "skuCode"), 160);
            String variation = boundedNullable(text(sku, "variationName", "name", "title"), 255);
            String baseKey = firstNonBlank(externalId, sellerSku, "sku-" + (index + 1));
            String skuKey = bounded(uniqueKey(baseKey, usedKeys), 160);
            BigDecimal price = decimal(value(sku, "price", "salePrice"), "SKU 价格");
            BigDecimal originalPrice = decimal(value(sku, "originalPrice", "listPrice"), "SKU 原价");
            Integer stock = nonNegativeInteger(value(sku, "stock", "inventory", "quantity"), "SKU 库存");
            String imageUrl = optionalImageUrl(value(sku, "imageUrl", "skuImage", "image"));
            String imageSource = normalizeSkuImageSource(text(sku, "imageSource"), imageUrl);
            Map<String, Object> attributes = mapValue(sku, "attributes", "options", "properties");
            skuMapper.insert(new ProductCaptureSku(
                    capture.getOrganizationId(), capture.getId(), skuKey, externalId,
                    sellerSku, variation, price, originalPrice, stock,
                    imageUrl, imageSource, writeJson(attributes), writeJson(sku), index
            ));
        }
    }

    private List<Map<String, Object>> resolveSkuImages(Object rawSkus, Object rawImages) {
        List<Map<String, Object>> values = mapList(rawSkus, "SKU");
        String productMainImage = productMainImageUrl(rawImages);
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> value : values) {
            Map<String, Object> sku = new LinkedHashMap<>(value);
            Object directImage = value(sku, "imageUrl", "skuImage", "modelImageUrl", "modelImage", "image");
            Object variantImage = value(sku, "variantImageUrl", "variationImageUrl", "optionImageUrl");
            String imageUrl = imageUrlFromValue(directImage);
            String inferredSource = imageUrl == null ? null : "SKU";
            if (imageUrl == null) {
                imageUrl = imageUrlFromValue(variantImage);
                if (imageUrl != null) inferredSource = "VARIANT_OPTION";
            }
            if (imageUrl == null && productMainImage != null) {
                imageUrl = productMainImage;
                inferredSource = "PRODUCT_MAIN";
            }
            String imageSource = normalizeSkuImageSource(text(sku, "imageSource"), imageUrl);
            if (imageSource == null) imageSource = inferredSource;
            putOrRemove(sku, "imageUrl", imageUrl);
            putOrRemove(sku, "imageSource", imageSource);
            sku.put("imageInherited", "PRODUCT_MAIN".equals(imageSource));
            resolved.add(sku);
        }
        return resolved;
    }

    private String productMainImageUrl(Object rawImages) {
        List<Object> values = objectList(rawImages, "图片");
        Object fallback = null;
        for (Object value : values) {
            if (fallback == null) fallback = value;
            if (value instanceof Map<?, ?> map) {
                String role = text(stringKeyMap(map), "role", "type");
                if (role != null && "MAIN".equalsIgnoreCase(role)) return imageUrlFromValue(value);
            }
        }
        return fallback == null ? null : imageUrlFromValue(fallback);
    }

    private String imageUrlFromValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            return optionalImageUrl(value(stringKeyMap(map), "url", "originalUrl", "src", "imageUrl"));
        }
        return optionalImageUrl(value);
    }

    private String optionalImageUrl(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return safeImageUrl.validate(String.valueOf(value));
    }

    private String normalizeSkuImageSource(String value, String imageUrl) {
        if (imageUrl == null) return null;
        String normalized = boundedNullable(value, 60);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,60}")) throw badRequest("SKU 图片来源格式不正确");
        if ("PRODUCT_MAIN_PAGE".equals(normalized)) return "PRODUCT_MAIN";
        return normalized;
    }

    private List<Map<String, Object>> declaredImageCompleteness(Map<String, Object> body,
                                                                Map<String, Object> normalized) {
        List<Map<String, Object>> declarations = new ArrayList<>();
        Object topLevel = body.get("imageCompleteness");
        if (topLevel instanceof Map<?, ?> map) declarations.add(stringKeyMap(map));
        for (String container : List.of("normalizedData", "normalized", "domData", "dom", "fields")) {
            Object nested = body.get(container);
            if (!(nested instanceof Map<?, ?> nestedMap)) continue;
            Object declaration = nestedMap.get("imageCompleteness");
            if (declaration instanceof Map<?, ?> map) declarations.add(stringKeyMap(map));
        }
        Object declaration = normalized.get("imageCompleteness");
        if (declaration instanceof Map<?, ?> map) declarations.add(stringKeyMap(map));
        return List.copyOf(declarations);
    }

    private Map<String, Object> mergeImageCompleteness(Map<String, Object> server,
                                                        List<Map<String, Object>> declarations) {
        Map<String, Object> merged = server;
        for (Map<String, Object> declaration : declarations) {
            merged = mergeImageCompletenessDeclaration(merged, declaration);
        }
        return merged;
    }

    private Map<String, Object> mergeImageCompletenessDeclaration(Map<String, Object> server,
                                                                   Map<String, Object> declared) {
        int serverExpected = requiredImageCount(server, "expectedCount", "服务端预期图片数量");
        int serverCaptured = requiredImageCount(server, "capturedCount", "服务端已采集图片数量");
        int serverRequiredSkuImages = requiredImageCount(
                server, "requiredSkuImageCount", "服务端所需 SKU 图片数量"
        );
        int serverCoveredSkuImages = requiredImageCount(
                server, "coveredSkuImageCount", "服务端已覆盖 SKU 图片数量"
        );
        Integer declaredExpected = nonNegativeInteger(value(declared, "expectedCount"), "预期图片数量");
        Integer declaredCaptured = nonNegativeInteger(value(declared, "capturedCount"), "已采集图片数量");
        Integer declaredRequiredSkuImages = nonNegativeInteger(
                value(declared, "requiredSkuImageCount"), "所需 SKU 图片数量"
        );
        Integer declaredCoveredSkuImages = nonNegativeInteger(
                value(declared, "coveredSkuImageCount"), "已覆盖 SKU 图片数量"
        );

        int expected = Math.max(serverExpected,
                declaredExpected == null ? serverExpected : declaredExpected);
        int captured = Math.min(serverCaptured,
                declaredCaptured == null ? serverCaptured : declaredCaptured);
        int requiredSkuImages = Math.max(serverRequiredSkuImages,
                declaredRequiredSkuImages == null ? serverRequiredSkuImages : declaredRequiredSkuImages);
        int coveredSkuImages = Math.min(serverCoveredSkuImages,
                declaredCoveredSkuImages == null ? serverCoveredSkuImages : declaredCoveredSkuImages);
        LinkedHashSet<String> issues = new LinkedHashSet<>(skuIssues(value(server, "issues")));
        issues.addAll(skuIssues(value(declared, "issues")));
        boolean complete = Boolean.TRUE.equals(booleanValue(value(server, "complete")))
                && Boolean.TRUE.equals(booleanValue(value(declared, "complete")))
                && expected == captured
                && requiredSkuImages == coveredSkuImages
                && issues.isEmpty();

        Map<String, Object> result = new LinkedHashMap<>(server);
        result.put("expectedCount", expected);
        result.put("capturedCount", captured);
        result.put("requiredSkuImageCount", requiredSkuImages);
        result.put("coveredSkuImageCount", coveredSkuImages);
        result.put("complete", complete);
        result.put("issues", List.copyOf(issues));
        return Map.copyOf(result);
    }

    private int requiredImageCount(Map<String, Object> metadata, String field, String label) {
        Integer count = nonNegativeInteger(metadata.get(field), label);
        if (count == null) throw new IllegalStateException(label + "缺失");
        return count;
    }

    private boolean sameSkuContent(List<Map<String, Object>> requested,
                                   List<Map<String, Object>> persisted) {
        if (requested.size() != persisted.size()) return false;
        for (int index = 0; index < requested.size(); index++) {
            if (!comparableSku(requested.get(index)).equals(comparableSku(persisted.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> comparableSku(Map<String, Object> sku) {
        Map<String, Object> comparable = new LinkedHashMap<>();
        comparable.put("externalSkuId", boundedNullable(
                text(sku, "externalSkuId", "skuId", "id", "modelId"), 160));
        comparable.put("sellerSku", boundedNullable(text(sku, "sellerSku", "sku", "skuCode"), 160));
        comparable.put("variationName", boundedNullable(
                text(sku, "variationName", "name", "title"), 255));
        comparable.put("price", decimal(value(sku, "price", "salePrice"), "SKU 价格"));
        comparable.put("originalPrice", decimal(
                value(sku, "originalPrice", "listPrice"), "SKU 原价"));
        comparable.put("stock", nonNegativeInteger(
                value(sku, "stock", "inventory", "quantity"), "SKU 库存"));
        String imageUrl = optionalImageUrl(value(sku, "imageUrl", "skuImage", "image"));
        comparable.put("imageUrl", imageUrl);
        comparable.put("imageSource", normalizeSkuImageSource(text(sku, "imageSource"), imageUrl));
        comparable.put("attributes", canonical(mapValue(sku, "attributes", "options", "properties")));
        return comparable;
    }

    private void normalizeSkuCompleteness(Map<String, Object> body,
                                          Map<String, Object> normalized,
                                          Object rawSkus,
                                          boolean userChangedSkus,
                                          boolean manualConfirmation,
                                          Map<String, Object> previousMetadata) {
        Map<String, Object> nestedNormalized = mapValue(body, "normalizedData", "normalized");
        boolean topLevelDeclaration = value(body, "skuCompleteness") instanceof Map<?, ?>;
        boolean nestedDeclaration = value(nestedNormalized, "skuCompleteness") instanceof Map<?, ?>;
        boolean declarationSupplied = topLevelDeclaration || nestedDeclaration
                || (!userChangedSkus && value(normalized, "skuCompleteness") instanceof Map<?, ?>);

        Map<String, Object> metadata = topLevelDeclaration
                ? mapValue(body, "skuCompleteness")
                : nestedDeclaration
                    ? mapValue(nestedNormalized, "skuCompleteness")
                    : mapValue(normalized, "skuCompleteness");
        List<Map<String, Object>> skus = mapList(rawSkus, "SKU");
        if (skus.size() > MAX_SKUS) throw badRequest("单个商品最多保存 500 个 SKU");

        Integer expectedCount = skuCount(value(metadata, "expectedCount"), "预期 SKU 数量");
        Integer capturedCount = declarationSupplied
                ? skuCount(value(metadata, "capturedCount"), "已采集 SKU 数量")
                : skus.size();
        if (capturedCount == null) capturedCount = skus.size();
        boolean claimedComplete = declarationSupplied
                && Boolean.TRUE.equals(booleanValue(value(metadata, "complete")));
        String source = skuCompletenessSource(metadata, previousMetadata,
                userChangedSkus || manualConfirmation);
        int hintCount = variantHintCount(firstNonNull(value(metadata, "variantHints"),
                value(normalized, "variantHints")));
        Integer declaredDimensionCount = skuCount(value(metadata, "variantDimensionCount"), "SKU 规格维度数量");
        int variantDimensionCount = Math.max(declaredDimensionCount == null ? 0 : declaredDimensionCount,
                hintCount);
        boolean hasVariants = Boolean.TRUE.equals(booleanValue(firstNonNull(
                value(metadata, "hasVariants"), value(normalized, "hasVariants"))))
                || variantDimensionCount > 0 || hintCount > 0 || skus.size() > 1;
        List<String> declaredIssues = skuIssues(value(metadata, "issues"));
        List<String> issues = blockingSkuIssues(declaredIssues);
        boolean declarationAllowsCompleteness = claimedComplete
                || onlyOptionalStockIssues(declaredIssues);

        boolean validatedComplete = declarationAllowsCompleteness
                && expectedCount != null
                && expectedCount == skus.size()
                && capturedCount == skus.size()
                && issues.isEmpty()
                && completeSkuItems(skus, hasVariants, variantDimensionCount);
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("expectedCount", expectedCount);
        saved.put("capturedCount", capturedCount);
        saved.put("complete", validatedComplete);
        saved.put("source", source);
        saved.put("hasVariants", hasVariants);
        saved.put("variantDimensionCount", variantDimensionCount);
        saved.put("issues", issues);
        normalized.put("skuCompleteness", saved);
    }

    private boolean manualSkuConfirmationRequested(Map<String, Object> body) {
        Map<String, Object> nestedNormalized = mapValue(body, "normalizedData", "normalized");
        Map<String, Object> metadata = value(body, "skuCompleteness") instanceof Map<?, ?>
                ? mapValue(body, "skuCompleteness")
                : mapValue(nestedNormalized, "skuCompleteness");
        return Boolean.TRUE.equals(booleanValue(value(metadata, "manualConfirmed")))
                && Boolean.TRUE.equals(booleanValue(value(metadata, "complete")));
    }

    private String skuCompletenessSource(Map<String, Object> metadata,
                                         Map<String, Object> previousMetadata,
                                         boolean userAuthored) {
        if (userAuthored) return "USER";
        String previous = boundedNullable(text(previousMetadata, "source"), 32);
        if (previous != null) return validatedSkuCompletenessSource(previous);
        return automaticSkuCompletenessSource(text(metadata, "source"));
    }

    private String automaticSkuCompletenessSource(String value) {
        String source = validatedSkuCompletenessSource(value == null ? "DOM" : value);
        return source.matches(".*(?:USER|MANUAL|HUMAN|EDITOR|REVIEWER).*") ? "DOM" : source;
    }

    private String validatedSkuCompletenessSource(String value) {
        String source = boundedNullable(value, 32);
        if (source == null) return "DOM";
        source = source.toUpperCase(Locale.ROOT);
        if (!source.matches("[A-Z0-9_-]{1,32}")) throw badRequest("SKU 完整度来源格式不正确");
        return source;
    }

    private boolean validatedSkuCompleteness(Map<String, Object> normalized,
                                             List<Map<String, Object>> skus) {
        Map<String, Object> metadata = mapValue(normalized, "skuCompleteness");
        Integer expectedCount = skuCount(value(metadata, "expectedCount"), "预期 SKU 数量");
        Integer capturedCount = skuCount(value(metadata, "capturedCount"), "已采集 SKU 数量");
        int hintCount = variantHintCount(firstNonNull(value(metadata, "variantHints"),
                value(normalized, "variantHints")));
        Integer declaredDimensionCount = skuCount(value(metadata, "variantDimensionCount"), "SKU 规格维度数量");
        int variantDimensionCount = Math.max(declaredDimensionCount == null ? 0 : declaredDimensionCount,
                hintCount);
        boolean hasVariants = Boolean.TRUE.equals(booleanValue(firstNonNull(
                value(metadata, "hasVariants"), value(normalized, "hasVariants"))))
                || variantDimensionCount > 0 || hintCount > 0 || skus.size() > 1;
        List<String> declaredIssues = skuIssues(value(metadata, "issues"));
        return (Boolean.TRUE.equals(booleanValue(value(metadata, "complete")))
                    || onlyOptionalStockIssues(declaredIssues))
                && expectedCount != null
                && capturedCount != null
                && expectedCount == skus.size()
                && capturedCount == skus.size()
                && blockingSkuIssues(declaredIssues).isEmpty()
                && completeSkuItems(skus, hasVariants, variantDimensionCount);
    }

    private boolean completeSkuItems(List<Map<String, Object>> skus, boolean hasVariants,
                                     int variantDimensionCount) {
        if (skus.isEmpty()) return false;
        Set<String> externalIds = new HashSet<>();
        Set<String> attributeCombinations = new HashSet<>();
        for (Map<String, Object> sku : skus) {
            String externalId = boundedNullable(text(sku, "externalSkuId", "skuId", "modelId"), 160);
            if (externalId == null || !externalIds.add(externalId)) return false;
            BigDecimal price = decimal(value(sku, "price", "salePrice"), "SKU 价格");
            if (price == null) return false;
            BigDecimal originalPrice = decimal(value(sku, "originalPrice", "listPrice"), "SKU 原价");
            if (originalPrice == null || originalPrice.compareTo(price) < 0) return false;
            if (boundedNullable(text(sku, "imageUrl", "skuImage", "image"), 4096) == null) return false;
            Map<String, Object> attributes = mapValue(sku, "attributes", "options", "properties");
            if (hasVariants && (!completeAttributes(attributes)
                    || variantDimensionCount > 0 && attributes.size() != variantDimensionCount)) return false;
            if (hasVariants && !attributeCombinations.add(writeJson(canonical(attributes)))) return false;
        }
        return true;
    }

    private int variantHintCount(Object raw) {
        if (raw == null) return 0;
        if (!(raw instanceof List<?> hints)) throw badRequest("SKU 规格提示必须是数组");
        if (hints.size() > 30) throw badRequest("SKU 规格维度数量不能超过 30");
        return hints.size();
    }

    private List<String> skuIssues(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) throw badRequest("SKU 完整性问题必须是数组");
        if (values.size() > 80) throw badRequest("SKU 完整性问题不能超过 80 项");
        List<String> issues = new ArrayList<>();
        for (Object value : values) {
            if (value == null) continue;
            String issue = boundedNullable(String.valueOf(value), 100);
            if (issue != null) issues.add(issue);
        }
        return List.copyOf(issues);
    }

    private List<String> blockingSkuIssues(List<String> issues) {
        return issues.stream().filter(issue -> !isOptionalStockIssue(issue)).toList();
    }

    private boolean onlyOptionalStockIssues(List<String> issues) {
        return !issues.isEmpty() && issues.stream().allMatch(this::isOptionalStockIssue);
    }

    private boolean isOptionalStockIssue(String issue) {
        return OPTIONAL_STOCK_ISSUE.matcher(issue).matches();
    }

    private boolean completeAttributes(Object value) {
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty() && map.values().stream().allMatch(this::completeAttributeValue);
        }
        return false;
    }

    private boolean completeAttributeValue(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty() && map.values().stream().allMatch(this::completeAttributeValue);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty() && list.stream().allMatch(this::completeAttributeValue);
        }
        return true;
    }

    private Integer skuCount(Object value, String label) {
        Integer result = nonNegativeInteger(value, label);
        if (result != null && result > MAX_SKUS) throw badRequest(label + "不能超过 500");
        return result;
    }

    private List<Map<String, Object>> skuEntityMaps(ProductCapture capture) {
        return skuEntityMaps(skuMapper.selectList(new LambdaQueryWrapper<ProductCaptureSku>()
                .eq(ProductCaptureSku::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureSku::getCaptureId, capture.getId())
                .orderByAsc(ProductCaptureSku::getSortOrder)));
    }

    private List<Map<String, Object>> skuEntityMaps(List<ProductCaptureSku> skus) {
        return skus.stream().map(sku -> {
            Map<String, Object> value = readMap(sku.getSourceJson());
            value.put("externalSkuId", sku.getExternalSkuId());
            value.put("sellerSku", sku.getSellerSku());
            value.put("variationName", sku.getVariationName());
            value.put("price", sku.getPrice());
            value.put("originalPrice", sku.getOriginalPrice());
            value.put("stock", sku.getStock());
            value.put("imageUrl", sku.getImageUrl());
            value.put("imageSource", sku.getImageSource());
            value.put("imageInherited", "PRODUCT_MAIN".equals(sku.getImageSource()));
            value.put("attributes", readMap(sku.getAttributesJson()));
            return value;
        }).toList();
    }

    private List<Map<String, Object>> imageEntityMaps(List<ProductCaptureImage> images) {
        return images.stream().map(image -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", image.getOriginalUrl());
            value.put("role", image.getRoleName());
            value.put("sortOrder", image.getSortOrder());
            value.put("alt", image.getAltText());
            value.put("mimeType", image.getMimeType());
            return value;
        }).toList();
    }

    private void insertImages(ProductCapture capture, Object raw) {
        List<Object> values = objectList(raw, "图片");
        if (values.size() > MAX_IMAGES) throw badRequest("单个商品最多保存 " + MAX_IMAGES + " 张图片");
        Set<String> urls = new HashSet<>();
        int order = 0;
        for (Object value : values) {
            Map<String, Object> image = value instanceof Map<?, ?> map ? stringKeyMap(map)
                    : new LinkedHashMap<>(Map.of("url", String.valueOf(value)));
            String url = safeImageUrl.validate(text(image, "url", "originalUrl", "src"));
            if (!urls.add(url)) continue;
            int requestedOrder = optionalInt(value(image, "position", "sortOrder", "order"), order);
            String role = normalizeProductImageRole(text(image, "role", "type"), order == 0);
            String alt = boundedNullable(text(image, "alt", "altText"), 500);
            String mime = boundedNullable(text(image, "mimeType", "contentType"), 120);
            imageMapper.insert(new ProductCaptureImage(
                    capture.getOrganizationId(), capture.getId(), url, role, alt,
                    Math.max(0, requestedOrder), mime, writeJson(image)
            ));
            order++;
        }
    }

    private String normalizeProductImageRole(String value, boolean first) {
        String normalized = value == null || value.isBlank()
                ? (first ? "MAIN" : "GALLERY") : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MAIN" -> "MAIN";
            case "VARIANT", "SKU", "MODEL", "VARIANT_OPTION" -> "VARIANT";
            case "DETAIL", "DESCRIPTION" -> "DETAIL";
            default -> "GALLERY";
        };
    }

    private void insertFieldSources(ProductCapture capture, Map<String, Object> body,
                                    Map<String, Object> normalized, String automaticSource) {
        String trustedSource = normalizeFieldSource(automaticSource);
        Set<String> inserted = new HashSet<>();
        for (Map<String, Object> source : mapList(value(body, "fieldSources"), "字段来源")) {
            String field = bounded(requiredText(source, "field", "fieldName"), 160);
            String path = boundedNullable(text(source, "path", "sourcePath"), 500);
            Object sourceValue = value(source, "value", "sourceValue");
            BigDecimal confidence = confidence(value(source, "confidence"));
            fieldSourceMapper.insert(new ProductCaptureFieldSource(
                    capture.getOrganizationId(), capture.getId(), field, trustedSource, path,
                    sourceValue == null ? null : writeJson(sourceValue), confidence
            ));
            inserted.add(field + "\u0000" + trustedSource);
        }
        for (String field : List.of("title", "description", "categoryName", "brandName", "currency",
                "priceMin", "priceMax", "sourceProductId", "sourceShopId")) {
            Object value = normalized.get(field);
            if (value == null || !inserted.add(field + "\u0000" + trustedSource)) continue;
            fieldSourceMapper.insert(new ProductCaptureFieldSource(
                    capture.getOrganizationId(), capture.getId(), field, trustedSource,
                    "$." + field, writeJson(value), BigDecimal.ONE
            ));
        }
    }

    private void insertChangedFieldSources(ProductCapture capture, Map<String, Object> body) {
        insertChangedFieldSource(capture, body, "title", "title", "name", "productName");
        insertChangedFieldSource(capture, body, "description", "description", "productDescription");
        insertChangedFieldSource(capture, body, "categoryName", "categoryName", "category");
        insertChangedFieldSource(capture, body, "brandName", "brandName", "brand");
        insertChangedFieldSource(capture, body, "currency", "currency", "currencyCode");
        insertChangedFieldSource(capture, body, "priceMin", "priceMin", "minPrice", "price");
        insertChangedFieldSource(capture, body, "priceMax", "priceMax", "maxPrice", "price");
    }

    private void insertChangedFieldSource(ProductCapture capture, Map<String, Object> body,
                                          String field, String... aliases) {
        for (String alias : aliases) {
            if (!body.containsKey(alias)) continue;
            Object value = body.get(alias);
            fieldSourceMapper.insert(new ProductCaptureFieldSource(
                    capture.getOrganizationId(), capture.getId(), field, "USER", "$." + alias,
                    value == null ? null : writeJson(value), BigDecimal.ONE
            ));
            return;
        }
    }

    private void insertStructuralEditSources(ProductCapture capture, Map<String, Object> body) {
        Set<String> fields = new LinkedHashSet<>();
        if (bodyContains(body, "skus", "variants", "models")) fields.add("skus");
        if (bodyContains(body, "images", "imageUrls")) fields.add("images");
        if (bodyContains(body, "skuCompleteness")) fields.add("skuCompleteness");
        if (bodyContains(body, "normalizedData", "normalized")) fields.add("normalizedData");
        for (String field : fields) {
            fieldSourceMapper.insert(new ProductCaptureFieldSource(
                    capture.getOrganizationId(), capture.getId(), field, "USER",
                    "$." + field, null, BigDecimal.ONE
            ));
        }
    }

    private void deleteSkus(ProductCapture capture) {
        skuMapper.delete(new LambdaQueryWrapper<ProductCaptureSku>()
                .eq(ProductCaptureSku::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureSku::getCaptureId, capture.getId()));
    }

    private void deleteImages(ProductCapture capture) {
        imageMapper.delete(new LambdaQueryWrapper<ProductCaptureImage>()
                .eq(ProductCaptureImage::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureImage::getCaptureId, capture.getId()));
    }

    private void deleteFieldSources(ProductCapture capture) {
        fieldSourceMapper.delete(new LambdaQueryWrapper<ProductCaptureFieldSource>()
                .eq(ProductCaptureFieldSource::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureFieldSource::getCaptureId, capture.getId()));
    }

    private void copyKnownFields(Map<String, Object> source, Map<String, Object> target) {
        copy(source, target, "title", "title", "name", "productName");
        copy(source, target, "description", "description", "productDescription");
        copy(source, target, "categoryName", "categoryName", "category");
        copy(source, target, "brandName", "brandName", "brand");
        copy(source, target, "currency", "currency", "currencyCode");
        copy(source, target, "priceMin", "priceMin", "minPrice", "price");
        copy(source, target, "priceMax", "priceMax", "maxPrice", "price");
        copy(source, target, "skus", "skus", "variants", "models");
        copy(source, target, "skuCompleteness", "skuCompleteness");
        copy(source, target, "images", "images", "imageUrls");
    }

    private void copy(Map<String, Object> source, Map<String, Object> target,
                      String targetKey, String... aliases) {
        for (String alias : aliases) {
            if (source.containsKey(alias) && source.get(alias) != null) {
                target.put(targetKey, source.get(alias));
                return;
            }
        }
    }

    private void putStandardFields(Map<String, Object> target, String title, String description,
                                   String category, String brand, String currency,
                                   BigDecimal priceMin, BigDecimal priceMax) {
        target.put("title", title);
        putOrRemove(target, "description", description);
        putOrRemove(target, "categoryName", category);
        putOrRemove(target, "brandName", brand);
        putOrRemove(target, "currency", currency);
        putOrRemove(target, "priceMin", priceMin);
        putOrRemove(target, "priceMax", priceMax);
    }

    private void putOrRemove(Map<String, Object> map, String key, Object value) {
        if (value == null) map.remove(key); else map.put(key, value);
    }

    private String normalizeFieldSource(String value) {
        String normalized = value == null ? "DOM" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DOM", "REEFAPI", "TMAPI", "DERIVED", "USER" -> normalized;
            case "JSON_LD", "JSON-LD", "META", "DOM_META", "SCRIPT", "NETWORK" -> "DERIVED";
            default -> "DERIVED";
        };
    }

    private BigDecimal confidence(Object value) {
        BigDecimal result = decimal(value, "字段可信度");
        if (result == null) return null;
        if (result.compareTo(BigDecimal.ZERO) < 0 || result.compareTo(BigDecimal.ONE) > 0) {
            throw badRequest("字段可信度必须在 0 到 1 之间");
        }
        return result.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value, String label) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        String text = String.valueOf(value).trim().replace(",", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?").matcher(text);
        if (!matcher.find()) throw badRequest(label + "格式不正确");
        try {
            BigDecimal result = new BigDecimal(matcher.group()).setScale(2, RoundingMode.HALF_UP);
            if (result.signum() < 0) throw badRequest(label + "不能小于 0");
            return result;
        } catch (NumberFormatException exception) {
            throw badRequest(label + "格式不正确");
        }
    }

    private Integer nonNegativeInteger(Object value, String label) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value).trim());
            long number = decimal.longValueExact();
            if (number < 0 || number > Integer.MAX_VALUE) throw badRequest(label + "超出允许范围");
            return (int) number;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw badRequest(label + "格式不正确");
        }
    }

    List<String> approvalMissingFields(ProductCapture capture, List<ProductCaptureSku> skus,
                                               List<ProductCaptureImage> images) {
        List<String> missing = new ArrayList<>();
        Map<String, Object> normalized = readMap(capture.getNormalizedData());
        if (capture.getTitle() == null || capture.getTitle().isBlank()) missing.add("标题");
        if (capture.getDescription() == null || capture.getDescription().isBlank()) missing.add("描述");
        if (capture.getPriceMin() == null || capture.getPriceMax() == null) missing.add("商品价格");
        if (skus.isEmpty()) {
            missing.add("SKU");
        } else {
            if (skus.stream().anyMatch(sku -> sku.getPrice() == null)) missing.add("SKU 价格");
            if (skus.stream().anyMatch(sku -> sku.getOriginalPrice() == null
                    || sku.getPrice() != null && sku.getOriginalPrice().compareTo(sku.getPrice()) < 0)) {
                missing.add("SKU 原价");
            }
            if (skus.stream().anyMatch(sku -> sku.getImageUrl() == null || sku.getImageUrl().isBlank())) {
                missing.add("SKU 图片");
            }
            if (capture.getPriceMin() != null && capture.getPriceMax() != null
                    && skus.stream().allMatch(sku -> sku.getPrice() != null)) {
                BigDecimal skuPriceMin = skus.stream().map(ProductCaptureSku::getPrice)
                        .min(Comparator.naturalOrder()).orElseThrow();
                BigDecimal skuPriceMax = skus.stream().map(ProductCaptureSku::getPrice)
                        .max(Comparator.naturalOrder()).orElseThrow();
                if (capture.getPriceMin().compareTo(skuPriceMin) != 0
                        || capture.getPriceMax().compareTo(skuPriceMax) != 0) {
                    missing.add("商品价格区间与 SKU 售价不一致");
                }
            }
        }
        if (!validatedSkuCompleteness(normalized, skuEntityMaps(skus))) {
            missing.add("SKU 完整性声明");
        }
        if (!validatedImageCompleteness(normalized)) missing.add("商品图片完整性声明");
        if (images.isEmpty()) {
            missing.add("商品图片");
        } else if (images.stream().anyMatch(image -> !"PROCESSED".equals(image.getStatus()))) {
            missing.add("尚未处理完成的商品图片");
        }
        Set<String> processedImageUrls = images.stream()
                .filter(image -> "PROCESSED".equals(image.getStatus()))
                .map(ProductCaptureImage::getOriginalUrl)
                .collect(java.util.stream.Collectors.toSet());
        if (skus.stream().map(ProductCaptureSku::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .anyMatch(url -> !processedImageUrls.contains(url))) {
            missing.add("尚未处理完成的 SKU 图片");
        }
        return missing;
    }

    private boolean validatedImageCompleteness(Map<String, Object> normalized) {
        Map<String, Object> metadata = mapValue(normalized, "imageCompleteness");
        if (metadata.isEmpty()) return false;
        Integer expected = nonNegativeInteger(value(metadata, "expectedCount"), "预期图片数量");
        Integer captured = nonNegativeInteger(value(metadata, "capturedCount"), "已采集图片数量");
        Integer requiredSkuImages = nonNegativeInteger(
                value(metadata, "requiredSkuImageCount"), "所需 SKU 图片数量"
        );
        Integer coveredSkuImages = nonNegativeInteger(
                value(metadata, "coveredSkuImageCount"), "已覆盖 SKU 图片数量"
        );
        return Boolean.TRUE.equals(booleanValue(value(metadata, "complete")))
                && expected != null && captured != null && expected.equals(captured)
                && requiredSkuImages != null && coveredSkuImages != null
                && requiredSkuImages.equals(coveredSkuImages)
                && skuIssues(value(metadata, "issues")).isEmpty();
    }

    private int optionalInt(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void validatePriceRange(BigDecimal min, BigDecimal max) {
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw badRequest("最高价格不能低于最低价格");
        }
    }

    private String currency(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,12}")) throw badRequest("币种代码格式不正确");
        return normalized;
    }

    private String idempotencyKey(String header, String body, String fallback) {
        String result = firstNonBlank(header, body, fallback);
        if (result == null || !result.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw badRequest("幂等键必须为 8-128 位字母、数字或 . _ : -");
        }
        return result;
    }

    private BusinessException idempotencyConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "幂等键已用于另一份请求");
    }

    private String uniqueKey(String base, Set<String> used) {
        String normalized = base.trim();
        String candidate = normalized;
        int suffix = 2;
        while (!used.add(candidate)) candidate = normalized + "-" + suffix++;
        return candidate;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private void sanitizeCapturedUrls(Object value, String canonicalUrl) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (List.of("sourceurl", "producturl", "pageurl", "canonicalurl").contains(
                        key.toLowerCase(Locale.ROOT))) {
                    entry.setValue(canonicalUrl);
                } else {
                    sanitizeCapturedUrls(entry.getValue(), canonicalUrl);
                }
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> sanitizeCapturedUrls(item, canonicalUrl));
        }
    }

    private boolean dryRunProvider(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).contains("DRY_RUN");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw badRequest("采集数据包含无法保存的内容");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception exception) {
            throw new IllegalStateException("商品草稿 JSON 已损坏", exception);
        }
    }

    private Map<String, Object> mutableMap(Map<String, Object> value) {
        if (value == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, child) -> result.put(key, mutableValue(child)));
        return result;
    }

    private Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), mutableValue(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::mutableValue).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        return value;
    }

    private Map<String, Object> mapValue(Map<String, Object> map, String... keys) {
        Object raw = value(map, keys);
        return raw instanceof Map<?, ?> child ? stringKeyMap(child) : new LinkedHashMap<>();
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<Map<String, Object>> mapList(Object raw, String label) {
        List<Object> values = objectList(raw, label);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) throw badRequest(label + "列表格式不正确");
            result.add(stringKeyMap(map));
        }
        return result;
    }

    private List<Object> objectList(Object raw, String label) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw badRequest(label + "必须是数组");
        return new ArrayList<>(list);
    }

    private Object value(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String requiredText(Map<String, Object> map, String... keys) {
        String result = text(map, keys);
        if (result == null || result.isBlank()) throw badRequest("商品标题不能为空");
        return result;
    }

    private boolean bodyContains(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return true;
        return false;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private String bounded(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw badRequest("字段内容超过 " + max + " 个字符");
        return normalized;
    }

    private String boundedNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return bounded(value, max);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private String readable(Exception exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return fallback;
        return message.substring(0, Math.min(1000, message.length()));
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    public record CaptureDetail(ProductCapture capture, List<ProductCaptureSku> skus,
                                List<ProductCaptureImage> images,
                                List<ProductCaptureFieldSource> fieldSources,
                                Map<String, Object> normalizedData,
                                Map<String, Object> rawData,
                                String originType) {
        public CaptureDetail(ProductCapture capture, List<ProductCaptureSku> skus,
                             List<ProductCaptureImage> images, List<ProductCaptureFieldSource> fieldSources,
                             Map<String, Object> normalizedData, Map<String, Object> rawData) {
            this(capture, skus, images, fieldSources, normalizedData, rawData, "CAPTURED");
        }
    }

    public record CaptureSummary(ProductCapture capture, ProductCaptureImage thumbnail, String originType) {
        public CaptureSummary(ProductCapture capture, ProductCaptureImage thumbnail) {
            this(capture, thumbnail, "CAPTURED");
        }
    }

    public record CapturePage(List<CaptureSummary> content, int page, int size,
                              long totalElements, int totalPages,
                              Map<String, Long> statusCounts) {}

    public record ImageProcessResult(ProductImageProcessingJob job, CaptureDetail capture,
                                     boolean dryRun, String provider) {}
    public record PublishResult(ProductPublishAudit audit, CaptureDetail capture) {}
}
