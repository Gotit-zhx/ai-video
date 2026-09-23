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
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

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

        if ("READY".equals(job.get("status")) && CommerceDrapeReuseWorkflow.MASTER.equals(job.get("kind")))
            return retryDrapeMaster(run, job, retryKey, user);

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

    /** Replaces only a confirmed, archived pose master after an explicit user retry. */
    private Retried retryDrapeMaster(CommerceBatchExecution run, Map<String,Object> master,
                                    String retryKey, PlatformPrincipal user) {
        if (!"MOCKUP".equals(run.stage()) || !Set.of("WAITING_INPUT", "PAUSED", "FAILED").contains(run.status()))
            throw conflict("仅能在套版已停止时重新生成搭放母图");
        if (run.leaseToken() != null || run.leaseUntil() != null)
            throw conflict("批次仍有执行租约，不能替换在途母图");
        if (!db.queryForList("SELECT item_id FROM commerce_batch_publications WHERE execution_id=? AND organization_id=? FOR UPDATE",
                run.id(), user.organizationId()).isEmpty())
            throw conflict("本批已固定发布素材，不能替换搭放母图；原商品和任务保留");
        var jobs = db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status<>'RETRIED' FOR UPDATE",
                run.id(), user.organizationId());
        if (jobs.stream().anyMatch(job -> Set.of("PREPARED", "PENDING", "QUEUED", "RUNNING", "SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")
                .contains(Objects.toString(job.get("status"), ""))))
            throw conflict("该批次仍有未决模型任务或待归档结果，暂不能替换搭放母图");
        if (jobs.stream().anyMatch(job -> Objects.equals(master.get("capture_id"), job.get("capture_id"))
                && Set.of(CommerceDrapeReuseWorkflow.BLANK, CommerceDrapeReuseWorkflow.OUTPUT).contains(Objects.toString(job.get("kind"), ""))))
            throw conflict("原母图已用于校准或搭放成品，不能直接替换；已有结果保持归档");
        if (Objects.toString(master.get("storage_key"), "").isBlank())
            throw conflict("搭放母图尚未完整归档，不能重复生成");

        // The prompt upgrade changes only image order, never the frozen template/scene/artwork bytes.
        var json = new ObjectMapper();
        List<String> refs;
        try {
            refs = json.readValue(Objects.toString(master.get("reference_keys"), ""),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (RuntimeException invalid) { throw conflict("原母图参考图记录不完整，不能安全替换"); }
        if (refs == null || refs.size() != 3 || refs.stream().anyMatch(key -> key == null || key.isBlank()))
            throw conflict("原母图缺少三张固定参考图，不能安全替换");
        if (CommerceDrapeReuseWorkflow.LEGACY_PROMPT.equals(master.get("prompt")))
            refs = List.of(refs.get(1), refs.get(0), refs.get(2));
        else if (!CommerceDrapeReuseWorkflow.TEMPLATE_FIRST_PROMPT.equals(master.get("prompt"))
                && !CommerceDrapeMasterRetry.supportedPrompt(Objects.toString(master.get("prompt"), "")))
            throw conflict("原母图提示词版本无法识别，不能安全调整参考图顺序");
        var guides = jobs.stream().filter(job -> CommerceDrapeReuseWorkflow.GUIDE.equals(job.get("kind"))
                && "READY".equals(job.get("status"))
                && Objects.equals(master.get("capture_id"), job.get("capture_id"))
                && Objects.equals(master.get("capture_version"), job.get("capture_version"))).toList();
        if (guides.size() != 1 || !refs.getFirst().equals(guides.getFirst().get("storage_key")))
            throw conflict("母图与本批模板定位图绑定不一致，不能安全替换");
        try {
            List<String> guideRefs = json.readValue(Objects.toString(guides.getFirst().get("reference_keys"), ""),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
            if (guideRefs == null || guideRefs.size() != 3 || !refs.get(2).equals(guideRefs.get(2)))
                throw conflict("母图与本批固定印花绑定不一致，不能安全替换");
        } catch (BusinessException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw conflict("模板定位图参考记录不完整，不能安全替换"); }

        if (!limits.reserve(run.id(), user.organizationId(), retryKey, run.stage()))
            throw conflict("原模型调用上限已用完，无法重试；已完成图片仍保留");
        String newId = UUID.randomUUID().toString();
        if (db.update("""
                INSERT INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,request_key,
                 status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
                SELECT ?,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,?,
                       'PREPARED',?,model_config_id,model_fingerprint,?, ?,NOW(6),NOW(6)
                FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND kind='DRAPE_MASTER' AND status='READY'
                """, newId, retryKey, CommerceDrapeReuseWorkflow.PROMPT, json.writeValueAsString(refs),
                "已按模板优先规则重新准备母图；保留原母图、印花和全部精确套版", master.get("id"), run.id(), user.organizationId()) != 1)
            throw conflict("母图状态已变化，请刷新后重试");
        if (db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND kind='DRAPE_MASTER' AND status='READY'",
                master.get("id"), run.id(), user.organizationId()) != 1)
            throw conflict("母图状态已变化，请刷新后重试");
        Instant now = Instant.now();
        String message = "已安排重新生成搭放母图，沿用模板版型与印花位置，保留其他已完成结果；原任务 " + master.get("id") + "，新任务 " + newId;
        if (db.update("UPDATE commerce_batch_executions SET status='QUEUED',message=?,updated_at=? WHERE id=? AND organization_id=? AND stage='MOCKUP' AND status IN ('WAITING_INPUT','PAUSED','FAILED') AND lease_token IS NULL AND lease_until IS NULL",
                message, Timestamp.from(now), run.id(), user.organizationId()) != 1)
            throw conflict("批次状态已变化，未启动母图重试");
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
