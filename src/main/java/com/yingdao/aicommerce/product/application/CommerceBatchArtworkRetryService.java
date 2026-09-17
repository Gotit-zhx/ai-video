package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An explicit new attempt after a confirmed refusal or invalid output; unresolved submissions are never replayed. */
@Service
public class CommerceBatchArtworkRetryService {
    public record Retried(CommerceBatchExecution execution, String artifactId) {}

    private final JdbcTemplate db;
    private final CommerceBatchExecutionService executions;
    private final CommerceBatchModelLimitService limits;

    public CommerceBatchArtworkRetryService(JdbcTemplate db, CommerceBatchExecutionService executions,
                                           CommerceBatchModelLimitService limits) {
        this.db = db;
        this.executions = executions;
        this.limits = limits;
    }

    @Transactional
    public Retried retry(String batch, String artifactId, PlatformPrincipal user) {
        if (user == null || user.organizationId() == null
                || !user.permissionCodes().contains("product:edit")
                || !user.permissionCodes().contains("product:publish"))
            throw new BusinessException(HttpStatus.FORBIDDEN, "重试图片需要商品编辑与发布权限");

        // Includes batch membership and access checks for every frozen source product.
        var accessible = executions.get(batch, user);
        if (accessible == null || !Objects.equals(accessible.organizationId(), user.organizationId()))
            throw conflict("批次执行不存在");

        // Same lock order as the submission ledger: execution, artwork job, then call budget.
        var executionsLocked = db.query("SELECT * FROM commerce_batch_executions WHERE id=? AND organization_id=? AND batch_id=? FOR UPDATE",
                EXECUTION_ROW, accessible.id(), user.organizationId(), batch);
        if (executionsLocked.isEmpty()) throw conflict("批次执行不存在");
        var run = executionsLocked.getFirst();
        if (!Objects.equals(run.createdBy(), user.userId()))
            throw new BusinessException(HttpStatus.FORBIDDEN, "请由批次启动者重试图片");

        var jobs = db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE",
                artifactId, run.id(), user.organizationId());
        if (jobs.isEmpty()) throw conflict("图片任务不存在或不属于该批次");
        var job = jobs.getFirst();
        String retryKey = CommerceImageArchive.sha((job.get("request_key") + ":manual-retry").getBytes(StandardCharsets.UTF_8));

        if ("RETRIED".equals(job.get("status"))) {
            var replacement = db.queryForList("SELECT id FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND request_key=? FOR UPDATE",
                    run.id(), user.organizationId(), retryKey);
            if (replacement.isEmpty()) throw conflict("未找到已保存的重试任务，请核查该批次");
            return new Retried(run, (String) replacement.getFirst().get("id"));
        }

        if (!"FAILED".equals(run.status()) || !java.util.Set.of("REJECTED","TRANSPARENCY_REJECTED","INVALID_IMAGE_REJECTED").contains(job.get("status")))
            throw conflict("仅能重试已停止批次中被模型明确拒绝或透明背景不合格的图片");
        if (java.util.Set.of("TRANSPARENCY_REJECTED","INVALID_IMAGE_REJECTED").contains(job.get("status")) && !java.util.Set.of("EXTRACT","VARIATE").contains(run.stage()))
            throw conflict("旧印花已进入套版阶段，请新建批次使用原生透明印花；已有结果与草稿保持归档");
        var unresolved = db.queryForList("SELECT id,status FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status IN ('SUBMITTING','UNKNOWN','RESULT_RECEIVED') FOR UPDATE",
                run.id(), user.organizationId());
        if (!unresolved.isEmpty()) throw conflict("该批次仍有未决模型提交或待归档结果，暂不能重试");

        // A new reservation consumes only the originally approved limit. The worker reuses this key.
        if (!limits.reserve(run.id(), user.organizationId(), retryKey, run.stage()))
            throw conflict("原模型调用上限已用完，无法重试；已完成图片仍保留");

        String newId = UUID.randomUUID().toString();
        int inserted = db.update("""
                INSERT INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,request_key,
                 status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
                SELECT ?,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,?,
                       'PREPARED',prompt,model_config_id,model_fingerprint,reference_keys,?,NOW(6),NOW(6)
                FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND status IN ('REJECTED','TRANSPARENCY_REJECTED','INVALID_IMAGE_REJECTED')
                """, newId, retryKey, "已创建手动重试，沿用固定图片、提示词和模型配置", artifactId, run.id(), user.organizationId());
        if (inserted != 1) throw conflict("图片任务状态已变化，请刷新后重试");
        if (db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status IN ('REJECTED','TRANSPARENCY_REJECTED','INVALID_IMAGE_REJECTED')",
                artifactId, run.id(), user.organizationId()) != 1)
            throw conflict("图片任务状态已变化，请刷新后重试");

        Instant now = Instant.now();
        String message = "已为未通过图片创建新尝试，保留其他已完成结果；原任务 " + artifactId + "，新任务 " + newId;
        if (db.update("UPDATE commerce_batch_executions SET status='QUEUED',message=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND organization_id=? AND status='FAILED'",
                message, Timestamp.from(now), run.id(), user.organizationId()) != 1)
            throw conflict("批次状态已变化，请刷新后重试");
        db.update("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(?,?,?,'QUEUED',?,?)",
                UUID.randomUUID().toString(), run.id(), run.stage(), message, Timestamp.from(now));
        return new Retried(new CommerceBatchExecution(run.id(), run.organizationId(), run.batchId(), run.createdBy(),
                run.stage(), "QUEUED", message, null, null, run.createdAt(), now), newId);
    }

    private static final RowMapper<CommerceBatchExecution> EXECUTION_ROW = (row, index) -> {
        Timestamp lease = row.getTimestamp("lease_until");
        return new CommerceBatchExecution(row.getString("id"), row.getString("organization_id"),
                row.getString("batch_id"), row.getString("created_by"), row.getString("stage"), row.getString("status"),
                row.getString("message"), row.getString("lease_token"), lease == null ? null : lease.toInstant(),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    };

    private static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }
}
