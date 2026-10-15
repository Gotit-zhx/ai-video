package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stages a tenant-owned local capture archive; never fetches video URLs from request payloads. */
@Service
public class TikTokProductVideoUploader {
    static final String UPLOAD = "/product/202309/files/upload";
    static final long MAX_VIDEO_BYTES = 100_000_000L;
    private static final Pattern TRANSIENT_FAILURE = Pattern.compile(
            "(?is).*(HTTP (408|425|429|5[0-9]{2})|36009003|请求失败|中断|超时|too many|rate.?limit|try (again )?later|system error|internal error|network|timeout|overload).*");

    public record UploadedVideo(String videoId, String sourceVideoId, int sourceCount) {
        public static UploadedVideo none() { return new UploadedVideo(null, null, 0); }
        public boolean present() { return videoId != null && !videoId.isBlank(); }
    }

    private final JdbcTemplate db;
    private final TikTokProductVideoUploadLedger ledger;
    private final TikTokShopOfficialClient client;
    private final ObjectMapper json;
    private final Path root;
    private final String destination;

    public TikTokProductVideoUploader(JdbcTemplate db, TikTokProductVideoUploadLedger ledger,
                                      TikTokShopOfficialClient client, ObjectMapper json,
                                      @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storage,
                                      @Value("${app.shop-auth.tiktok-shop.api-base-url:https://open-api.tiktokglobalshop.com}") String apiBaseUrl,
                                      @Value("${app.shop-auth.tiktok-shop.app-key:}") String appKey) {
        this.db = db;
        this.ledger = ledger;
        this.client = client;
        this.json = json;
        this.root = Path.of(storage, "capture-videos").toAbsolutePath().normalize();
        this.destination = digest((apiBaseUrl.replaceAll("/+$", "") + "\n" + appKey.trim()).getBytes(StandardCharsets.UTF_8));
    }

    public UploadedVideo upload(ProductCapture capture, MarketplaceAccess.Connection connection, String idempotencyKey) {
        try { return uploadArchive(capture, connection, idempotencyKey); }
        catch (org.springframework.dao.DataAccessException exception) {
            throw new VideoUploadPendingException("TikTok 视频上传进度暂时无法保存，商品尚未创建，后台稍后会重试", exception);
        }
    }

    private UploadedVideo uploadArchive(ProductCapture capture, MarketplaceAccess.Connection connection, String idempotencyKey) {
        String org = capture.getOrganizationId();
        if (!org.equals(connection.shop().getOrganizationId())
                || !MarketplacePlatform.TIKTOK_SHOP.name().equals(connection.shop().getPlatform())) {
            throw conflict("采集视频与目标 TikTok 店铺不属于同一企业或平台");
        }
        var sources = db.queryForList("SELECT id,mime_type,byte_length,content_sha256,storage_video_id FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND superseded_by IS NULL ORDER BY created_at,id",
                org, capture.getId());
        if (sources.isEmpty()) return UploadedVideo.none();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw conflict("视频上传缺少固定请求编号，商品尚未创建；请从一键上传重新发起");
        }
        String requestKey = digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        // Create Product accepts one video object. Reuse a deterministic archive on every retry.
        Map<String, Object> source = sources.getFirst();
        String video = text(source, "id"), hash = text(source, "content_sha256");
        if (video == null || !video.matches("[a-f0-9-]{36}") || hash == null || !hash.matches("[a-f0-9]{64}")) {
            throw conflict("采集视频归档信息无效，商品尚未创建");
        }
        if (!(source.get("byte_length") instanceof Number size)
                || size.longValue() < 12 || size.longValue() > MAX_VIDEO_BYTES) {
            throw conflict("采集视频为空或超过 TikTok Shop 的 100 MB 限制，商品尚未创建");
        }
        long byteLength = ((Number) source.get("byte_length")).longValue();
        String mime = text(source, "mime_type");
        String extension = switch (mime == null ? "" : mime) {
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            default -> throw conflict("采集视频格式不受支持，目前支持已归档 MP4 和 WebM，商品尚未创建");
        };
        String id = digest((org + ":" + connection.shop().getId() + ":" + hash + ":" + destination).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> row = ledger.prepare(id, org, connection.shop().getId(), video, hash, destination, byteLength, requestKey);
        if ("READY".equals(row.get("status"))) return uploaded(row, video, sources.size());
        if ("FAILED".equals(row.get("attempt_status"))) throw conflict("TikTok 视频处理失败：" + text(row, "attempt_message"));
        String worker = UUID.randomUUID().toString();
        if (!ledger.acquire(id, org, worker)) {
            throw new VideoUploadPendingException("该视频正在上传到目标 TikTok 店铺，后台稍后会复用上传结果");
        }
        try {
            row = ledger.get(id, org, requestKey);
            if ("READY".equals(row.get("status"))) return uploaded(row, video, sources.size());
            if ("FAILED".equals(row.get("attempt_status"))) throw conflict("TikTok 视频处理失败：" + text(row, "attempt_message"));
            String archiveVideo=java.util.Objects.toString(source.get("storage_video_id"),video);
            if(!archiveVideo.matches("[a-f0-9-]{36}"))throw conflict("采集视频归档信息无效，商品尚未创建");
            byte[] bytes = readArchive(archiveVideo, hash, byteLength);
            if (!ledger.beginUpload(id, org, worker, requestKey)) {
                throw conflict("本次 TikTok 视频上传已尝试 3 次仍未取得有效视频编号，商品尚未创建；修复连接后可新建上传批次重试");
            }
            String filename = "video" + hash.substring(0, 24) + extension;
            // TikTok documents data + name, not file_type/use_case, and does not require shop_cipher here.
            // apiMultipart already bounds both response headers and body to 90 seconds.
            Map<String, Object> response = client.apiMultipart(UPLOAD, connection, Map.of(),
                    filename, mime, bytes, Map.of("name", filename));
            Map<String, Object> data = MarketplaceHttp.object(response, "data");
            String videoId = text(data, "id");
            if (videoId == null || !videoId.matches("[A-Za-z0-9._:-]{1,255}")) {
                throw new VideoUploadPendingException("TikTok 未返回可确认的视频编号，商品尚未创建，后台将有限重试媒体上传");
            }
            String format = text(data, "format");
            if (format != null && !List.of("MP4", "WEBM").contains(format.toUpperCase(Locale.ROOT))) {
                String message = "TikTok 返回的文件不是已提交的视频格式，商品尚未创建";
                ledger.failed(id, org, worker, requestKey, message);
                throw conflict(message);
            }
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("id", videoId);
            receipt.put("name", filename);
            receipt.put("format", extension.substring(1).toUpperCase(Locale.ROOT));
            if (text(response, "request_id") != null) receipt.put("request_id", text(response, "request_id"));
            ledger.ready(id, org, worker, videoId, json.writeValueAsString(receipt));
            return new UploadedVideo(videoId, video, sources.size());
        } catch (MarketplaceApiException exception) {
            if (exception.getCause() != null || TRANSIENT_FAILURE.matcher(String.valueOf(exception.getMessage())).matches()) {
                // A lost response can leave an unused media file, never a duplicate product. Keep a finite retry budget.
                throw new VideoUploadPendingException("TikTok 视频上传连接暂时失败，商品尚未创建；后台将按已保存的次数重试媒体上传", exception);
            }
            String message = rejectionMessage(exception.getMessage());
            ledger.failed(id, org, worker, requestKey, message);
            throw conflict(message);
        } finally {
            ledger.release(id, org, worker);
        }
    }

    private UploadedVideo uploaded(Map<String, Object> row, String video, int count) {
        String id = text(row, "video_id");
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,255}")) throw conflict("TikTok 已完成的视频记录缺少有效编号，商品尚未创建");
        return new UploadedVideo(id, video, count);
    }

    private byte[] readArchive(String video, String hash, long byteLength) {
        try {
            Path file = root.resolve(video);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != byteLength
                    || !file.toRealPath().startsWith(root.toRealPath())) throw conflict("采集视频归档文件缺失或长度不一致，商品尚未创建");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != byteLength || !hash.equals(digest(bytes))) throw conflict("采集视频归档校验失败，商品尚未创建");
            return bytes;
        } catch (java.io.IOException exception) {
            throw conflict("读取本地采集视频失败，商品尚未创建");
        }
    }

    private String rejectionMessage(String message) {
        if (message != null && message.contains("12019122")) return "TikTok 视频画面比例必须在 9:16 到 16:9 之间，商品尚未创建";
        if (message != null && message.contains("12038005")) return "TikTok 拒绝了视频大小，视频必须小于 100 MB，商品尚未创建";
        var code = Pattern.compile("错误码 ([0-9]+)").matcher(message == null ? "" : message);
        return "TikTok 拒绝视频文件" + (code.find() ? "（错误码 " + code.group(1) + "）" : "")
                + "，请检查视频格式、内容和店铺视频权限；商品尚未创建";
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String text(Map<String, Object> value, String key) {
        Object result = value.get(key);
        return result == null || String.valueOf(result).isBlank() ? null : String.valueOf(result).trim();
    }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
