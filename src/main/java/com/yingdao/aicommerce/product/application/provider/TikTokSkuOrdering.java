package com.yingdao.aicommerce.product.application.provider;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Orders complete outgoing SKU rows, so stock, price, seller identity and images cannot drift. */
final class TikTokSkuOrdering {
    private TikTokSkuOrdering() {}

    private static final Set<String> SIZE_NAMES = Set.of(
            "size", "sizes", "saiz", "ukuran", "尺码", "尺碼", "尺寸");
    private static final Pattern LETTER_SIZE = Pattern.compile(
            "^(X{0,6}S|M|X{0,6}L|[2-9]X[SL]|10X[SL])(?=$|\\s|[(/-])");
    private static final Pattern NUMBER_SIZE = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d+)?)(?:\\s*(?:CM|码|碼|号|號))?(?:\\s*\\([^)]*\\))?$");

    static List<Map<String, Object>> order(List<Map<String, Object>> rows) {
        if (rows.size() < 2) return rows;
        int dimensions = attributes(rows.getFirst()).size();
        if (dimensions == 0 || rows.stream().anyMatch(row -> attributes(row).size() != dimensions)) return rows;
        var ranks = new ArrayList<Map<String, Integer>>();
        boolean orderedSize = false;
        for (int dimension = 0; dimension < dimensions; dimension++) {
            var options = new LinkedHashMap<String, Size>();
            boolean size = false;
            for (var row : rows) {
                var attribute = attributes(row).get(dimension);
                size |= SIZE_NAMES.contains(normalize(attribute.get("name")).toLowerCase(Locale.ROOT));
                options.putIfAbsent(identity(attribute), parse(attribute.get("value_name")));
            }
            var values = new ArrayList<>(options.keySet());
            // Unknown values stay in their original relative order; never compare an
            // apparel letter scale with a shoe/height number scale or infer value IDs.
            var scales = new HashSet<String>();
            options.values().stream().filter(Objects::nonNull).forEach(value -> scales.add(value.scale()));
            if (size && scales.size() == 1 && options.values().stream().filter(Objects::nonNull).distinct().count() > 1) {
                values.sort(Comparator.comparing(options::get, Comparator.nullsLast(Comparator.comparing(Size::value))));
                orderedSize = true;
            }
            var positions = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < values.size(); i++) positions.put(values.get(i), i);
            ranks.add(positions);
        }
        if (!orderedSize) return rows;
        var sorted = new ArrayList<>(rows);
        sorted.sort((left, right) -> {
            for (int i = 0; i < ranks.size(); i++) {
                int comparison = Integer.compare(ranks.get(i).get(identity(attributes(left).get(i))),
                        ranks.get(i).get(identity(attributes(right).get(i))));
                if (comparison != 0) return comparison;
            }
            return 0;
        });
        return sorted;
    }

    private static Size parse(Object raw) {
        String value = normalize(raw).toUpperCase(Locale.ROOT);
        var letter = LETTER_SIZE.matcher(value);
        if (letter.find()) {
            String label = letter.group(1);
            if (label.equals("M")) return new Size("LETTER", BigDecimal.ONE);
            String prefix = label.substring(0, label.length() - 1);
            int extra = prefix.matches("\\d+X") ? Integer.parseInt(prefix.substring(0, prefix.length() - 1)) : prefix.length();
            return new Size("LETTER", BigDecimal.valueOf(label.endsWith("S") ? -extra : 2 + extra));
        }
        var number = NUMBER_SIZE.matcher(value);
        return number.matches() ? new Size("NUMBER", new BigDecimal(number.group(1))) : null;
    }

    private static String normalize(Object value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC).strip();
    }

    private static String identity(Map<String, Object> attribute) {
        return attribute.containsKey("value_id") ? "id:" + attribute.get("value_id")
                : "name:" + attribute.getOrDefault("value_name", "");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attributes(Map<String, Object> row) {
        return row.get("sales_attributes") instanceof List<?> values
                ? (List<Map<String, Object>>) values : List.of();
    }

    private record Size(String scale, BigDecimal value) {}
}
