package com.yingdao.aicommerce.product.application.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** A product rollback must not discard a confirmed TikTok media upload. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TikTokProductVideoUploadLedger {
    private final JdbcTemplate db;

    public TikTokProductVideoUploadLedger(JdbcTemplate db) { this.db = db; }

    public Map<String, Object> prepare(String id, String org, String shop, String video,
                                      String hash, String destination, long byteLength, String requestKey) {
        db.update("INSERT IGNORE INTO tiktok_product_video_uploads(id,organization_id,shop_id,source_video_id,content_sha256,destination_key,byte_length,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PREPARED',NOW(6),NOW(6))",
                id, org, shop, video, hash, destination, byteLength);
        db.update("INSERT IGNORE INTO tiktok_product_video_upload_attempts(upload_id,organization_id,request_key,status,created_at,updated_at) VALUES(?,?,?,'PREPARED',NOW(6),NOW(6))", id, org, requestKey);
        return get(id, org, requestKey);
    }

    public Map<String, Object> get(String id, String org, String requestKey) {
        var rows = db.queryForList("SELECT u.*,a.status AS attempt_status,a.upload_attempts,a.message AS attempt_message FROM tiktok_product_video_uploads u JOIN tiktok_product_video_upload_attempts a ON a.upload_id=u.id AND a.organization_id=u.organization_id WHERE u.id=? AND u.organization_id=? AND a.request_key=?", id, org, requestKey);
        if (rows.isEmpty()) throw new IllegalStateException("TikTok 视频上传记录不存在");
        return rows.getFirst();
    }

    public boolean acquire(String id, String org, String worker) {
        return db.update("UPDATE tiktok_product_video_uploads SET locked_by=?,lease_until=DATE_ADD(NOW(6),INTERVAL 300 SECOND),updated_at=NOW(6) WHERE id=? AND organization_id=? AND (locked_by IS NULL OR lease_until<NOW(6))",
                worker, id, org) == 1;
    }

    public boolean beginUpload(String id, String org, String worker, String requestKey) {
        return db.update("UPDATE tiktok_product_video_upload_attempts a JOIN tiktok_product_video_uploads u ON u.id=a.upload_id AND u.organization_id=a.organization_id SET a.status='UPLOADING',a.upload_attempts=a.upload_attempts+1,a.message=NULL,a.updated_at=NOW(6) WHERE a.upload_id=? AND a.organization_id=? AND u.locked_by=? AND a.request_key=? AND u.video_id IS NULL AND a.upload_attempts<3 AND a.status IN ('PREPARED','UPLOADING')",
                id, org, worker, requestKey) == 1;
    }

    public void ready(String id, String org, String worker, String videoId, String resultJson) {
        changed(db.update("UPDATE tiktok_product_video_uploads SET status='READY',video_id=?,result_json=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?",
                videoId, resultJson, id, org, worker));
    }

    public void failed(String id, String org, String worker, String requestKey, String message) {
        changed(db.update("UPDATE tiktok_product_video_upload_attempts a JOIN tiktok_product_video_uploads u ON u.id=a.upload_id AND u.organization_id=a.organization_id SET a.status='FAILED',a.message=?,a.updated_at=NOW(6) WHERE a.upload_id=? AND a.organization_id=? AND u.locked_by=? AND a.request_key=?",
                message, id, org, worker, requestKey));
    }

    public void release(String id, String org, String worker) {
        db.update("UPDATE tiktok_product_video_uploads SET locked_by=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND locked_by=?", id, org, worker);
    }

    private void changed(int count) {
        if (count != 1) throw new VideoUploadPendingException("TikTok 视频上传记录已由另一任务接管，后台将读取已保存结果");
    }
}
