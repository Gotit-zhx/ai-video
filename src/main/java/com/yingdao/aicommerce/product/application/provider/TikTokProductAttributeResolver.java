package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves target-category product attributes from collected facts, without inferring missing facts.
 * Official contracts: get-attributes-202309 and create-product-202309 (verified 2026-09-10).
 * Get Attributes spells its required flag "is_requried"; accept the corrected spelling as well.
 */
public final class TikTokProductAttributeResolver {
    private static final Map<String, String> PROPERTY_NAMES = aliases(new String[][] {
            {"product condition", "condition", "item condition", "keadaan", "keadaan barang", "kondisi barang", "商品状况", "商品成色", "成色"},
            {"material", "fabric", "bahan", "材质", "面料", "材料"},
            {"neckline", "neck line", "garis leher", "领型", "领口"},
            {"sleeve length", "panjang lengan baju", "panjang lengan", "袖长"},
            {"pattern", "corak", "图案"},
            {"style", "gaya", "风格"},
            {"season", "musim", "季节"},
            {"fit", "fit type", "potongan", "版型"},
            {"occasion", "peristiwa", "场合"},
            {"size type", "jenis saiz", "尺码类型"},
            {"clothing length", "clothes length", "panjang pakaian", "衣长"},
            {"quantity per pack", "quantity in pack", "pack quantity", "kuantiti setiap pek", "每包数量"}
    });
    private static final Map<String, Map<String, String>> VALUE_NAMES = Map.ofEntries(
            Map.entry("productcondition", aliases(new String[][] {
                    {"new", "brand new", "baru", "全新", "https://schema.org/NewCondition", "http://schema.org/NewCondition"},
                    {"used", "second hand", "second-hand", "terpakai", "二手", "https://schema.org/UsedCondition", "http://schema.org/UsedCondition"},
                    {"refurbished", "翻新", "https://schema.org/RefurbishedCondition", "http://schema.org/RefurbishedCondition"}})),
            Map.entry("material", aliases(new String[][] {
                    {"polyester", "poliester", "聚酯纤维", "涤纶"}, {"cotton", "kapas", "棉"},
                    {"nylon", "nilon", "尼龙", "锦纶"}, {"linen", "亚麻"}, {"silk", "sutera", "真丝"}})),
            Map.entry("neckline", aliases(new String[][] {
                    {"crew neck", "crewneck", "leher krew", "圆领"}, {"v neck", "v-neck", "领v"}})),
            Map.entry("sleevelength", aliases(new String[][] {
                    {"short sleeve", "short sleeves", "lengan pendek", "短袖"},
                    {"long sleeve", "long sleeves", "lengan panjang", "长袖"},
                    {"sleeveless", "tanpa lengan", "无袖"}})),
            Map.entry("pattern", aliases(new String[][] {
                    {"plain", "solid", "solid color", "polos", "纯色"},
                    {"striped", "stripes", "berjalur", "条纹"}, {"floral", "bunga", "花卉"}})),
            Map.entry("style", aliases(new String[][] {
                    {"basic", "asas", "基础"}, {"japanese", "japanese style", "jepun", "日系"},
                    {"cute", "comel", "可爱"}, {"korean", "korean style", "korea", "韩系"}})),
            Map.entry("season", aliases(new String[][] {
                    {"summer", "musim panas", "夏季"}, {"winter", "musim sejuk", "冬季"},
                    {"spring", "musim bunga", "春季"}, {"autumn", "fall", "musim luruh", "秋季"}})),
            Map.entry("fit", aliases(new String[][] {
                    {"slim fit", "slim fitting", "muat ramping", "修身"},
                    {"regular fit", "regular fitting", "regular", "常规"}, {"loose fit", "loose", "宽松"}})),
            Map.entry("occasion", aliases(new String[][] {{"casual", "santai", "休闲"}})),
            Map.entry("sizetype", aliases(new String[][] {{"regular", "biasa", "常规"}, {"plus size", "大码"}})));

    private final TikTokShopOfficialClient client;
    private final ObjectMapper objectMapper;

    public TikTokProductAttributeResolver(TikTokShopOfficialClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /** The category version must be the same target version used to select categoryId and create the product. */
    public List<Map<String, Object>> resolve(MarketplaceAccess.Connection connection, ProductCapture capture,
                                             String categoryId, String categoryVersion, Object suppliedAttributes) {
        if (categoryId == null || !categoryId.matches("[0-9]+")) throw conflict("TikTok Shop 目标叶子类目 ID 无效");
        if (categoryVersion == null || !Set.of("v1", "v2").contains(categoryVersion)) throw conflict("TikTok Shop 目标类目版本无效");
        Map<String, Object> response = client.apiGet(
                "/product/" + client.apiVersion() + "/categories/" + categoryId + "/attributes",
                connection.tokens().accessToken(), connection.shop().getShopCipher(),
                Map.of("locale", "en-US", "category_version", categoryVersion));
        Object definitions = object(response.get("data")).get("attributes");
        if (!(definitions instanceof List<?> list)) throw conflict("TikTok Shop 官方类目属性响应缺少 attributes，未创建商品");
        return resolveFromDefinitions(capture, list, suppliedAttributes);
    }

    public List<Map<String, Object>> resolveFromDefinitions(ProductCapture capture, List<?> definitions,
                                                            Object suppliedAttributes) {
        Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
        for (Object raw : definitions) {
            Map<String, Object> definition = object(raw);
            if ("SALES_PROPERTY".equals(text(definition.get("type")))) continue;
            if (!"PRODUCT_PROPERTY".equals(text(definition.get("type")))
                    || text(definition.get("id")) == null || text(definition.get("name")) == null) {
                throw conflict("TikTok Shop 官方商品属性定义不完整，未创建商品");
            }
            String id = text(definition.get("id"));
            if (properties.putIfAbsent(id, definition) != null) throw conflict("TikTok Shop 官方商品属性 ID 重复：" + id);
        }
        Map<String, List<String>> source = sourceAttributes(capture);
        Map<String, List<Map<String, Object>>> resolved = new LinkedHashMap<>();
        if (suppliedAttributes != null && !(suppliedAttributes instanceof List<?>)) {
            throw conflict("TikTok Shop 商品属性参数必须是列表");
        }
        if (suppliedAttributes instanceof List<?> supplied) {
            for (Object raw : supplied) {
                Map<String, Object> attribute = object(raw);
                String id = text(attribute.get("id"));
                Map<String, Object> definition = properties.get(id);
                if (definition == null) throw conflict("TikTok Shop 商品属性不属于目标类目：" + (id == null ? "缺少属性 ID" : id));
                if (resolved.containsKey(id)) throw conflict("TikTok Shop 商品属性重复：" + name(definition));
                List<Map<String, Object>> values = suppliedValues(definition, attribute.get("values"));
                if (values.isEmpty()) throw conflict("TikTok Shop 商品属性缺少有效值：" + name(definition));
                resolved.put(id, values);
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
            if (resolved.containsKey(entry.getKey())) continue;
            List<String> values = source.getOrDefault(propertyName(name(entry.getValue())), List.of());
            List<Map<String, Object>> matched = sourceValues(entry.getValue(), values);
            if (!matched.isEmpty()) resolved.put(entry.getKey(), matched);
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
            if (!resolved.containsKey(entry.getKey()) && required(entry.getValue(), resolved)) {
                missing.add(name(entry.getValue()) + "（" + entry.getKey() + "）");
            }
        }
        if (!missing.isEmpty()) throw conflict("TikTok Shop 目标类目缺少可可靠匹配的必填商品属性："
                + String.join("、", missing) + "。原商品资料未提供有效值，未创建商品");
        List<Map<String, Object>> result = new ArrayList<>();
        resolved.forEach((id, values) -> result.add(Map.of("id", id, "values", List.copyOf(values))));
        return List.copyOf(result);
    }

    private List<Map<String, Object>> sourceValues(Map<String, Object> definition, List<String> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(values)) {
            Map<String, Object> whole = matchBuiltIn(definition, raw);
            if (whole != null) { result.add(whole); continue; }
            String[] parts = raw.split("[,;，；、|]", -1);
            if (parts.length > 1 && !flag(definition.get("is_multiple_selection"))) return List.of();
            for (String part : parts) {
                Map<String, Object> matched = matchName(definition, part.trim());
                // Do not choose only the recognized half of a mixed or ambiguous source value.
                if (matched == null) return List.of();
                result.add(matched);
            }
        }
        result = distinct(result);
        return result.size() > 1 && !flag(definition.get("is_multiple_selection")) ? List.of() : result;
    }

    private List<Map<String, Object>> suppliedValues(Map<String, Object> definition, Object supplied) {
        if (!(supplied instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> value = object(raw), matched;
            String id = text(value.get("id"));
            if (id != null) {
                boolean known = objects(definition.get("values")).stream().anyMatch(candidate -> id.equals(text(candidate.get("id"))));
                matched = known ? Map.of("id", id) : null;
            } else matched = matchName(definition, text(value.get("name")));
            if (matched == null) throw conflict("TikTok Shop 商品属性值不属于目标类目或不允许自定义：" + name(definition));
            result.add(matched);
        }
        List<Map<String, Object>> unique = distinct(result);
        if (unique.size() != result.size()) throw conflict("TikTok Shop 商品属性值重复：" + name(definition));
        if (unique.size() > 1 && !flag(definition.get("is_multiple_selection"))) throw conflict("TikTok Shop 商品属性只允许单选：" + name(definition));
        return unique;
    }

    private Map<String, Object> matchName(Map<String, Object> definition, String value) {
        if (value == null || value.isBlank()) return null;
        Map<String, Object> builtIn = matchBuiltIn(definition, value);
        if (builtIn != null) return builtIn;
        if (matchingBuiltInIds(definition, value).size() > 1) return null;
        if (!flag(definition.get("is_customizable")) || value.length() > 2000) return null;
        String format = text(definition.get("value_data_format"));
        if (format != null) {
            if (!"POSITIVE_INT_OR_DECIMAL".equals(format)) return null;
            try { if (new BigDecimal(value).signum() <= 0) return null; }
            catch (NumberFormatException ignored) { return null; }
        }
        return Map.of("name", value);
    }

    private Map<String, Object> matchBuiltIn(Map<String, Object> definition, String value) {
        if (value == null || value.isBlank()) return null;
        Set<String> matches = matchingBuiltInIds(definition, value);
        return matches.size() == 1 ? Map.of("id", matches.iterator().next()) : null;
    }

    private Set<String> matchingBuiltInIds(Map<String, Object> definition, String value) {
        String property = propertyName(name(definition));
        Set<String> matches = new LinkedHashSet<>();
        for (Map<String, Object> candidate : objects(definition.get("values"))) {
            String id = text(candidate.get("id")), label = text(candidate.get("name"));
            if (id != null && label != null && valueName(property, value).equals(valueName(property, label))) matches.add(id);
        }
        return matches;
    }

    private boolean required(Map<String, Object> definition, Map<String, List<Map<String, Object>>> resolved) {
        if (flag(definition.get("is_required")) || flag(definition.get("is_requried"))) return true;
        Object raw = definition.get("requirement_conditions");
        if (raw == null) return false;
        if (!(raw instanceof List<?> conditions)) throw conflict("TikTok Shop 必填条件定义无效：" + name(definition));
        for (Object condition : conditions) {
            Map<String, Object> rule = object(condition);
            if (!"VALUE_ID_MATCH".equals(text(rule.get("condition_type")))
                    || text(rule.get("attribute_id")) == null || text(rule.get("attribute_value_id")) == null) {
                throw conflict("TikTok Shop 暂不能确认商品属性的必填条件：" + name(definition));
            }
            if (resolved.getOrDefault(text(rule.get("attribute_id")), List.of()).stream()
                    .anyMatch(value -> text(rule.get("attribute_value_id")).equals(text(value.get("id"))))) return true;
        }
        return false;
    }

    private Map<String, List<String>> sourceAttributes(ProductCapture capture) {
        Map<String, Object> normalized;
        try {
            normalized = capture.getNormalizedData() == null || capture.getNormalizedData().isBlank() ? Map.of()
                    : objectMapper.readValue(capture.getNormalizedData(), new TypeReference<>() {});
        } catch (Exception exception) { throw conflict("原商品属性数据无法读取，未创建商品"); }
        if (normalized == null) normalized = Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : List.of("attributes", "productAttributes", "product_attributes", "productProperties", "product_properties")) {
            collectSource(normalized.get(key), result);
        }
        // Only explicit product-condition facts count; titles, item status, booleans and source IDs do not imply "New".
        for (String key : List.of("condition", "productCondition", "product_condition", "itemCondition", "item_condition")) {
            addSource(result, "product condition", normalized.get(key));
        }
        return result;
    }

    private void collectSource(Object raw, Map<String, List<String>> result) {
        if (raw instanceof List<?> list) { list.forEach(value -> collectSource(value, result)); return; }
        if (!(raw instanceof Map<?, ?>)) return;
        Map<String, Object> source = object(raw);
        String label = firstText(source, "name", "attribute_name", "attributeName", "property_name", "propertyName");
        if (label != null) {
            for (String key : List.of("values", "value", "value_name", "valueName")) {
                if (source.containsKey(key)) { addSource(result, label, source.get(key)); return; }
            }
            return;
        }
        source.forEach((name, value) -> addSource(result, name, value));
    }

    private void addSource(Map<String, List<String>> result, String name, Object rawValue) {
        List<String> values = sourceTexts(rawValue);
        if (!values.isEmpty()) result.computeIfAbsent(propertyName(name), ignored -> new ArrayList<>()).addAll(values);
    }

    private List<String> sourceTexts(Object raw) {
        if (raw instanceof List<?> list) return list.stream().flatMap(value -> sourceTexts(value).stream()).toList();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> value = object(raw);
            for (String key : List.of("name", "value_name", "valueName", "value", "values")) {
                if (value.containsKey(key)) return sourceTexts(value.get(key));
            }
            return List.of(); // Source IDs are never interpreted as target IDs or names.
        }
        String value = text(raw);
        return value == null ? List.of() : List.of(value);
    }

    private static List<Map<String, Object>> distinct(List<Map<String, Object>> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
    private static String name(Map<String, Object> definition) { return text(definition.get("name")); }
    private static boolean flag(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)); }
    private static String text(Object value) {
        return value instanceof String text ? text.trim().isEmpty() ? null : text.trim()
                : value instanceof Number || value instanceof Boolean ? String.valueOf(value) : null;
    }
    private static String firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) { String value = text(values.get(key)); if (value != null) return value; }
        return null;
    }
    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> list ? list.stream().map(TikTokProductAttributeResolver::object).toList() : List.of();
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String propertyName(String value) { return PROPERTY_NAMES.getOrDefault(normalize(value), normalize(value)); }
    private static String valueName(String property, String value) {
        return VALUE_NAMES.getOrDefault(property, Map.of()).getOrDefault(normalize(value), normalize(value));
    }
    private static Map<String, String> aliases(String[][] groups) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] group : groups) for (String value : group) result.put(normalize(value), normalize(group[0]));
        return Map.copyOf(result);
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
