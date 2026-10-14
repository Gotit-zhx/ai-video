package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** A country's origin policy is shared by shops, while official warehouse IDs remain shop-owned. */
@Service
public class ProductListingFulfillmentService {
    private final JdbcTemplate db;
    private final ShopAuthorizationService shops;
    private final TikTokShopOfficialClient tiktok;
    private final ObjectMapper json;
    public ProductListingFulfillmentService(JdbcTemplate db, ShopAuthorizationService shops,
            TikTokShopOfficialClient tiktok, ObjectMapper json) {
        this.db = db; this.shops = shops; this.tiktok = tiktok; this.json = json;
    }
    public Map<String,Object> get(String platform, String country, String origin, PlatformPrincipal user) {
        ProductListingTemplateService.requireAccess(user, false);
        String market = country(country);
        return config(user.organizationId(), ProductListingTemplateService.platform(platform), market, origin(origin,market));
    }
    private Map<String,Object> config(String org, String platform, String country, String origin) {
        var rows = db.query("""
                SELECT config_json,version FROM product_listing_country_fulfillment
                WHERE organization_id=? AND platform=? AND country=? AND origin_country=?
                """, (rs,n) -> {
            var result = read(rs.getString(1));
            result.put("version", rs.getLong(2)); return result;
        }, org, platform, country, origin);
        if (!rows.isEmpty()) return rows.getFirst();
        var defaults = new LinkedHashMap<String,Object>();
        defaults.put("platform",platform); defaults.put("country",country);
        defaults.put("originCountry",origin); defaults.put("version",0);
        for (String field : List.of("warehouseName","contactName","phone","province","city","postalCode","address"))
            defaults.put(field,"");
        return defaults;
    }
    @Transactional
    public Map<String,Object> save(String platform, String country, Map<String,Object> body, PlatformPrincipal user) {
        ProductListingTemplateService.requireAccess(user, true);
        String target = ProductListingTemplateService.platform(platform), market = country(country);
        var values = new LinkedHashMap<String,Object>();
        values.put("platform",target); values.put("country",market);
        values.put("originCountry",origin(String.valueOf(body.getOrDefault("originCountry",market)),market));
        for (String key : List.of("warehouseName","contactName","phone","province","city","postalCode","address")) {
            String value = String.valueOf(body.getOrDefault(key,"")).strip();
            if (value.length() > ("address".equals(key) ? 1000 : 200)) throw bad("地址或联系人信息过长");
            values.put(key,value);
        }
        long version;
        try { version = new java.math.BigDecimal(String.valueOf(body.get("version"))).longValueExact(); }
        catch (Exception e) { throw bad("请重新读取国家地址配置后保存"); }
        int count;
        if (version == 0) count = db.update("""
                INSERT IGNORE INTO product_listing_country_fulfillment
                (organization_id,platform,country,origin_country,config_json,version,updated_by) VALUES (?,?,?,?,?,1,?)
                """,user.organizationId(),target,market,values.get("originCountry"),json.writeValueAsString(values),user.userId());
        else if (version > 0) count = db.update("""
                UPDATE product_listing_country_fulfillment SET config_json=?,version=version+1,
                updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
                WHERE organization_id=? AND platform=? AND country=? AND origin_country=? AND version=?
                """,json.writeValueAsString(values),user.userId(),user.organizationId(),target,market,values.get("originCountry"),version);
        else throw bad("配置版本无效");
        if (count != 1) throw new BusinessException(HttpStatus.CONFLICT,"国家地址已被修改，请重新读取后保存");
        values.put("version",version+1); return values;
    }
    public Map<String,Object> snapshot(String org, String shopId) {
        return snapshot(org,shopId,"TARGET");
    }
    public Map<String,Object> snapshot(String org, String shopId, String selectedOrigin) {
        var connection = shops.connectionForPublishing(org,shopId);
        String market = country(connection.shop().getRegion());
        var result = config(org,connection.shop().getPlatform(),market,origin(selectedOrigin,market));
        result.put("organizationId",org); result.put("shopId",shopId);
        return result;
    }
    public Map<String,Object> freezeSnapshot(String org, String shopId, String selectedOrigin) {
        var result = snapshot(org,shopId,selectedOrigin);
        if ("TIKTOK_SHOP".equals(result.get("platform")))
            result.put("resolvedWarehouseId",resolveWarehouse(org,shopId,result));
        return result;
    }
    public Map<String,Object> publishSnapshot(String org, String captureId, String key, String shopId, String selectedOrigin) {
        var rows = db.query("""
                SELECT request_json FROM product_publish_workflows
                WHERE organization_id=? AND capture_id=? AND idempotency_key=?
                """,(rs,n)->read(rs.getString(1)),org,captureId,key);
        return rows.isEmpty() ? freezeSnapshot(org,shopId,selectedOrigin) : snapshotFrom(rows.getFirst());
    }
    public String resolveWarehouse(String org, String shopId, Map<String,Object> config) {
        var connection = shops.connectionForPublishing(org,shopId);
        if (!org.equals(config.get("organizationId")) || !shopId.equals(config.get("shopId"))
                || !connection.shop().getPlatform().equals(config.get("platform"))
                || !country(connection.shop().getRegion()).equals(config.get("country")))
            throw new BusinessException(HttpStatus.CONFLICT,"发货配置与目标店铺不一致");
        Object data = tiktok.apiGet("/logistics/202309/warehouses",connection.tokens().accessToken(),
                connection.shop().getShopCipher(),Map.of()).get("data");
        if (!(data instanceof Map<?,?> map) || !(map.get("warehouses") instanceof List<?> list))
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"无法读取店铺官方仓库，请稍后重试");
        return selectWarehouse(list,config);
    }
    public static String selectWarehouse(List<?> warehouses, Map<String,Object> config) {
        String origin = country(String.valueOf(config.get("originCountry")));
        String name = String.valueOf(config.getOrDefault("warehouseName","")).strip();
        String frozenId = String.valueOf(config.getOrDefault("resolvedWarehouseId","")).strip();
        var candidates = new ArrayList<Map<?,?>>();
        for (Object raw : warehouses) {
            if (!(raw instanceof Map<?,?> warehouse) || !"SALES_WAREHOUSE".equals(warehouse.get("type"))) continue;
            Object status = warehouse.get("status");
            if (status != null && !Set.of("ENABLED","ACTIVE").contains(status.toString())) continue;
            Object address = warehouse.get("address");
            Object region = address instanceof Map<?,?> fields ? fields.get("region_code") : null;
            if (region == null) region = warehouse.get("region");
            // Never silently use a domestic warehouse for an overseas policy (or the reverse).
            if (region == null || !origin.equals(String.valueOf(region).toUpperCase(Locale.ROOT))) continue;
            if (!frozenId.isEmpty() && !frozenId.equals(String.valueOf(warehouse.get("id")))) continue;
            if (frozenId.isEmpty() && !name.isEmpty() && !name.equals(String.valueOf(warehouse.get("name")))) continue;
            candidates.add(warehouse);
        }
        var defaults = candidates.stream().filter(w->Boolean.TRUE.equals(w.get("is_default"))).toList();
        Map<?,?> selected = candidates.size()==1 ? candidates.getFirst()
                : defaults.size()==1 ? defaults.getFirst() : null;
        if (selected == null || selected.get("id")==null)
            throw new BusinessException(HttpStatus.CONFLICT,"未找到唯一的 "+origin+" 发货仓库，请在国家模板填写官方仓库名称，并在店铺后台登记对应地址");
        return selected.get("id").toString();
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> snapshotFrom(Map<String,Object> body) {
        return body.get("listingFulfillmentSnapshot") instanceof Map<?,?> map
                ? new LinkedHashMap<>((Map<String,Object>)map) : Map.of();
    }
    public static void apply(Map<String,Object> body, Map<String,Object> snapshot) {
        body.remove("listingFulfillmentSnapshot");
        if (snapshot != null && !snapshot.isEmpty()) {
            body.put("listingFulfillmentSnapshot",snapshot);
            if ("TIKTOK_SHOP".equals(snapshot.get("platform"))) body.remove("warehouseId");
        }
    }
    private Map<String,Object> read(String value) { return new LinkedHashMap<>(json.readValue(value,Map.class)); }
    public static String country(String value) {
        String code = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of(Locale.getISOCountries()).contains(code)) throw bad("请选择有效的国家");
        return code;
    }
    public static String origin(String value, String market) {
        return value == null || value.isBlank() || "TARGET".equals(value) ? country(market) : country(value);
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,message); }
}
