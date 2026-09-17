package com.yingdao.aicommerce.product.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Optional real-file regression: gray-source white-source report-path. No model or archive writes. */
class WatermarkBackgroundFixtureCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("gray-source white-source report-path required");
        String gray = check(Path.of(args[0]), false), white = check(Path.of(args[1]), true);
        String report = "{\"tests\":2,\"failures\":0,\"gray\":" + gray + ",\"whiteT\":" + white + "}\n";
        Files.writeString(Path.of(args[2]), report);
        System.out.println(report);
    }

    private static String check(Path path, boolean expected) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        Map<String,Object> result = WatermarkDetectionView.assessBackground(bytes);
        boolean actual = WatermarkDetectionView.permitsSkipping(result);
        if (expected != actual) throw new AssertionError("Background eligibility regression: " + path.getFileName());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return "{\"sourceFile\":" + quote(path.getFileName().toString()) + ",\"sourceSha256\":" + quote(hash)
                + ",\"eligibleToSkip\":" + actual + ",\"nearWhiteFraction\":" + result.get("nearWhiteFraction")
                + ",\"borderNearWhiteFraction\":" + result.get("borderNearWhiteFraction")
                + ",\"sampleWidth\":" + result.get("sampleWidth") + ",\"sampleHeight\":" + result.get("sampleHeight") + "}";
    }

    private static String quote(String text) {
        var result = new StringBuilder("\"");
        for (char value : text.toCharArray()) {
            if (value == '\\' || value == '"') result.append('\\').append(value);
            else if (value < 32) result.append(String.format("\\u%04x", (int) value));
            else result.append(value);
        }
        return result.append('"').toString();
    }
}
