package com.yingdao.aicommerce.product.application.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Media checkpoints survive a surrounding product publication transaction rollback. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ShopeeProductVideoUploadLedger {
    private final JdbcTemplate db;
    public ShopeeProductVideoUploadLedger(JdbcTemplate db) { this.db = db; }

    public Map<String, Object> prepare(String id, String org, String shop, String video,
                                       String hash, String destination, long byteLength) {
        db.update("INSERT IGNORE INTO shopee_product_video_uploads(id,organization_id,shop_id,source_video_id,content_sha256,destination_key,byte_length,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PREPARED',NOW(6),NOW(6))",
                id, org, shop, video, hash, destination, byteLength);
        return get(id, org);
    }

    public Map<String, Object> get(String id, String org) {
        var rows = db.queryForList("SELECT * FROM shopee_product_video_uploads WHERE id=? AND organization_id=?", id, org);
        if (rows.isEmpty()) throw new IllegalStateException("视频上传记录不存在");
        return rows.getFirst();
    }

    public boolean acquire(String id, String org, String worker) {
        return db.update("UPDATE shopee_product_video_uploads SET locked_by=?,lease_until=DATE_ADD(NOW(6),INTERVAL 300 SECOND),updated_at=NOW(6) WHERE id=? AND organization_id=? AND (locked_by IS NULL OR lease_until<NOW(6))",
                worker, id, org) == 1;
    }

    public void renew(String id, String org, String worker) {
        changed(db.update("UPDATE shopee_product_video_uploads SET lease_until=DATE_ADD(NOW(6),INTERVAL 300 SECOND),updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?",
                id, org, worker));
    }

    public void initialized(String id, String org, String worker, String uploadId) {
        changed(db.update("UPDATE shopee_product_video_uploads SET video_upload_id=?,status='UPLOADING',next_part=0,message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?",
                uploadId, id, org, worker));
    }

    public boolean beginInitialization(String id, String org, String worker) {
        return db.update("UPDATE shopee_product_video_uploads SET status='INITIATING',init_attempts=init_attempts+1,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=? AND video_upload_id IS NULL AND init_attempts<3",
                id, org, worker) == 1;
    }

    public void partUploaded(String id, String org, String worker, int sequence) {
        changed(db.update("UPDATE shopee_product_video_uploads SET next_part=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=? AND next_part=?",
                sequence + 1, id, org, worker, sequence));
    }

    public void phase(String id, String org, String worker, String status) {
        if (!java.util.Set.of("INITIATING", "COMPLETING", "TRANSCODING").contains(status)) throw new IllegalArgumentException("视频状态无效");
        changed(db.update("UPDATE shopee_product_video_uploads SET status=?,message=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?", status, id, org, worker));
    }

    public void result(String id, String org, String worker, String status, String json, String message) {
        if (!java.util.Set.of("READY", "FAILED").contains(status)) throw new IllegalArgumentException("视频结果状态无效");
        changed(db.update("UPDATE shopee_product_video_uploads SET status=?,result_json=?,message=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?",
                status, json, message, id, org, worker));
    }

    public void release(String id, String org, String worker) {
        db.update("UPDATE shopee_product_video_uploads SET locked_by=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?", id, org, worker);
    }

    private void changed(int rows) {
        if (rows != 1) throw new IllegalStateException("视频上传任务已由其他请求接管，停止当前操作");
    }
}
