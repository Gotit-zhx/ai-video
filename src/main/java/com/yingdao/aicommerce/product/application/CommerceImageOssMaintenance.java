package com.yingdao.aicommerce.product.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Migrate originals first; retire generated-image payloads only after OSS and READY are durable. */
@Service
@ConditionalOnProperty(name = "app.oss.enabled", havingValue = "true")
public class CommerceImageOssMaintenance {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommerceImageOssMaintenance.class);
    private final CommerceImageArchive archive;
    private final CommerceDesignImageGateway gateway;
    private final JdbcTemplate db;
    private final AtomicBoolean running = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "commerce-image-oss"); thread.setDaemon(true); return thread;
    });
    public CommerceImageOssMaintenance(CommerceImageArchive archive, CommerceDesignImageGateway gateway, JdbcTemplate db) {
        this.archive = archive; this.gateway = gateway; this.db = db;
    }
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void schedule() {
        if (!archive.usesOss() || !running.compareAndSet(false, true)) return;
        try {
            com.yingdao.aicommerce.ops.MaintenanceGate.execute(worker,() -> {
                try { migrate(); }
                catch (Exception failure) { log.warn("Commerce image OSS maintenance deferred ({})", failure.getClass().getSimpleName()); }
                finally { running.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { running.set(false); }
    }
    void migrate() throws Exception {
        if (!archive.usesOss()) return;
        var priority = db.queryForList("""
                SELECT organization_id,storage_key FROM product_main_image_template_images
                UNION SELECT organization_id,source_key FROM product_main_image_jobs
                UNION SELECT organization_id,reference_key FROM product_main_image_jobs
                UNION SELECT organization_id,storage_key FROM product_main_image_jobs WHERE storage_key IS NOT NULL
                """);
        for (var row : priority) archive.migrateKnown((String) row.get("organization_id"), (String) row.get("storage_key"));
        int count = archive.migrateLocal(25);
        if (count > 0) log.info("Migrated {} commerce image files to verified OSS storage", count);
        var rows = db.queryForList("""
                SELECT id,organization_id,storage_key,attempt_count FROM product_main_image_jobs
                WHERE status='READY' AND storage_key IS NOT NULL AND result_json IS NOT NULL
                ORDER BY updated_at LIMIT 25
                """);
        for (var row : rows) {
            String id = (String) row.get("id"), org = (String) row.get("organization_id"), key = (String) row.get("storage_key");
            archive.read(org, key);
            int attempt = ((Number) row.get("attempt_count")).intValue();
            gateway.releaseArchivedOperation(id + (attempt == 0 ? "" : "_r" + attempt), key);
            db.update("""
                    UPDATE product_main_image_jobs SET result_json=NULL
                    WHERE id=? AND organization_id=? AND status='READY' AND storage_key=? AND attempt_count=?
                    """, id, org, key, attempt);
        }
    }
    @jakarta.annotation.PreDestroy
    public void close() { worker.shutdownNow(); }
}
