package com.yingdao.aicommerce.product.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.*;
import java.util.function.LongSupplier;

/** Reconciles stopped detail operations; this component never submits image generation. */
@Service
public class CommerceDetailGenerationRecovery {
    static final int MAX_UNAVAILABLE_CHECKS = 3;
    static final int MAX_SAVED_RESPONSE_REPLAYS = 3;
    static final long MAX_ACTIVE_WAIT_MS = 30 * 60_000L;
    static final String CANDIDATES = """
            SELECT j.id AS job_id,j.execution_id,j.organization_id,j.kind,j.status AS job_status,
                   j.updated_at AS job_updated_at,j.storage_key,j.request_key,e.status AS execution_status,e.stage,
                   e.updated_at AS execution_updated_at,c.report_json
            FROM commerce_batch_artwork_jobs j JOIN commerce_batch_executions e
              ON e.id=j.execution_id AND e.organization_id=j.organization_id
            LEFT JOIN commerce_batch_artwork_checks c ON c.job_id=j.id
            WHERE e.stage='MOCKUP' AND e.status IN ('UNKNOWN','WAITING_INPUT')
              AND (e.lease_until IS NULL OR e.lease_until<=NOW(6))
              AND (j.status IN ('UNKNOWN','SUBMITTING','RESULT_RECEIVED')
                OR (j.status='PREPARED' AND e.status='UNKNOWN' AND NULLIF(TRIM(j.request_key),'') IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs unfinished
                    WHERE unfinished.execution_id=j.execution_id AND unfinished.organization_id=j.organization_id
                      AND unfinished.status NOT IN ('READY','RETRIED','FAILED','REJECTED','INVALID_IMAGE_REJECTED','TRANSPARENCY_REJECTED','PREPARED','RESULT_RECEIVED')))
                OR (j.status='READY' AND e.status='UNKNOWN' AND NULLIF(TRIM(j.storage_key),'') IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs pending
                    WHERE pending.execution_id=j.execution_id AND pending.organization_id=j.organization_id
                      AND pending.status NOT IN ('READY','RETRIED','FAILED','REJECTED','INVALID_IMAGE_REJECTED','TRANSPARENCY_REJECTED'))))
              AND j.kind REGEXP '^MODEL_DETAIL_(GRID_)?[1-9][0-9]*$'
              AND COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(c.report_json,'$.generationRecovery.nextAttemptAt')) AS UNSIGNED),0)<=UNIX_TIMESTAMP(NOW(3))*1000
            ORDER BY e.updated_at,j.created_at LIMIT 5
            """;
    static final String EXECUTION_LOCK = """
            SELECT id,status,stage,updated_at FROM commerce_batch_executions
            WHERE id=? AND organization_id=? AND stage='MOCKUP' AND status IN ('UNKNOWN','WAITING_INPUT')
              AND (lease_until IS NULL OR lease_until<=NOW(6)) FOR UPDATE
            """;
    static final String JOB_LOCK = "SELECT id,kind,status,result_json,storage_key,request_key,updated_at FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE";
    static final String CHECK_LOCK = "SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=? FOR UPDATE";
    static final String READY_BLOCKERS_LOCK = """
            SELECT id,status FROM commerce_batch_artwork_jobs
            WHERE execution_id=? AND organization_id=?
              AND status NOT IN ('READY','RETRIED','FAILED','REJECTED','INVALID_IMAGE_REJECTED','TRANSPARENCY_REJECTED') FOR UPDATE
            """;
    static final String PREPARED_BLOCKERS_LOCK = """
            SELECT id,status FROM commerce_batch_artwork_jobs
            WHERE execution_id=? AND organization_id=?
              AND status NOT IN ('READY','RETRIED','FAILED','REJECTED','INVALID_IMAGE_REJECTED','TRANSPARENCY_REJECTED','PREPARED','RESULT_RECEIVED') FOR UPDATE
            """;
    private static final Set<String> JOB_STATES = Set.of("UNKNOWN", "SUBMITTING", "RESULT_RECEIVED", "READY", "PREPARED");
    private static final Set<String> EXECUTION_STATES = Set.of("UNKNOWN", "WAITING_INPUT");
    private static final Set<String> NETWORK_KINDS = Set.of("NETWORK", "NETWORK_TIMEOUT");
    private final JdbcTemplate db;
    private final CommerceDesignImageGateway gateway;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final LongSupplier now;

    @Autowired
    public CommerceDetailGenerationRecovery(JdbcTemplate db, CommerceDesignImageGateway gateway,
            TransactionTemplate transactions, ObjectMapper json) {
        this(db, gateway, transactions, json, System::currentTimeMillis);
    }
    CommerceDetailGenerationRecovery(JdbcTemplate db, CommerceDesignImageGateway gateway,
            TransactionTemplate transactions, ObjectMapper json, LongSupplier now) {
        this.db=db; this.gateway=gateway; this.transactions=transactions; this.json=json; this.now=now;
    }

    @Scheduled(initialDelay=30_000, fixedDelay=15_000)
    public void sweep() {
        for (var candidate : db.queryForList(CANDIDATES)) {
            try { recover(candidate); }
            catch (RuntimeException unavailable) {
                // Never log upstream messages: they may contain a signed result URL or a credential.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Detail operation reconciliation could not persist; state retained ({})", unavailable.getClass().getSimpleName());
            }
        }
    }

    void recover(Map<String,Object> candidate) {
        if (!candidate(candidate)) return;
        ObjectNode observed = report(candidate.get("report_json"));
        if (observed != null && observed.path("generationRecovery").path("nextAttemptAt").asLong(0)>now.getAsLong()) return;
        if(Set.of("RESULT_RECEIVED","READY","PREPARED").contains(candidate.get("job_status"))) {
            transactions.executeWithoutResult(ignored -> reconcile(candidate,observed,null,null,""));
            return;
        }
        CommerceDesignImageGateway.Operation operation = null;
        String unavailableCode = "OPERATION_NOT_FOUND";
        try { operation = gateway.operation((String)candidate.get("job_id")); }
        catch (RuntimeException unavailable) { unavailableCode = "OPERATION_LOOKUP_UNAVAILABLE"; }
        var response = operation != null && "COMPLETED".equals(operation.status()) && !operation.active()
                && usable(operation.response(),grid(candidate)) ? operation.response() : null;
        var snapshot = operation;
        var reason = unavailableCode;
        // HTTP finishes before acquiring any execution/job lock or beginning the transaction.
        transactions.executeWithoutResult(ignored -> reconcile(candidate, observed, snapshot, response, reason));
    }

    private void reconcile(Map<String,Object> candidate, ObjectNode observed,
            CommerceDesignImageGateway.Operation operation, CommerceDesignImageGateway.Response response, String unavailableCode) {
        String execution=(String)candidate.get("execution_id"), org=(String)candidate.get("organization_id"), id=(String)candidate.get("job_id");
        var executions=db.queryForList(EXECUTION_LOCK,execution,org);
        if(executions.size()!=1) return;
        var run=executions.getFirst();
        if(!Objects.equals(run.get("status"),candidate.get("execution_status"))
                ||!Objects.equals(run.get("updated_at"),candidate.get("execution_updated_at"))) return;
        var jobs=db.queryForList(JOB_LOCK,id,execution,org);
        if(jobs.size()!=1) return;
        var job=jobs.getFirst();
        if(!JOB_STATES.contains(Objects.toString(job.get("status"),"")) || !Objects.equals(job.get("kind"),candidate.get("kind"))
                ||!Objects.equals(job.get("status"),candidate.get("job_status"))
                ||!Objects.equals(job.get("updated_at"),candidate.get("job_updated_at"))) return;
        // Recheck after the lease lock: an old READY image must not mask a newer unfinished request.
        if("READY".equals(job.get("status"))&&(!"UNKNOWN".equals(candidate.get("execution_status"))
                ||Objects.toString(job.get("storage_key"),"").isBlank()
                ||!Objects.equals(candidate.get("storage_key"),job.get("storage_key"))
                ||!db.queryForList(READY_BLOCKERS_LOCK,execution,org).isEmpty()))return;
        // PREPARED means submission intent has not been claimed. Resume the same persisted request only.
        if("PREPARED".equals(job.get("status"))&&(!"UNKNOWN".equals(candidate.get("execution_status"))
                ||Objects.toString(job.get("request_key"),"").isBlank()
                ||!Objects.equals(candidate.get("request_key"),job.get("request_key"))
                ||!db.queryForList(PREPARED_BLOCKERS_LOCK,execution,org).isEmpty()))return;
        var saved=db.queryForList(CHECK_LOCK,id);
        ObjectNode current=report(saved.isEmpty()?null:saved.getFirst().get("report_json"));
        if(!Objects.equals(current,observed)) return; // A concurrent observer already recorded this check.
        if(current==null) {
            if(Set.of("READY","PREPARED").contains(job.get("status"))) {
                execution(candidate,"FAILED","详情恢复记录无法核对，已停止；保留原任务和已有图片供人工恢复");
                return;
            }
            terminal(candidate,job,null,"RECOVERY_METADATA_INVALID","详情生成状态记录无法核对，已停止；原结果保留，可人工恢复",false,null);
            return;
        }
        var metadata=current.withObject("generationRecovery");
        long timestamp=now.getAsLong();
        metadata.put("checkedAt",timestamp);
        metadata.put("operationId",id);
        if(operation!=null) metadata.put("operationUpdatedAt",operation.updatedAt());
        if("PREPARED".equals(job.get("status"))) {
            metadata.put("status","PREPARED_CONTINUED");metadata.put("reasonCode","ORIGINAL_REQUEST_AVAILABLE");
            metadata.put("detailRetryEligible",false);metadata.put("terminalOperation",false);metadata.remove("nextAttemptAt");
            save(id,current);execution(candidate,"QUEUED","详情请求已准备，正在继续原任务；原请求编号和调用预算保持不变");
            return;
        }
        if("READY".equals(job.get("status"))) {
            readyRecovery(candidate,current);
            return;
        }
        if("RESULT_RECEIVED".equals(job.get("status"))) {
            archiveRecovery(candidate,job,current);
            return;
        }

        // A locally persisted response takes priority over an older or unavailable remote snapshot.
        String prior=Objects.toString(job.get("result_json"),"");
        if(!prior.isBlank()) {
            try {
                if(usable(json.readValue(prior,CommerceDesignImageGateway.Response.class),grid(candidate))) {
                    terminal(candidate,job,current,"LOCAL_RESULT_AVAILABLE","详情生图结果已核对，继续归档已保存图片",false,prior);
                    return;
                }
            } catch(RuntimeException invalidMetadata) { /* Preserve it below; never replace blindly. */ }
        }
        if(response!=null) {
            String merged=mergeResponse(prior,response);
            if(merged==null) {
                terminal(candidate,job,current,"RESULT_METADATA_CONFLICT","详情结果已返回，但与已保存记录冲突；已停止并保留原记录，可人工核对",false,null);
            } else terminal(candidate,job,current,"RESULT_RECOVERED","详情生图结果已找回，继续归档；不会重新生成",false,merged);
            return;
        }
        if(operation!=null && operation.active()) {
            long observedAt=metadata.path("activeObservedAt").asLong(0);
            if(observedAt<=0)observedAt=operation.updatedAt()>0?Math.min(timestamp,operation.updatedAt()):timestamp;
            metadata.put("activeObservedAt",observedAt);
            if(timestamp-observedAt>=MAX_ACTIVE_WAIT_MS) {
                terminal(candidate,job,current,"ACTIVE_OPERATION_TIMEOUT","详情生成原请求仍在运行，已超过30分钟等待上限；已停止自动等待并保留请求编号，待人工核对，不重复提交",false,null);
                return;
            }
            metadata.put("status","ACTIVE"); metadata.put("nextAttemptAt",timestamp+30_000);
            metadata.put("detailRetryEligible",false); metadata.put("terminalOperation",false);
            save(id,current);
            return;
        }
        if(operation!=null && "RECOVERABLE".equals(operation.status()) && operation.error()!=null
                && Boolean.TRUE.equals(operation.error().get("savedResponseAvailable"))) {
            savedResponseRecovery(candidate,job,current);
            return;
        }
        if(operation!=null && "FAILED".equals(operation.status())) {
            var error=operation.error()==null?Map.<String,Object>of():operation.error();
            boolean eligible=retryable(error);
            String code=safeReason(error.get("failureCode"),eligible?"TRANSIENT_OPERATION_FAILURE":"PERMANENT_OPERATION_FAILURE");
            String message=eligible?"详情生成遇到暂时网络故障，已核对操作结束；继续在原调用上限内恢复未完成详情"
                    :permanentMessage(error);
            terminal(candidate,job,current,code,message,eligible,null);
            return;
        }
        int checks=metadata.path("unavailableChecks").asInt(0)+1;
        metadata.put("unavailableChecks",checks);
        String code=operation==null?unavailableCode:"COMPLETED".equals(operation.status())?"OPERATION_RESULT_INVALID"
                :"RUNNING".equals(operation.status())?"OPERATION_NOT_ACTIVE":"OPERATION_NOT_FOUND";
        if(checks>=MAX_UNAVAILABLE_CHECKS) {
            terminal(candidate,job,current,code,"详情生成状态连续3次无法确认，已停止自动等待；保留原请求和结果，可人工核对后恢复",false,null);
        } else {
            metadata.put("status","LOOKUP_RETRY_PENDING"); metadata.put("reasonCode",code);
            metadata.put("nextAttemptAt",timestamp+(checks==1?15_000:45_000));
            metadata.put("detailRetryEligible",false); metadata.put("terminalOperation",false);
            save(id,current);
        }
    }

    private void readyRecovery(Map<String,Object> candidate, ObjectNode report) {
        var recovery=report.withObject("generationRecovery");
        recovery.put("status","READY_CONTINUED");recovery.put("reasonCode","ARCHIVED_DETAIL_AVAILABLE");
        recovery.put("detailRetryEligible",false);recovery.put("terminalOperation",true);recovery.remove("nextAttemptAt");
        save((String)candidate.get("job_id"),report);
        execution(candidate,"QUEUED","详情图片已保存，正在继续切分或核对已有结果");
    }

    /** Replays the exact existing operation so Pi can finish its persisted response, without another provider POST. */
    private void savedResponseRecovery(Map<String,Object> candidate, Map<String,Object> job, ObjectNode report) {
        String id=(String)candidate.get("job_id");var recovery=report.withObject("generationRecovery");
        int attempts=recovery.path("savedResponseReplays").asInt(0);
        recovery.put("detailRetryEligible",false);recovery.put("terminalOperation",true);
        if(attempts>=MAX_SAVED_RESPONSE_REPLAYS) {
            terminal(candidate,job,report,"SAVED_RESPONSE_REPLAY_EXHAUSTED",
                    "详情生图原响应已保存，结果恢复连续3次失败；已停止自动恢复，保留原请求和结果供人工核对",false,null);
            return;
        }
        long delay=new long[]{15_000,30_000,60_000}[Math.max(0,attempts)];
        long failedAt=job.get("updated_at") instanceof java.util.Date time?time.getTime():now.getAsLong();
        long due="SAVED_RESPONSE_RETRY_PENDING".equals(recovery.path("status").asText())
                ?recovery.path("nextAttemptAt").asLong(failedAt+delay):failedAt+delay;
        if(now.getAsLong()<due) {
            recovery.put("status","SAVED_RESPONSE_RETRY_PENDING");recovery.put("nextAttemptAt",due);save(id,report);return;
        }
        recovery.put("savedResponseReplays",attempts+1);recovery.put("status","SAVED_RESPONSE_QUEUED");
        recovery.put("reasonCode","SAVED_RESPONSE_AVAILABLE");recovery.remove("nextAttemptAt");save(id,report);
        String message="详情生图原响应已保存，自动恢复结果（"+(attempts+1)+"/3）：复用原请求，不重新生成";
        if(db.update("UPDATE commerce_batch_artwork_jobs SET status='PREPARED',message=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status=?",
                message,id,candidate.get("execution_id"),candidate.get("organization_id"),job.get("status"))!=1)
            throw new IllegalStateException("Detail job changed during saved response recovery");
        execution(candidate,"QUEUED",message);
    }

    private void archiveRecovery(Map<String,Object> candidate, Map<String,Object> job, ObjectNode report) {
        String id=(String)candidate.get("job_id");var recovery=report.withObject("generationRecovery");
        int attempts=recovery.path("archiveAttempts").asInt(0);
        recovery.put("detailRetryEligible",false);recovery.put("terminalOperation",true);
        if(Objects.toString(job.get("result_json"),"").isBlank()||attempts>=4) {
            recovery.put("status","ARCHIVE_STOPPED");recovery.put("reasonCode",attempts>=4?"ARCHIVE_RETRIES_EXHAUSTED":"SAVED_RESULT_MISSING");
            recovery.remove("nextAttemptAt");save(id,report);
            execution(candidate,"FAILED",attempts>=4?"原结果已保存，归档失败；该详情已自动恢复4次，停止自动等待，可人工修复归档后恢复":"详情归档缺少已保存的结果记录，已停止；保留任务供人工核对");
            return;
        }
        long delay=new long[]{15_000,30_000,60_000,120_000}[Math.max(0,attempts)];
        long failedAt=job.get("updated_at") instanceof java.util.Date time?time.getTime():now.getAsLong();
        long due=recovery.path("nextAttemptAt").asLong(0);
        if(due<=0)due=failedAt+delay;
        if(!"UNKNOWN".equals(candidate.get("execution_status"))&&now.getAsLong()<due) {
            recovery.put("status","ARCHIVE_RETRY_PENDING");recovery.put("nextAttemptAt",due);save(id,report);return;
        }
        recovery.put("archiveAttempts",attempts+1);recovery.put("status","ARCHIVE_QUEUED");recovery.remove("nextAttemptAt");
        save(id,report);
        execution(candidate,"QUEUED","详情图片归档自动恢复（"+(attempts+1)+"/4）：复用原结果，不重新生成");
    }

    private void terminal(Map<String,Object> candidate, Map<String,Object> job, ObjectNode report,
            String reason, String message, boolean retryEligible, String response) {
        String id=(String)candidate.get("job_id"), execution=(String)candidate.get("execution_id"), org=(String)candidate.get("organization_id");
        if(report!=null) {
            var recovery=report.withObject("generationRecovery");
            recovery.put("status",response!=null?"RESULT_RECOVERED":retryEligible?"RETRY_ELIGIBLE":"STOPPED");
            recovery.put("detailRetryEligible",retryEligible); recovery.put("terminalOperation",retryEligible || response!=null || !Set.of("OPERATION_NOT_FOUND","OPERATION_LOOKUP_UNAVAILABLE","OPERATION_NOT_ACTIVE","OPERATION_RESULT_INVALID","ACTIVE_OPERATION_TIMEOUT").contains(reason));
            recovery.put("reasonCode",reason); recovery.put("checkedAt",now.getAsLong()); recovery.remove("nextAttemptAt");
            save(id,report);
        }
        int changed=response==null
                ?db.update("UPDATE commerce_batch_artwork_jobs SET status='FAILED',message=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status=?",message,id,execution,org,job.get("status"))
                :db.update("UPDATE commerce_batch_artwork_jobs SET status='RESULT_RECEIVED',result_json=?,message=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status=?",response,message,id,execution,org,job.get("status"));
        if(changed!=1)throw new IllegalStateException("Detail job changed during reconciliation");
        String state=response!=null || retryEligible?"QUEUED":"FAILED";
        execution(candidate,state,message);
    }

    private void execution(Map<String,Object> candidate,String state,String message) {
        String execution=(String)candidate.get("execution_id"),org=(String)candidate.get("organization_id");
        if(db.update("UPDATE commerce_batch_executions SET status=?,message=?,lease_token=NULL,lease_until=NULL,updated_at=NOW(6) WHERE id=? AND organization_id=? AND stage='MOCKUP' AND status=? AND (lease_until IS NULL OR lease_until<=NOW(6))",state,message,execution,org,candidate.get("execution_status"))!=1)
            throw new IllegalStateException("Detail execution changed during reconciliation");
        db.update("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(?,?,'MOCKUP',?,?,NOW(6))",UUID.randomUUID().toString(),execution,state,message);
    }

    private void save(String id, ObjectNode report) {
        if(db.update("INSERT INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6)) ON DUPLICATE KEY UPDATE report_json=VALUES(report_json)",id,json.writeValueAsString(report))<1)
            throw new IllegalStateException("Detail recovery observation was not persisted");
    }
    private ObjectNode report(Object value) {
        if(value==null)return json.createObjectNode();
        try {var parsed=json.readTree(value.toString());return parsed.isObject()&&(parsed.path("generationRecovery").isMissingNode()||parsed.path("generationRecovery").isObject())?(ObjectNode)parsed:null;}
        catch(RuntimeException invalid){return null;}
    }
    private String mergeResponse(String prior, CommerceDesignImageGateway.Response response) {
        ObjectNode merged;
        try {merged=prior.isBlank()?json.createObjectNode():(ObjectNode)json.readTree(prior);}
        catch(RuntimeException invalid){return null;}
        JsonNode received=json.valueToTree(response);
        for(String key:List.of("artifact","result")) {
            JsonNode incoming=received.path(key);if(incoming.isNull()||incoming.isMissingNode())continue;
            JsonNode old=merged.path(key);
            if(!old.isMissingNode()&&!old.isNull()&&!old.equals(incoming))return null;
            merged.set(key,incoming);
        }
        return json.writeValueAsString(merged);
    }
    static boolean retryable(Map<String,Object> error) {
        String kind=Objects.toString(error.get("failureKind"),"");
        if(Set.of("POLICY","COPYRIGHT","AUTHENTICATION","AUTHORIZATION").contains(kind))return false;
        Object status=error.get("providerStatus");
        if(status instanceof Number n && Set.of(401,403).contains(n.intValue()))return false;
        boolean transport=NETWORK_KINDS.contains(kind);
        if(status instanceof Number n && Set.of(502,503,504,429).contains(n.intValue())
                && (transport || n.intValue()==429&&"CONCURRENCY".equals(kind)))return true;
        return transport && Boolean.TRUE.equals(error.get("retryAuthorized"))
                && Set.of("NOT_SENT","RESPONSE_TIMEOUT","ACCEPTED_OR_UNKNOWN").contains(Objects.toString(error.get("requestState"),""));
    }
    static boolean usable(CommerceDesignImageGateway.Response response) {
        return usable(response,false);
    }
    static boolean usable(CommerceDesignImageGateway.Response response,boolean grid) {
        if(response==null)return false;
        var artifact=response.artifact();
        if(artifact!=null&&Set.of("image/png","image/jpeg","image/webp").contains(Objects.toString(artifact.mimeType(),""))
                &&artifact.dataBase64()!=null&&!artifact.dataBase64().isBlank()&&artifact.dataBase64().length()<=(grid?44_740_000:12_000_000)) {
            try {byte[] bytes=Base64.getDecoder().decode(artifact.dataBase64());if(grid)CommerceModelDetailGridComposer.normalizeGenerated(bytes);else CommerceMockupComposer.decode(bytes);return true;}
            catch(IllegalArgumentException invalid){return false;}
        }
        if(response.result()!=null)try {
            var uri=URI.create(response.result().url());
            return Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;
        } catch(RuntimeException invalid){return false;}
        return false;
    }
    private static String permanentMessage(Map<String,Object> error) {
        return switch(Objects.toString(error.get("failureKind"),"")) {
            case "POLICY" -> "详情生成被服务商内容审核拒绝，已停止；请调整参考内容后人工恢复";
            case "COPYRIGHT" -> "详情生成触发服务商版权限制，已停止；请调整参考内容后人工恢复";
            default -> "详情生成未返回可恢复结果，已停止自动重试；原请求记录保留，可人工核对后恢复";
        };
    }
    private static String safeReason(Object value,String fallback) {return value instanceof String text&&text.matches("[A-Z][A-Z0-9_]{0,79}")?text:fallback;}
    private static boolean grid(Map<String,Object> candidate){return Objects.toString(candidate.get("kind"),"").startsWith("MODEL_DETAIL_GRID_");}
    private static boolean candidate(Map<String,Object> c) {
        return c!=null&&Objects.toString(c.get("kind"),"").matches("MODEL_DETAIL_(?:GRID_)?[1-9][0-9]*")
                &&JOB_STATES.contains(Objects.toString(c.get("job_status"),""))&&EXECUTION_STATES.contains(Objects.toString(c.get("execution_status"),""))
                &&(!"READY".equals(c.get("job_status"))||("UNKNOWN".equals(c.get("execution_status"))&&!Objects.toString(c.get("storage_key"),"").isBlank()))
                &&(!"PREPARED".equals(c.get("job_status"))||("UNKNOWN".equals(c.get("execution_status"))&&!Objects.toString(c.get("request_key"),"").isBlank()))
                &&"MOCKUP".equals(c.get("stage"))&&List.of("job_id","execution_id","organization_id").stream().allMatch(k->c.get(k) instanceof String s&&!s.isBlank());
    }
}
