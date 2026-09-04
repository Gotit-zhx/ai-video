package com.yingdao.aicommerce.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

/** Optional dedicated read-only identity for long-lived, direct OSS URLs. */
@Service
@ConditionalOnProperty(name = "app.oss.enabled", havingValue = "true")
public class OssReadOnlySigner {
    private final OSS client;
    private final Duration ttl;

    public OssReadOnlySigner(
            @Value("${app.oss.public-endpoint}") String endpoint,
            @Value("${app.oss.region}") String region,
            @Value("${app.oss.signing-credentials-file:}") String credentialsFile,
            @Value("${app.oss.direct-link-ttl:72h}") Duration ttl) {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("OSS 直链有效期必须在 1 秒至 7 天之间");
        }
        this.ttl = ttl;
        if (credentialsFile == null || credentialsFile.isBlank()) {
            this.client = null;
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(credentialsFile), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception exception) {
            // Do not leak file contents, keys, or values in startup errors.
            throw new IllegalStateException("无法读取 OSS 专用签名凭证文件");
        }
        String id = properties.getProperty("accessKeyId", "").trim();
        String secret = properties.getProperty("accessKeySecret", "").trim();
        if (id.isBlank() || secret.isBlank() || id.startsWith("STS.")) {
            throw new IllegalArgumentException("OSS 专用签名凭证必须同时配置长期 AccessKey ID 和 Secret");
        }
        CredentialsProvider provider = new CredentialsProvider() {
            @Override public void setCredentials(Credentials credentials) { }
            @Override public Credentials getCredentials() { return new DefaultCredentials(id, secret); }
        };
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        this.client = OSSClientBuilder.create().endpoint(endpoint).region(region)
                .credentialsProvider(provider).clientConfiguration(config).build();
    }

    public boolean isEnabled() { return client != null; }

    public String sign(String bucket, String key) {
        if (!isEnabled()) throw new IllegalStateException("尚未配置 OSS 专用只读签名密钥");
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
        request.setExpiration(Date.from(Instant.now().plus(ttl)));
        return client.generatePresignedUrl(request).toExternalForm();
    }

    @PreDestroy
    public void close() {
        if (client != null) client.shutdown();
    }
}
