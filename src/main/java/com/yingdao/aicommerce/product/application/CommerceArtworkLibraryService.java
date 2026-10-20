package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Read-only library of completed, reusable print masters; product mockups are never library artwork. */
@Service
public class CommerceArtworkLibraryService {
    public static final int PAGE_SIZE = 24;
    public record Item(String id, String name, String batchName, int variant, Instant createdAt,
                       String contentSha256, String imageUrl) {}
    public record Page(int page, boolean hasMore, List<Item> items) {}
    /** Trusted server-side source. Storage keys are deliberately absent from the public list response. */
    public record ResolvedArtwork(String id, String contentSha256, String storageKey) {}

    private static final String FROM = """
            FROM commerce_batch_artwork_jobs j
            JOIN commerce_batch_executions e ON e.id=j.execution_id AND e.organization_id=j.organization_id
            JOIN commerce_batches b ON b.id=e.batch_id AND b.organization_id=j.organization_id
            """;
    private static final String SCOPE = " WHERE j.organization_id=? AND (? IS NULL OR e.created_by=?) ";
    private static final String CANDIDATES_SQL = """
            WITH candidates AS (
              SELECT artwork_id AS id,kind,variant_index,created_at,content_sha256,storage_key,batch_name,
                native_verified,
                ROW_NUMBER() OVER (PARTITION BY content_sha256 ORDER BY created_at DESC,artwork_id DESC) AS content_rank
              FROM commerce_material_library_entries
              WHERE organization_id=? AND (? IS NULL OR created_by=?)
            )
            """;
    private static final String MATCHES = " WHERE content_rank=1 AND (?='' OR LOCATE(?,CONCAT_WS(' ',batch_name,'印花',CONCAT('V',variant_index)))>0) ";
    static final String LIST_SQL = CANDIDATES_SQL
            + " SELECT id,kind,variant_index,created_at,content_sha256,storage_key,batch_name,native_verified FROM candidates "
            + MATCHES + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?";
    static final String LEGACY_SQL = CANDIDATES_SQL + " SELECT id FROM candidates " + MATCHES
            + " AND native_verified=0 LIMIT 1";

    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    // Small previews only; source masters never enter this cache. Authorize every read
    // before using the tenant/content key, including browser cache revalidation.
    private final Map<String, byte[]> thumbnails = Collections.synchronizedMap(
            new LinkedHashMap<>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > 128; }
            });
    private final Object[] thumbnailLocks = new Object[16];
    private final java.util.concurrent.Semaphore thumbnailDecoders = new java.util.concurrent.Semaphore(2, true);
    private final Map<String, Boolean> legacyValidity = Collections.synchronizedMap(
            new LinkedHashMap<>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 256; }
            });

    public CommerceArtworkLibraryService(JdbcTemplate db, CommerceImageArchive archive) {
        this.db = db;
        this.archive = archive;
        Arrays.setAll(thumbnailLocks, ignored -> new Object());
    }

    public Page list(int page, String query, PlatformPrincipal user) {
        access(user);
        if (page < 0 || page > 1_000) throw error(HttpStatus.BAD_REQUEST, "印花素材页码超出范围，请输入批次名称缩小搜索范围");
        String search = query == null ? "" : query.strip();
        if (search.length() > 120) throw error(HttpStatus.BAD_REQUEST, "搜索文字不能超过 120 字");
        String owner = user.externallyRegistered() ? user.userId() : null;
        int[] legacyChecks = {0};
        // Healthy libraries page directly in SQL. Probe only later pages: the first page is
        // already bounded, while rescanning every prior page would make large libraries slow.
        if (page > 0 && db.queryForList(LEGACY_SQL, user.organizationId(), owner, owner, search, search).isEmpty()) {
            var rows = db.queryForList(LIST_SQL, user.organizationId(), owner, owner, search, search,
                    PAGE_SIZE + 1, (long) page * PAGE_SIZE);
            if (rows.stream().allMatch(CommerceArtworkLibraryService::nativeVerified))
                return new Page(page, rows.size() > PAGE_SIZE, rows.stream().limit(PAGE_SIZE).map(CommerceArtworkLibraryService::item).toList());
            // A newly completed legacy result may have appeared between queries; use the
            // full compatibility path so an old opaque image never leaks into the page.
        }
        var items = new ArrayList<Item>();
        long accepted = 0, skip = (long) page * PAGE_SIZE, offset = 0;
        final int chunk = 48;
        while (items.size() <= PAGE_SIZE) {
            if (offset >= 960) throw narrowSearch();
            var rows = db.queryForList(LIST_SQL, user.organizationId(), owner, owner, search, search, chunk, offset);
            for (var row : rows) {
                if (!listedNative(row, user, legacyChecks)) continue;
                if (accepted++ < skip) continue;
                items.add(item(row));
                if (items.size() > PAGE_SIZE) break;
            }
            if (rows.size() < chunk) break;
            offset += rows.size();
        }
        return new Page(page, items.size() > PAGE_SIZE, List.copyOf(items.subList(0, Math.min(items.size(), PAGE_SIZE))));
    }

    private boolean listedNative(Map<String, Object> row, PlatformPrincipal user, int[] legacyChecks) {
        if (nativeVerified(row)) return true;
        String key = Objects.toString(row.get("storage_key"), "");
        Boolean cached = legacyValidity.get(key);
        if (cached != null) return cached;
        if (++legacyChecks[0] > 48) throw narrowSearch();
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(archive.read(user.organizationId(), key).dataBase64());
            if (!CommerceImageArchive.sha(bytes).equals(row.get("content_sha256"))) return false;
        } catch (IllegalStateException | IllegalArgumentException temporarilyUnavailable) {
            return false; // Do not cache unavailable files: storage may recover without changing the source.
        }
        boolean valid;
        try { CommerceTransparentArtworkPolicy.requireNative(bytes); valid = true; }
        catch (IllegalArgumentException invalid) { valid = false; }
        legacyValidity.put(key, valid);
        if(valid)db.update("""
                UPDATE commerce_material_library_entries SET native_verified=TRUE
                WHERE organization_id=? AND artwork_id=? AND content_sha256=?
                """,user.organizationId(),row.get("id"),row.get("content_sha256"));
        return valid;
    }

    private static boolean nativeVerified(Map<String,Object> row) {
        Object value=row.get("native_verified");
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()==1;
    }

    private static BusinessException narrowSearch() {
        return error(HttpStatus.BAD_REQUEST, "待兼容校验的历史印花较多，请输入批次名称缩小搜索范围后重试");
    }

    public ResolvedArtwork resolve(String id, PlatformPrincipal user) {
        var row = find(id, user);
        readChecked(row, user);
        return new ResolvedArtwork((String) row.get("id"), (String) row.get("content_sha256"),
                (String) row.get("storage_key"));
    }

    public CommerceDesignImageGateway.Reference image(String id, PlatformPrincipal user) {
        return readChecked(find(id, user), user);
    }

    public byte[] thumbnail(String id, PlatformPrincipal user) {
        var row = find(id, user);
        String key = (String) row.get("storage_key");
        synchronized (thumbnailLocks[Math.floorMod(key.hashCode(), thumbnailLocks.length)]) {
            byte[] cached = thumbnails.get(key);
            if (cached == null) {
                try { thumbnailDecoders.acquire(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error(HttpStatus.SERVICE_UNAVAILABLE, "印花缩略图读取中断，请重新读取");
                }
                try {
                    var source = readChecked(row, user);
                    cached = preview(Base64.getDecoder().decode(source.dataBase64()));
                    thumbnails.put(key, cached);
                } finally { thumbnailDecoders.release(); }
            }
            return cached.clone();
        }
    }

    private static byte[] preview(byte[] bytes) {
        var source = CommerceMockupComposer.decode(bytes);
        double scale = Math.min(1, 256.0 / Math.max(source.getWidth(), source.getHeight()));
        var image = new BufferedImage(Math.max(1, (int) Math.round(source.getWidth() * scale)),
                Math.max(1, (int) Math.round(source.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally { graphics.dispose(); }
        try {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            return output.toByteArray();
        } catch (IOException invalid) {
            throw error(HttpStatus.CONFLICT, "印花缩略图暂不可用，请重新读取");
        }
    }

    private Map<String, Object> find(String id, PlatformPrincipal user) {
        access(user);
        if(id==null || id.isBlank() || id.length()>36)
            throw error(HttpStatus.NOT_FOUND,"素材不存在或无权访问");
        String owner = user.externallyRegistered() ? user.userId() : null;
        var rows = db.queryForList("""
                SELECT artwork_id AS id,kind,content_sha256,storage_key
                FROM commerce_material_library_entries
                WHERE organization_id=? AND (? IS NULL OR created_by=?) AND artwork_id=?
                """,user.organizationId(),owner,owner,id);
        if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"素材不存在或未保存到当前公司的素材库");
        var row=rows.getFirst();
        String kind=Objects.toString(row.get("kind"),"");
        if(!(kind.equals("VARIATE")||kind.matches("VARIATE_G[1-9][0-9]*"))
                || row.containsKey("status")&&!"READY".equals(row.get("status")))
            throw error(HttpStatus.CONFLICT,"素材不是已保存的透明印花");
        String hash=Objects.toString(row.get("content_sha256"),"");
        if(!hash.matches("[a-f0-9]{64}")||!CommerceSurfaceTemplateInputs.key(user.organizationId(),hash).equals(row.get("storage_key")))
            throw error(HttpStatus.CONFLICT,"素材归档信息不完整");
        return row;
    }

    @org.springframework.transaction.annotation.Transactional
    public void saveGenerated(String id, PlatformPrincipal user) {
        access(user);
        if(!user.permissionCodes().contains("product:edit"))
            throw error(HttpStatus.FORBIDDEN,"保存素材需要商品编辑权限");
        var row=findGenerated(id,user);
        readChecked(row,user);
        db.update("""
                INSERT IGNORE INTO commerce_material_library_entries
                (organization_id,artwork_id,kind,variant_index,batch_name,content_sha256,storage_key,created_by,created_at,native_verified)
                VALUES(?,?,?,?,?,?,?,?,?,TRUE)
                """,user.organizationId(),id,row.get("kind"),row.get("variant_index"),row.get("batch_name"),
                row.get("content_sha256"),row.get("storage_key"),user.userId(),row.get("created_at"));
    }

    private Map<String, Object> findGenerated(String id, PlatformPrincipal user) {
        access(user);
        if (id == null || id.isBlank() || id.length() > 36)
            throw error(HttpStatus.NOT_FOUND, "印花素材不存在或无权访问");
        String owner = user.externallyRegistered() ? user.userId() : null;
        var rows = db.queryForList("SELECT j.id,j.status,j.kind,j.variant_index,j.created_at,j.storage_key,j.content_sha256,b.name AS batch_name " + FROM
                + SCOPE + " AND j.id=?", user.organizationId(), owner, owner, id);
        if (rows.size() != 1) throw error(HttpStatus.NOT_FOUND, "印花素材不存在或无权访问");
        var row = rows.getFirst();
        String kind = Objects.toString(row.get("kind"), "");
        if (!"READY".equals(row.get("status")) || !(kind.equals("VARIATE") || kind.matches("VARIATE_G[1-9][0-9]*")))
            throw error(HttpStatus.CONFLICT, "该素材不是已完成的裂变印花，请重新选择");
        String hash = Objects.toString(row.get("content_sha256"), "");
        if (!hash.matches("[a-f0-9]{64}") || !CommerceSurfaceTemplateInputs.key(user.organizationId(), hash).equals(row.get("storage_key")))
            throw error(HttpStatus.CONFLICT, "印花归档信息不完整或已变化，请重新选择");
        return row;
    }

    private CommerceDesignImageGateway.Reference readChecked(Map<String, Object> row, PlatformPrincipal user) {
        try {
            var reference = archive.read(user.organizationId(), (String) row.get("storage_key"));
            byte[] bytes = Base64.getDecoder().decode(reference.dataBase64());
            if (!CommerceImageArchive.sha(bytes).equals(row.get("content_sha256")))
                throw new IllegalArgumentException("归档内容与素材指纹不一致");
            CommerceTransparentArtworkPolicy.requireNative(bytes);
            return new CommerceDesignImageGateway.Reference("image/png", reference.dataBase64());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw error(HttpStatus.CONFLICT, "印花素材无法复用：归档不可读取、内容已变化或不具备有效透明背景，请重新选择");
        }
    }

    private static Item item(Map<String, Object> row) {
        String id = (String) row.get("id"), batch = Objects.toString(row.get("batch_name"), "裂变印花");
        int variant = ((Number) row.get("variant_index")).intValue();
        String kind = (String) row.get("kind");
        String group = kind.startsWith("VARIATE_G") ? " · 图案 " + kind.substring("VARIATE_G".length()) : "";
        Object createdAt = row.get("created_at");
        Instant created = createdAt instanceof Timestamp value ? value.toInstant() : (Instant) createdAt;
        return new Item(id, batch + " · 印花 V" + variant + group, batch, variant, created,
                (String) row.get("content_sha256"), "/api/v1/commerce-artwork-library/" + id + "/image");
    }

    private static void access(PlatformPrincipal user) {
        if (user == null || user.organizationId() == null || user.organizationId().isBlank()
                || !(user.permissionCodes().contains("product:view") || user.permissionCodes().contains("product:publish")))
            throw error(HttpStatus.FORBIDDEN, "无印花素材查看权限");
    }

    private static BusinessException error(HttpStatus status, String message) {
        return new BusinessException(status, message);
    }
}
