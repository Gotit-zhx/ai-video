package com.yingdao.aicommerce.product.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifies Shopee's different encodes of one video without merging unrelated URLs. */
public final class CaptureVideoSourceKey {
    private static final Pattern SHOPEE_VIDEO_PATH = Pattern.compile(
            "^/api/v[0-9]+/[0-9]+/mms/([a-z]{2}-[0-9]+-[a-z0-9]+-[a-z0-9]+)(?:\\.(?:default|[0-9]+))?\\.mp4$");

    private CaptureVideoSourceKey() {}

    public static String key(String sourceUrl) {
        String identity = "url:" + sourceUrl;
        try {
            URI uri = URI.create(sourceUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean shopeeCdn = host.endsWith(".vod.susercontent.com") || host.endsWith(".vod.shopee.com");
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && shopeeCdn) {
                var match = SHOPEE_VIDEO_PATH.matcher(uri.getRawPath());
                if (match.matches()) identity = "shopee-video:" + match.group(1);
            }
        } catch (IllegalArgumentException ignored) {
            // Unknown or malformed source URLs retain their exact identity.
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
