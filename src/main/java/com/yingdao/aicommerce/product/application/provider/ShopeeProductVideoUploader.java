package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.ProductCapture;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException;
import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.IntStream;

/** Publishes only tenant-scoped, locally archived capture videos; never fetches source URLs. */
@Service
public class ShopeeProductVideoUploader {
    static final int PART_BYTES = 4 * 1024 * 1024;
    static final long MAX_VIDEO_BYTES = 30L * 1024 * 1024;
    static final int POLL_ATTEMPTS = 30;
    static final long POLL_DELAY_MILLIS = 2_000;
    static final String INIT = "/api/v2/media_space/init_video_upload";
    static final String RESULT = "/api/v2/media_space/get_video_upload_result";

    public record UploadedVideo(String uploadId, String sourceVideoId, int sourceCount) {
        public static UploadedVideo none() { return new UploadedVideo(null, null, 0); }
        public boolean present() { return uploadId != null && !uploadId.isBlank(); }
    }

    private final JdbcTemplate db;
    private final ShopeeProductVideoUploadLedger ledger;
    private final ShopeeOfficialClient client;
    private final ObjectMapper json;
    private final Path root;
    private final ShopeeVideoPreprocessor preprocessor;

    @org.springframework.beans.factory.annotation.Autowired
    public ShopeeProductVideoUploader(JdbcTemplate db, ShopeeProductVideoUploadLedger ledger,
                                      ShopeeOfficialClient client, ObjectMapper json,
                                      @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storage) {
        this(db, ledger, client, json, storage, new ShopeeVideoPreprocessor(Path.of(storage, "capture-videos")));
    }

    ShopeeProductVideoUploader(JdbcTemplate db, ShopeeProductVideoUploadLedger ledger,
                               ShopeeOfficialClient client, ObjectMapper json, String storage, ShopeeVideoPreprocessor preprocessor) {
        this.preprocessor = preprocessor;
        this.db = db; this.ledger = ledger; this.client = client; this.json = json;
        this.root = Path.of(storage, "capture-videos").toAbsolutePath().normalize();
    }

    public UploadedVideo upload(ProductCapture capture, MarketplaceAccess.Connection connection) {
        try { return upload(capture, connection, this::pause); }
        catch (org.springframework.dao.DataAccessException exception) {
            throw new VideoUploadPendingException("视频上传进度暂时无法保存，商品尚未创建，后台稍后会继续", exception);
        }
    }

    UploadedVideo upload(ProductCapture capture, MarketplaceAccess.Connection connection, LongConsumer sleeper) {
        String org = capture.getOrganizationId();
        if (!org.equals(connection.shop().getOrganizationId())) throw conflict("视频与目标店铺不属于同一企业");
        var sources = db.queryForList("SELECT id,mime_type,byte_length,content_sha256,storage_video_id FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND superseded_by IS NULL ORDER BY created_at,id",
                org, capture.getId());
        if (sources.isEmpty()) return UploadedVideo.none();
        // Shopee add_item accepts one video_upload_id. Selection is stable across single/bulk/retry.
        var source = sources.getFirst();
        String video = String.valueOf(source.get("id")), hash = String.valueOf(source.get("content_sha256"));
        if (!video.matches("[a-f0-9-]{36}") || !hash.matches("[a-f0-9]{64}")) throw conflict("采集视频归档信息无效");
        long byteLength = ((Number) source.get("byte_length")).longValue();
        if (byteLength < 12 || byteLength > 200L * 1024 * 1024) throw conflict("采集视频无效或超过 200 MB 预处理上限，商品尚未提交");
        String mime = String.valueOf(source.get("mime_type"));
        if (!List.of("video/mp4", "video/webm").contains(mime)) throw conflict("采集视频格式不受支持");
        String shop = connection.shop().getId();
        String destination = digest("SHA-256", client.videoUploadScope().getBytes(StandardCharsets.UTF_8));
        String id = digest("SHA-256", (org + ":" + shop + ":" + hash + ":" + destination).getBytes(StandardCharsets.UTF_8));
        var row = ledger.prepare(id, org, shop, video, hash, destination, byteLength);
        if ("READY".equals(row.get("status"))) return uploaded(row, video, sources.size());
        String archiveVideo=Objects.toString(source.get("storage_video_id"),video);
        if(!archiveVideo.matches("[a-f0-9-]{36}"))throw conflict("采集视频归档信息无效");
        byte[] bytes = preprocessor.prepare(readArchive(archiveVideo, hash, byteLength));
        String preparedHash = digest("SHA-256", bytes);
        if (!preparedHash.equals(hash)) {
            id = digest("SHA-256", (org + ":" + shop + ":" + preparedHash + ":" + destination).getBytes(StandardCharsets.UTF_8));
            row = ledger.prepare(id, org, shop, video, preparedHash, destination, bytes.length);
            if ("READY".equals(row.get("status"))) return uploaded(row, video, sources.size());
        }
        if ("FAILED".equals(row.get("status"))) throw conflict("Shopee 视频处理失败：" + row.get("message"));
        String worker = UUID.randomUUID().toString();
        if (!ledger.acquire(id, org, worker)) throw new VideoUploadPendingException("该视频正在上传到目标店铺，后台稍后会复用上传结果");
        try {
            row = ledger.get(id, org);
            if ("READY".equals(row.get("status"))) return uploaded(row, video, sources.size());
            String uploadId = text(row, "video_upload_id");
            String phase = text(row, "status");
            if (uploadId == null) {
                if (!ledger.beginInitialization(id, org, worker)) throw conflict("Shopee 视频初始化已尝试 3 次仍未得到上传编号，商品尚未创建，请检查店铺媒体接口连接");
                // A lost init response may leave an unused media session; it cannot create an item.
                var initialized = client.shopPost(INIT, connection,
                        Map.of("file_md5", digest("MD5", bytes), "file_size", bytes.length));
                uploadId = text(MarketplaceHttp.object(initialized, "response"), "video_upload_id");
                if (uploadId == null || uploadId.length() > 255) throw conflict("Shopee 初始化视频未返回有效 video_upload_id");
                ledger.initialized(id, org, worker, uploadId);
                row = ledger.get(id, org); phase = "UPLOADING";
            }
            int partCount = (bytes.length + PART_BYTES - 1) / PART_BYTES;
            if ("UPLOADING".equals(phase)) {
                long started = System.nanoTime();
                int nextPart = ((Number) row.get("next_part")).intValue();
                if (nextPart < 0 || nextPart > partCount) throw conflict("视频上传分片记录无效");
                for (int part = nextPart; part < partCount; part++) {
                    ledger.renew(id, org, worker);
                    byte[] chunk = Arrays.copyOfRange(bytes, part * PART_BYTES, Math.min(bytes.length, (part + 1) * PART_BYTES));
                    client.uploadVideoPart(uploadId, part, digest("MD5", chunk), chunk);
                    ledger.partUploaded(id, org, worker, part);
                }
                ledger.phase(id, org, worker, "COMPLETING");
                client.completeVideoUpload(uploadId, sequences(partCount), (System.nanoTime() - started) / 1_000_000L);
                ledger.phase(id, org, worker, "TRANSCODING");
            } else if ("COMPLETING".equals(phase)) {
                // Complete may have committed before a lost response. Query before re-sending it.
                var state = readResult(connection, uploadId);
                if (handleResult(id, org, worker, state)) return new UploadedVideo(uploadId, video, sources.size());
                if ("INITIATED".equals(normalizedStatus(state))) client.completeVideoUpload(uploadId, sequences(partCount), 0);
                ledger.phase(id, org, worker, "TRANSCODING");
            } else if (!"TRANSCODING".equals(phase)) throw conflict("视频上传状态异常：" + phase);
            for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
                ledger.renew(id, org, worker);
                var state = readResult(connection, uploadId);
                if (handleResult(id, org, worker, state)) return new UploadedVideo(uploadId, video, sources.size());
                if (attempt + 1 < POLL_ATTEMPTS) sleeper.accept(POLL_DELAY_MILLIS);
            }
            throw new VideoUploadPendingException("Shopee 视频仍在转码，商品尚未创建；后台将继续读取结果，不重复上传视频");
        } catch (MarketplaceApiException exception) {
            String message = String.valueOf(exception.getMessage());
            if (exception.getCause() != null || message.matches("(?is).*(HTTP (408|425|429|5[0-9]{2})|请求失败|中断|超时|too many|rate.?limit|try (again )?later|system error|internal error|network|timeout|overload).*$")) {
                throw new VideoUploadPendingException("视频上传连接暂时失败，后台将从已保存的媒体进度继续：" + message, exception);
            }
            throw exception;
        } finally {
            ledger.release(id, org, worker);
        }
    }

    private Map<String, Object> readResult(MarketplaceAccess.Connection connection, String uploadId) {
        return MarketplaceHttp.object(client.shopGet(RESULT, connection, Map.of("video_upload_id", uploadId)), "response");
    }

    private boolean handleResult(String id, String org, String worker, Map<String, Object> state) {
        String status = normalizedStatus(state);
        if ("SUCCEEDED".equals(status)) {
            ledger.result(id, org, worker, "READY", json.writeValueAsString(state), null);
            return true;
        }
        if (List.of("FAILED", "CANCELLED").contains(status)) {
            String message = text(state, "message");
            message = message == null ? status : message.substring(0, Math.min(message.length(), 1500));
            ledger.result(id, org, worker, "FAILED", json.writeValueAsString(state), message);
            throw conflict("Shopee 视频处理失败：" + message);
        }
        if (!List.of("INITIATED", "TRANSCODING").contains(status)) throw new VideoUploadPendingException("Shopee 未返回可确认的视频状态，后台将继续查询原视频上传编号");
        return false;
    }

    private byte[] readArchive(String video, String hash, long byteLength) {
        try {
            Path file = root.resolve(video);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != byteLength
                    || !file.toRealPath().startsWith(root.toRealPath())) throw conflict("采集视频归档文件缺失或长度不一致");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != byteLength || !hash.equals(digest("SHA-256", bytes))) throw conflict("采集视频归档校验失败，商品尚未提交");
            return bytes;
        } catch (java.io.IOException exception) {
            throw conflict("读取本地采集视频失败，商品尚未提交");
        }
    }

    private UploadedVideo uploaded(Map<String, Object> row, String video, int count) {
        String uploadId = text(row, "video_upload_id");
        if (uploadId == null) throw conflict("已完成视频记录缺少上传编号");
        return new UploadedVideo(uploadId, video, count);
    }
    private List<Integer> sequences(int count) { return IntStream.range(0, count).boxed().toList(); }
    private String normalizedStatus(Map<String, Object> state) { return String.valueOf(state.get("status")).toUpperCase(Locale.ROOT); }
    private String text(Map<String, Object> value, String key) { Object result = value.get(key); return result == null || String.valueOf(result).isBlank() ? null : String.valueOf(result); }
    static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void pause(long delay) {
        try { Thread.sleep(delay); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new VideoUploadPendingException("视频上传等待被中断，已保留上传记录", exception); }
    }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
