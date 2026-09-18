package com.yingdao.aicommerce.storage;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;
import com.aliyun.oss.model.PartETag;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.oss.enabled", havingValue = "true")
public class OssStorageService {
    private static final Logger log = LoggerFactory.getLogger(OssStorageService.class);
    private static final int MULTIPART_SIZE = 8 * 1024 * 1024;
    private final String bucket;
    private final Duration signedUrlTtl;
    private final OSS internalClient;
    private final OSS publicClient;
    private final OssReadOnlySigner readOnlySigner;
    private final boolean publicImages;
    private final URI publicEndpoint;
    private final boolean disableImageProcessing;
    private final OssStorageService legacyStorage;

    public OssStorageService(
            @Value("${app.oss.bucket}") String bucket,
            @Value("${app.oss.region}") String region,
            @Value("${app.oss.endpoint}") String endpoint,
            @Value("${app.oss.public-endpoint}") String publicEndpoint,
            @Value("${app.oss.ecs-role-name}") String roleName,
            @Value("${app.oss.signed-url-ttl:2h}") Duration signedUrlTtl,
            @Value("${app.oss.public-images:${APP_OSS_PUBLIC_IMAGES:false}}") boolean publicImages,
            OssReadOnlySigner readOnlySigner,
            @Value("${app.oss.credentials-file:${APP_OSS_CREDENTIALS_FILE:}}") String credentialsFile,
            @Value("${APP_DAYAN_OSS_BUCKET:}") String dayanBucket,
            @Value("${APP_DAYAN_OSS_CREDENTIALS_FILE:}") String dayanCredentialsFile
    ) throws Exception {
        if (dayanBucket != null && !dayanBucket.isBlank()) {
            if (dayanBucket.trim().equals(bucket)) throw new IllegalArgumentException("新旧存储 Bucket 名称必须不同以保留历史素材路径");
            if (dayanCredentialsFile == null || dayanCredentialsFile.isBlank()) throw new IllegalArgumentException("缺少大言云上传凭证文件");
            this.legacyStorage = new OssStorageService(bucket, region, endpoint, publicEndpoint, roleName,
                    signedUrlTtl, publicImages, readOnlySigner, credentialsFile, "", "");
            bucket = dayanBucket;
            region = "cn-chengdu";
            endpoint = "https://oss.cloud.dayan.design";
            publicEndpoint = endpoint;
            credentialsFile = dayanCredentialsFile;
            publicImages = false;
            readOnlySigner = null;
        } else {
            this.legacyStorage = null;
        }
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("OSS Bucket 不能为空");

        this.bucket = bucket.trim();
        this.signedUrlTtl = signedUrlTtl;
        this.readOnlySigner = readOnlySigner;
        this.publicImages = publicImages;
        this.publicEndpoint = URI.create(publicEndpoint);
        this.disableImageProcessing = this.publicEndpoint.getHost().endsWith(".cloud.dayan.design");

        CredentialsProvider provider = credentialsProvider(roleName, credentialsFile);
        ClientBuilderConfiguration clientConfig = new ClientBuilderConfiguration();
        clientConfig.setSignatureVersion(disableImageProcessing ? SignVersion.V1 : SignVersion.V4);
        clientConfig.setConnectionTimeout(10_000);
        clientConfig.setSocketTimeout(30_000);
        clientConfig.setMaxErrorRetry(2);
        String proxyHost = System.getProperty("https.proxyHost", "");
        if (!proxyHost.isBlank()) {
            clientConfig.setProxyHost(proxyHost);
            clientConfig.setProxyPort(Integer.parseInt(System.getProperty("https.proxyPort", "443")));
        }
        this.internalClient = client(endpoint, region, provider, clientConfig);
        this.publicClient = client(publicEndpoint, region, provider, clientConfig);
    }

    static CredentialsProvider credentialsProvider(String roleName, String credentialsFile) throws Exception {
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            java.util.Properties properties = new java.util.Properties();
            try (var reader = Files.newBufferedReader(Path.of(credentialsFile), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (Exception exception) {
                throw new IllegalStateException("无法读取 OSS 上传凭证文件");
            }
            String id = properties.getProperty("accessKeyId", "").trim();
            String secret = properties.getProperty("accessKeySecret", "").trim();
            if (id.isBlank() || secret.isBlank()) throw new IllegalArgumentException("OSS 上传凭证缺少 AccessKey ID 或 Secret");
            return new CredentialsProvider() {
                @Override public void setCredentials(Credentials credentials) { }
                @Override public Credentials getCredentials() { return new DefaultCredentials(id, secret); }
            };
        }
        if (roleName == null || roleName.isBlank()) throw new IllegalArgumentException("OSS 需要配置上传凭证文件或 ECS RAM 角色");
        var config = new com.aliyun.credentials.models.Config().setType("ecs_ram_role").setRoleName(roleName.trim());
        var client = new com.aliyun.credentials.Client(config);
        return new CredentialsProvider() {
            @Override public void setCredentials(Credentials credentials) { }
            @Override public Credentials getCredentials() {
                CredentialModel value = client.getCredential();
                return new DefaultCredentials(value.getAccessKeyId(), value.getAccessKeySecret(), value.getSecurityToken());
            }
        };
    }

    @PostConstruct
    void verifyAccess() {
        String key = "heyi/_health/startup-" + UUID.randomUUID() + ".txt";
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(payload.length);
            metadata.setContentType("text/plain");
            internalClient.putObject(bucket, key, new ByteArrayInputStream(payload), metadata);
            if (!internalClient.doesObjectExist(bucket, key)) {
                throw new IllegalStateException("OSS 校验对象写入后不可读");
            }
            log.info("OSS storage verified for private bucket {}", bucket);
        } catch (RuntimeException exception) {
            log.error("OSS startup verification failed; storage operations remain unavailable, but login and local-file services will stay online", exception);
        } finally {
            try {
                internalClient.deleteObject(bucket, key);
            } catch (RuntimeException cleanupException) {
                log.warn("Unable to remove OSS startup verification object {}", key, cleanupException);
            }
        }
    }

    private OSS client(String endpoint, String region, CredentialsProvider provider,
                       ClientBuilderConfiguration config) {
        return OSSClientBuilder.create().endpoint(endpoint).region(region)
                .credentialsProvider(provider).clientConfiguration(config).build();
    }

    public String publish(Path source, String contentType, String category) throws Exception {
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IllegalStateException("待上传 OSS 的素材不存在");
        try (InputStream input = Files.newInputStream(normalized)) {
            StoredObject stored = store(input, Files.size(normalized), Files.size(normalized), contentType,
                    category, normalized.getFileName() == null ? "asset" : normalized.getFileName().toString());
            return signedUrl(stored.location());
        }
    }

    public StoredObject store(byte[] content, String contentType, String category, String fileName) throws Exception {
        if (content == null || content.length == 0) throw new IllegalStateException("待上传 OSS 的内容为空");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String key = objectKey(category, fileName, digest.substring(0, 24));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType);
        configureVisibility(metadata);
        internalClient.putObject(new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata));
        return new StoredObject(location(key), content.length);
    }

    public StoredObject store(InputStream input, long declaredLength, long maxBytes, String contentType,
                              String category, String fileName) throws Exception {
        if (input == null) throw new IllegalStateException("待上传 OSS 的数据流为空");
        if (declaredLength > maxBytes) throw new IllegalStateException("待上传 OSS 的文件超过大小限制");
        if (declaredLength >= 0 && declaredLength <= MULTIPART_SIZE) {
            byte[] bytes = input.readNBytes(Math.toIntExact(declaredLength) + 1);
            if (bytes.length != declaredLength) throw new IllegalStateException("媒体响应长度与声明不一致");
            return store(bytes, contentType, category, fileName);
        }

        String key = objectKey(category, fileName, UUID.randomUUID().toString().replace("-", ""));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType);
        configureVisibility(metadata);
        InitiateMultipartUploadRequest initiate = new InitiateMultipartUploadRequest(bucket, key);
        initiate.setObjectMetadata(metadata);
        InitiateMultipartUploadResult initiated = internalClient.initiateMultipartUpload(initiate);
        List<PartETag> parts = new ArrayList<>();
        long total = 0;
        try {
            int partNumber = 1;
            while (true) {
                byte[] chunk = input.readNBytes(MULTIPART_SIZE);
                if (chunk.length == 0) break;
                total += chunk.length;
                if (total > maxBytes) throw new IllegalStateException("待上传 OSS 的文件超过大小限制");
                UploadPartRequest upload = new UploadPartRequest();
                upload.setBucketName(bucket);
                upload.setKey(key);
                upload.setUploadId(initiated.getUploadId());
                upload.setPartNumber(partNumber++);
                upload.setPartSize(chunk.length);
                upload.setInputStream(new ByteArrayInputStream(chunk));
                UploadPartResult result = internalClient.uploadPart(upload);
                parts.add(result.getPartETag());
            }
            if (parts.isEmpty()) throw new IllegalStateException("待上传 OSS 的内容为空");
            CompleteMultipartUploadRequest complete = new CompleteMultipartUploadRequest(
                    bucket, key, initiated.getUploadId(), parts);
            // OSS applies multipart object ACL on completion, not initiation.
            if (publicImages) complete.setObjectACL(isImageContentType(metadata.getContentType())
                    ? CannedAccessControlList.PublicRead : CannedAccessControlList.Private);
            internalClient.completeMultipartUpload(complete);
            return new StoredObject(location(key), total);
        } catch (Exception exception) {
            try {
                internalClient.abortMultipartUpload(new AbortMultipartUploadRequest(
                        bucket, key, initiated.getUploadId()));
            } catch (RuntimeException abortException) {
                exception.addSuppressed(abortException);
            }
            throw exception;
        }
    }

    public boolean isOssLocation(String value) {
        return value != null && (value.startsWith("oss://" + bucket + "/")
                || (legacyStorage != null && legacyStorage.isOssLocation(value)));
    }

    public boolean exists(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.exists(location);
        return internalClient.doesObjectExist(bucket, key(location));
    }

    public byte[] readAllBytes(String location, int maxBytes) throws Exception {
        try (InputStream input = open(location)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IllegalStateException("OSS 素材超过读取大小限制");
            return bytes;
        }
    }

    public InputStream open(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.open(location);
        return internalClient.getObject(bucket, key(location)).getObjectContent();
    }

    public String signedUrl(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.signedUrl(location);
        // Provider submissions must go straight to OSS, not through the app's
        // bandwidth-limited material gateway. Sign again on each submission.
        String objectKey = key(location);
        if (isPublicImage(objectKey)) return publicImageUrl(objectKey, null);
        if (readOnlySigner != null && readOnlySigner.isEnabled()) return readOnlySigner.sign(bucket, objectKey);
        return signedUrl(location, null);
    }

    /** Stream with current ECS credentials, not credentials captured in a URL. */
    public Download download(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.download(location);
        String objectKey = key(location);
        ObjectMetadata metadata = internalClient.getObjectMetadata(bucket, objectKey);
        long size = metadata.getContentLength();
        Resource resource = new AbstractResource() {
            @Override public String getDescription() { return "Private OSS reference"; }
            @Override public String getFilename() { return objectKey.substring(objectKey.lastIndexOf('/') + 1); }
            @Override public long contentLength() { return size; }
            @Override public InputStream getInputStream() { return open(location); }
        };
        return new Download(resource, size, metadata.getContentType());
    }

    public record Download(Resource resource, long size, String contentType) {}

    public String signedImagePreviewUrl(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.signedImagePreviewUrl(location);
        if (disableImageProcessing) return signedUrl(location);
        return signedUrl(location,
                "image/resize,m_lfit,w_960,limit_1/format,webp/quality,q_82");
    }

    private String signedUrl(String location, String process) {
        String objectKey = key(location);
        if (isPublicImage(objectKey)) return publicImageUrl(objectKey, process);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
        request.setExpiration(Date.from(Instant.now().plus(signedUrlTtl)));
        if (process != null && !process.isBlank()) request.setProcess(process);
        return publicClient.generatePresignedUrl(request).toExternalForm();
    }

    public String signedDownloadUrl(String location, String fileName) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) return legacyStorage.signedDownloadUrl(location, fileName);
        String objectKey = key(location);
        if (isPublicImage(objectKey)) return publicImageUrl(objectKey, null);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
        request.setExpiration(Date.from(Instant.now().plus(signedUrlTtl)));
        return publicClient.generatePresignedUrl(request).toExternalForm();
    }

    private void configureVisibility(ObjectMetadata metadata) {
        boolean image = publicImages && isImageContentType(metadata.getContentType());
        metadata.setCacheControl((image ? "public" : "private") + ", max-age=31536000, immutable");
        if (publicImages) {
            // Ordinary PUT ACL is atomic with the upload. Multipart also sets
            // this ACL on its completion request before returning the new URL.
            metadata.setObjectAcl(image ? CannedAccessControlList.PublicRead : CannedAccessControlList.Private);
        }
    }

    private boolean isImageContentType(String contentType) {
        return contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private boolean isPublicImage(String objectKey) {
        // Use MIME metadata rather than filenames so extensionless images work
        // and a video or archive named *.png is not exposed as an image.
        return publicImages && objectKey.startsWith("heyi/")
                && isImageContentType(internalClient.getObjectMetadata(bucket, objectKey).getContentType());
    }

    private String publicImageUrl(String objectKey, String process) {
        try {
            String host = publicEndpoint.getHost();
            if (!host.startsWith(bucket + ".")) host = bucket + "." + host;
            return new URI(publicEndpoint.getScheme(), null, host, publicEndpoint.getPort(),
                    "/" + objectKey, process == null ? null : "x-oss-process=" + process, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("OSS 图片公开地址无效", exception);
        }
    }

    public int deleteOlderThan(String categoryPrefix, Duration age) {
        String safePrefix = categoryPrefix == null ? "" : categoryPrefix.replaceAll("[^A-Za-z0-9/_-]", "-");
        String prefix = "heyi/" + safePrefix;
        Instant cutoff = Instant.now().minus(age);
        int deleted = 0;
        String continuationToken = null;
        do {
            ListObjectsV2Request request = new ListObjectsV2Request(bucket).withPrefix(prefix).withMaxKeys(500);
            if (continuationToken != null) request.setContinuationToken(continuationToken);
            ListObjectsV2Result result = internalClient.listObjectsV2(request);
            for (var summary : result.getObjectSummaries()) {
                if (summary.getLastModified() != null && summary.getLastModified().toInstant().isBefore(cutoff)) {
                    internalClient.deleteObject(bucket, summary.getKey());
                    deleted++;
                }
            }
            continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
        } while (continuationToken != null && !continuationToken.isBlank());
        return deleted;
    }

    public void delete(String location) {
        if (legacyStorage != null && legacyStorage.isOssLocation(location)) { legacyStorage.delete(location); return; }
        if (isOssLocation(location)) internalClient.deleteObject(bucket, key(location));
    }

    private String objectKey(String category, String sourceFileName, String identity) {
        String safeCategory = category == null ? "materials" : category.replaceAll("[^A-Za-z0-9/_-]", "-");
        String fileName = sourceFileName == null ? "asset" : sourceFileName;
        fileName = fileName.replaceAll("[^A-Za-z0-9._-]", "-");
        return "heyi/" + safeCategory + "/" + identity + "/" + fileName;
    }

    private String location(String key) {
        return "oss://" + bucket + "/" + key;
    }

    private String key(String location) {
        if (!isOssLocation(location)) throw new IllegalArgumentException("不是当前 Bucket 的 OSS 地址");
        URI uri = URI.create(location);
        String value = uri.getPath();
        if (value == null || value.length() <= 1) throw new IllegalArgumentException("OSS 对象地址无效");
        return value.substring(1);
    }

    @PreDestroy
    public void close() {
        internalClient.shutdown();
        publicClient.shutdown();
        if (legacyStorage != null) legacyStorage.close();
    }

    public record StoredObject(String location, long size) {}
}
