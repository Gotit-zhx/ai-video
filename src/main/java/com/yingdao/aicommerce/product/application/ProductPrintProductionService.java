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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ProductPrintProductionService {
    private static final String KEY_PREFIX = "print-production-";
    private static final String TEMPLATE_KEY = "printProductionTemplateId";

    public record Settings(String resultId, String templateId, String title, String category,
                           String description, String currency, BigDecimal price, Integer stock) {}
    public record Draft(String id, String title, String status, String thumbnailUrl, int stock) {}

    private final ProductCaptureService captures;
    private final ProductPrintCaptureService print;
    private final ProductCaptureTemplateService templates;
    private final CommerceImageArchive archive;
    private final ProductCaptureMapper captureMapper;
    private final ProductCaptureImageMapper imageMapper;
    private final ProductCaptureSkuMapper skuMapper;
    private final ObjectMapper json;

    public ProductPrintProductionService(ProductCaptureService captures, ProductPrintCaptureService print,
                                         ProductCaptureTemplateService templates, CommerceImageArchive archive,
                                         ProductCaptureMapper captureMapper, ProductCaptureImageMapper imageMapper,
                                         ProductCaptureSkuMapper skuMapper, ObjectMapper json) {
        this.captures = captures;
        this.print = print;
        this.templates = templates;
        this.archive = archive;
        this.captureMapper = captureMapper;
        this.imageMapper = imageMapper;
        this.skuMapper = skuMapper;
        this.json = json;
    }

    @Transactional
    public Draft prepare(String sourceId, Settings settings, MultipartFile image, PlatformPrincipal user) {
        if (user == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (!user.permissionCodes().containsAll(List.of("product:edit", "product:publish"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "缺少商品制作和上传权限");
        }
        var source = captures.getPrintSource(sourceId, user).capture();
        boolean hasArtwork = print.results(sourceId, user).stream()
                .anyMatch(result -> result.id().equals(settings.resultId()) && result.imageUrl() != null);
        if (!hasArtwork) throw bad("请选择可用的印花提取结果");
        String templateId = templates.requirePrintBase(settings.templateId(), user);

        String title = required(settings.title(), 500, "商品名称");
        String category = required(settings.category(), 255, "商品类目");
        String description = required(settings.description(), 10_000, "商品描述");
        String currency = Objects.toString(settings.currency(), "").strip().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException invalid) {
            throw bad("请输入有效的三位币种代码");
        }
        BigDecimal price = settings.price();
        if (price == null || price.signum() <= 0 || price.stripTrailingZeros().scale() > 2
                || price.compareTo(new BigDecimal("999999999999.99")) > 0) throw bad("请输入有效的售价，最多两位小数");
        Integer stock = settings.stock();
        if (stock == null || stock < 0 || stock > 999_999) throw bad("库存须为 0 到 999999 的整数");
        byte[] bytes = imageBytes(image);
        String org = user.organizationId();
        String hash = CommerceImageArchive.sha(json.writeValueAsBytes(List.of(
                user.userId(), sourceId, settings.resultId(), templateId, title, category, description,
                currency, price.stripTrailingZeros().toPlainString(), stock, CommerceImageArchive.sha(bytes))));
        String key = KEY_PREFIX + hash;
        ProductCapture existing = captureMapper.selectOne(query(org, key));
        if (existing != null) {
            return new Draft(existing.getId(), existing.getTitle(), existing.getStatus(),
                    CommercePublicationDrafts.PREFIX + archive.store(org, bytes).storageKey(), stock);
        }

        var archived = archive.store(org, bytes);
        String url = CommercePublicationDrafts.PREFIX + archived.storageKey();
        var normalized = Map.of(
                "originalDescription", description,
                "skuCompleteness", Map.of("complete", true, "expectedCount", 1, "capturedCount", 1,
                        "hasVariants", false, "variantDimensionCount", 0, "source", "COMMERCE_GENERATED", "issues", List.of()),
                "imageCompleteness", Map.of("complete", true, "expectedCount", 1, "capturedCount", 1,
                        "requiredSkuImageCount", 1, "coveredSkuImageCount", 1, "issues", List.of()));
        String rawData = json.writeValueAsString(Map.of(TEMPLATE_KEY, templateId,
                "printCaptureId", sourceId, "printResultId", settings.resultId()));
        var draft = new ProductCapture(org, user.userId(), key, hash, source.getSourcePlatform(),
                source.getSourceUrl(), source.getSourceShopId(), source.getSourceProductId(), title, description,
                category, null, currency, price, price, json.writeValueAsString(normalized), rawData);
        draft.assignModule(CommerceModule.CAPTURE);
        draft.imagesReady();
        try {
            captureMapper.insert(draft);
        } catch (DuplicateKeyException duplicate) {
            var winner = captureMapper.selectOne(query(org, key).last("FOR UPDATE"));
            if (winner == null) throw duplicate;
            return new Draft(winner.getId(), winner.getTitle(), winner.getStatus(), url, stock);
        }
        String originalUrl = url + "?original";
        var main = new ProductCaptureImage(org, draft.getId(), originalUrl, "MAIN", title, 0, archived.mimeType(),
                json.writeValueAsString(Map.of("commerceStorageKey", archived.storageKey())));
        main.processed(url);
        imageMapper.insert(main);
        String sku = "PRINT-" + hash.substring(0, 20).toUpperCase(Locale.ROOT);
        skuMapper.insert(new ProductCaptureSku(org, draft.getId(), sku, sku, sku, "默认规格",
                price, price, stock, originalUrl, "COMMERCE_GENERATED", "{}", "{}", 0));
        return new Draft(draft.getId(), draft.getTitle(), draft.getStatus(), url, stock);
    }

    static String templateId(ProductCapture capture, ObjectMapper json) {
        if (capture.getIdempotencyKey() == null || !capture.getIdempotencyKey().startsWith(KEY_PREFIX)) return "";
        var raw = json.readTree(Objects.toString(capture.getRawData(), "{}"));
        return raw.path(TEMPLATE_KEY).asText("");
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

    private static String required(String input, int maxLength, String label) {
        String value = Objects.toString(input, "").strip();
        if (value.isEmpty() || value.length() > maxLength) throw bad(label + "须填写，最多 " + maxLength + " 个字符");
        return value;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }
}
