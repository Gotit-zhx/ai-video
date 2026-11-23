package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ProductCaptureTemplateService {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final String BASE = "/api/v1/product-capture-templates";
    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    private final ObjectMapper json;
    public record Color(String id, String name, String hex, String colorCode) {}
    public record Variant(String id, String name, String imageId, List<Color> colors, String styleCode) {}
    public record Save(long version, String name, String description, List<Variant> variants) {}
    public record Image(String id, String imageUrl, String filename, String mimeType, int width, int height) {}
    public record VariantView(String id, String name, String imageId, String image, String filename, List<Color> colors, String styleCode) {}
    public record View(String id, long version, String name, String description, List<VariantView> variants) {}
    public record Page(List<View> items, long total, int offset, int limit) {}

    public ProductCaptureTemplateService(JdbcTemplate db, CommerceImageArchive archive, ObjectMapper json) {
        this.db = db; this.archive = archive; this.json = json;
    }
    public static void access(PlatformPrincipal user, boolean edit) {
        if (user == null) throw fail(HttpStatus.UNAUTHORIZED, "请先登录");
        if (user.organizationId() == null || user.organizationId().isBlank() ||
                (edit ? !user.permissionCodes().contains("product:edit") : user.permissionCodes().stream().noneMatch(
                        Set.of("product:view", "product:edit", "product:publish", "product:collect")::contains)))
            throw fail(HttpStatus.FORBIDDEN, "没有采集模板权限");
    }
    public Page list(int offset, int limit, PlatformPrincipal user) {
        access(user, false);
        if (offset < 0 || limit < 1 || limit > 100) throw bad("分页参数无效");
        Long total = db.queryForObject("SELECT COUNT(*) FROM product_capture_templates WHERE organization_id=?", Long.class, user.organizationId());
        var rows = db.queryForList("SELECT * FROM product_capture_templates WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
                user.organizationId(), limit, offset);
        return new Page(rows.stream().map(r -> view(r, user.organizationId())).toList(), total == null ? 0 : total, offset, limit);
    }
    public View get(String id, PlatformPrincipal user) {
        access(user, false); uuid(id);
        return view(row(id, user.organizationId()), user.organizationId());
    }
    /** An absent scope selects the existing enterprise configuration. A style scope must exist in this enterprise. */
    public String settingsScope(String id, PlatformPrincipal user, boolean edit) {
        if (id == null) return "";
        access(user, edit);
        uuid(id);
        return (String) row(id, user.organizationId()).get("id");
    }
    @Transactional
    public View save(String id, Save input, PlatformPrincipal user) {
        access(user, true); uuid(id);
        Save value = normalize(input);
        // Only ledger-owned image ids are accepted. Never accept URLs, storage keys or organization ids from a client.
        for (Variant variant : value.variants()) imageRow(variant.imageId(), user.organizationId());
        String content = json.writeValueAsString(value.variants());
        String fingerprint = sha(json.writeValueAsBytes(List.of(value.name(), value.description(), value.variants())));
        int changed;
        if (value.version() == 0) {
            try {
                changed = db.update("""
                        INSERT INTO product_capture_templates(organization_id,id,name,description,content_json,version,fingerprint,created_by,updated_by,created_at,updated_at)
                        VALUES(?,?,?,?,?,1,?,?,?,NOW(6),NOW(6))
                        """, user.organizationId(), id, value.name(), value.description(), content, fingerprint, user.userId(), user.userId());
            } catch (DuplicateKeyException duplicate) { changed = 0; }
        } else {
            changed = db.update("""
                    UPDATE product_capture_templates SET name=?,description=?,content_json=?,fingerprint=?,version=version+1,updated_by=?,updated_at=NOW(6)
                    WHERE organization_id=? AND id=? AND version=?
                    """, value.name(), value.description(), content, fingerprint, user.userId(), user.organizationId(), id, value.version());
        }
        var rows = db.queryForList("SELECT * FROM product_capture_templates WHERE organization_id=? AND id=?", user.organizationId(), id);
        if (rows.isEmpty()) throw missing();
        var saved = rows.getFirst();
        // A lost response may be retried; an identical request is safe, a stale different edit is not.
        if (changed == 0 && !fingerprint.equals(saved.get("fingerprint")))
            throw fail(HttpStatus.CONFLICT, "模板已被修改，请重新打开后再保存");
        return view(saved, user.organizationId());
    }
    public Image upload(MultipartFile file, PlatformPrincipal user) {
        access(user, true);
        if (!archive.usesOss()) throw fail(HttpStatus.SERVICE_UNAVAILABLE, "OSS 未配置，无法上传图片");
        if (file == null || file.isEmpty() || file.getSize() > MAX_IMAGE_BYTES) throw bad("图片须为 8 MiB 以内的 PNG、JPG 或 WebP");
        byte[] bytes;
        int[] size;
        try { bytes = file.getBytes(); size = dimensions(bytes); }
        catch (BusinessException invalid) { throw invalid; }
        catch (Exception invalid) { throw bad("图片内容无效，请更换图片"); }
        CommerceImageArchive.Archived stored;
        try { stored = archive.store(user.organizationId(), bytes); }
        catch (RuntimeException unavailable) { throw fail(HttpStatus.SERVICE_UNAVAILABLE, "OSS 上传失败，请重试"); }
        String id = UUID.nameUUIDFromBytes(stored.storageKey().getBytes(StandardCharsets.UTF_8)).toString();
        String filename = Objects.toString(file.getOriginalFilename(), "image").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank()) filename = "image";
        filename = filename.substring(0, Math.min(180, filename.length()));
        db.update("""
                INSERT IGNORE INTO product_capture_template_images(organization_id,id,created_by,storage_key,file_name,mime_type,byte_length,width,height,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,NOW(6))
                """, user.organizationId(), id, user.userId(), stored.storageKey(), filename, stored.mimeType(), stored.byteLength(), size[0], size[1]);
        return imageView(imageRow(id, user.organizationId()));
    }
    public CommerceDesignImageGateway.Reference image(String id, PlatformPrincipal user) {
        access(user, false); uuid(id);
        var image = imageRow(id, user.organizationId());
        if (!archive.usesOss()) throw fail(HttpStatus.SERVICE_UNAVAILABLE, "OSS 暂不可用");
        try { return archive.read(user.organizationId(), (String) image.get("storage_key")); }
        catch (RuntimeException unavailable) { throw fail(HttpStatus.SERVICE_UNAVAILABLE, "图片读取失败，请重试"); }
    }
    static Save normalize(Save input) {
        if (input == null || input.version() < 0) throw bad("模板版本无效");
        String name = text(input.name(), 50, true, "模板名称");
        String description = text(input.description(), 160, false, "模板说明");
        if (input.variants() == null || input.variants().size() != 1) throw bad("每个模板只能配置一个款式");
        Set<String> variantIds = new HashSet<>();
        List<Variant> variants = new ArrayList<>();
        for (Variant v : input.variants()) {
            if (v == null) throw bad("版式无效");
            uuid(v.id()); uuid(v.imageId());
            if (!variantIds.add(v.id())) throw bad("版式编号重复");
            String variantName = text(v.name(), 40, true, "款式名称");
            String styleCode = Objects.toString(v.styleCode(), "").trim().toUpperCase(Locale.ROOT);
            if (!styleCode.matches("[A-Z][0-9]{2}")) throw bad("款式编码请输入一个英文字母和两位数字，例如 A01");
            if (v.colors() == null || v.colors().isEmpty() || v.colors().size() > 30) throw bad("每个版式支持 1 至 30 个颜色");
            Set<String> colorIds = new HashSet<>(), colorCodes = new HashSet<>(); List<Color> colors = new ArrayList<>();
            for (Color c : v.colors()) {
                if (c == null) throw bad("颜色无效");
                uuid(c.id());
                if (!colorIds.add(c.id())) throw bad("颜色编号重复");
                String colorName = text(c.name(), 24, true, "颜色名称");
                String colorCode = Objects.toString(c.colorCode(), "").trim().toUpperCase(Locale.ROOT);
                if (!colorCode.matches("[A-Z0-9]{1,16}")) throw bad("颜色编码请输入 1 至 16 位英文字母或数字");
                if (!colorCodes.add(colorCode)) throw bad("同一款式的颜色编码不能重复");
                String hex = Objects.toString(c.hex(), "").trim().toUpperCase(Locale.ROOT);
                if (hex.matches("#[A-F0-9]{3}")) hex = "#" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
                if (!hex.matches("#[A-F0-9]{6}")) throw bad("颜色须为有效的 HEX 色值");
                colors.add(new Color(c.id(), colorName, hex, colorCode));
            }
            variants.add(new Variant(v.id(), variantName, v.imageId(), List.copyOf(colors), styleCode));
        }
        return new Save(input.version(), name, description, List.copyOf(variants));
    }
    private View view(Map<String, Object> row, String org) {
        List<Variant> variants = json.readValue(row.get("content_json").toString(), new TypeReference<List<Variant>>() {});
        var result = variants.stream().map(v -> {
            Image i = imageView(imageRow(v.imageId(), org));
            return new VariantView(v.id(), v.name(), v.imageId(), i.imageUrl(), i.filename(), v.colors(), v.styleCode());
        }).toList();
        return new View((String) row.get("id"), ((Number) row.get("version")).longValue(), (String) row.get("name"), (String) row.get("description"), result);
    }
    private Image imageView(Map<String, Object> row) {
        String id = (String) row.get("id");
        return new Image(id, BASE + "/images/" + id, (String) row.get("file_name"), (String) row.get("mime_type"),
                ((Number) row.get("width")).intValue(), ((Number) row.get("height")).intValue());
    }
    private Map<String, Object> row(String id, String org) {
        var rows = db.queryForList("SELECT * FROM product_capture_templates WHERE organization_id=? AND id=?", org, id);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private Map<String, Object> imageRow(String id, String org) {
        var rows = db.queryForList("SELECT * FROM product_capture_template_images WHERE organization_id=? AND id=?", org, id);
        if (rows.isEmpty()) throw fail(HttpStatus.NOT_FOUND, "版式图片不存在"); return rows.getFirst();
    }
    static int[] dimensions(byte[] bytes) throws java.io.IOException {
        if (bytes.length < 12 || bytes.length > MAX_IMAGE_BYTES) throw bad("图片大小无效");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw bad("仅支持有效的 PNG、JPG 或 WebP 图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!Set.of("PNG", "JPEG", "JPG", "WEBP").contains(reader.getFormatName().toUpperCase(Locale.ROOT))) throw bad("图片格式不支持");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 128 || h < 128 || (long) w * h > 40_000_000) throw bad("图片至少 128 像素且不超过 4000 万像素");
                if (reader.read(0) == null) throw bad("图片内容不完整");
                return new int[]{w, h};
            } finally { reader.dispose(); }
        }
    }
    private static void uuid(String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw bad("编号无效");
    }
    private static String text(String input, int max, boolean required, String label) {
        String value = Objects.toString(input, "").trim();
        if (value.length() > max || required && value.isEmpty()) throw bad(label + "须为" + (required ? "1" : "0") + "至" + max + "字");
        return value;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static BusinessException missing() { return fail(HttpStatus.NOT_FOUND, "模板不存在"); }
    private static BusinessException bad(String message) { return fail(HttpStatus.BAD_REQUEST, message); }
    private static BusinessException fail(HttpStatus status, String message) { return new BusinessException(status, message); }
}
