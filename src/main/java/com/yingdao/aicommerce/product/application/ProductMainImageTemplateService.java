package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.Executor;

@Service
public class ProductMainImageTemplateService {
    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    private final ProductMainImageStyleVision vision;
    private final ObjectMapper json;
    private final Executor executor;

    public record ImageView(String id, int index, String imageUrl, String status, int width, int height,
                            Map<String, Object> analysis, String error) {}
    public record View(String id, String name, String status, int imageCount, List<ImageView> images) {}

    public ProductMainImageTemplateService(JdbcTemplate db, CommerceImageArchive archive,
            ProductMainImageStyleVision vision, ObjectMapper json,
            @Qualifier("productPublishWorkflowExecutor") Executor executor) {
        this.db = db; this.archive = archive; this.vision = vision; this.json = json; this.executor = executor;
    }

    @Transactional
    public View create(String name, List<MultipartFile> files, String key, PlatformPrincipal user) {
        access(user, true);
        if (name == null || name.isBlank() || name.trim().length() > 120) throw bad("模板名称须为1至120字");
        if (files == null || files.isEmpty() || files.size() > ProductMainImageStylePolicy.MAX_IMAGES)
            throw bad("每套主图模板需上传1至9张图片");
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) throw bad("缺少模板保存请求编号");
        var uploads = new ArrayList<Map<String, Object>>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty() || file.getSize() > 8 * 1024 * 1024) throw bad("每张参考图须小于等于8MiB");
            try {
                byte[] bytes = file.getBytes();
                int[] dimensions = dimensions(bytes);
                var stored = archive.store(user.organizationId(), bytes);
                uploads.add(Map.of("storageKey", stored.storageKey(), "width", dimensions[0], "height", dimensions[1]));
            } catch (BusinessException failure) { throw failure; }
            catch (Exception failure) { throw bad("参考图无法读取，仅支持有效的PNG、JPEG或WEBP图片"); }
        }
        String fingerprint = ProductMainImageStylePolicy.fingerprint(Map.of("name", name.trim(), "images", uploads), json);
        String id = UUID.randomUUID().toString();
        db.update("INSERT IGNORE INTO product_main_image_templates(id,organization_id,created_by,name,request_key,request_fingerprint,created_at) VALUES(?,?,?,?,?,?,NOW(6))",
                id, user.organizationId(), user.userId(), name.trim(), key, fingerprint);
        var row = db.queryForMap("SELECT * FROM product_main_image_templates WHERE organization_id=? AND request_key=?", user.organizationId(), key);
        if (!fingerprint.equals(row.get("request_fingerprint"))) throw conflict("同一请求编号不能保存不同图集");
        if (id.equals(row.get("id"))) {
            for (int index = 0; index < uploads.size(); index++) {
                var image = uploads.get(index);
                db.update("INSERT INTO product_main_image_template_images(id,template_id,organization_id,sort_order,storage_key,width,height,status,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',NOW(6))",
                        UUID.randomUUID().toString(), id, user.organizationId(), index, image.get("storageKey"), image.get("width"), image.get("height"));
            }
        }
        return get((String) row.get("id"), user);
    }

    public List<View> list(PlatformPrincipal user) {
        access(user, false);
        return db.queryForList("SELECT * FROM product_main_image_templates WHERE organization_id=? AND archived=FALSE ORDER BY created_at DESC LIMIT 100",
                user.organizationId()).stream().map(this::view).toList();
    }

    public View get(String id, PlatformPrincipal user) { access(user, false); return view(row(id, user.organizationId(), true)); }

    @Transactional
    public void archive(String id, PlatformPrincipal user) {
        access(user, true); row(id, user.organizationId(), true);
        db.update("UPDATE product_main_image_templates SET archived=TRUE WHERE id=? AND organization_id=?", id, user.organizationId());
    }

    public View retry(String id, PlatformPrincipal user) {
        access(user, true); row(id, user.organizationId(), true);
        db.update("UPDATE product_main_image_template_images SET status='PENDING',error_message=NULL,updated_at=NOW(6) WHERE template_id=? AND organization_id=? AND status='FAILED'", id, user.organizationId());
        return get(id, user);
    }

    @Transactional
    public View savePrompt(String id, String imageId, String prompt, PlatformPrincipal user) {
        access(user, true); row(id, user.organizationId(), true);
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000 || !prompt.contains("{{product}}"))
            throw bad("提示词须保留{{product}}，且不超过4000字");
        var rows = db.queryForList("SELECT * FROM product_main_image_template_images WHERE id=? AND template_id=? AND organization_id=? AND status='READY' FOR UPDATE",
                imageId, id, user.organizationId());
        if (rows.isEmpty()) throw conflict("仅识别完成的图片可以修改提示词");
        var analysis = map(rows.getFirst().get("analysis_json"));
        if (ProductMainImageStylePolicy.preservesOriginal(analysis)) throw conflict("原样保留图片不需要修改生成提示词");
        analysis.put("prompt", prompt.trim());
        ProductMainImageStylePolicy.validateAnalysis(analysis);
        db.update("UPDATE product_main_image_template_images SET analysis_json=?,updated_at=NOW(6) WHERE id=? AND organization_id=?",
                json.writeValueAsString(analysis), imageId, user.organizationId());
        return get(id, user);
    }

    public CommerceDesignImageGateway.Reference image(String id, String imageId, PlatformPrincipal user) {
        access(user, false); row(id, user.organizationId(), false);
        var rows = db.queryForList("SELECT storage_key FROM product_main_image_template_images WHERE id=? AND template_id=? AND organization_id=?", imageId, id, user.organizationId());
        if (rows.isEmpty()) throw missing();
        return archive.read(user.organizationId(), (String) rows.getFirst().get("storage_key"));
    }

    public Map<String, Object> current(String id, PlatformPrincipal user) {
        access(user, false);
        return current(id, user.organizationId());
    }

    /** Workers read the current template each time; edits apply to unfinished uploads. */
    public Map<String, Object> current(String id, String org) {
        var template = row(id, org, true);
        var images = imageRows(id, org);
        if (images.isEmpty() || images.size() > ProductMainImageStylePolicy.MAX_IMAGES
                || images.stream().anyMatch(image -> !"READY".equals(image.get("status")))) throw conflict("主图风格模板尚未全部识别完成");
        var slots = images.stream().map(image -> {
            var analysis = map(image.get("analysis_json"));
            ProductMainImageStylePolicy.validateAnalysis(analysis);
            return Map.of("imageId", image.get("id"), "storageKey", image.get("storage_key"),
                    "width", image.get("width"), "height", image.get("height"), "analysis", analysis);
        }).toList();
        return Map.of("id", id, "name", template.get("name"), "images", slots);
    }

    @Scheduled(initialDelayString = "${app.main-image-style.initial-delay:5000}", fixedDelay = 3000)
    public void poll() {
        // Expired analyses are explicit failures, never silently repeated paid calls.
        db.update("UPDATE product_main_image_template_images SET status='FAILED',error_message='识别超时，可重试未完成图片',updated_at=NOW(6) WHERE status IN ('QUEUED','ANALYZING') AND updated_at<DATE_SUB(NOW(6),INTERVAL 15 MINUTE)");
        var candidates = db.queryForList("""
                SELECT i.* FROM product_main_image_template_images i JOIN product_main_image_templates t ON t.id=i.template_id
                WHERE i.status='PENDING' AND t.archived=FALSE ORDER BY t.created_at,i.sort_order LIMIT 2
                """);
        for (var image : candidates) {
            String id = (String) image.get("id");
            if (db.update("UPDATE product_main_image_template_images SET status='QUEUED',analysis_json=NULL,updated_at=NOW(6) WHERE id=? AND status='PENDING'", id) != 1) continue;
            try { executor.execute(() -> analyze(image)); }
            catch (java.util.concurrent.RejectedExecutionException busy) {
                db.update("UPDATE product_main_image_template_images SET status='PENDING',updated_at=NOW(6) WHERE id=? AND status='QUEUED'", id);
            }
        }
    }

    private void analyze(Map<String, Object> image) {
        String id = (String) image.get("id"), org = (String) image.get("organization_id");
        if (db.update("""
                UPDATE product_main_image_template_images i JOIN product_main_image_templates t ON t.id=i.template_id
                SET i.status='ANALYZING',i.updated_at=NOW(6)
                WHERE i.id=? AND i.status='QUEUED' AND t.archived=FALSE
                """, id) != 1) return;
        try {
            var result = vision.analyze(org, "PRODUCT_MAIN_IMAGE_STYLE_V1",
                    List.of(archive.read(org, (String) image.get("storage_key"))), Map.of(), progress ->
                    db.update("UPDATE product_main_image_template_images SET analysis_json=?,updated_at=NOW(6) WHERE id=? AND status='ANALYZING'",
                            json.writeValueAsString(Map.of("progress", progress)), id));
            ProductMainImageStylePolicy.validateAnalysis(result);
            db.update("UPDATE product_main_image_template_images SET status='READY',analysis_json=?,error_message=NULL,updated_at=NOW(6) WHERE id=? AND status='ANALYZING'",
                    json.writeValueAsString(result), id);
        } catch (RuntimeException failure) {
            String reason = failure instanceof BusinessException ? failure.getMessage() : "视觉分析暂不可用或返回格式不完整，请重试";
            db.update("UPDATE product_main_image_template_images SET status='FAILED',error_message=?,updated_at=NOW(6) WHERE id=? AND status='ANALYZING'",
                    reason.substring(0, Math.min(reason.length(), 1000)), id);
        }
    }

    private View view(Map<String, Object> row) {
        String id = (String) row.get("id"), org = (String) row.get("organization_id");
        var images = imageRows(id, org).stream().map(image -> new ImageView((String) image.get("id"), ((Number) image.get("sort_order")).intValue(),
                "/api/v1/product-main-image-templates/" + id + "/images/" + image.get("id"), (String) image.get("status"),
                ((Number) image.get("width")).intValue(), ((Number) image.get("height")).intValue(),
                map(image.get("analysis_json")), (String) image.get("error_message"))).toList();
        String status = images.stream().anyMatch(image -> "FAILED".equals(image.status())) ? "FAILED"
                : !images.isEmpty() && images.stream().allMatch(image -> "READY".equals(image.status())) ? "READY" : "ANALYZING";
        return new View(id, (String) row.get("name"), status, images.size(), images);
    }
    private List<Map<String, Object>> imageRows(String id, String org) {
        return db.queryForList("SELECT * FROM product_main_image_template_images WHERE template_id=? AND organization_id=? ORDER BY sort_order", id, org);
    }
    private Map<String, Object> row(String id, String org, boolean active) {
        var rows = db.queryForList("SELECT * FROM product_main_image_templates WHERE id=? AND organization_id=?" + (active ? " AND archived=FALSE" : ""), id, org);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    private Map<String, Object> map(Object value) {
        return value == null ? new LinkedHashMap<>() : json.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }
    static int[] dimensions(byte[] bytes) throws java.io.IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw bad("图片无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 128 || height < 128 || (long) width * height > 40_000_000) throw bad("参考图至少128像素，且不能超过4000万像素");
                if (reader.read(0) == null) throw bad("图片内容不完整");
                return new int[]{width, height};
            } finally { reader.dispose(); }
        }
    }
    private static void access(PlatformPrincipal user, boolean edit) {
        if (user == null || user.organizationId() == null || !(edit ? user.permissionCodes().contains("product:edit")
                : user.permissionCodes().stream().anyMatch(p -> Set.of("product:view", "product:publish", "product:edit").contains(p))))
            throw new BusinessException(HttpStatus.FORBIDDEN, "没有主图风格模板权限");
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "主图风格模板不存在"); }
}
