package com.yingdao.aicommerce.video.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class VideoReferenceLinkService {
    private final String publicBaseUrl;
    private final boolean allowInsecurePublicHttp;
    private final byte[] signingSecret;
    private final Duration linkTtl;

    public VideoReferenceLinkService(
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${app.allow-insecure-public-http:false}") boolean allowInsecurePublicHttp,
            @Value("${app.video.reference-link-secret:${app.model-secret-key}}") String signingSecret,
            @Value("${app.video.reference-link-ttl:6h}") Duration linkTtl) {
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.allowInsecurePublicHttp = allowInsecurePublicHttp;
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("视频参考素材链接签名密钥不能为空");
        }
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.linkTtl = linkTtl == null || linkTtl.isZero() || linkTtl.isNegative()
                ? Duration.ofHours(6) : linkTtl;
    }

    public String asset(String assetId) { return link("asset", assetId); }
    public String deliveryAsset(String assetId) { return link("delivery-asset", assetId); }
    public String source(String itemId) { return link("source", itemId); }
    public String scriptSource(String scriptId) { return link("script-source", scriptId); }
    public String scriptAudio(String scriptId) {
        return link("script-audio", scriptId, "/transcription-audio.mp3");
    }
    public String taskAudio(String taskId) { return link("task-audio", taskId); }

    public boolean isValid(String kind, String id, long expires, String signature) {
        if (kind == null || kind.isBlank() || id == null || id.isBlank()
                || signature == null || signature.isBlank()) return false;
        long now = Instant.now().getEpochSecond();
        if (expires < now) return false;
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return MessageDigest.isEqual(sign(kind, id, expires), actual);
    }

    public boolean isPubliclyReachable() {
        String value = publicBaseUrl.toLowerCase();
        boolean supportedScheme = value.startsWith("https://")
                || (allowInsecurePublicHttp && value.startsWith("http://"));
        return supportedScheme && !value.contains("localhost") && !value.contains("127.0.0.1");
    }

    private String link(String kind, String id) {
        return link(kind, id, "");
    }

    private String link(String kind, String id, String suffix) {
        long expires = Instant.now().plus(linkTtl).getEpochSecond();
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(kind, id, expires));
        return publicBaseUrl + "/api/public/v1/video-references/" + kind + "/" + id + suffix
                + "?expires=" + expires + "&signature=" + signature;
    }

    private byte[] sign(String kind, String id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return mac.doFinal((kind + "\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("视频参考素材链接签名失败", exception);
        }
    }
}
