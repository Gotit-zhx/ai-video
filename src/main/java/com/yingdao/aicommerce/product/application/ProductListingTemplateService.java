package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Organization-scoped listing content, independent of print/design templates. */
@Service
public class ProductListingTemplateService {
    public static final String SHOPEE_DESCRIPTION = """
            We specialize in stretchable, body-hugging, curve-enhancing outfits made for every Filipina who wants to feel confident, sexy, and comfortable at the same time.

            Why girls love us:
            High elasticity fabric — kasya sa different body shapes
            Snatched fit that enhances your natural curves
            Comfy kahit all-day wear (no stiff feeling!)
            Perfect for TikTok outfits, daily wear, and night outs

            Best for:
            Bodycon dresses / seamless sets / crop tops / leggings / fitted skirts / club & date night looks

            Our promise:
            Every piece is designed to hug your body in the right places and boost your confidence instantly.

            CurveLuxe PH — “Stretch. Slay.”
            """.strip();
    private final JdbcTemplate db;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceImageArchive imageArchive;

    public ProductListingTemplateService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<Template> list(PlatformPrincipal principal) {
        requireAccess(principal, false);
        return List.of(get(principal.organizationId(), "SHOPEE"), get(principal.organizationId(), "TIKTOK_SHOP"));
    }

    public Template get(String organizationId, String platform) {
        String target = platform(platform);
        var rows = db.query("SELECT * FROM product_listing_templates WHERE organization_id=? AND platform=?",
                (rs, n) -> new Template(target, rs.getString("name"), rs.getBoolean("enabled"),
                rs.getString("description"), rs.getBigDecimal("weight_grams"),
                rs.getBigDecimal("package_length_cm"), rs.getBigDecimal("package_width_cm"),
                rs.getBigDecimal("package_height_cm"), rs.getLong("version"),
                rs.getString("size_chart_image_key")), organizationId, target);
        return rows.isEmpty() ? defaults(target) : rows.getFirst();
    }

    @Transactional
    public Template save(String platform, Map<String, Object> body, PlatformPrincipal principal) {
        requireAccess(principal, true);
        String target = platform(platform);
        if (!(body.get("enabled") instanceof Boolean enabled)) throw bad("请选择是否自动应用模板");
        String name = text(body.get("name")), description = text(body.get("description"));
        if (name.isBlank() || name.length() > 100) throw bad("模板名称需为 1–100 个字符");
        if (description.length() > 9000 || enabled && description.isBlank()) throw bad("启用的详情文案需为 1–9000 个字符");
        BigDecimal weight = positive(body.get("weightGrams"), "重量", 3, 1_000_000);
        BigDecimal length = positive(body.get("packageLengthCm"), "包装长度", 2, 1000);
        BigDecimal width = positive(body.get("packageWidthCm"), "包装宽度", 2, 1000);
        BigDecimal height = positive(body.get("packageHeightCm"), "包装高度", 2, 1000);
        long version;
        try { version = new BigDecimal(String.valueOf(body.get("version"))).longValueExact(); }
        catch (Exception e) { throw bad("请重新读取模板后保存"); }
        if (version < 0) throw bad("模板版本无效");
        String sizeChartKey = body.containsKey("sizeChartImageKey") ? text(body.get("sizeChartImageKey"))
                : text(get(principal.organizationId(), target).sizeChartImageKey());
        if (!sizeChartKey.isBlank()) {
            try { imageArchive.read(principal.organizationId(), sizeChartKey); }
            catch (RuntimeException e) { throw bad("尺码模板图片不存在或不属于当前公司，请重新上传"); }
        }
        if (sizeChartKey.isBlank()) sizeChartKey = null;
        int changed;
        if (version == 0) {
            changed = db.update("""
                    INSERT IGNORE INTO product_listing_templates
                    (organization_id,platform,name,enabled,description,size_chart_image_key,weight_grams,package_length_cm,
                     package_width_cm,package_height_cm,version,updated_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,1,?)
                    """, principal.organizationId(), target, name, enabled, description, sizeChartKey, weight,
                    length, width, height, principal.userId());
        } else {
            changed = db.update("""
                    UPDATE product_listing_templates SET name=?,enabled=?,description=?,size_chart_image_key=?,weight_grams=?,
                    package_length_cm=?,package_width_cm=?,package_height_cm=?,version=version+1,
                    updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE organization_id=? AND platform=? AND version=?
                    """, name, enabled, description, sizeChartKey, weight, length, width, height, principal.userId(),
                    principal.organizationId(), target, version);
        }
        if (changed != 1) throw new BusinessException(HttpStatus.CONFLICT, "模板已被其他人修改，请重新打开后保存");
        return get(principal.organizationId(), target);
    }

    public Map<String, Object> snapshot(String organizationId, String destination, ProductCapture capture) {
        Template template = get(organizationId, destination);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("platform", template.platform());
        snapshot.put("name", template.name());
        snapshot.put("version", template.version());
        snapshot.put("enabled", template.enabled());
        snapshot.put("description", template.enabled() ? template.description() : originalDescription(capture));
        if (template.enabled()) {
            snapshot.put("sizeChartImageKey", text(template.sizeChartImageKey()));
            snapshot.put("weightGrams", template.weightGrams().toPlainString());
            snapshot.put("packageLengthCm", template.packageLengthCm().toPlainString());
            snapshot.put("packageWidthCm", template.packageWidthCm().toPlainString());
            snapshot.put("packageHeightCm", template.packageHeightCm().toPlainString());
        }
        return snapshot;
    }

    public Map<String, Object> publishSnapshot(String organizationId, String captureId, String key,
                                               String destination, ProductCapture capture) {
        var saved = db.query("""
                SELECT request_json FROM product_publish_workflows
                WHERE organization_id=? AND capture_id=? AND idempotency_key=?
                """, (rs, n) -> read(rs.getString(1)), organizationId, captureId, key);
        // Old workflows retain their original behavior. New ones reuse the immutable snapshot.
        return !saved.isEmpty() ? snapshotFrom(saved.getFirst()) : snapshot(organizationId, destination, capture);
    }

    public void applyCapture(String organizationId, String sourcePlatform, Map<String, Object> normalized) {
        Template template = get(organizationId, sourcePlatform);
        if (!template.enabled()) return;
        normalized.put("originalDescription", text(normalized.get("description")));
        normalized.put("description", template.description());
        normalized.put("listingTemplate", Map.of("platform", template.platform(), "version", template.version(), "name", template.name()));
    }

    public static void apply(Map<String, Object> body, Map<String, Object> snapshot) {
        body.remove("listingTemplateSnapshot");
        if (snapshot == null || snapshot.isEmpty()) return;
        body.put("listingTemplateSnapshot", snapshot);
        if (Boolean.TRUE.equals(snapshot.get("enabled"))) {
            for (String field : List.of("weightGrams", "packageLengthCm", "packageWidthCm", "packageHeightCm")) body.put(field, snapshot.get(field));
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> snapshotFrom(Map<String, Object> request) {
        return request.get("listingTemplateSnapshot") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    public static String publicationDescription(ProductCapture capture, Map<String, Object> request) {
        var snapshot = snapshotFrom(request);
        return snapshot.containsKey("description") ? text(snapshot.get("description")) : capture.getDescription();
    }

    /** Original facts survive replacing the customer-facing marketing description. */
    public static String sourceDescription(ProductCapture capture, ObjectMapper json) {
        if (capture.getNormalizedData() != null && !capture.getNormalizedData().isBlank()) {
            try {
                Map<?, ?> normalized = json.readValue(capture.getNormalizedData(), Map.class);
                if (normalized.containsKey("originalDescription"))
                    return text(normalized.get("originalDescription"));
            } catch (Exception ignored) { /* Legacy records can still use their description. */ }
        }
        return text(capture.getDescription());
    }

    public static String sizeChartImageKey(Map<String, Object> request) {
        String selected = text(request.get("sizeChartImageKey"));
        if (!selected.isBlank()) return selected;
        var snapshot = snapshotFrom(request);
        return Boolean.TRUE.equals(snapshot.get("enabled")) ? text(snapshot.get("sizeChartImageKey")) : "";
    }

    private String originalDescription(ProductCapture capture) {
        var normalized = read(capture.getNormalizedData());
        return normalized.containsKey("listingTemplate") && normalized.containsKey("originalDescription")
                ? text(normalized.get("originalDescription")) : text(capture.getDescription());
    }

    private Map<String, Object> read(String data) {
        if (data == null || data.isBlank()) return Map.of();
        try { return json.readValue(data, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("商品模板快照无法读取", e); }
    }

    public static Template defaults(String platform) {
        boolean shopee = "SHOPEE".equals(platform(platform));
        return new Template(shopee ? "SHOPEE" : "TIKTOK_SHOP",
                shopee ? "虾皮通用模板" : "TikTok 通用模板", shopee, shopee ? SHOPEE_DESCRIPTION : "",
                BigDecimal.valueOf(shopee ? 200 : 250), BigDecimal.valueOf(shopee ? 5 : 30),
                BigDecimal.valueOf(25), BigDecimal.valueOf(shopee ? 25 : 2), 0, null);
    }
    private static String platform(String value) {
        String target = text(value).toUpperCase(java.util.Locale.ROOT);
        if (List.of("TIKTOK", "TIKTOKSHOP", "TIKTOK_SHOP").contains(target)) return "TIKTOK_SHOP";
        if ("SHOPEE".equals(target)) return target;
        throw bad("模板仅支持 Shopee 和 TikTok Shop");
    }
    public static void requireAccess(PlatformPrincipal principal, boolean edit) {
        boolean allowed = principal != null && (principal.roleCodes().contains("ADMIN")
                || principal.permissionCodes().stream().anyMatch(code -> edit ? code.equals("product:edit")
                : List.of("product:view", "product:collect", "product:edit", "product:publish").contains(code)));
        if (!allowed) throw new BusinessException(HttpStatus.FORBIDDEN, edit ? "没有商品模板编辑权限" : "没有商品模板查看权限");
    }
    private static BigDecimal positive(Object value, String label, int scale, int maximum) {
        try {
            BigDecimal number = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
            if (number.signum() <= 0 || number.compareTo(BigDecimal.valueOf(maximum)) > 0 || number.scale() > scale) throw new ArithmeticException();
            return number;
        } catch (Exception e) { throw bad(label + "必须是有效的正数"); }
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
    public record Template(String platform, String name, boolean enabled, String description,
                           BigDecimal weightGrams, BigDecimal packageLengthCm, BigDecimal packageWidthCm,
                           BigDecimal packageHeightCm, long version, String sizeChartImageKey) {
        public Template(String platform, String name, boolean enabled, String description,
                        BigDecimal weightGrams, BigDecimal packageLengthCm, BigDecimal packageWidthCm,
                        BigDecimal packageHeightCm, long version) {
            this(platform, name, enabled, description, weightGrams, packageLengthCm, packageWidthCm,
                    packageHeightCm, version, null);
        }
    }
}
