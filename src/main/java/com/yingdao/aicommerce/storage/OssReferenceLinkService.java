package com.yingdao.aicommerce.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Compatibility for previously issued private OSS gateway links; new images use OSS directly. */
@Service
public class OssReferenceLinkService {
    private final String publicBaseUrl;
    private final byte[] signingSecret;
    private final Duration ttl;

    public OssReferenceLinkService(
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
            @Value("${app.video.reference-link-secret:${app.model-secret-key}}") String signingSecret,
            @Value("${app.video.reference-link-ttl:72h}") Duration ttl) {
        if (signingSecret == null || signingSecret.isBlank()) throw new IllegalArgumentException("OSS 参考链接签名密钥不能为空");
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(72) : ttl;
    }

    public String ossObject(String location) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(location.getBytes(StandardCharsets.UTF_8));
        long expires = Instant.now().plus(ttl).getEpochSecond();
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(id, expires));
        return publicBaseUrl + "/api/public/v1/video-references/oss-object/" + id
                + "?expires=" + expires + "&signature=" + signature;
    }

    public boolean isValid(String kind, String id, long expires, String signature) {
        if (!"oss-object".equals(kind) || id == null || id.isBlank() || signature == null || signature.isBlank()
                || expires < Instant.now().getEpochSecond()) return false;
        try {
            return MessageDigest.isEqual(sign(id, expires), Base64.getUrlDecoder().decode(signature));
        } catch (IllegalArgumentException exception) { return false; }
    }

    private byte[] sign(String id, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return mac.doFinal(("oss-object\n" + id + "\n" + expires).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new IllegalStateException("OSS 参考链接签名失败", exception); }
    }
}
