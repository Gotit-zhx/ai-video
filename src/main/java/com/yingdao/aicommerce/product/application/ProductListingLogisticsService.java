package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.application.ShopAuthorizationService;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Product logistics choices belong to a destination shop, never to a source shop/category. */
@Service
public class ProductListingLogisticsService {
    private final JdbcTemplate db;
    private final ShopAuthorizationService shops;
    private final ShopeeOfficialClient shopee;
    private final ObjectMapper json;
    public ProductListingLogisticsService(JdbcTemplate db, ShopAuthorizationService shops,
                                         ShopeeOfficialClient shopee, ObjectMapper json) {
        this.db = db; this.shops = shops; this.shopee = shopee; this.json = json;
    }
    public List<ShopAuthorizationService.ShopView> shops(PlatformPrincipal user) {
        ProductListingTemplateService.requireAccess(user, false);
        return shops.list(user, "SHOPEE").stream().filter(shop -> "ACTIVE".equals(shop.status())).toList();
    }
    public Config config(String org, String shopId) {
        var rows = db.query("""
                SELECT selection_mode,channel_ids_json,version FROM product_listing_logistics
                WHERE organization_id=? AND shop_id=?
                """, (rs, n) -> new Config(rs.getString(1), ids(read(rs.getString(2))), rs.getLong(3)), org, shopId);
        return rows.isEmpty() ? new Config("ALL_ENABLED", List.of(), 0) : rows.getFirst();
    }
    public View get(String shopId, PlatformPrincipal user) {
        ProductListingTemplateService.requireAccess(user, false);
        var connection = shops.connectionForPublishing(user.organizationId(), shopId, MarketplacePlatform.SHOPEE);
        return view(shopId, config(user.organizationId(), shopId), officialChannels(connection));
    }
    public void save(Map<String, Object> body, PlatformPrincipal user) {
        ProductListingTemplateService.requireAccess(user, true);
        String shopId = String.valueOf(body.getOrDefault("shopId", ""));
        var connection = shops.connectionForPublishing(user.organizationId(), shopId, MarketplacePlatform.SHOPEE);
        List<Channel> channels = officialChannels(connection);
        String mode = String.valueOf(body.get("mode"));
        if (!Set.of("ALL_ENABLED", "SELECTED").contains(mode)) throw bad("请选择物流启用方式");
        List<String> selected = "ALL_ENABLED".equals(mode) ? List.of() : ids(body.get("channelIds"));
        select(channels, new Config(mode, selected, 0)); // Validate against this shop before persisting.
        long version;
        try { version = Long.parseLong(String.valueOf(body.get("version"))); }
        catch (Exception e) { throw bad("请重新读取店铺物流后保存"); }
        int count;
        if (version == 0) {
            count = db.update("""
                    INSERT IGNORE INTO product_listing_logistics
                    (organization_id,shop_id,selection_mode,channel_ids_json,version,updated_by)
                    VALUES (?,?,?,?,1,?)
                    """, user.organizationId(), shopId, mode, write(selected), user.userId());
        } else if (version > 0) {
            count = db.update("""
                    UPDATE product_listing_logistics SET selection_mode=?,channel_ids_json=?,version=version+1,
                    updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
                    WHERE organization_id=? AND shop_id=? AND version=?
                    """, mode, write(selected), user.userId(), user.organizationId(), shopId, version);
        } else throw bad("物流配置版本无效");
        if (count != 1) throw new BusinessException(HttpStatus.CONFLICT, "物流配置已被修改，请重新选择店铺后保存");
    }
    public Map<String, Object> snapshot(String org, String shopId) {
        Config config = config(org, shopId);
        return Map.of("organizationId", org, "shopId", shopId, "mode", config.mode(),
                "channelIds", config.channelIds(), "version", config.version());
    }
    public Map<String, Object> publishSnapshot(String org, String captureId, String key, String shopId) {
        var rows = db.query("""
                SELECT request_json FROM product_publish_workflows
                WHERE organization_id=? AND capture_id=? AND idempotency_key=?
                """, (rs, n) -> readMap(rs.getString(1)), org, captureId, key);
        return rows.isEmpty() ? snapshot(org, shopId) : snapshotFrom(rows.getFirst());
    }
    public List<Map<String, Object>> resolve(MarketplaceAccess.Connection connection, Map<String, Object> snapshot) {
        if (!connection.shop().getOrganizationId().equals(snapshot.get("organizationId"))
                || !connection.shop().getId().equals(snapshot.get("shopId")))
            throw new BusinessException(HttpStatus.CONFLICT, "物流配置与当前公司或目标店铺不一致");
        var config = new Config(String.valueOf(snapshot.get("mode")), ids(snapshot.get("channelIds")), 0);
        return select(officialChannels(connection), config);
    }
    public static List<Map<String, Object>> select(List<Channel> channels, Config config) {
        Set<String> available = new LinkedHashSet<>();
        channels.stream().filter(Channel::enabled).forEach(channel -> available.add(channel.id()));
        List<String> selected;
        if ("ALL_ENABLED".equals(config.mode())) selected = List.copyOf(available);
        else if ("SELECTED".equals(config.mode())) {
            selected = config.channelIds();
            if (selected.stream().anyMatch(id -> !available.contains(id)))
                throw new BusinessException(HttpStatus.CONFLICT, "配置的物流渠道已停用或不属于当前店铺，请重新配置物流");
        } else throw bad("物流启用方式无效");
        if (selected.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "目标店铺没有已选且已开通的物流渠道");
        // Do not hardcode prices, size limits, or custom shipping fees from a screenshot.
        return channels.stream().filter(Channel::enabled).map(channel ->
                Map.<String, Object>of("logistic_id", Long.parseLong(channel.id()),
                        "enabled", selected.contains(channel.id()))).toList();
    }
    private List<Channel> officialChannels(MarketplaceAccess.Connection connection) {
        Object raw = shopee.shopGet("/api/v2/logistics/get_channel_list", connection, Map.of()).get("response");
        if (!(raw instanceof Map<?, ?> response)
                || !(response.get("logistics_channel_list") instanceof List<?> list) || list.isEmpty())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "暂未读取到店铺物流渠道，请稍后重试");
        List<Channel> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> channel)) throw bad("店铺返回的物流渠道格式不完整");
            String id = validId(channel.get("logistics_channel_id"));
            if (!seen.add(id)) throw bad("店铺返回了重复的物流渠道");
            String name = String.valueOf(channel.getOrDefault("logistics_channel_name", null));
            result.add(new Channel(id, "null".equals(name) ? "渠道 " + id : name,
                    Boolean.TRUE.equals(channel.get("enabled")), false));
        }
        return List.copyOf(result);
    }
    private View view(String shopId, Config config, List<Channel> channels) {
        return new View(shopId, config.mode(), config.version(), channels.stream().map(channel ->
                new Channel(channel.id(), channel.name(), channel.enabled(), channel.enabled()
                        && ("ALL_ENABLED".equals(config.mode()) || config.channelIds().contains(channel.id())))).toList());
    }
    private static List<String> ids(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 100) throw bad("物流渠道选择格式无效");
        List<String> ids = list.stream().map(ProductListingLogisticsService::validId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw bad("不能重复选择物流渠道");
        return ids;
    }
    private static String validId(Object value) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            if (id <= 0) throw new NumberFormatException();
            return String.valueOf(id);
        } catch (Exception e) { throw bad("物流渠道 ID 无效"); }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> snapshotFrom(Map<String, Object> body) {
        return body.get("listingLogisticsSnapshot") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }
    public static void apply(Map<String, Object> body, Map<String, Object> snapshot) {
        body.remove("listingLogisticsSnapshot");
        if (snapshot != null && !snapshot.isEmpty()) {
            body.put("listingLogisticsSnapshot", snapshot);
            // The provider derives logistics from this server-owned snapshot. Browser choices
            // must not change the request fingerprint when a pending workflow is retried.
            body.remove("logisticInfo");
        }
    }
    private List<?> read(String source) {
        try { return json.readValue(source, List.class); }
        catch (Exception e) { throw new IllegalStateException("物流配置无法读取", e); }
    }
    private Map<String, Object> readMap(String source) {
        try { return json.readValue(source, Map.class); }
        catch (Exception e) { throw new IllegalStateException("物流快照无法读取", e); }
    }
    private String write(Object value) { return json.writeValueAsString(value); }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
    public record Config(String mode, List<String> channelIds, long version) {}
    public record Channel(String id, String name, boolean enabled, boolean selected) {}
    public record View(String shopId, String mode, long version, List<Channel> channels) {}
}
