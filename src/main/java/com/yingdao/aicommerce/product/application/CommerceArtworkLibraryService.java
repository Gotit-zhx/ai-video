package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

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
    private static final String PRINT = " (j.kind='VARIATE' OR j.kind REGEXP '^VARIATE_G[1-9][0-9]*$') ";
    private static final String NATIVE_REPORT = """
                  JSON_UNQUOTE(JSON_EXTRACT(c.report_json,'$.transparency.status'))='VERIFIED_ALPHA'
              AND JSON_UNQUOTE(JSON_EXTRACT(c.report_json,'$.transparency.alphaChannel'))='true'
              AND JSON_EXTRACT(c.report_json,'$.transparency.width') BETWEEN 2 AND 4096
              AND JSON_EXTRACT(c.report_json,'$.transparency.height') BETWEEN 2 AND 4096
              AND JSON_EXTRACT(c.report_json,'$.transparency.totalPixels') BETWEEN 4 AND 12000000
              AND JSON_EXTRACT(c.report_json,'$.transparency.visiblePixels') >=
                  GREATEST(4,FLOOR(JSON_EXTRACT(c.report_json,'$.transparency.totalPixels')/10000))
              AND JSON_EXTRACT(c.report_json,'$.transparency.clearPixels') >=
                  GREATEST(4,JSON_EXTRACT(c.report_json,'$.transparency.totalPixels')*0.03)
            """;
    // The native-alpha completion marker is only written after the full policy passes. Missing
    // legacy inspections are checked once per content hash, never silently hidden forever.
    // Reuse always re-reads the bytes, independently of this inexpensive listing index/cache.
    private static final String CANDIDATES_SQL = """
            WITH candidates AS (
              SELECT j.id,j.kind,j.variant_index,j.created_at,j.content_sha256,j.storage_key,b.name AS batch_name,
                CASE WHEN j.message='原生透明印花已归档，透明背景检查通过' AND
            """ + NATIVE_REPORT + """
                THEN 1 ELSE 0 END AS native_verified,
                ROW_NUMBER() OVER (PARTITION BY j.content_sha256 ORDER BY j.created_at DESC,j.id DESC) AS content_rank
            """ + FROM + " LEFT JOIN commerce_batch_artwork_checks c ON c.job_id=j.id " + SCOPE + """
              AND j.status='READY' AND j.model_config_id<>'SAVED_ARTWORK' AND
            """ + PRINT + """
              AND j.content_sha256 REGEXP '^[a-f0-9]{64}$'
              AND j.storage_key=CONCAT(SHA2(j.organization_id,256),'/',j.content_sha256)
              AND (JSON_EXTRACT(c.report_json,'$.transparency.status') IS NULL OR
            """ + NATIVE_REPORT + """
              )
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
    private final Map<String, Boolean> legacyValidity = Collections.synchronizedMap(
            new LinkedHashMap<>(64, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 256; }
            });

    public CommerceArtworkLibraryService(JdbcTemplate db, CommerceImageArchive archive) {
        this.db = db;
        this.archive = archive;
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
            if (rows.stream().allMatch(row -> row.get("native_verified") instanceof Number verified && verified.intValue() == 1))
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
        if (row.get("native_verified") instanceof Number verified && verified.intValue() == 1) return true;
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
        return valid;
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

    private Map<String, Object> find(String id, PlatformPrincipal user) {
        access(user);
        if (id == null || id.isBlank() || id.length() > 36)
            throw error(HttpStatus.NOT_FOUND, "印花素材不存在或无权访问");
        String owner = user.externallyRegistered() ? user.userId() : null;
        var rows = db.queryForList("SELECT j.id,j.status,j.kind,j.storage_key,j.content_sha256 " + FROM
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
