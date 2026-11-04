package com.yingdao.aicommerce.product.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Stable merchant codes for captured variants; source IDs remain separate. */
final class ProductSkuNaming {
    static final String METADATA = "_merchantSkuNaming";
    private static final String VERSION = "1";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{1,14}-[A-Z][0-9]{6}-[A-Z0-9]{1,10}-[A-F0-9]{8}");
    private static final Pattern STYLE_SUFFIX = Pattern.compile("^(.+?)\\s*[/\\-]\\s*([1-9][0-9]*)$");
    private static final Set<String> SIZE_NAMES = Set.of("size", "sizes", "尺码", "尺碼", "尺寸", "ukuran", "ไซส์", "ขนาด", "kích thước");
    private static final Set<String> PATTERN_NAMES = Set.of("pattern", "print", "design", "style", "motif", "款式", "图案", "圖案", "花型", "款号", "款號", "corak");
    private static final Map<String,String> COLORS = colors();

    private ProductSkuNaming() {}

    static List<Map<String,Object>> assign(String organization, String sourceIdentity,
                                          List<Map<String,Object>> requested,
                                          List<Map<String,Object>> previous,
                                          Function<String,String> styleCodes) {
        Map<String,Map<String,Object>> saved = new HashMap<>();
        for (var sku : previous) {
            String id = externalId(sku);
            if (!id.isBlank()) saved.put(id, sku);
        }
        Set<String> numberedColors = numberedColors(requested);
        var result = new ArrayList<Map<String,Object>>();
        var used = new HashSet<String>();
        for (var source : requested) {
            var sku = new LinkedHashMap<String,Object>(source);
            Map<String,Object> attributes = map(first(source, "attributes", "options", "properties"));
            String colorLabel = dimension(attributes, true);
            String pattern = pattern(attributes);
            var suffix = STYLE_SUFFIX.matcher(colorLabel);
            if (pattern.isBlank() && suffix.matches() && numberedColors.contains(normalize(suffix.group(1)))) {
                pattern = "source-style:" + suffix.group(2);
                colorLabel = suffix.group(1).strip();
            } else if (pattern.isBlank() && "PWH".equals(color(colorLabel))) {
                // Sources use "Pure white" for an independent unprinted option.
                // Keep this named option separate unless an explicit pattern
                // attribute proves it belongs to another color's pattern.
                pattern = "source-option:PUREWHITE";
            } else if (pattern.isBlank() && !numberedColors.isEmpty()) {
                // A plain, unnumbered color must not be merged into one of the printed variants.
                pattern = "unnumbered:" + normalize(colorLabel);
            }
            String color = color(colorLabel);
            String size = size(dimension(attributes, false));
            // The source's pattern group, not variant IDs/colors/sizes, owns the style code.
            String identity = organization + "\n" + sourceIdentity + "\n" + pattern;
            String style = styleCodes.apply(digest(identity));
            if (style == null || !style.matches("[A-Z][0-9]{6}")) throw new IllegalArgumentException("款式编码不正确");
            String prefix = color + "-" + style + "-" + size + "-";
            String id = externalId(source);
            Map<String,Object> old = id.isBlank() ? Map.of() : saved.getOrDefault(id, Map.of());
            String merchant = reusable(old, prefix);
            if (merchant == null) {
                do { merchant = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT); }
                while (used.contains(merchant));
            }
            if (!used.add(merchant)) throw new IllegalArgumentException("采集 SKU 身份重复，不能生成重复商家编码");
            var metadata = new LinkedHashMap<String,Object>();
            metadata.put("version", VERSION);
            metadata.put("styleCode", style);
            metadata.put("color", color);
            metadata.put("size", size);
            metadata.put("patternGroup", pattern.isBlank() ? "SOURCE_PRODUCT" : pattern);
            metadata.put("sourceIdentity", sourceIdentity);
            metadata.put("originalSellerSku", text(first(source, "sellerSku", "sku", "skuCode")));
            if (!old.isEmpty() && map(old.get(METADATA)).containsKey("originalSellerSku"))
                metadata.put("originalSellerSku", map(old.get(METADATA)).get("originalSellerSku"));
            sku.put("sellerSku", merchant);
            sku.put(METADATA, metadata);
            result.add(sku);
        }
        return result;
    }

    private static String reusable(Map<String,Object> old, String prefix) {
        if (!VERSION.equals(text(map(old.get(METADATA)).get("version")))) return null;
        String code = text(old.get("sellerSku"));
        return CODE.matcher(code).matches() && code.startsWith(prefix) ? code : null;
    }

    private static String pattern(Map<String,Object> attributes) {
        var selected = new TreeMap<String,String>();
        for (var entry : attributes.entrySet()) {
            String name = normalize(entry.getKey()), value = normalize(text(entry.getValue()));
            if (PATTERN_NAMES.contains(name) && !value.isBlank()) selected.put(name, value);
        }
        return selected.isEmpty() ? "" : selected.toString();
    }

    private static Set<String> numberedColors(List<Map<String,Object>> skus) {
        var result = new HashSet<String>();
        for (var sku : skus) {
            String label = dimension(map(first(sku, "attributes", "options", "properties")), true);
            var m = STYLE_SUFFIX.matcher(label);
            // A known color followed by a source print number is a stable pattern
            // axis even when only one color/print is currently present.
            if (m.matches() && COLORS.containsKey(normalize(m.group(1))))
                result.add(normalize(m.group(1)));
        }
        return result;
    }

    private static String dimension(Map<String,Object> attributes, boolean color) {
        for (var entry : attributes.entrySet())
            if (color ? CommerceSourceColorResolver.isColorDimension(entry.getKey()) : SIZE_NAMES.contains(normalize(entry.getKey())))
                return text(entry.getValue()).strip();
        return "";
    }

    static String color(String value) {
        String key = normalize(value);
        String known = COLORS.get(key);
        if (known != null) return known;
        if (key.isBlank()) return "UNK";
        String ascii = ascii(key);
        // Unknown English labels get a stable short token, without guessing their color.
        if (key.matches("[a-z\\s_-]+") && !ascii.isBlank())
            return ascii.length() <= 3 ? ascii : ascii.substring(0, 2) + digest(key).substring(0, 6);
        return "UNK";
    }

    static String size(String value) {
        String key = normalize(value);
        if (key.isBlank()) return "NOSIZE";
        if (Set.of("one size", "onesize", "free size", "freesize", "均码", "均碼", "通码", "通碼").contains(key)) return "ONESIZE";
        String ascii = ascii(key);
        if (ascii.matches("X{2,8}L")) return (ascii.length() - 1) + "XL";
        if (ascii.isBlank()) return "SIZE" + digest(key).substring(0, 6);
        return ascii.length() <= 10 ? ascii : ascii.substring(0, 4) + digest(key).substring(0, 6);
    }

    private static String ascii(String value) { return normalize(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }
    private static String normalize(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static String externalId(Map<String,Object> sku) { return text(first(sku, "externalSkuId", "skuId", "id", "modelId")); }
    private static Object first(Map<String,Object> source, String... keys) {
        for (String key : keys) if (source.get(key) != null) return source.get(key);
        return null;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).strip(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return value instanceof Map<?,?> ? (Map<String,Object>) value : Map.of(); }
    private static String digest(String value) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Map<String,String> colors() {
        var result = new HashMap<String,String>();
        aliases(result, "BK", "black", "黑", "黑色", "hitam");
        aliases(result, "WH", "white", "白", "白色", "putih");
        aliases(result, "PWH", "pure white", "纯白", "纯白色", "純白", "純白色");
        aliases(result, "GY", "gray", "grey", "灰", "灰色", "abu abu", "abu-abu");
        aliases(result, "LGY", "light gray", "light grey", "浅灰", "浅灰色", "淺灰", "淺灰色");
        aliases(result, "DGY", "dark gray", "dark grey", "深灰", "深灰色");
        aliases(result, "RD", "red", "红", "红色", "紅", "紅色", "merah");
        aliases(result, "BL", "blue", "蓝", "蓝色", "藍", "藍色", "biru");
        aliases(result, "NV", "navy", "navy blue", "藏青", "藏青色", "深蓝", "深蓝色");
        aliases(result, "LBL", "light blue", "浅蓝", "浅蓝色", "淺藍", "淺藍色");
        aliases(result, "SBL", "sky blue", "天蓝", "天蓝色");
        aliases(result, "GN", "green", "绿", "绿色", "綠", "綠色", "hijau");
        aliases(result, "DGN", "dark green", "深绿", "深绿色", "深綠", "深綠色");
        aliases(result, "YL", "yellow", "黄", "黄色", "黃", "黃色", "kuning");
        aliases(result, "PK", "pink", "粉", "粉色", "粉红", "粉红色", "粉紅", "粉紅色", "merah muda");
        aliases(result, "PU", "purple", "紫", "紫色", "ungu");
        aliases(result, "OR", "orange", "橙", "橙色", "jingga", "oranye");
        aliases(result, "BR", "brown", "棕", "棕色", "咖啡", "咖啡色", "coklat");
        aliases(result, "BG", "beige", "米", "米色", "杏", "杏色");
        aliases(result, "KH", "khaki", "卡其", "卡其色");
        aliases(result, "CR", "cream", "奶油", "奶油色", "奶白", "奶白色");
        aliases(result, "IV", "ivory", "象牙", "象牙色", "象牙白");
        aliases(result, "BD", "burgundy", "wine red", "酒红", "酒红色", "酒紅", "酒紅色");
        aliases(result, "SV", "silver", "银", "银色", "銀", "銀色");
        aliases(result, "GD", "gold", "golden", "金", "金色");
        aliases(result, "MC", "multicolor", "multi color", "multi-color", "彩色", "多色");
        return Map.copyOf(result);
    }
    private static void aliases(Map<String,String> map, String code, String... labels) {
        map.put(normalize(code), code);
        for (String label : labels) map.put(normalize(label), code);
    }
}
