package com.yingdao.aicommerce.shop.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Resolves current draft-listing capability for the exact authorized destination shop. */
public final class TikTokShopListingCapabilityResolver {
    public static final String RULES_PATH = "/product/202507/global_listing_rules";
    private static final String LOCAL_REPLICATION = "LOCAL_REPLICATION";
    private final TikTokShopOfficialClient client;

    public TikTokShopListingCapabilityResolver(TikTokShopOfficialClient client) {
        this.client = client;
    }

    public Capability resolve(MarketplaceAccess.Connection connection) {
        String sellerType = connection.shop().getSellerType();
        sellerType = sellerType == null ? "" : sellerType.trim().toUpperCase(Locale.ROOT);
        // Preserve the already-supported local flow without an additional network dependency.
        if ("LOCAL".equals(sellerType)) return new Capability("LOCAL", null, List.of());
        if (!"CROSS_BORDER".equals(sellerType)) {
            throw unavailable("店铺 seller_type 缺失或不受支持，请重新授权确认店铺类型");
        }
        String cipher = connection.shop().getShopCipher();
        if (cipher == null || cipher.isBlank()) {
            throw unavailable("目标店铺缺少 shop_cipher，请重新授权");
        }
        Map<String, Object> response;
        try {
            response = client.apiGet(RULES_PATH, connection.tokens().accessToken(), cipher, Map.of());
        } catch (RuntimeException exception) {
            BusinessException failure = new BusinessException(HttpStatus.BAD_GATEWAY,
                    "跨境刊登模式未就绪：读取 TikTok 官方刊登能力失败，请稍后重试；尚未提交商品");
            failure.initCause(exception);
            throw failure;
        }
        Object data = response == null ? null : response.get("data");
        Object methods = data instanceof Map<?, ?> map ? map.get("listing_methods") : null;
        boolean supported;
        if (methods instanceof String method) {
            supported = LOCAL_REPLICATION.equals(method);
        } else if (methods instanceof List<?> values && values.stream().allMatch(String.class::isInstance)) {
            supported = values.contains(LOCAL_REPLICATION);
        } else {
            throw unavailable("官方未返回可识别的 listing_methods，无法确认 LOCAL_REPLICATION；尚未提交商品");
        }
        if (!supported) {
            throw unavailable("官方尚未确认此店铺支持 LOCAL_REPLICATION；旧 GLOBAL_PUBLISHING 已退役，"
                    + "请在 TikTok Shop 完成跨境刊登模式迁移后重试，尚未提交商品");
        }
        String requestId = response.get("request_id") instanceof String id && !id.isBlank() ? id : null;
        return new Capability(LOCAL_REPLICATION, requestId,
                inventoryRules(((Map<?, ?>) data).get("inventory_rules")));
    }

    private List<Map<String, Object>> inventoryRules(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw unavailable("官方未返回有效的 inventory_rules，无法确认目标仓库；尚未提交商品");
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        Set<String> warehouses = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)
                    || !(source.get("local_warehouse_id") instanceof String warehouse) || warehouse.isBlank()
                    || !(source.get("allocation_mode") instanceof String mode)
                    || !Set.of("SHARED", "DYNAMIC", "MANUAL").contains(mode)
                    || !warehouses.add(warehouse)) {
                throw unavailable("官方 inventory_rules 仓库或库存分配模式无法确认；尚未提交商品");
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("local_warehouse_id", warehouse);
            rule.put("allocation_mode", mode);
            if (source.get("associated_warehouses") != null) {
                if (!(source.get("associated_warehouses") instanceof List<?> associated)) {
                    throw unavailable("官方 inventory_rules 关联仓库格式无法确认；尚未提交商品");
                }
                List<Map<String, Object>> related = new ArrayList<>();
                for (Object item : associated) {
                    if (!(item instanceof Map<?, ?> entry)
                            || !(entry.get("warehouse_id") instanceof String id) || id.isBlank()
                            || !(entry.get("region") instanceof String region) || region.isBlank()) {
                        throw unavailable("官方 inventory_rules 关联仓库信息无法确认；尚未提交商品");
                    }
                    related.add(Map.of("warehouse_id", id, "region", region));
                }
                rule.put("associated_warehouses", List.copyOf(related));
            }
            rules.add(Map.copyOf(rule));
        }
        return List.copyOf(rules);
    }

    private static BusinessException unavailable(String detail) {
        return new BusinessException(HttpStatus.CONFLICT, "跨境刊登模式未就绪：" + detail);
    }

    public record Capability(String listingMethod, String requestId, List<Map<String, Object>> inventoryRules) {
        public Capability { inventoryRules = List.copyOf(inventoryRules); }

        public Map<String, Object> requireWarehouse(String warehouseId) {
            if (!LOCAL_REPLICATION.equals(listingMethod)) return Map.of();
            return inventoryRules.stream().filter(rule -> warehouseId.equals(rule.get("local_warehouse_id")))
                    .findFirst().orElseThrow(() -> unavailable(
                            "所选发货仓库不属于官方确认的目标店铺 inventory_rules，请重新读取店铺仓库；尚未提交商品"));
        }
    }
}
