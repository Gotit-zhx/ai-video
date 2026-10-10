package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable bounded attempts after known failures; never replays an unresolved submission. */
@Service
public class CommerceTransparentArtworkRetry {
    public static final int MAX_ATTEMPTS = 3;
    public record Result(String status, String jobId, int attempt, String message) {
        public boolean queued() { return "QUEUED".equals(status) || "REUSED".equals(status); }
    }

    static final String LEASE_LOCK = "SELECT id,stage,status FROM commerce_batch_executions WHERE id=? AND organization_id=? AND created_by=? AND status IN ('RUNNING','PAUSING') AND lease_token=? AND lease_until>NOW(6) FOR UPDATE";
    static final String JOB_LOCK = "SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE";
    static final String CHAIN_LOCK = "SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND capture_id=? AND capture_version=? AND kind=? AND variant_index=? AND color_key <=> ? FOR UPDATE";
    static final String PENDING_LOCK = "SELECT id,status FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status IN ('SUBMITTING','UNKNOWN','RESULT_RECEIVED') FOR UPDATE";

    private final JdbcTemplate db;
    private final CommerceBatchModelLimitService limits;
    private final ObjectMapper json;

    public CommerceTransparentArtworkRetry(JdbcTemplate db, CommerceBatchModelLimitService limits, ObjectMapper json) {
        this.db = db; this.limits = limits; this.json = json;
    }

    /** Called by the lease-owning worker. Returning QUEUED does not submit to the provider. */
    @Transactional
    public Result retry(CommerceBatchExecution run, Map<String, Object> candidate) {
        if (run == null || candidate == null || !(candidate.get("id") instanceof String id) || id.isBlank())
            return stop("NOT_RETRYABLE", 0, "缺少原印花任务，未创建新的模型调用");
        var leases = db.queryForList(LEASE_LOCK, run.id(), run.organizationId(), run.createdBy(), run.leaseToken());
        if (leases.isEmpty()) return stop("LEASE_LOST", 0, "执行租约已变化，未创建新的印花尝试");
        if ("PAUSING".equals(leases.getFirst().get("status")))
            return stop("PAUSE_REQUESTED", 0, "批次正在暂停，未创建新的印花尝试");
        if (!Objects.equals(run.stage(), leases.getFirst().get("stage")) || !Set.of("EXTRACT", "VARIATE").contains(run.stage()))
            return stop("NOT_RETRYABLE", 0, "批次已离开印花生成阶段，已有套版及发布结果保持不变");

        // Candidate maps can be stale or omit rejection details. Always reread the authoritative row.
        var jobs = db.queryForList(JOB_LOCK, id, run.id(), run.organizationId());
        if (jobs.isEmpty()) return stop("NOT_RETRYABLE", 0, "原印花任务不存在或不属于当前批次");
        var job = jobs.getFirst();
        String kind = Objects.toString(job.get("kind"), ""), state = Objects.toString(job.get("status"), "");
        String stage = "EXTRACT".equals(kind) ? "EXTRACT" : kind.matches("VARIATE(?:_G[1-9][0-9]*)?") ? "VARIATE" : "";
        if (!run.stage().equals(stage)) return stop("NOT_RETRYABLE", 0, "当前任务不是本阶段可重试的印花提取或裂变");
        if (Set.of("SUBMITTING", "UNKNOWN", "RESULT_RECEIVED").contains(state))
            return stop("UNRESOLVED", 0, "模型请求仍未决或结果尚未归档，保留原请求，不新增调用");
        if (!Set.of("REJECTED", "TRANSPARENCY_REJECTED", "INVALID_IMAGE_REJECTED", "RETRIED").contains(state))
            return stop("NOT_RETRYABLE", 0, "当前印花任务未明确失败，不新增调用");
        if ("REJECTED".equals(state)) {
            String reason = rejectionBlocker(job);
            if (reason != null) return stop("NOT_RETRYABLE", 0, reason);
        }
        if ("TRANSPARENCY_REJECTED".equals(state) && (!nonblank(job.get("storage_key"))
                || !Objects.toString(job.get("content_sha256"), "").matches("[0-9a-fA-F]{64}")))
            return stop("UNRESOLVED", 0, "透明背景未通过的原图尚未完整归档，先保留并归档结果，不新增调用");
        if ("INVALID_IMAGE_REJECTED".equals(state) && !nonblank(job.get("result_json")))
            return stop("UNRESOLVED", 0, "无效图片的模型原始响应尚未保存，不新增调用；下载或存储异常须先恢复原请求");
        if (!(job.get("variant_index") instanceof Number variant) || !(job.get("capture_version") instanceof Number)
                || !nonblank(job.get("capture_id")))
            return stop("CHAIN_INVALID", 0, "印花任务缺少固定来源信息，不能重新计算尝试次数");

        String root = rootKey(run.id(), (String) job.get("capture_id"), kind, variant.intValue(), (String) job.get("color_key"));
        var chain = db.queryForList(CHAIN_LOCK, run.id(), run.organizationId(), job.get("capture_id"),
                job.get("capture_version"), kind, job.get("variant_index"), job.get("color_key"));
        var attempts = new TreeMap<Integer, Map<String, Object>>();
        for (var row : chain) {
            int attempt = attemptOf(root, Objects.toString(row.get("request_key"), ""));
            if (attempt == 0 || attempts.put(attempt, row) != null || !sameInputs(job, row))
                return stop("CHAIN_INVALID", 0, "印花重试链包含历史手动尝试或不同固定输入，已停止自动重试以避免重置次数");
        }
        int current = attemptOf(root, Objects.toString(job.get("request_key"), ""));
        if (current == 0 || !attempts.containsKey(current) || !id.equals(attempts.get(current).get("id")))
            return stop("CHAIN_INVALID", 0, "未找到与原请求一致的完整重试链，未新增调用");
        for (int attempt = 1; attempt <= attempts.size(); attempt++) {
            var row = attempts.get(attempt);
            if (row == null || (attempt < attempts.size() && !"RETRIED".equals(row.get("status"))))
                return stop("CHAIN_INVALID", current, "印花重试链缺少前序或存在并行活动尝试，未新增调用");
        }
        if ("RETRIED".equals(state)) {
            var replacement = attempts.get(current + 1);
            if (replacement == null) return stop("CHAIN_INVALID", current, "原尝试已替换但缺少后继记录，未新增调用");
            return new Result("REUSED", (String) replacement.get("id"), current + 1,
                    "已复用持久化的第 " + (current + 1) + " / " + MAX_ATTEMPTS + " 次印花尝试，未重复计次");
        }
        if (attempts.size() != current)
            return stop("CHAIN_INVALID", current, "原尝试状态与后继记录不一致，未新增调用");
        if (current >= MAX_ATTEMPTS)
            return stop("EXHAUSTED", current, "同一张印花已尝试 " + MAX_ATTEMPTS + " 次仍未通过，已停止；原始结果全部保留，未继续套版");
        if (!db.queryForList(PENDING_LOCK, run.id(), run.organizationId()).isEmpty())
            return stop("UNRESOLVED", current, "批次仍有未决模型请求或待归档结果，暂不新增印花调用");

        int next = current + 1;
        String nextKey = attemptKey(root, next);
        if (!limits.reserve(run.id(), run.organizationId(), nextKey, stage))
            return stop("LIMIT_REACHED", current, "原模型调用次数上限已用完，未增加预算；已完成印花和失败原图均保留");
        String nextId = UUID.randomUUID().toString();
        String message = "已排队同模型第 " + next + " / " + MAX_ATTEMPTS + " 次透明印花尝试；保留原图并沿用固定来源、提示词和模型配置";
        int inserted = db.update("""
                INSERT INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,request_key,
                 status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
                SELECT ?,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,?,
                       'PREPARED',prompt,model_config_id,model_fingerprint,reference_keys,?,NOW(6),NOW(6)
                FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND status=?
                """, nextId, nextKey, message, id, run.id(), run.organizationId(), state);
        if (inserted != 1) throw new IllegalStateException("印花状态已变化，新的尝试及调用计数未提交");
        int retired = db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status=?",
                id, run.id(), run.organizationId(), state);
        if (retired != 1) throw new IllegalStateException("原印花状态已变化，新的尝试及调用计数未提交");
        return new Result("QUEUED", nextId, next, message);
    }

    private String rejectionBlocker(Map<String, Object> job) {
        try {
            var detail = json.readTree(Objects.toString(job.get("result_json"), ""));
            if (detail == null || !"REJECTED".equals(detail.path("requestState").asText()))
                return "服务商拒绝记录不完整，不能确认本次请求未生成结果，未自动重试";
            String kind = detail.path("failureKind").asText().toUpperCase(Locale.ROOT);
            String code = detail.path("failureCode").asText().toUpperCase(Locale.ROOT);
            String message = Objects.toString(job.get("message"), "").toLowerCase(Locale.ROOT);
            int http = detail.path("providerStatus").asInt(0);
            if (kind.isBlank() || forbiddenKind(kind) || forbiddenKind(code)
                    || Set.of(400, 401, 402, 403, 404, 405, 410, 422).contains(http)
                    || List.of("不支持", "未配置", "认证", "鉴权", "密钥", "版权", "第三方内容", "内容审核", "余额不足",
                               "unsupported", "unauthorized", "invalid api key", "invalid_api_key", "authentication", "copyright", "content policy")
                            .stream().anyMatch(message::contains))
                return "本次拒绝属于内容限制、认证、模型能力或请求配置问题，重复同一请求无法解决，已停止自动重试";
            boolean temporary = Set.of(429, 502, 503, 504).contains(http)
                    || Set.of("CONCURRENCY", "RATE_LIMIT", "RATE_LIMITED", "OVERLOADED", "TEMPORARY_UNAVAILABLE", "SERVICE_UNAVAILABLE").contains(kind)
                    || Set.of("RATE_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS", "SERVER_OVERLOADED", "SERVICE_UNAVAILABLE").contains(code);
            if (!temporary) return "服务商未明确确认属于临时故障，已停止自动重试并保留原请求，请核对具体错误";
            return null;
        } catch (RuntimeException unreadable) {
            return "服务商拒绝记录无法读取，未自动重试或重复模型调用";
        }
    }
    private static boolean forbiddenKind(String value) {
        return List.of("POLICY", "COPYRIGHT", "AUTH", "PERMISSION", "FORBIDDEN", "UNSUPPORTED", "NOT_SUPPORTED", "MODEL_NOT_FOUND", "CONTENT_FILTER", "INVALID_REQUEST", "INVALID_ARGUMENT", "INSUFFICIENT_QUOTA")
                .stream().anyMatch(value::contains);
    }
    private static boolean sameInputs(Map<String, Object> first, Map<String, Object> second) {
        return List.of("capture_id", "capture_version", "kind", "variant_index", "color_key", "color_label",
                        "prompt", "model_config_id", "model_fingerprint", "reference_keys").stream()
                .allMatch(key -> Objects.equals(first.get(key), second.get(key)));
    }
    private static boolean nonblank(Object value) { return value instanceof String text && !text.isBlank(); }
    static String rootKey(String run, String capture, String kind, int variant, String color) {
        return CommerceImageArchive.sha((run + ":" + capture + ":" + kind + ":" + variant
                + (color == null ? "" : ":color:" + color)).getBytes(StandardCharsets.UTF_8));
    }
    static String attemptKey(String root, int attempt) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) throw new IllegalArgumentException("印花尝试次数无效");
        return attempt == 1 ? root : CommerceImageArchive.sha((root + ":transparent-retry:" + attempt).getBytes(StandardCharsets.UTF_8));
    }
    private static int attemptOf(String root, String key) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) if (attemptKey(root, attempt).equals(key)) return attempt;
        return 0;
    }
    private static Result stop(String status, int attempt, String message) { return new Result(status, null, attempt, message); }
}
