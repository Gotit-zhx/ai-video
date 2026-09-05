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
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ShopeeOfficialPublishingProvider implements ProductPublishingProvider {
    private static final String UPLOAD_IMAGE = "/api/v2/media_space/upload_image";
    private static final String ADD_ITEM = "/api/v2/product/add_item";
    private static final String INIT_VARIATION = "/api/v2/product/init_tier_variation";
    private static final String ADD_MODEL = "/api/v2/product/add_model";
    private static final String GET_MODEL_LIST = "/api/v2/product/get_model_list";
    private static final String GET_ITEM_BASE_INFO = "/api/v2/product/get_item_base_info";
    private static final String UNLIST_ITEM = "/api/v2/product/unlist_item";
    private static final int MODEL_BATCH_SIZE = 50;
    private static final int MODEL_READBACK_ATTEMPTS = 5;
    private static final long MODEL_READBACK_DELAY_MILLIS = 1_000L;
    private static final int ITEM_STATUS_READBACK_ATTEMPTS = 5;
    private static final long ITEM_STATUS_READBACK_DELAY_MILLIS = 1_000L;
    private static final long VARIATION_INITIALIZATION_DELAY_MILLIS = 5_000L;

    private final ShopAuthorizationService shopAuthorizationService;
    private final ShopeeOfficialClient client;
    private final ProductCaptureSkuMapper skuMapper;
    private final ProductCaptureImageMapper imageMapper;
    private final MarketplaceImageDownloader imageDownloader;
    private final ObjectMapper objectMapper;

    public ShopeeOfficialPublishingProvider(ShopAuthorizationService shopAuthorizationService,
                                            ShopeeOfficialClient client,
                                            ProductCaptureSkuMapper skuMapper,
                                            ProductCaptureImageMapper imageMapper,
                                            MarketplaceImageDownloader imageDownloader,
                                            ObjectMapper objectMapper) {
        this.shopAuthorizationService = shopAuthorizationService;
        this.client = client;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.imageDownloader = imageDownloader;
        this.objectMapper = objectMapper;
    }

    @Override public String code() { return "SHOPEE_OFFICIAL_V2"; }
    @Override public boolean dryRun() { return false; }
    @Override public boolean supports(String destinationPlatform) {
        return "SHOPEE".equalsIgnoreCase(destinationPlatform);
    }

    @Override
    public PublishResult publish(ProductCapture capture, String destinationPlatform,
                                 String shopId, Map<String, Object> request,
                                 ExternalCreateBoundary externalCreateBoundary) {
        MarketplaceAccess.Connection connection = shopAuthorizationService.connectionForPublishing(
                capture.getOrganizationId(), shopId, MarketplacePlatform.SHOPEE);
        verifyLocalProductRoute(connection);
        List<ProductCaptureSku> skus = skus(capture);
        List<ProductCaptureImage> images = images(capture);
        boolean variants = hasVariants(skus);
        rejectDryRunImages(images);
        int publishStock = requiredStock(request);
        long categoryId = requiredLong(request, "categoryId", "Shopee 发布前必须选择官方叶子类目");
        List<Map<String, Object>> logisticInfo = requiredLogisticInfo(request.get("logisticInfo"));
        BigDecimal weightKg = requiredDecimal(request, "weightGrams", "Shopee 发布前必须填写包裹重量")
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.UP);
        BigDecimal itemPrice = requiredPrice(basePrice(capture, skus), "Shopee 发布价格必须大于 0");
        skus.forEach(this::shopeeSku);
        if (variants) {
            Map<String, Object> preflight = variationPayload(
                    "0", skus, publishStock, Map.of(), request);
            ensureUniqueTierIndexes(modelList(preflight, "model"));
        }

        Map<String, String> uploadedByUrl = uploadImages(connection, images);
        List<String> galleryImageIds = images.stream()
                .sorted(Comparator.comparingInt(ProductCaptureImage::getSortOrder))
                .map(this::publishUrl)
                .map(uploadedByUrl::get)
                .filter(value -> value != null && !value.isBlank())
                .distinct().limit(9).toList();
        if (galleryImageIds.isEmpty()) {
            throw conflict("Shopee 发布至少需要一张处理完成的商品图");
        }

        Map<String, Object> addItem = mutableMap(request.get("shopeePayload"));
        addItem.put("category_id", categoryId);
        addItem.put("item_name", capture.getTitle());
        addItem.put("description", capture.getDescription());
        addItem.put("item_status", "UNLIST");
        addItem.put("condition", fallbackText(addItem.get("condition"), "NEW"));
        addItem.put("original_price", itemPrice);
        addItem.remove("normal_stock");
        addItem.remove("tier_variation");
        if (variants) addItem.remove("seller_stock");
        else addItem.put("seller_stock", sellerStock(publishStock));
        addItem.put("weight", weightKg);
        addItem.put("image", Map.of("image_id_list", galleryImageIds));
        String sellerSku = variants || skus.isEmpty() ? null : shopeeSku(skus.getFirst());
        if (sellerSku != null) addItem.put("item_sku", sellerSku);
        putOptional(addItem, "dimension", dimension(request));
        addItem.put("logistic_info", logisticInfo);
        putOptional(addItem, "attribute_list", listValue(request.get("productAttributes")));

        Map<String, Object> fullVariationPayload = variants
                ? variationPayload("0", skus, publishStock, uploadedByUrl, request)
                : Map.of();

        // Everything above is either local validation or image staging. Once ADD_ITEM can start,
        // a timeout/crash has an unknowable remote result and must never be retried blindly.
        externalCreateBoundary.enter();
        Map<String, Object> created = client.shopPost(ADD_ITEM, connection, addItem);
        String itemId = nestedText(created, "response", "item_id");
        if (itemId == null) itemId = text(created, "item_id");
        if (itemId == null) throw conflict("Shopee 创建商品成功响应缺少 item_id，已停止后续操作");
        long numericItemId = positiveLong(itemId,
                "Shopee 创建商品响应的 item_id 格式不正确，已停止后续操作");

        try {
            Map<String, Object> variationResponse = Map.of();
            int addModelBatchCount = 0;
            List<Map<String, Object>> publishedModels = List.of();
            if (variants) {
                waitForVariationInitializationWindow();
                Map<String, Object> submissionPayload = new LinkedHashMap<>(fullVariationPayload);
                submissionPayload.put("item_id", numericItemId);
                VariationSubmission variationSubmission = submitVariations(connection, submissionPayload);
                variationResponse = variationSubmission.initResponse();
                addModelBatchCount = variationSubmission.addModelResponses().size();
                publishedModels = readPublishedModels(connection, itemId,
                        modelList(submissionPayload, "model"));
            }
            Map<String, Object> activation = client.shopPost(UNLIST_ITEM, connection, Map.of(
                    "item_list", List.of(Map.of("item_id", numericItemId, "unlist", false))
            ));
            verifyActivationResponse(activation, numericItemId);
            String remoteStatus = readActivatedItemStatus(
                    connection, numericItemId, Thread::sleep);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("platform", "SHOPEE");
            receipt.put("apiVersion", "v2");
            receipt.put("externalShopId", connection.shop().getExternalShopId());
            receipt.put("itemId", itemId);
            receipt.put("imageCount", galleryImageIds.size());
            receipt.put("skuCount", skus.size());
            receipt.put("variantInitialized", !variationResponse.isEmpty());
            receipt.put("addModelBatchCount", addModelBatchCount);
            receipt.put("models", publishedModels);
            receipt.put("activationRequestId", text(activation, "request_id"));
            receipt.put("remoteStatus", remoteStatus);
            return new PublishResult(itemId, receipt);
        } catch (RuntimeException exception) {
            String reason = fallbackText(exception.getMessage(), "Shopee 官方接口未确认完成");
            throw conflict("Shopee 商品 " + itemId
                    + " 已按未上架状态创建，但后续发布未确认完成：" + truncate(reason, 500)
                    + "；请先到 Seller Center 核查，避免重复创建");
        }
    }

    Map<String, String> uploadImages(MarketplaceAccess.Connection connection,
                                     List<ProductCaptureImage> images) {
        Map<String, String> result = new LinkedHashMap<>();
        int index = 0;
        for (ProductCaptureImage image : images) {
            String url = publishUrl(image);
            if (url == null) continue;
            String imageId = result.get(url);
            if (imageId == null) {
                MarketplaceImageDownloader.ImageBinary binary = imageDownloader.download(url);
                Map<String, Object> response = client.shopMultipart(UPLOAD_IMAGE, connection,
                        "product-" + (++index) + binary.extension(), binary.contentType(), binary.bytes(),
                        Map.of("scene", "normal"));
                imageId = nestedText(response, "response", "image_info", "image_id");
                if (imageId == null) imageId = nestedText(response, "response", "image_id");
                if (imageId == null) throw conflict("Shopee 图片上传响应缺少 image_id");
            }
            result.put(url, imageId);
            if (image.getOriginalUrl() != null && !image.getOriginalUrl().isBlank()) {
                result.put(image.getOriginalUrl(), imageId);
            }
            if (image.getProcessedUrl() != null && !image.getProcessedUrl().isBlank()) {
                result.put(image.getProcessedUrl(), imageId);
            }
        }
        return result;
    }

    Map<String, Object> variationPayload(String itemId, List<ProductCaptureSku> skus,
                                         int stock, Map<String, String> uploadedByUrl,
                                         Map<String, Object> request) {
        List<Map<String, String>> attributes = skus.stream().map(this::attributes).toList();
        LinkedHashSet<String> dimensionNames = new LinkedHashSet<>();
        attributes.forEach(map -> dimensionNames.addAll(map.keySet()));
        if (dimensionNames.isEmpty()) dimensionNames.add("Variation");
        if (dimensionNames.size() > 2) {
            throw conflict("Shopee 最多支持两层销售规格；请在发布前调整 SKU 规格");
        }
        List<String> dimensions = List.copyOf(dimensionNames);
        List<Map<String, Object>> tiers = new ArrayList<>();
        List<Map<String, Integer>> optionIndexes = new ArrayList<>();
        for (String dimension : dimensions) {
            List<String> options = new ArrayList<>();
            for (int i = 0; i < skus.size(); i++) {
                String option = option(attributes.get(i), skus.get(i), dimension, dimensions.size());
                if (!options.contains(option)) options.add(option);
            }
            List<Map<String, Object>> optionList = new ArrayList<>();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (int i = 0; i < options.size(); i++) {
                String option = options.get(i);
                indexes.put(option, i);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("variation_option_id", 0);
                value.put("variation_option_name", option);
                if (tiers.isEmpty()) {
                    ProductCaptureSku matching = firstSkuForOption(skus, attributes, dimension,
                            dimensions.size(), option);
                    String imageId = matching == null ? null : uploadedByUrl.get(matching.getImageUrl());
                    if (imageId != null) value.put("image_id", imageId);
                }
                optionList.add(value);
            }
            tiers.add(Map.of(
                    "variation_id", 0,
                    "variation_group_id", 0,
                    "variation_name", dimension,
                    "variation_option_list", optionList));
            optionIndexes.add(indexes);
        }
        Map<String, Integer> stockBySku = integerMap(request.get("skuStocks"));
        List<Map<String, Object>> models = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            ProductCaptureSku sku = skus.get(i);
            List<Integer> tierIndex = new ArrayList<>();
            for (int d = 0; d < dimensions.size(); d++) {
                String value = option(attributes.get(i), sku, dimensions.get(d), dimensions.size());
                tierIndex.add(optionIndexes.get(d).get(value));
            }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("tier_index", tierIndex);
            model.put("original_price", requiredPrice(
                    sku.getPrice() == null ? basePrice(null, skus) : sku.getPrice(),
                    "Shopee SKU “" + fallbackText(sku.getVariationName(), sku.getSkuKey())
                            + "” 的发布价格必须大于 0"));
            model.put("seller_stock", sellerStock(stockBySku.getOrDefault(sku.getSkuKey(), stock)));
            String modelSku = shopeeSku(sku);
            if (modelSku != null) model.put("model_sku", modelSku);
            models.add(model);
        }
        validateStockKeys(stockBySku, skus);
        return Map.of("item_id", longNumber(itemId), "standardise_tier_variation", tiers, "model", models);
    }

    VariationSubmission submitVariations(MarketplaceAccess.Connection connection,
                                          Map<String, Object> fullPayload) {
        List<Map<String, Object>> models = modelList(fullPayload, "model");
        if (models.isEmpty()) throw conflict("Shopee 多规格商品至少需要一个型号");
        ensureUniqueTierIndexes(models);

        int firstBatchEnd = Math.min(MODEL_BATCH_SIZE, models.size());
        Map<String, Object> initPayload = new LinkedHashMap<>(fullPayload);
        initPayload.put("model", List.copyOf(models.subList(0, firstBatchEnd)));
        Map<String, Object> initResponse = client.shopPost(INIT_VARIATION, connection, initPayload);

        List<Map<String, Object>> addModelResponses = new ArrayList<>();
        for (int offset = firstBatchEnd; offset < models.size(); offset += MODEL_BATCH_SIZE) {
            int end = Math.min(offset + MODEL_BATCH_SIZE, models.size());
            Map<String, Object> addModelPayload = Map.of(
                    "item_id", fullPayload.get("item_id"),
                    "model_list", List.copyOf(models.subList(offset, end)));
            addModelResponses.add(client.shopPost(ADD_MODEL, connection, addModelPayload));
        }
        return new VariationSubmission(initResponse, List.copyOf(addModelResponses));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> modelList(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (!(raw instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)) throw conflict("Shopee 型号数据格式不正确");
            Map<String, Object> model = new LinkedHashMap<>();
            source.forEach((field, item) -> model.put(String.valueOf(field), item));
            result.add(model);
        }
        return result;
    }

    private void ensureUniqueTierIndexes(List<Map<String, Object>> models) {
        LinkedHashSet<String> tierIndexes = new LinkedHashSet<>();
        for (Map<String, Object> model : models) {
            String key = tierIndexKey(model.get("tier_index"));
            if (key == null || !tierIndexes.add(key)) {
                throw conflict("Shopee SKU 规格组合必须完整且不能重复；商品仍保持未上架");
            }
        }
    }

    private List<Map<String, Integer>> sellerStock(int stock) {
        return List.of(Map.of("stock", stock));
    }

    private void waitForVariationInitializationWindow() {
        pause(VARIATION_INITIALIZATION_DELAY_MILLIS,
                "Shopee 商品已按未上架状态创建，但等待规格初始化时任务被中断");
    }

    void verifyActivationResponse(Map<String, Object> activation, long itemId) {
        Object rawResponse = nestedValue(activation, "response");
        Map<?, ?> response = rawResponse instanceof Map<?, ?> values ? values : activation;
        Map<?, ?> failure = itemEntry(response.get("failure_list"), itemId);
        if (failure != null) {
            String reason = firstEntryText(failure, "failed_reason", "failure_reason", "message");
            throw conflict("Shopee 商品激活失败："
                    + truncate(fallbackText(reason, "官方返回 failure_list"), 500));
        }

        Map<?, ?> success = itemEntry(response.get("success_list"), itemId);
        if (success == null) {
            throw conflict("Shopee 激活响应未在 success_list 中确认目标商品");
        }
        if (!Boolean.FALSE.equals(success.get("unlist"))) {
            throw conflict("Shopee 激活响应未确认目标商品 unlist=false");
        }
    }

    String readActivatedItemStatus(MarketplaceAccess.Connection connection, long itemId,
                                   ItemStatusReadbackSleeper sleeper) {
        String lastStatus = null;
        for (int attempt = 1; attempt <= ITEM_STATUS_READBACK_ATTEMPTS; attempt++) {
            Map<String, Object> response = client.shopGet(GET_ITEM_BASE_INFO, connection,
                    Map.of("item_id_list", itemId));
            String status = itemStatus(response, itemId);
            if (status != null) {
                lastStatus = status.trim().toUpperCase(Locale.ROOT);
                if ("NORMAL".equals(lastStatus) || "REVIEWING".equals(lastStatus)) {
                    return lastStatus;
                }
                if (!"UNLIST".equals(lastStatus)) {
                    throw conflict("Shopee 商品激活后状态为 " + truncate(lastStatus, 80)
                            + "，不能标记为提交成功");
                }
            }
            if (attempt < ITEM_STATUS_READBACK_ATTEMPTS) {
                try {
                    sleeper.sleep(ITEM_STATUS_READBACK_DELAY_MILLIS * attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw conflict("Shopee 商品已请求激活，但等待状态回读时任务被中断");
                }
            }
        }
        if ("UNLIST".equals(lastStatus)) {
            throw conflict("Shopee 商品激活后回读仍为 UNLIST，不能标记为提交成功");
        }
        throw conflict("Shopee 商品激活后未能回读到目标商品状态");
    }

    private String itemStatus(Map<String, Object> response, long itemId) {
        Object rawItems = nestedValue(response, "response", "item_list");
        if (!(rawItems instanceof List<?>)) rawItems = response.get("item_list");
        Map<?, ?> item = itemEntry(rawItems, itemId);
        return item == null ? null : firstEntryText(item, "item_status");
    }

    private Map<?, ?> itemEntry(Object rawEntries, long itemId) {
        if (!(rawEntries instanceof List<?> entries)) return null;
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) continue;
            if (sameItemId(entry.get("item_id"), itemId)) return entry;
        }
        return null;
    }

    private boolean sameItemId(Object rawItemId, long itemId) {
        try {
            return new BigDecimal(String.valueOf(rawItemId)).longValueExact() == itemId;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String firstEntryText(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private List<Map<String, Object>> readPublishedModels(MarketplaceAccess.Connection connection,
                                                           String itemId,
                                                           List<Map<String, Object>> expectedModels) {
        List<Map<String, Object>> mappings = List.of();
        for (int attempt = 0; attempt < MODEL_READBACK_ATTEMPTS; attempt++) {
            Map<String, Object> response = client.shopGet(GET_MODEL_LIST, connection,
                    Map.of("item_id", longNumber(itemId)));
            mappings = modelMappings(response);
            if (completeModelMappings(mappings, expectedModels)) return mappings;
            if (attempt + 1 < MODEL_READBACK_ATTEMPTS) {
                pause(MODEL_READBACK_DELAY_MILLIS * (attempt + 1),
                        "Shopee 商品已保持未上架，但等待规格回读时任务被中断");
            }
        }
        throw conflict("Shopee 规格回读不完整（已读取 " + mappings.size() + " / 应有 "
                + expectedModels.size() + " 个 model_id）；商品仍保持未上架，请稍后检查");
    }

    boolean completeModelMappings(List<Map<String, Object>> mappings,
                                  List<Map<String, Object>> expectedModels) {
        if (mappings.size() != expectedModels.size()) return false;
        LinkedHashSet<String> expectedTierIndexes = new LinkedHashSet<>();
        for (Map<String, Object> expectedModel : expectedModels) {
            String key = tierIndexKey(expectedModel.get("tier_index"));
            if (key == null || !expectedTierIndexes.add(key)) return false;
        }
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();
        LinkedHashSet<String> tierIndexes = new LinkedHashSet<>();
        for (Map<String, Object> mapping : mappings) {
            Object modelId = mapping.get("model_id");
            String tierIndex = tierIndexKey(mapping.get("tier_index"));
            if (modelId == null || String.valueOf(modelId).isBlank() || tierIndex == null) return false;
            modelIds.add(String.valueOf(modelId));
            tierIndexes.add(tierIndex);
        }
        return modelIds.size() == expectedModels.size() && tierIndexes.equals(expectedTierIndexes);
    }

    private String tierIndexKey(Object value) {
        if (!(value instanceof List<?> indexes) || indexes.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Object index : indexes) {
            try {
                BigDecimal number = new BigDecimal(String.valueOf(index)).stripTrailingZeros();
                if (number.scale() > 0 || number.signum() < 0) return null;
                parts.add(number.toPlainString());
            } catch (Exception exception) {
                return null;
            }
        }
        return String.join(":", parts);
    }

    private void pause(long millis, String interruptedMessage) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw conflict(interruptedMessage);
        }
    }

    private List<Map<String, Object>> modelMappings(Map<String, Object> response) {
        if (response == null || response.isEmpty()) return List.of();
        Object raw = nestedValue(response, "response", "model");
        if (!(raw instanceof List<?>)) raw = nestedValue(response, "response", "model_list");
        if (!(raw instanceof List<?> models)) return List.of();
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Object entry : models) {
            if (!(entry instanceof Map<?, ?> model)) continue;
            Map<String, Object> mapping = new LinkedHashMap<>();
            copyIfPresent(model, mapping, "model_id");
            copyIfPresent(model, mapping, "model_sku");
            copyIfPresent(model, mapping, "tier_index");
            if (mapping.containsKey("model_id")) mappings.add(mapping);
        }
        return mappings;
    }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    void verifyLocalProductRoute(MarketplaceAccess.Connection connection) {
        Map<String, Object> shopInfo = client.shopGet(ShopeeOfficialClient.SHOP_INFO_PATH,
                connection, Map.of());
        Object response = nestedValue(shopInfo, "response");
        Map<?, ?> source = response instanceof Map<?, ?> values ? values : shopInfo;
        Object status = source.get("status");
        if (status == null || !"NORMAL".equalsIgnoreCase(String.valueOf(status))) {
            throw conflict("Shopee 店铺状态不是 NORMAL，已停止本地商品发布");
        }
        Object upgraded = source.get("is_upgraded_cbsc");
        if (upgraded == null) {
            throw conflict("Shopee 店铺信息缺少 is_upgraded_cbsc，无法安全判断本地商品或全球商品发布链路");
        }
        if (booleanValue(upgraded)) {
            throw conflict("该 Shopee 店铺是已升级跨境店，必须使用 global_product 官方链路；当前本地商品发布已安全阻止");
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private Object nestedValue(Map<String, Object> map, String... path) {
        Object value = map;
        for (String key : path) {
            if (!(value instanceof Map<?, ?> source)) return null;
            value = source.get(key);
        }
        return value;
    }

    private ProductCaptureSku firstSkuForOption(List<ProductCaptureSku> skus,
                                                 List<Map<String, String>> attributes,
                                                 String dimension, int dimensionCount,
                                                 String option) {
        for (int i = 0; i < skus.size(); i++) {
            if (option.equals(option(attributes.get(i), skus.get(i), dimension, dimensionCount))) return skus.get(i);
        }
        return null;
    }

    private String option(Map<String, String> attributes, ProductCaptureSku sku,
                          String dimension, int dimensionCount) {
        String value = attributes.get(dimension);
        if (value != null && !value.isBlank()) return truncate(value, 20);
        if (dimensionCount == 1 && sku.getVariationName() != null) return truncate(sku.getVariationName(), 20);
        throw conflict("SKU “" + fallbackText(sku.getVariationName(), sku.getSkuKey())
                + "” 缺少规格 “" + dimension + "”");
    }

    private Map<String, String> attributes(ProductCaptureSku sku) {
        if (sku.getAttributesJson() == null || sku.getAttributesJson().isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(sku.getAttributesJson(), new TypeReference<>() {});
            Map<String, String> values = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (value != null && !String.valueOf(value).isBlank()) {
                    values.put(truncate(key, 14), truncate(String.valueOf(value), 20));
                }
            });
            return values;
        } catch (Exception exception) {
            throw conflict("SKU 规格数据无法转换为 Shopee 型号");
        }
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
        if (images.stream().anyMatch(image -> image.getProcessedUrl() != null
                && image.getProcessedUrl().equals(image.getOriginalUrl()))) {
            throw conflict("商品图片仍是去水印 Dry Run 占位结果，禁止提交到真实 Shopee 店铺");
        }
    }

    private String publishUrl(ProductCaptureImage image) {
        return image.getProcessedUrl() == null || image.getProcessedUrl().isBlank()
                ? null : image.getProcessedUrl();
    }

    private boolean hasVariants(List<ProductCaptureSku> skus) {
        return skus.size() > 1 || skus.stream().anyMatch(sku -> !attributes(sku).isEmpty());
    }

    private BigDecimal basePrice(ProductCapture capture, List<ProductCaptureSku> skus) {
        return skus.stream().map(ProductCaptureSku::getPrice).filter(value -> value != null)
                .min(BigDecimal::compareTo)
                .orElseGet(() -> capture == null || capture.getPriceMin() == null
                        ? BigDecimal.ZERO : capture.getPriceMin());
    }

    private BigDecimal requiredPrice(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) throw conflict(message);
        return value;
    }

    private String shopeeSku(ProductCaptureSku sku) {
        String value = sku.getSellerSku();
        if (value == null || value.isBlank()) return null;
        value = value.trim();
        if (value.length() > 100) {
            throw conflict("Shopee Seller SKU 不能超过 100 个字符；请修正 SKU “"
                    + fallbackText(sku.getVariationName(), sku.getSkuKey()) + "”");
        }
        return value;
    }

    private void validateStockKeys(Map<String, Integer> stockBySku, List<ProductCaptureSku> skus) {
        if (stockBySku.isEmpty()) return;
        LinkedHashSet<String> knownKeys = new LinkedHashSet<>();
        skus.forEach(sku -> knownKeys.add(sku.getSkuKey()));
        List<String> unknown = stockBySku.keySet().stream()
                .filter(key -> !knownKeys.contains(key))
                .limit(3)
                .toList();
        if (!unknown.isEmpty()) {
            throw conflict("SKU 库存映射包含不存在的 SKU Key：" + String.join("、", unknown));
        }
    }

    private Map<String, Object> dimension(Map<String, Object> request) {
        BigDecimal length = decimal(request.get("packageLengthCm"));
        BigDecimal width = decimal(request.get("packageWidthCm"));
        BigDecimal height = decimal(request.get("packageHeightCm"));
        if (length == null || width == null || height == null) return Map.of();
        return Map.of("package_length", length, "package_width", width, "package_height", height);
    }

    int requiredStock(Map<String, Object> request) {
        return strictStock(request.get("publishStock"),
                "发布库存必须是 0 到 999999 的整数；库存无需采集，但发布时必须填写");
    }

    private long requiredLong(Map<String, Object> request, String key, String message) {
        return positiveLong(request.get(key), message);
    }

    private long positiveLong(Object raw, String message) {
        try {
            long value = Long.parseLong(String.valueOf(raw));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception exception) {
            throw conflict(message);
        }
    }

    private BigDecimal requiredDecimal(Map<String, Object> request, String key, String message) {
        BigDecimal value = decimal(request.get(key));
        if (value == null || value.signum() <= 0) throw conflict(message);
        return value;
    }

    private BigDecimal decimal(Object value) {
        try { return value == null ? null : new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<?> listValue(Object value) { return value instanceof List<?> list ? list : List.of(); }

    List<Map<String, Object>> requiredLogisticInfo(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            throw conflict("Shopee 发布前必须选择目标店铺返回的官方物流渠道");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        boolean hasEnabledChannel = false;
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> source)) {
                throw conflict("Shopee 物流渠道格式不正确，必须包含 logistic_id 与 enabled");
            }
            long id;
            try {
                id = Long.parseLong(String.valueOf(source.get("logistic_id")));
                if (id <= 0 || !ids.add(id)) throw new NumberFormatException();
            } catch (Exception exception) {
                throw conflict("Shopee 物流渠道 logistic_id 必须是唯一的正整数");
            }
            Object enabledValue = source.get("enabled");
            if (!(enabledValue instanceof Boolean)
                    && !"true".equalsIgnoreCase(String.valueOf(enabledValue))
                    && !"false".equalsIgnoreCase(String.valueOf(enabledValue))) {
                throw conflict("Shopee 物流渠道 enabled 必须是 true 或 false");
            }
            boolean enabled = Boolean.parseBoolean(String.valueOf(enabledValue));
            hasEnabledChannel |= enabled;
            Map<String, Object> normalized = new LinkedHashMap<>();
            source.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            normalized.put("logistic_id", id);
            normalized.put("enabled", enabled);
            result.add(normalized);
        }
        if (!hasEnabledChannel) {
            throw conflict("Shopee 发布前至少要启用一个目标店铺可用的官方物流渠道");
        }
        return List.copyOf(result);
    }
    private void putOptional(Map<String, Object> target, String key, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) target.put(key, value);
        if (value instanceof List<?> list && !list.isEmpty()) target.put(key, value);
    }

    private Map<String, Integer> integerMap(Object value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(value instanceof Map<?, ?> raw)) {
            throw conflict("SKU 库存必须按 SKU Key 提交 0 到 999999 的整数映射");
        }
        raw.forEach((key, item) -> result.put(String.valueOf(key), strictStock(item,
                "SKU “" + key + "” 的库存必须是 0 到 999999 的整数")));
        return result;
    }

    private int strictStock(Object raw, String message) {
        try {
            if (raw == null || String.valueOf(raw).isBlank()) throw new NumberFormatException();
            BigDecimal value = new BigDecimal(String.valueOf(raw)).stripTrailingZeros();
            if (value.scale() > 0 || value.signum() < 0
                    || value.compareTo(BigDecimal.valueOf(999_999)) > 0) {
                throw new NumberFormatException();
            }
            return value.intValueExact();
        } catch (Exception exception) {
            throw conflict(message);
        }
    }

    @SuppressWarnings("unchecked")
    private String nestedText(Map<String, Object> map, String... path) {
        Object value = map;
        for (String key : path) {
            if (!(value instanceof Map<?, ?> source)) return null;
            value = source.get(key);
        }
        return value == null ? null : String.valueOf(value);
    }

    private String text(Map<String, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Object longNumber(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return value; }
    }

    private String fallbackText(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    record VariationSubmission(Map<String, Object> initResponse,
                               List<Map<String, Object>> addModelResponses) { }

    @FunctionalInterface
    interface ItemStatusReadbackSleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
