package com.yingdao.aicommerce.product.application.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.product.domain.ProductCaptureImage;
import com.yingdao.aicommerce.product.domain.ProductCaptureSku;
import com.yingdao.aicommerce.product.mapper.ProductCaptureImageMapper;
import com.yingdao.aicommerce.product.mapper.ProductCaptureSkuMapper;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.application.TikTokShopListingCapabilityResolver;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TikTokShopOfficialPublishingProvider implements ProductPublishingProvider {
    private static final long[] READBACK_RETRY_DELAYS_MILLIS = {1000, 2000, 4000, 8000, 15000};
    private static final int READBACK_MAX_ATTEMPTS = READBACK_RETRY_DELAYS_MILLIS.length + 1;
    private static final Set<String> LISTING_CHECK_UNSUPPORTED_CREATE_FIELDS = Set.of(
            "save_mode", "category_version", "is_not_for_sale", "idempotency_key");
    private static final Set<String> ACCEPTED_REMOTE_PRODUCT_STATUSES = Set.of(
            "DRAFT");
    private final ShopAuthorizationService shopAuthorizationService;
    private final TikTokShopOfficialClient client;
    private final ProductCaptureSkuMapper skuMapper;
    private final ProductCaptureImageMapper imageMapper;
    private final MarketplaceImageDownloader imageDownloader;
    private final ObjectMapper objectMapper;
    private final TikTokProductVideoUploader videoUploader;
    private final TikTokProductDescription descriptionBuilder;
    private final TikTokProductAttributeResolver attributeResolver;
    private final TikTokProductSizeChart sizeChartBuilder;
    private final TikTokShopListingCapabilityResolver listingCapabilityResolver;

    public TikTokShopOfficialPublishingProvider(ShopAuthorizationService shopAuthorizationService,
                                                TikTokShopOfficialClient client,
                                                ProductCaptureSkuMapper skuMapper,
                                                ProductCaptureImageMapper imageMapper,
                                                MarketplaceImageDownloader imageDownloader,
                                                ObjectMapper objectMapper,
                                                TikTokProductVideoUploader videoUploader,
                                                TikTokProductDescription descriptionBuilder) {
        this(shopAuthorizationService, client, skuMapper, imageMapper, imageDownloader, objectMapper,
                videoUploader, descriptionBuilder, new TikTokProductSizeChart(client, imageDownloader, objectMapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TikTokShopOfficialPublishingProvider(ShopAuthorizationService shopAuthorizationService,
                                                TikTokShopOfficialClient client,
                                                ProductCaptureSkuMapper skuMapper,
                                                ProductCaptureImageMapper imageMapper,
                                                MarketplaceImageDownloader imageDownloader,
                                                ObjectMapper objectMapper,
                                                TikTokProductVideoUploader videoUploader,
                                                TikTokProductDescription descriptionBuilder,
                                                TikTokProductSizeChart sizeChartBuilder) {
        this.shopAuthorizationService = shopAuthorizationService;
        this.client = client;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.imageDownloader = imageDownloader;
        this.objectMapper = objectMapper;
        this.videoUploader = videoUploader;
        this.descriptionBuilder = descriptionBuilder;
        this.attributeResolver = new TikTokProductAttributeResolver(client, objectMapper);
        this.sizeChartBuilder = sizeChartBuilder;
        this.listingCapabilityResolver = new TikTokShopListingCapabilityResolver(client);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.ProductMainImageAssets mainImageAssets;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.ProductListingWarehouseService listingWarehouses;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yingdao.aicommerce.product.application.ProductPublicationSkuNaming publicationSkuNaming;

    @Override public String code() { return "TIKTOK_SHOP_OFFICIAL_202309"; }
    @Override public boolean dryRun() { return false; }
    @Override public boolean supports(String destinationPlatform) {
        return "TIKTOK_SHOP".equalsIgnoreCase(destinationPlatform)
                || "TIKTOK".equalsIgnoreCase(destinationPlatform);
    }

    @Override
    public PublishResult publish(ProductCapture capture, String destinationPlatform,
                                 String shopId, Map<String, Object> request,
                                 ExternalCreateBoundary externalCreateBoundary) {
        MarketplaceAccess.Connection connection = shopAuthorizationService.connectionForPublishing(
                capture.getOrganizationId(), shopId, MarketplacePlatform.TIKTOK_SHOP);
        // Recheck the authorized target when this queued upload actually runs. A client
        // supplied mode or an earlier publish-options response is not execution authority.
        var listingCapability = listingCapabilityResolver.resolve(connection);
        List<ProductCaptureSku> skus = skus(capture);
        if (!com.yingdao.aicommerce.product.application.ProductPublicationSkuNaming.style(request.get("merchantStyleCode")).isEmpty())
            skus = publicationSkuNaming.apply(capture, skus, request);
        List<ProductCaptureImage> images = images(capture);
        var styleGallery = mainImageAssets == null ? null : mainImageAssets.gallery(capture, request);
        rejectDryRunImages(images);
        int stock = requiredStock(request);
        String categoryId = requiredText(request, "categoryId",
                "TikTok Shop 发布前必须选择官方叶子类目");
        String warehouseId = text(request.get("warehouseId"));
        if (warehouseId == null) {
            var warehouseResponse = client.apiGet("/logistics/202309/warehouses",connection.tokens().accessToken(),
                    connection.shop().getShopCipher(),Map.of());
            var warehouseData = warehouseResponse.get("data") instanceof Map<?,?> m ? m : Map.of();
            var candidates = com.yingdao.aicommerce.shop.application.TikTokWarehouseSelection.candidates(
                    warehouseData.get("warehouses"),listingCapability);
            String configured = listingWarehouses == null ? "" : listingWarehouses.config(
                    capture.getOrganizationId(),shopId,capture.getDataModule()).warehouseId();
            warehouseId = String.valueOf(com.yingdao.aicommerce.shop.application.TikTokWarehouseSelection.choose(candidates,configured).get("id"));
        }
        Map<String, Object> inventoryRule = listingCapability.requireWarehouse(warehouseId);
        BigDecimal weightGrams = requiredPositiveDecimal(request, "weightGrams",
                "TikTok Shop 发布前必须填写包裹重量");
        BigDecimal length = requiredPositiveDecimal(request, "packageLengthCm",
                "TikTok Shop 发布前必须填写包裹长度");
        BigDecimal width = requiredPositiveDecimal(request, "packageWidthCm",
                "TikTok Shop 发布前必须填写包裹宽度");
        BigDecimal height = requiredPositiveDecimal(request, "packageHeightCm",
                "TikTok Shop 发布前必须填写包裹高度");
        buildSkus(capture, skus, request, warehouseId, stock, Map.of());
        String categoryVersion = requiresCategoryVersionV2(connection.shop().getRegion()) ? "v2"
                : fallback(text(request.get("categoryVersion")), "v1");
        if ("LOCAL_REPLICATION".equals(listingCapability.listingMethod())) {
            requireAvailableCrossBorderCategory(connection, categoryId, categoryVersion);
        }
        List<Map<String, Object>> productAttributes = attributeResolver.resolve(connection, capture,
                categoryId, categoryVersion, request.get("productAttributes"));
        Map<String, Object> categoryRules = client.apiGet(
                "/product/" + client.apiVersion() + "/categories/" + categoryId + "/rules",
                connection.tokens().accessToken(), connection.shop().getShopCipher(),
                Map.of("category_version", categoryVersion));
        TikTokProductSizeChart.PreparedSizeChart sizeChart = sizeChartBuilder.prepare(
                capture, images, connection, categoryRules, request);

        TikTokProductVideoUploader.UploadedVideo video = videoUploader.upload(capture, connection, text(request.get("idempotencyKey")));
        UploadedImages uploaded = uploadImages(connection, images, skus, styleGallery);
        if (uploaded.mainImages().isEmpty()) {
            throw conflict("TikTok Shop 发布至少需要一张处理完成的商品图");
        }

        TikTokProductDescription.PreparedDescription description = request.containsKey("listingTemplateSnapshot")
                ? descriptionBuilder.prepare(capture, images, connection,
                    com.yingdao.aicommerce.product.application.ProductListingTemplateService.publicationDescription(capture, request))
                : descriptionBuilder.prepare(capture, images, connection);
        Map<String, Object> body = mutableMap(request.get("tiktokShopPayload"));
        body.remove("size_chart");
        if (sizeChart.present()) body.put("size_chart", sizeChart.payload());
        body.remove("video");
        if (video.present()) body.put("video", Map.of("id", video.videoId()));
        body.put("save_mode", "AS_DRAFT");
        body.put("title", capture.getTitle());
        body.put("description", description.html());
        body.put("category_id", categoryId);
        body.put("category_version", categoryVersion);
        body.put("main_images", uploaded.mainImages());
        body.put("package_weight", packageWeight(weightGrams));
        body.put("package_dimensions", packageDimensions(length, width, height));
        body.put("skus", buildSkus(capture, skus, request, warehouseId, stock,
                uploaded.attributeImageBySkuKey()));
        body.remove("product_attributes");
        putOptional(body, "product_attributes", productAttributes);
        putOptional(body, "brand_id", text(request.get("brandId")));
        putOptional(body, "manufacturer_ids", listValue(request.get("manufacturerIds")));
        putOptional(body, "responsible_person_ids", listValue(request.get("responsiblePersonIds")));
        putOptional(body, "delivery_option_ids", listValue(request.get("deliveryOptionIds")));
        putOptional(body, "idempotency_key", text(request.get("idempotencyKey")));

        String productBasePath = "/product/" + client.apiVersion() + "/products";
        Map<String, Object> listingCheckResponse = client.apiPost(
                productBasePath + "/listing_check", connection, Map.of(), listingCheckPayload(body));
        verifyListingCheck(listingCheckResponse);

        // The listing check is retryable validation. Persist the irreversible boundary only after
        // it passes and immediately before Create Product can reach TikTok Shop.
        if (styleGallery != null) mainImageAssets.verifyCurrentGallery(capture, request, styleGallery);
        externalCreateBoundary.enter();
        Map<String, Object> response = client.apiPost(productBasePath, connection, Map.of(), body);
        String productId = nestedText(response, "data", "product_id");
        if (productId == null) productId = nestedText(response, "data", "id");
        if (productId == null) throw conflict("TikTok Shop 创建商品响应缺少 product_id");

        ReadbackResult readback = readbackWithRetry(
                connection, productBasePath, productId, body, response, Thread::sleep);
        Map<String, Object> readbackResponse = readback.response();
        ReadbackVerification verification = readback.verification();
        rejectCreateWarnings(productId, response);

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("platform", "TIKTOK_SHOP");
        receipt.put("listingMethod", listingCapability.listingMethod());
        receipt.put("inventoryWarehouseId", warehouseId);
        if (listingCapability.requestId() != null) receipt.put("listingRulesRequestId", listingCapability.requestId());
        if (!inventoryRule.isEmpty()) {
            receipt.put("inventoryRules", listingCapability.inventoryRules());
            receipt.put("inventoryWarehouseId", warehouseId);
            receipt.put("inventoryAllocationMode", inventoryRule.get("allocation_mode"));
        }
        receipt.put("apiVersion", client.apiVersion());
        receipt.put("externalShopId", connection.shop().getExternalShopId());
        receipt.put("productId", productId);
        receipt.put("imageCount", uploaded.uploadedCount());
        receipt.put("mainImageCount", uploaded.mainImages().size());
        receipt.put("descriptionImageCount", description.imageCount());
        if (sizeChart.present()) {
            receipt.put("sizeChart", sizeChart.payload());
            receipt.put("sizeChartSourceType", sizeChart.sourceType());
            if (sizeChart.sourceImageId() != null) receipt.put("sizeChartSourceImageId", sizeChart.sourceImageId());
        }
        receipt.put("attributeImageCount", uploaded.attributeImageBySkuKey().values().stream()
                .distinct().count());
        receipt.put("skuCount", Math.max(1, skus.size()));
        receipt.put("requestId", text(response.get("request_id")));
        receipt.put("listingCheckRequestId", text(listingCheckResponse.get("request_id")));
        receipt.put("readbackRequestId", text(readbackResponse.get("request_id")));
        receipt.put("verification", "CONFIRMED_BY_GET_PRODUCT");
        receipt.put("remoteStatus", verification.status());
        receipt.put("remoteSkuIds", verification.skuIdsBySellerSku());
        if (video.present()) {
            receipt.put("videoId", video.videoId());
            receipt.put("sourceVideoId", video.sourceVideoId());
            receipt.put("sourceVideoCount", video.sourceCount());
            receipt.put("videoCount", 1);
            receipt.put("videoVerification", "CONFIRMED_BY_GET_PRODUCT");
        }
        return new PublishResult(productId, receipt);
    }

    void requireAvailableCrossBorderCategory(MarketplaceAccess.Connection connection,
                                             String categoryId, String categoryVersion) {
        Map<String, Object> response;
        try {
            response = client.apiGet("/product/" + client.apiVersion() + "/categories",
                    connection.tokens().accessToken(), connection.shop().getShopCipher(),
                    Map.of("locale", "en-US", "category_version", categoryVersion));
        } catch (RuntimeException exception) {
            BusinessException failure = new BusinessException(HttpStatus.BAD_GATEWAY,
                    "读取目标跨境店铺类目权限失败，请稍后重试；尚未提交商品");
            failure.initCause(exception);
            throw failure;
        }
        Map<String, Map<String, Object>> categories = new LinkedHashMap<>();
        for (Map<String, Object> category : objectList(objectValue(response == null ? null : response.get("data")).get("categories"))) {
            String id = text(category.get("id"));
            if (id != null && categories.putIfAbsent(id, category) != null) {
                throw conflict("目标跨境店铺返回了重复类目信息，请重新读取类目后重试；尚未提交商品");
            }
        }
        Map<String, Object> selected = categories.get(categoryId);
        if (selected == null || !Boolean.TRUE.equals(selected.get("is_leaf"))) {
            throw conflict("所选类目不是目标跨境店铺返回的有效叶子类目，请切换店铺或重新读取类目；尚未提交商品");
        }
        String currentId = categoryId;
        Set<String> visited = new HashSet<>();
        while (currentId != null && !"0".equals(currentId)) {
            Map<String, Object> category = categories.get(currentId);
            if (category == null || !visited.add(currentId)) {
                throw conflict("目标跨境店铺类目层级信息不完整，请重新读取类目后重试；尚未提交商品");
            }
            Object rawStatuses = category.get("permission_statuses");
            if (!(rawStatuses instanceof List<?> statuses) || statuses.isEmpty()
                    || !statuses.stream().allMatch("AVAILABLE"::equals)) {
                throw conflict("当前跨境店铺未开通或尚未确认所选商品类目“"
                        + fallback(text(selected.get("local_name")), fallback(text(selected.get("name")), categoryId))
                        + "”的发布权限，请切换已开通该类目的店铺，或开通后重试；尚未提交商品");
            }
            currentId = text(category.get("parent_id"));
            if (currentId == null) {
                throw conflict("目标跨境店铺类目层级信息不完整，请重新读取类目后重试；尚未提交商品");
            }
        }
    }

    ReadbackResult readbackWithRetry(MarketplaceAccess.Connection connection,
                                     String productBasePath,
                                     String productId,
                                     Map<String, Object> createPayload,
                                     Map<String, Object> createResponse,
                                     ReadbackSleeper sleeper) {
        Exception lastRetryableFailure = null;
        // Ask explicitly for the version we just saved; the default returns the
        // latest product information and may describe a different version.
        Map<String, Object> query = "AS_DRAFT".equals(createPayload.get("save_mode"))
                ? Map.of("return_draft_version", true) : Map.of();
        for (int attempt = 1; attempt <= READBACK_MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> response = client.apiGet(productBasePath + "/" + productId,
                        connection.tokens().accessToken(), connection.shop().getShopCipher(), query);
                ReadbackVerification verification = verifyReadback(
                        productId, createPayload, createResponse, response);
                return new ReadbackResult(response, verification);
            } catch (BusinessException mismatch) {
                lastRetryableFailure = mismatch;
            } catch (Exception exception) {
                if (!readbackRetryable(exception) || Thread.currentThread().isInterrupted()) {
                    throw readbackFailure(productId, "官方 Get Product 请求失败：" + safeMessage(exception));
                }
                lastRetryableFailure = exception;
            }
            if (attempt < READBACK_MAX_ATTEMPTS) {
                try {
                    sleeper.sleep(READBACK_RETRY_DELAYS_MILLIS[attempt - 1]);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw readbackFailure(productId, "等待远端数据一致时线程被中断");
                }
            }
        }
        String detail = lastRetryableFailure instanceof BusinessException
                ? lastRetryableFailure.getMessage()
                : "多次 Get Product 仍未找到商品：" + safeMessage(lastRetryableFailure);
        throw readbackFailure(productId, "共尝试 " + READBACK_MAX_ATTEMPTS + " 次仍无法确认：" + detail);
    }

    Map<String, Object> listingCheckPayload(Map<String, Object> createPayload) {
        Map<String, Object> result = new LinkedHashMap<>(createPayload);
        LISTING_CHECK_UNSUPPORTED_CREATE_FIELDS.forEach(result::remove);
        return result;
    }

    void verifyListingCheck(Map<String, Object> response) {
        Map<String, Object> data = objectValue(response.get("data"));
        String checkResult = text(data.get("check_result"));
        Object rawFailures = data.get("fail_reasons");
        List<String> failures = issueMessages(rawFailures);
        boolean hasFailureReasons = meaningful(rawFailures);
        Object warnings = data.get("warnings");
        boolean hasBlockingWarnings = meaningful(warnings);
        if (!"PASS".equalsIgnoreCase(fallback(checkResult, ""))
                || hasFailureReasons || hasBlockingWarnings) {
            List<String> issues = new ArrayList<>(failures);
            if (hasFailureReasons && failures.isEmpty()) {
                issues.add("官方返回未识别的 fail_reason");
            }
            if (hasBlockingWarnings) {
                List<String> warningMessages = issueMessages(warnings);
                issues.add(warningMessages.isEmpty()
                        ? "官方返回未识别的 blocker warning" : String.join("；", warningMessages));
            }
            if (issues.isEmpty()) {
                issues.add("check_result=" + fallback(checkResult, "缺失"));
            }
            throw conflict("TikTok Shop 商品预检未通过，未创建商品："
                    + truncate(String.join("；", issues), 800));
        }
    }

    ReadbackVerification verifyReadback(String productId, Map<String, Object> createPayload,
                                        Map<String, Object> createResponse,
                                        Map<String, Object> readbackResponse) {
        Map<String, Object> data = objectValue(readbackResponse.get("data"));
        String remoteProductId = text(data.get("id"));
        if (!productId.equals(remoteProductId)) {
            throw readbackFailure(productId, "Get Product 返回的 data.id 不匹配");
        }
        List<Map<String, Object>> expectedSkus = objectList(createPayload.get("skus"));
        List<Map<String, Object>> remoteSkus = objectList(data.get("skus"));
        if (expectedSkus.isEmpty() || remoteSkus.size() != expectedSkus.size()) {
            throw readbackFailure(productId, "SKU 数量不一致，提交 " + expectedSkus.size()
                    + " 个，远端回读 " + remoteSkus.size() + " 个");
        }

        Map<String, Map<String, Object>> remoteBySellerSku = indexBySellerSku(
                productId, remoteSkus, "Get Product");
        Map<String, String> skuIdsBySellerSku = new LinkedHashMap<>();
        Set<String> expectedSellerSkus = new HashSet<>();
        for (Map<String, Object> expectedSku : expectedSkus) {
            String sellerSku = text(expectedSku.get("seller_sku"));
            if (sellerSku == null || !expectedSellerSkus.add(sellerSku)) {
                throw readbackFailure(productId, "提交的 seller_sku 缺失或重复，无法建立 SKU 映射");
            }
            Map<String, Object> remoteSku = remoteBySellerSku.get(sellerSku);
            if (remoteSku == null) {
                throw readbackFailure(productId, "远端缺少 seller_sku=" + truncate(sellerSku, 80));
            }
            String remoteSkuId = text(remoteSku.get("id"));
            if (remoteSkuId == null) {
                throw readbackFailure(productId, "seller_sku=" + truncate(sellerSku, 80)
                        + " 的远端 SKU ID 缺失");
            }
            verifySalesAttributes(productId, sellerSku,
                    objectList(expectedSku.get("sales_attributes")),
                    objectList(remoteSku.get("sales_attributes")));
            skuIdsBySellerSku.put(sellerSku, remoteSkuId);
        }

        verifyCreateResponseSkuMapping(productId, createResponse, skuIdsBySellerSku);
        String status = fallback(text(data.get("status")), text(data.get("product_status")));
        if (status == null) {
            throw readbackFailure(productId, "Get Product 响应缺少 status/product_status");
        }
        verifyRemoteStatus(productId, status);
        String expectedVideoId = nestedText(createPayload, "video", "id");
        if (expectedVideoId != null && !expectedVideoId.equals(nestedText(data, "video", "id"))) {
            throw readbackFailure(productId, "Get Product 未确认已提交的视频 ID，视频缺失或不一致；禁止盲目重试创建商品");
        }
        return new ReadbackVerification(
                status, Map.copyOf(skuIdsBySellerSku));
    }

    void verifyRemoteStatus(String productId, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (ACCEPTED_REMOTE_PRODUCT_STATUSES.contains(normalized)) return;
        throw readbackFailure(productId, "Get Product 未返回可接受的刊登状态 status="
                + truncate(status, 80) + "；请人工核查该商品，禁止盲目重试");
    }

    void rejectCreateWarnings(String productId, Map<String, Object> createResponse) {
        Object warnings = objectValue(createResponse.get("data")).get("warnings");
        if (!meaningful(warnings)) return;
        List<String> messages = issueMessages(warnings);
        String detail = messages.isEmpty()
                ? "官方返回未识别的 critical warning"
                : String.join("；", messages);
        throw remoteCreatedFailure(productId, "Create Product 返回 critical warning，虽然 Get Product 已确认"
                + "远端商品存在，仍不会把本次发布标记为成功：" + truncate(detail, 600));
    }

    private void verifySalesAttributes(String productId, String sellerSku,
                                       List<Map<String, Object>> expected,
                                       List<Map<String, Object>> remote) {
        if (remote.size() != expected.size()) {
            throw readbackFailure(productId, "seller_sku=" + truncate(sellerSku, 80)
                    + " 的销售规格层数不一致");
        }
        List<Map<String, Object>> unmatched = new ArrayList<>(remote);
        for (Map<String, Object> expectedAttribute : expected) {
            Map<String, Object> matched = unmatched.stream()
                    .filter(candidate -> sameAttribute(expectedAttribute, candidate))
                    .findFirst().orElse(null);
            if (matched == null) {
                throw readbackFailure(productId, "seller_sku=" + truncate(sellerSku, 80)
                        + " 的销售规格或规格值映射不一致");
            }
            String expectedImageUri = nestedText(expectedAttribute, "sku_img", "uri");
            if (expectedImageUri != null
                    && !expectedImageUri.equals(nestedText(matched, "sku_img", "uri"))) {
                throw readbackFailure(productId, "seller_sku=" + truncate(sellerSku, 80)
                        + " 的规格图片 URI 映射不一致");
            }
            unmatched.remove(matched);
        }
    }

    private boolean sameAttribute(Map<String, Object> expected, Map<String, Object> remote) {
        String expectedId = text(expected.get("id"));
        String expectedName = text(expected.get("name"));
        String expectedValueId = text(expected.get("value_id"));
        String expectedValueName = text(expected.get("value_name"));
        boolean sameDimension = expectedId != null
                ? expectedId.equals(text(remote.get("id")))
                : expectedName != null && expectedName.equals(text(remote.get("name")));
        boolean sameValue = expectedValueId != null
                ? expectedValueId.equals(text(remote.get("value_id")))
                : expectedValueName != null && expectedValueName.equals(text(remote.get("value_name")));
        return sameDimension && sameValue;
    }

    private void verifyCreateResponseSkuMapping(String productId, Map<String, Object> createResponse,
                                                Map<String, String> readbackSkuIds) {
        List<Map<String, Object>> createdSkus = objectList(objectValue(createResponse.get("data")).get("skus"));
        if (createdSkus.isEmpty()) return;
        if (createdSkus.size() != readbackSkuIds.size()) {
            throw readbackFailure(productId, "Create Product 响应与 Get Product 的 SKU 数量不一致");
        }
        for (Map<String, Object> createdSku : createdSkus) {
            String sellerSku = text(createdSku.get("seller_sku"));
            String skuId = text(createdSku.get("id"));
            if (sellerSku == null || skuId == null || !skuId.equals(readbackSkuIds.get(sellerSku))) {
                throw readbackFailure(productId, "Create Product 响应与 Get Product 的 SKU ID 映射不一致");
            }
        }
    }

    private Map<String, Map<String, Object>> indexBySellerSku(
            String productId, List<Map<String, Object>> skus, String source) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> sku : skus) {
            String sellerSku = text(sku.get("seller_sku"));
            if (sellerSku == null || result.putIfAbsent(sellerSku, sku) != null) {
                throw readbackFailure(productId, source + " 返回的 seller_sku 缺失或重复");
            }
        }
        return result;
    }

    private UploadedImages uploadImages(MarketplaceAccess.Connection connection,
                                         List<ProductCaptureImage> images,
                                         List<ProductCaptureSku> skus) {
        return uploadImages(connection, images, skus, null);
    }

    private UploadedImages uploadImages(MarketplaceAccess.Connection connection,
                                         List<ProductCaptureImage> images,
                                         List<ProductCaptureSku> skus,
                                         List<ProductCaptureImage> styleGallery) {
        List<ProductCaptureImage> galleryCandidates = styleGallery == null ? images.stream()
                .filter(image -> !"VARIANT".equalsIgnoreCase(image.getRoleName())
                        && !"DETAIL".equalsIgnoreCase(image.getRoleName()))
                .toList() : styleGallery;
        if (galleryCandidates.isEmpty()) galleryCandidates = images.stream()
                .filter(image -> !"DETAIL".equalsIgnoreCase(image.getRoleName())).toList();
        List<Map<String, Object>> mainImages = new ArrayList<>();
        Set<String> uploadedMainUrls = new HashSet<>();
        int[] index = {0};
        for (ProductCaptureImage image : galleryCandidates) {
            if (mainImages.size() >= 9) break;
            String url = publishUrl(image);
            if (url == null || !uploadedMainUrls.add(url)) continue;
            mainImages.add(Map.of("uri", uploadImage(connection, url, image, "MAIN_IMAGE", ++index[0])));
        }

        Map<String, ProductCaptureImage> captureImageBySourceUrl = new LinkedHashMap<>();
        for (ProductCaptureImage image : images) {
            if (image.getOriginalUrl() != null && !image.getOriginalUrl().isBlank()) {
                captureImageBySourceUrl.putIfAbsent(image.getOriginalUrl(), image);
            }
            if (image.getProcessedUrl() != null && !image.getProcessedUrl().isBlank()) {
                captureImageBySourceUrl.putIfAbsent(image.getProcessedUrl(), image);
            }
        }
        Map<String, String> attributeUriByProcessedUrl = new LinkedHashMap<>();
        Map<String, String> attributeImageBySkuKey = new LinkedHashMap<>();
        if (skus.size() > 1) {
            for (ProductCaptureSku sku : skus) {
                if (sku.getImageUrl() == null || sku.getImageUrl().isBlank()) continue;
                ProductCaptureImage image = captureImageBySourceUrl.get(sku.getImageUrl());
                String processedUrl = image == null ? null : publishUrl(image);
                if (processedUrl == null) continue;
                String uri = attributeUriByProcessedUrl.get(processedUrl);
                if (uri == null) {
                    uri = uploadImage(connection, processedUrl, image, "ATTRIBUTE_IMAGE", ++index[0]);
                    attributeUriByProcessedUrl.put(processedUrl, uri);
                }
                attributeImageBySkuKey.put(sku.getSkuKey(), uri);
            }
        }
        return new UploadedImages(List.copyOf(mainImages), Map.copyOf(attributeImageBySkuKey), index[0]);
    }

    private String uploadImage(MarketplaceAccess.Connection connection, String url,
                               ProductCaptureImage image, String useCase, int index) {
        MarketplaceImageDownloader.ImageBinary binary = imageDownloader.downloadProductImage(url, image);
        String path = "/product/" + client.apiVersion() + "/images/upload";
        Map<String, Object> response = client.apiMultipart(path, connection,
                Map.of(), "product-" + index + binary.extension(), binary.contentType(),
                binary.bytes(), Map.of("use_case", useCase));
        String uri = nestedText(response, "data", "uri");
        if (uri == null) uri = nestedText(response, "data", "image", "uri");
        if (uri == null) throw conflict("TikTok Shop 图片上传响应缺少 uri");
        return uri;
    }

    List<Map<String, Object>> buildSkus(ProductCapture capture, List<ProductCaptureSku> skus,
                                        Map<String, Object> request, String warehouseId,
                                        int defaultStock,
                                        Map<String, String> attributeImageBySkuKey) {
        List<ProductCaptureSku> values = skus;
        if (values.isEmpty()) values = List.of();
        Map<String, Integer> stockBySku = skuStockMap(request.get("skuStocks"), values);
        Map<String, Object> salesBySku = mutableMap(request.get("salesAttributesBySku"));
        List<List<Map<String, Object>>> salesMatrix = new ArrayList<>();
        boolean hasExplicitSalesMapping = false;
        for (ProductCaptureSku sku : values) {
            List<?> supplied = listValue(salesBySku.get(sku.getSkuKey()));
            boolean explicitlyMapped = !supplied.isEmpty();
            hasExplicitSalesMapping |= explicitlyMapped;
            if (!explicitlyMapped) supplied = sourceSalesAttributes(sku);
            List<Map<String, Object>> normalized = normalizeSalesAttributes(supplied);
            if (explicitlyMapped && normalized.isEmpty()) {
                throw conflict("TikTok Shop SKU “" + fallback(sku.getVariationName(), sku.getSkuKey())
                        + "” 的 sales_attributes 映射格式不正确");
            }
            if (normalized.isEmpty()) normalized = customSalesAttributes(sku);
            salesMatrix.add(normalized);
        }
        if (!hasExplicitSalesMapping) orderSourceSalesDimensions(salesMatrix);
        validateSalesAttributeMatrix(values, salesMatrix);
        attachAttributeImages(values, salesMatrix, attributeImageBySkuKey);
        List<Map<String, Object>> result = new ArrayList<>();
        if (values.isEmpty()) {
            BigDecimal price = capture.getPriceMin();
            if (price == null || price.signum() <= 0) throw conflict("TikTok Shop 发布价格不能为空");
            result.add(Map.of(
                    "seller_sku", "capture-" + capture.getId().substring(0, 8),
                    "price", Map.of("amount", com.yingdao.aicommerce.product.application.PublishExchangeRates.convert(price, request).toPlainString(), "currency", com.yingdao.aicommerce.product.application.PublishExchangeRates.targetCurrency(requiredCurrency(capture), request)),
                    "inventory", List.of(inventory(warehouseId, defaultStock))
            ));
            return result;
        }
        for (int index = 0; index < values.size(); index++) {
            ProductCaptureSku sku = values.get(index);
            if (sku.getPrice() == null || sku.getPrice().signum() <= 0) {
                throw conflict("SKU “" + fallback(sku.getVariationName(), sku.getSkuKey()) + "” 缺少发布价格");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("seller_sku", sellerSku(sku));
            value.put("price", Map.of("amount", com.yingdao.aicommerce.product.application.PublishExchangeRates.convert(sku.getPrice(), request).toPlainString(),
                    "currency", com.yingdao.aicommerce.product.application.PublishExchangeRates.targetCurrency(requiredCurrency(capture), request)));
            value.put("inventory", List.of(inventory(
                    warehouseId, stockBySku.getOrDefault(sku.getSkuKey(), defaultStock))));
            List<Map<String, Object>> sales = salesMatrix.get(index);
            if (values.size() > 1 && sales.isEmpty()) {
                throw conflict("多 SKU 商品缺少可用于 TikTok Shop 的销售规格；缺少 SKU “"
                        + fallback(sku.getVariationName(), sku.getSkuKey()) + "” 的 sales_attributes");
            }
            if (!sales.isEmpty()) value.put("sales_attributes", sales);
            result.add(value);
        }
        return TikTokSkuOrdering.order(result);
    }

    private List<?> sourceSalesAttributes(ProductCaptureSku sku) {
        if (sku.getSourceJson() == null || sku.getSourceJson().isBlank()) return List.of();
        try {
            Map<String, Object> source = objectMapper.readValue(sku.getSourceJson(), new TypeReference<>() {});
            for (String key : List.of("sales_attributes", "salesAttributes", "sku_sales_attrs", "skuSalesAttrs")) {
                List<?> value = listValue(source.get(key));
                if (!value.isEmpty()) return value;
            }
        } catch (Exception ignored) {
            // A user-confirmed destination mapping can still be supplied in salesAttributesBySku.
        }
        return List.of();
    }

    private List<Map<String, Object>> normalizeSalesAttributes(List<?> values) {
        if (values.size() > 3) throw conflict("TikTok Shop 每个 SKU 最多支持 3 层销售规格");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof Map<?, ?> raw)) return List.of();
            Map<String, Object> value = new LinkedHashMap<>();
            putFirstText(raw, value, "id", "id", "attribute_id", "attributeId");
            putFirstText(raw, value, "name", "name", "attribute_name", "attributeName");
            putFirstText(raw, value, "value_id", "value_id", "valueId");
            putFirstText(raw, value, "value_name", "value_name", "valueName", "value");
            Object skuImage = raw.get("sku_img");
            if (skuImage instanceof Map<?, ?> image && text(image.get("uri")) != null) {
                value.put("sku_img", Map.of("uri", text(image.get("uri"))));
            }
            if ((!value.containsKey("id") && !value.containsKey("name"))
                    || (!value.containsKey("value_id") && !value.containsKey("value_name"))) {
                return List.of();
            }
            String customName = text(value.get("name"));
            String customValue = text(value.get("value_name"));
            if (customName != null && customName.length() > 20) {
                throw conflict("TikTok Shop 自定义销售规格名称不能超过 20 个字符");
            }
            if (customValue != null && customValue.length() > 50) {
                throw conflict("TikTok Shop 自定义销售规格值不能超过 50 个字符");
            }
            result.add(value);
        }
        ensureUniqueSalesDimensions(result);
        return result;
    }

    private List<Map<String, Object>> customSalesAttributes(ProductCaptureSku sku) {
        if (sku.getAttributesJson() == null || sku.getAttributesJson().isBlank()) return List.of();
        try {
            Map<String, Object> attributes = objectMapper.readValue(
                    sku.getAttributesJson(), new TypeReference<>() {});
            if (attributes.size() > 3) {
                throw conflict("TikTok Shop 每个 SKU 最多支持 3 层销售规格");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            attributes.forEach((name, rawValue) -> {
                String value = text(rawValue);
                if (name != null && !name.isBlank() && value != null) {
                    result.add(Map.of(
                            "name", truncate(name, 20),
                            "value_name", truncate(value, 50)));
                }
            });
            ensureUniqueSalesDimensions(result);
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void orderSourceSalesDimensions(List<List<Map<String, Object>>> matrix) {
        if (matrix.isEmpty() || matrix.getFirst().isEmpty()) return;
        // MySQL JSON objects do not preserve the source variant order. TikTok attaches
        // variant images to the first sales dimension, so color must precede size.
        List<Map<String, Object>> primary = new ArrayList<>(matrix.getFirst());
        primary.sort(java.util.Comparator.comparingInt(attribute -> isColorDimension(attribute) ? 0 : 1));
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < primary.size(); index++) order.put(salesAttributeIdentity(primary.get(index)), index);
        for (List<Map<String, Object>> row : matrix) {
            if (!row.isEmpty()) row.sort(java.util.Comparator.comparingInt(attribute -> order.getOrDefault(salesAttributeIdentity(attribute), Integer.MAX_VALUE)));
        }
    }

    private boolean isColorDimension(Map<String, Object> attribute) {
        String name = String.valueOf(attribute.getOrDefault("name", ""));
        return Set.of("color", "colour", "colors", "colours", "warna", "颜色", "顏色", "色")
                .contains(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
                        .trim().toLowerCase(Locale.ROOT));
    }

    private void validateSalesAttributeMatrix(List<ProductCaptureSku> skus,
                                              List<List<Map<String, Object>>> matrix) {
        if (skus.size() <= 1) return;
        List<String> expected = null;
        for (int index = 0; index < matrix.size(); index++) {
            List<Map<String, Object>> attributes = matrix.get(index);
            if (attributes.isEmpty()) continue;
            List<String> dimensions = attributes.stream().map(this::salesAttributeIdentity).toList();
            if (expected == null) expected = dimensions;
            else if (!expected.equals(dimensions)) {
                throw conflict("TikTok Shop 要求每个 SKU 使用相同且同顺序的销售规格；SKU “"
                        + fallback(skus.get(index).getVariationName(), skus.get(index).getSkuKey())
                        + "” 的规格结构不一致");
            }
        }
    }

    private void attachAttributeImages(List<ProductCaptureSku> skus,
                                       List<List<Map<String, Object>>> matrix,
                                       Map<String, String> imageBySkuKey) {
        if (skus.size() <= 1 || matrix.stream().anyMatch(List::isEmpty)) return;
        Map<String, String> uriByPrimaryValue = new LinkedHashMap<>();
        boolean completeAndConsistent = true;
        for (int index = 0; index < skus.size(); index++) {
            String uri = imageBySkuKey.get(skus.get(index).getSkuKey());
            String primaryValue = salesAttributeValueIdentity(matrix.get(index).getFirst());
            if (uri == null || primaryValue == null) {
                completeAndConsistent = false;
                break;
            }
            String existing = uriByPrimaryValue.putIfAbsent(primaryValue, uri);
            if (existing != null && !existing.equals(uri)) {
                completeAndConsistent = false;
                break;
            }
        }
        if (!completeAndConsistent) return;
        for (int index = 0; index < skus.size(); index++) {
            Map<String, Object> primary = new LinkedHashMap<>(matrix.get(index).getFirst());
            primary.put("sku_img", Map.of("uri", imageBySkuKey.get(skus.get(index).getSkuKey())));
            matrix.get(index).set(0, primary);
        }
    }

    private String salesAttributeIdentity(Map<String, Object> value) {
        return value.containsKey("id") ? "id:" + value.get("id") : "name:" + value.get("name");
    }

    private void ensureUniqueSalesDimensions(List<Map<String, Object>> attributes) {
        Set<String> dimensions = new HashSet<>();
        for (Map<String, Object> attribute : attributes) {
            if (!dimensions.add(salesAttributeIdentity(attribute))) {
                throw conflict("TikTok Shop 同一个 SKU 不能重复使用相同的销售规格");
            }
        }
    }

    private String salesAttributeValueIdentity(Map<String, Object> value) {
        if (value.containsKey("value_id")) return "id:" + value.get("value_id");
        return value.containsKey("value_name") ? "name:" + value.get("value_name") : null;
    }

    private void putFirstText(Map<?, ?> source, Map<String, Object> target,
                              String destination, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (value == null) continue;
            target.put(destination, value);
            return;
        }
    }

    private String sellerSku(ProductCaptureSku sku) {
        String value = fallback(sku.getSellerSku(), sku.getSkuKey());
        if (value == null || value.isBlank() || value.length() > 50 || value.matches(".*\\s+.*")) {
            throw conflict("TikTok Shop seller_sku 必须为 1-50 个不含空格的字符；请修正 SKU “"
                    + fallback(sku.getVariationName(), sku.getSkuKey()) + "”");
        }
        return value;
    }

    private List<ProductCaptureSku> skus(ProductCapture capture) {
        return skuMapper.selectList(new LambdaQueryWrapper<ProductCaptureSku>()
                .eq(ProductCaptureSku::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureSku::getCaptureId, capture.getId())
                .orderByAsc(ProductCaptureSku::getSortOrder));
    }

    private List<ProductCaptureImage> images(ProductCapture capture) {
        return imageMapper.selectList(new LambdaQueryWrapper<ProductCaptureImage>()
                .eq(ProductCaptureImage::getOrganizationId, capture.getOrganizationId())
                .eq(ProductCaptureImage::getCaptureId, capture.getId())
                .eq(ProductCaptureImage::getStatus, "PROCESSED")
                .orderByAsc(ProductCaptureImage::getSortOrder));
    }

    private void rejectDryRunImages(List<ProductCaptureImage> images) {
        // Original measurement charts intentionally retain their pixels. Ordinary
        // product images must still pass the live-processing guard.
        if (images.stream().anyMatch(image -> !"SIZE_CHART".equals(image.getRoleName())
                && image.getProcessedUrl() != null
                && image.getProcessedUrl().equals(image.getOriginalUrl()))) {
            throw conflict("商品图片仍是去水印 Dry Run 占位结果，禁止提交到真实 TikTok Shop 店铺");
        }
    }

    private String publishUrl(ProductCaptureImage image) {
        return image.getProcessedUrl() == null || image.getProcessedUrl().isBlank()
                ? null : image.getProcessedUrl();
    }

    int requiredStock(Map<String, Object> request) {
        return strictStock(request.get("publishStock"),
                "发布库存必须是 0 到 99999 的整数；0 库存不会向 TikTok Shop 发送 quantity");
    }

    private String requiredText(Map<String, Object> request, String key, String message) {
        String value = text(request.get(key));
        if (value == null || value.isBlank()) throw conflict(message);
        return value;
    }

    private BigDecimal requiredPositiveDecimal(Map<String, Object> request, String key, String message) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(request.get(key)));
            if (value.signum() <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception exception) {
            throw conflict(message);
        }
    }

    private String wholeCentimeters(BigDecimal value) {
        return value.setScale(0, RoundingMode.UP).toPlainString();
    }

    Map<String, String> packageWeight(BigDecimal weightGrams) {
        return Map.of(
                "value", weightGrams.divide(BigDecimal.valueOf(1000), 3, RoundingMode.UP)
                        .stripTrailingZeros().toPlainString(),
                "unit", "KILOGRAM");
    }

    Map<String, String> packageDimensions(BigDecimal length, BigDecimal width, BigDecimal height) {
        return Map.of(
                "length", wholeCentimeters(length),
                "width", wholeCentimeters(width),
                "height", wholeCentimeters(height),
                "unit", "CENTIMETER");
    }

    private String requiredCurrency(ProductCapture capture) {
        if (capture.getCurrency() == null || capture.getCurrency().isBlank()) {
            throw conflict("TikTok Shop 发布币种不能为空");
        }
        return capture.getCurrency().trim().toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Map<String, Object> objectValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            result.add(item instanceof Map<?, ?> ? objectValue(item) : Map.of());
        }
        return result;
    }

    private List<String> issueMessages(Object value) {
        List<String> result = new ArrayList<>();
        collectIssueMessages(value, result);
        return result.stream().filter(message -> !message.isBlank()).limit(8).toList();
    }

    private void collectIssueMessages(Object value, List<String> result) {
        if (value instanceof List<?> list) {
            for (Object item : list) collectIssueMessages(item, result);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            String message = text(map.get("message"));
            String code = text(map.get("code"));
            if (message != null) {
                result.add((code == null ? "" : "[" + truncate(code, 40) + "] ")
                        + safeExternalText(message, 240));
            }
            return;
        }
        String message = text(value);
        if (message != null) result.add(safeExternalText(message, 240));
    }

    private boolean meaningful(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence text) return !text.toString().isBlank();
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(this::meaningful);
        if (value instanceof List<?> list) return list.stream().anyMatch(this::meaningful);
        if (value instanceof Boolean bool) return bool;
        return true;
    }

    private Map<String, Integer> skuStockMap(Object value, List<ProductCaptureSku> skus) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(value instanceof Map<?, ?> map)) {
            throw conflict("TikTok Shop SKU 库存必须按 skuKey 提交为对象");
        }
        Set<String> knownSkuKeys = new HashSet<>();
        for (ProductCaptureSku sku : skus) knownSkuKeys.add(sku.getSkuKey());
        map.forEach((key, raw) -> {
            String skuKey = String.valueOf(key);
            if (!knownSkuKeys.contains(skuKey)) {
                throw conflict("TikTok Shop SKU 库存包含未知 skuKey：" + truncate(skuKey, 80));
            }
            result.put(skuKey, strictStock(raw,
                    "SKU “" + truncate(skuKey, 80) + "” 的发布库存必须是 0 到 99999 的整数"));
        });
        return result;
    }

    private int strictStock(Object raw, String message) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw));
            int result = value.intValueExact();
            if (result < 0 || result > 99_999) throw new ArithmeticException();
            return result;
        } catch (Exception exception) {
            throw conflict(message);
        }
    }

    private Map<String, Object> inventory(String warehouseId, int stock) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warehouse_id", warehouseId);
        if (stock > 0) result.put("quantity", stock);
        return result;
    }

    private List<?> listValue(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private void putOptional(Map<String, Object> target, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) target.put(key, text);
        if (value instanceof List<?> list && !list.isEmpty()) target.put(key, list);
    }

    private String nestedText(Map<String, Object> map, String... path) {
        Object value = map;
        for (String key : path) {
            if (!(value instanceof Map<?, ?> source)) return null;
            value = source.get(key);
        }
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int max) {
        String result = value == null ? "" : value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    private String safeMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (message == null || message.isBlank()) return "未知错误";
        return safeExternalText(message, 300);
    }

    private String safeExternalText(String value, int max) {
        String cleaned = value == null ? "" : value
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("(?i)(access_token|refresh_token|app_secret|x-tts-access-token)"
                        + "\\s*[:=]\\s*[^,}\\s]+", "$1=REDACTED");
        return truncate(cleaned, max);
    }

    private boolean readbackRetryable(Exception exception) {
        if (readbackNotFound(exception)) return true;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return false;
            if (cause instanceof java.io.IOException) return true;
            String message = String.valueOf(cause.getMessage());
            if (message.matches("(?is).*(HTTP (408|425|429|5[0-9]{2})|36009003|timeout|timed out|network|connection reset|超时|连接中断).*") ) return true;
        }
        return false;
    }

    private boolean readbackNotFound(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("12019150")
                    || message.contains("12052032")
                    || message.contains("12052260")
                    || message.contains("HTTP 404"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private BusinessException readbackFailure(String productId, String detail) {
        return conflict("TikTok Shop Create Product 已返回 product_id=" + truncate(productId, 128)
                + "，但创建后回读核验失败；远端商品可能已经存在，发布将记为失败且禁止盲目重试："
                + truncate(detail, 500));
    }

    private BusinessException remoteCreatedFailure(String productId, String detail) {
        return conflict("TikTok Shop Create Product 已返回 product_id=" + truncate(productId, 128)
                + "；远端商品可能已经存在，发布将记为失败且禁止盲目重试："
                + truncate(detail, 800));
    }

    boolean requiresCategoryVersionV2(String region) {
        return region != null && List.of("US", "PH", "MY", "ID", "TH", "VN", "SG")
                .contains(region.trim().toUpperCase());
    }

    private String htmlDescription(String value) {
        String source = value == null ? "" : value.trim();
        String html = source.matches("(?s).*<[^>]+>.*") ? source
                : "<p>" + source.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>") + "</p>";
        if (html.length() > 10_000) {
            throw conflict("TikTok Shop 商品描述转换为 HTML 后超过官方 10000 字符上限");
        }
        return html;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    private record UploadedImages(List<Map<String, Object>> mainImages,
                                  Map<String, String> attributeImageBySkuKey,
                                  int uploadedCount) {}

    record ReadbackVerification(String status, Map<String, String> skuIdsBySellerSku) {}

    record ReadbackResult(Map<String, Object> response, ReadbackVerification verification) {}

    @FunctionalInterface
    interface ReadbackSleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
