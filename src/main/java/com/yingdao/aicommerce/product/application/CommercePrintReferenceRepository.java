package com.yingdao.aicommerce.product.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Every transition is an autocommitted CAS before/after slow external operations. */
@Repository
public class CommercePrintReferenceRepository {
    public record Job(String id, String organizationId, String createdBy, String requestId,
                      String originalImageId, boolean alreadyPlain, String promptVersion, String prompt,
                      String modelConfigId, String modelId, String status, String stage, String message,
                      String resultJson, String sheetImageId, String baseImagesJson, String leaseToken, int collectAttempts) {}
    public record Work(String organizationId, String id) {}
    public record RequestKey(String jobId, String originalImageId, boolean alreadyPlain) {}
    private final JdbcTemplate db;
    private static final RowMapper<Job> ROW = (r, n) -> new Job(r.getString("id"), r.getString("organization_id"),
            r.getString("created_by"), r.getString("request_id"), r.getString("original_image_id"), r.getBoolean("already_plain"),
            r.getString("prompt_version"), r.getString("prompt"), r.getString("model_config_id"), r.getString("model_id"),
            r.getString("status"), r.getString("stage"), r.getString("message"), r.getString("result_json"),
            r.getString("sheet_image_id"), r.getString("base_images_json"), r.getString("lease_token"), r.getInt("collect_attempts"));

    public CommercePrintReferenceRepository(JdbcTemplate db) { this.db = db; }
    private Job one(String sql, Object... args) { var rows = db.query(sql, ROW, args); return rows.isEmpty() ? null : rows.getFirst(); }
    public Job find(String org, String id) { return one("SELECT * FROM commerce_print_reference_jobs WHERE organization_id=? AND id=?", org, id); }
    public Job byRequest(String org, String request) { return one("SELECT * FROM commerce_print_reference_jobs WHERE organization_id=? AND request_id=?", org, request); }
    public Job bySource(String org, String source, String version) { return one("SELECT * FROM commerce_print_reference_jobs WHERE organization_id=? AND original_image_id=? AND prompt_version=?", org, source, version); }
    public RequestKey requestKey(String org, String request) {
        var rows = db.query("SELECT job_id,original_image_id,already_plain FROM commerce_print_reference_requests WHERE organization_id=? AND request_id=?", (r,n) -> new RequestKey(r.getString(1),r.getString(2),r.getBoolean(3)), org, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public RequestKey bindRequest(String org, String request, String source, boolean plain, String job) {
        db.update("INSERT IGNORE INTO commerce_print_reference_requests(organization_id,request_id,job_id,original_image_id,already_plain,created_at) VALUES(?,?,?,?,?,NOW(6))", org, request, job, source, plain);
        // Locking read sees the winning concurrent request binding, even inside REPEATABLE READ.
        var rows = db.query("SELECT job_id,original_image_id,already_plain FROM commerce_print_reference_requests WHERE organization_id=? AND request_id=? FOR UPDATE", (r,n) -> new RequestKey(r.getString(1),r.getString(2),r.getBoolean(3)), org, request);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void insert(Job j) {
        db.update("INSERT IGNORE INTO commerce_print_reference_jobs(id,organization_id,created_by,request_id,original_image_id,already_plain,prompt_version,prompt,model_config_id,model_id,status,stage,message,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED','QUEUED',?,NOW(6),NOW(6))",
                j.id(), j.organizationId(), j.createdBy(), j.requestId(), j.originalImageId(), j.alreadyPlain(), j.promptVersion(), j.prompt(), j.modelConfigId(), j.modelId(), j.message());
    }
    public List<Work> pending(int limit) {
        return db.query("SELECT organization_id,id FROM commerce_print_reference_jobs WHERE status IN ('QUEUED','RESULT_RECEIVED') AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(6)) ORDER BY created_at LIMIT ?",
                (r,n) -> new Work(r.getString(1),r.getString(2)), Math.max(1, Math.min(16, limit)));
    }
    public boolean claim(Job j, String token, boolean collecting) {
        String from = collecting ? "RESULT_RECEIVED" : "QUEUED", to = collecting ? "COLLECTING" : "SUBMITTING";
        return db.update("UPDATE commerce_print_reference_jobs SET status=?,stage=?,message=?,lease_token=?,lease_until=DATE_ADD(NOW(6),INTERVAL 180 SECOND),submitted_at=IF(?='SUBMITTING',NOW(6),submitted_at),collect_attempts=collect_attempts+?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status=?",
                to, collecting ? "SPLITTING" : "GENERATING", collecting ? "正在归档并裁切同组四面参考图" : "正在生成同款前后左右四面参考图", token, to, collecting ? 1 : 0, j.id(), j.organizationId(), from) == 1;
    }
    public void received(Job j, String token, String result) {
        db.update("UPDATE commerce_print_reference_jobs SET status='RESULT_RECEIVED',stage='RESULT_RECEIVED',message='生图结果已保存，等待裁切归档',result_json=?,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND lease_token=? AND status IN ('SUBMITTING','UNKNOWN') AND result_json IS NULL", result, j.id(), j.organizationId(), token);
    }
    public void ready(Job j, String token, String sheet, String images) {
        db.update("UPDATE commerce_print_reference_jobs SET status='READY',stage='READY',message='同组四面参考图已生成，可自动建立粗模',sheet_image_id=?,base_images_json=?,lease_token=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='COLLECTING' AND lease_token=?", sheet, images, j.id(), j.organizationId(), token);
    }
    public void uncertain(Job j, String token) {
        db.update("UPDATE commerce_print_reference_jobs SET status='UNKNOWN',stage='OUTCOME_UNKNOWN',message='模型提交结果待核查，已保留任务，不会重复收费生成',lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='SUBMITTING' AND lease_token=?", j.id(), j.organizationId(), token);
    }
    public void preflightFailed(Job j, String stage, String message) {
        db.update("UPDATE commerce_print_reference_jobs SET status='FAILED',stage=?,message=?,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='QUEUED'", stage, message, j.id(), j.organizationId());
    }
    public void failed(Job j, String token, String stage, String message) {
        db.update("UPDATE commerce_print_reference_jobs SET status='FAILED',stage=?,message=?,lease_token=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status IN ('SUBMITTING','COLLECTING') AND lease_token=?", stage, message, j.id(), j.organizationId(), token);
    }
    public void collectRetry(Job j, String token) {
        db.update("UPDATE commerce_print_reference_jobs SET status=IF(collect_attempts>=5,'FAILED','RESULT_RECEIVED'),stage=IF(collect_attempts>=5,'COLLECT_FAILED','DOWNLOADING'),message='模型结果已保留；下载或归档暂时失败，重试不会再次调用生图',lease_token=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(NOW(6),INTERVAL 20 SECOND),updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='COLLECTING' AND lease_token=?", j.id(), j.organizationId(), token);
    }
    public void retrySafe(Job j) {
        if ("COLLECT_FAILED".equals(j.stage()) && j.resultJson() != null) {
            db.update("UPDATE commerce_print_reference_jobs SET status='RESULT_RECEIVED',stage='DOWNLOADING',collect_attempts=0,next_attempt_at=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='FAILED' AND stage='COLLECT_FAILED'", j.id(), j.organizationId());
        } else if (Set.of("NOT_SENT", "CONFIG_FAILED", "SOURCE_FAILED").contains(j.stage())) {
            db.update("UPDATE commerce_print_reference_jobs SET status='QUEUED',stage='QUEUED',message='已重新排队',next_attempt_at=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND status='FAILED' AND stage IN ('NOT_SENT','CONFIG_FAILED','SOURCE_FAILED')", j.id(), j.organizationId());
        }
    }
    public void heartbeat(Work work, String token) {
        db.update("UPDATE commerce_print_reference_jobs SET lease_until=DATE_ADD(NOW(6),INTERVAL 180 SECOND) WHERE id=? AND organization_id=? AND lease_token=? AND status IN ('SUBMITTING','COLLECTING')", work.id(), work.organizationId(), token);
    }
    public void recoverExpired() {
        db.update("UPDATE commerce_print_reference_jobs SET status='UNKNOWN',stage='OUTCOME_UNKNOWN',message='处理进程中断或超时，模型结果待核查；不会重新收费提交',lease_until=NULL,updated_at=NOW(6) WHERE status='SUBMITTING' AND (lease_until<NOW(6) OR submitted_at<DATE_SUB(NOW(6),INTERVAL 125 MINUTE))");
        db.update("UPDATE commerce_print_reference_jobs SET status='RESULT_RECEIVED',stage='DOWNLOADING',message='恢复已保存的生图结果，继续裁切归档',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,updated_at=NOW(6) WHERE status='COLLECTING' AND lease_until<NOW(6) AND result_json IS NOT NULL");
    }
}
