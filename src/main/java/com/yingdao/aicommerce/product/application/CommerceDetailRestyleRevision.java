package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit user restyling may refresh an unpublished local draft, never an external publication. */
@Service
public class CommerceDetailRestyleRevision {
    public static final String CONTRACT = "COMMERCE_DETAIL_RESTYLE_V1";
    static final String EXECUTION_LOCK = "SELECT id,created_by,stage,status FROM commerce_batch_executions WHERE id=? AND organization_id=? FOR UPDATE";
    static final String REVISION_LOCK = """
            SELECT j.id,j.capture_id,j.variant_index,c.report_json FROM commerce_batch_artwork_jobs j
            JOIN commerce_batch_artwork_checks c ON c.job_id=j.id
            WHERE j.execution_id=? AND j.organization_id=? AND j.kind REGEXP '^MODEL_DETAIL_GRID_[1-9][0-9]*$'
              AND JSON_UNQUOTE(JSON_EXTRACT(c.report_json,'$.restyleRevision.status'))='ACTIVE' FOR UPDATE
            """;
    static final String PUBLICATIONS_LOCK = "SELECT * FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? FOR UPDATE";
    static final String CAPTURE_LOCK = "SELECT id,status,version,published_at,deleted_at FROM product_captures WHERE organization_id=? AND id=? FOR UPDATE";
    static final String WORKFLOWS_LOCK = "SELECT id FROM product_publish_workflows WHERE organization_id=? AND capture_id=? FOR UPDATE";
    static final String AUDITS_LOCK = "SELECT id FROM product_publish_audits WHERE organization_id=? AND capture_id=? FOR UPDATE";
    static final String IMAGES_LOCK = "SELECT id,role_name,sort_order,processed_url,source_json,status FROM product_capture_images WHERE organization_id=? AND capture_id=? FOR UPDATE";
    static final String TARGETS_LOCK = """
            SELECT j.id,j.kind,j.storage_key,j.content_sha256,c.report_json FROM commerce_batch_artwork_jobs j
            JOIN commerce_batch_artwork_checks c ON c.job_id=j.id
            WHERE j.organization_id=? AND j.execution_id=? AND j.capture_id=? AND j.variant_index=?
              AND j.color_key IS NULL AND j.status='READY' AND j.kind REGEXP '^MODEL_DETAIL_[1-9][0-9]*$' FOR UPDATE
            """;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public CommerceDetailRestyleRevision(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

    /** Acquired before the publication row so artwork and local-draft writers use the same lock order. */
    public void lockExecution(String execution,String org){
        if(db.queryForList(EXECUTION_LOCK,execution,org).size()!=1)throw invalid("执行不存在或归属不一致");
    }

    @Transactional
    public void assertMutable(CommerceBatchExecution run){
        var executions=db.queryForList(EXECUTION_LOCK,run.id(),run.organizationId());
        if(executions.size()!=1||!"MOCKUP".equals(executions.getFirst().get("stage"))
                ||!"RUNNING".equals(executions.getFirst().get("status"))
                ||!Objects.equals(run.createdBy(),executions.getFirst().get("created_by")))throw invalid("批次不在可重设计状态");
        var revision=revision(run.organizationId(),run.id());
        if(!run.createdBy().equals(revision.intent.path("requestedBy").asText()))throw invalid("重设计用户不一致");
        validatePublications(run.organizationId(),run.id(),revision);
    }

    /** Called inside prepare's transaction, after locking the execution and its existing publication. */
    @Transactional
    public String refresh(String org,String execution,String item,int variant,Map<String,Object> publication,List<Map<String,Object>> manifest){
        var executions=db.queryForList(EXECUTION_LOCK,execution,org);
        if(executions.size()!=1||!"PUBLISH".equals(executions.getFirst().get("stage"))
                ||!"RUNNING".equals(executions.getFirst().get("status")))throw invalid("批次不在本地商品打包阶段");
        var revision=revision(org,execution);
        if(!Objects.equals(executions.getFirst().get("created_by"),revision.intent.path("requestedBy").asText()))throw invalid("重设计用户不一致");
        var publications=validatePublications(org,execution,revision);
        if(publications.size()!=1||!item.equals(publication.get("item_id"))||variant!=number(publication.get("variant_index")))
            throw invalid("当前重设计不允许跨商品刷新");
        var target=revision.intent.path("publication");
        if(!item.equals(target.path("itemId").asText())||variant!=target.path("variant").asInt())throw invalid("发布目标不匹配");
        String capture=target.path("captureId").asText();
        if(!capture.equals(publication.get("capture_id")))throw invalid("本地商品关联发生变化");
        JsonNode old=json.readTree((String)publication.get("manifest_json")),next=json.valueToTree(manifest);
        var indices=indices(revision.intent);var oldKeys=detailKeys(old);var newKeys=detailKeys(next);
        if(oldKeys.size()!=newKeys.size()||old.size()!=next.size()||new HashSet<>(newKeys).size()!=newKeys.size())throw invalid("详情数量、重复图片或颜色数量不符合原清单");
        var expected=old.deepCopy();
        for(int index:indices){
            if(index>oldKeys.size())throw invalid("详情序号超出原草稿范围");
            archiveKey(org,newKeys.get(index-1));
            ((tools.jackson.databind.node.ArrayNode)expected.get(0).get("detailKeys")).set(index-1,json.getNodeFactory().stringNode(newKeys.get(index-1)));
        }
        if(!expected.equals(next))throw invalid("仅允许替换本次指定详情，主图和其他配置必须保持不变");
        var images=db.queryForList(IMAGES_LOCK,org,capture);
        if(images.size()!=target.path("expectedImageCount").asInt())throw invalid("本地商品图片数量发生变化");
        var outputs=db.queryForList(TARGETS_LOCK,org,execution,revision.intent.path("sourceCaptureId").asText(),variant);
        for(int index:indices){
            String oldKey=oldKeys.get(index-1),newKey=newKeys.get(index-1);
            if(oldKey.equals(newKey))throw invalid("本次指定详情尚未替换为新结果");
            var saved=target.path("images").path(Integer.toString(index));
            if(!oldKey.equals(saved.path("storageKey").asText()))throw invalid("原详情素材发生变化");
            var selected=images.stream().filter(image->saved.path("imageId").asText().equals(image.get("id"))).toList();
            if(selected.size()!=1)throw invalid("原详情记录缺失或重复");
            var image=selected.getFirst();
            if(!"DETAIL".equals(image.get("role_name"))||number(image.get("sort_order"))!=index||!"PROCESSED".equals(image.get("status"))
                    ||!(CommercePublicationDrafts.PREFIX+oldKey).equals(image.get("processed_url"))
                    ||!oldKey.equals(json.readTree(Objects.toString(image.get("source_json"),"{}")).path("commerceStorageKey").asText()))
                throw invalid("原详情图与固定草稿不一致");
            var matches=outputs.stream().filter(output->CommerceModelDetailPolicy.kind(index).equals(output.get("kind"))).toList();
            if(matches.size()!=1)throw invalid("新详情图不是唯一就绪结果");
            var output=matches.getFirst();var review=json.readTree(Objects.toString(output.get("report_json"),"{}"));
            if(!newKey.equals(output.get("storage_key"))||!archiveKey(org,newKey).equals(output.get("content_sha256"))
                    ||!review.path("passed").isBoolean()||!review.path("passed").asBoolean()
                    ||CommerceModelDetailReview.CHECKS.stream().anyMatch(flag->!review.path(flag).isBoolean()||!review.path(flag).asBoolean())
                    ||!CommerceModelDetailReview.CONTRACT_VERSION.equals(review.path("contractVersion").asText())
                    ||!revision.intent.path("sourceCaptureId").asText().equals(review.path("captureId").asText())
                    ||variant!=review.path("variant").asInt()||index!=review.path("detailIndex").asInt()
                    ||!archiveKey(org,newKey).equals(review.path("outputSha256").asText()))throw invalid("新详情缺少绑定图片的完整通过结论");
            var source=(ObjectNode)json.readTree((String)image.get("source_json"));source.put("commerceStorageKey",newKey);
            source.put("restyleRevisionId",revision.intent.path("revisionId").asText());
            String url=CommercePublicationDrafts.PREFIX+newKey;
            requireOne(db.update("UPDATE product_capture_images SET original_url=?,processed_url=?,source_json=?,updated_at=NOW(6) WHERE organization_id=? AND capture_id=? AND id=? AND role_name='DETAIL' AND sort_order=? AND processed_url=?",
                    url+"?original",url,json.writeValueAsString(source),org,capture,image.get("id"),index,CommercePublicationDrafts.PREFIX+oldKey));
        }
        String newManifest=json.writeValueAsString(manifest);
        requireOne(db.update("UPDATE commerce_batch_publications SET manifest_json=? WHERE organization_id=? AND execution_id=? AND item_id=? AND variant_index=? AND capture_id=? AND workflow_id IS NULL AND manifest_json=?",
                newManifest,org,execution,item,variant,capture,publication.get("manifest_json")));
        requireOne(db.update("UPDATE product_captures SET request_fingerprint=?,version=version+1,updated_at=NOW(6) WHERE organization_id=? AND id=? AND version=? AND published_at IS NULL AND deleted_at IS NULL",
                sha(newManifest),org,capture,target.path("expectedCaptureVersion").asLong()));
        revision.intent.put("status","DONE");revision.intent.put("completedAt",System.currentTimeMillis());
        revision.intent.put("refreshedManifestSha256",sha(newManifest));revision.intent.put("refreshedCaptureVersion",target.path("expectedCaptureVersion").asLong()+1);
        requireOne(db.update("UPDATE commerce_batch_artwork_checks SET report_json=? WHERE job_id=? AND BINARY CAST(report_json AS CHAR)=BINARY ?",json.writeValueAsString(revision.report),revision.jobId,revision.original));
        return capture;
    }

    private List<Map<String,Object>> validatePublications(String org,String execution,Revision revision){
        var rows=db.queryForList(PUBLICATIONS_LOCK,org,execution);
        // Deliberately narrow: this path updates one existing local product only.
        if(rows.size()!=1)throw invalid("重设计必须关联唯一未发布本地商品");
        var row=rows.getFirst();var expected=revision.intent.path("publication");
        String capture=expected.path("captureId").asText();
        if(capture.isBlank()||!capture.equals(row.get("capture_id"))||row.get("workflow_id")!=null
                ||!expected.path("itemId").asText().equals(row.get("item_id"))||expected.path("variant").asInt()!=number(row.get("variant_index"))
                ||!sha(Objects.toString(row.get("manifest_json"),"")).equals(expected.path("manifestSha256").asText()))throw invalid("发布关联或原素材清单已变化");
        var captures=db.queryForList(CAPTURE_LOCK,org,capture);
        if(captures.size()!=1||captures.getFirst().get("published_at")!=null||captures.getFirst().get("deleted_at")!=null
                ||number(captures.getFirst().get("version"))!=expected.path("expectedCaptureVersion").asLong()
                ||!Set.of("DRAFT","IMAGES_READY","APPROVED").contains(Objects.toString(captures.getFirst().get("status"),"")))throw invalid("本地商品版本或状态已变化");
        if(!db.queryForList(WORKFLOWS_LOCK,org,capture).isEmpty()||!db.queryForList(AUDITS_LOCK,org,capture).isEmpty())throw invalid("商品已有关联发布任务或平台请求，不允许刷新");
        return rows;
    }

    private Revision revision(String org,String execution){
        var rows=db.queryForList(REVISION_LOCK,execution,org);
        if(rows.size()!=1)throw invalid("缺少唯一明确用户重设计记录");
        var row=rows.getFirst();String original=(String)row.get("report_json");
        var report=(ObjectNode)json.readTree(original);var intent=(ObjectNode)report.path("restyleRevision");
        if(!CONTRACT.equals(intent.path("contractVersion").asText())||!"USER_REQUESTED_RESTYLE".equals(intent.path("intent").asText())
                ||!"ACTIVE".equals(intent.path("status").asText())||!org.equals(intent.path("organizationId").asText())
                ||!execution.equals(intent.path("executionId").asText())||!Objects.equals(row.get("capture_id"),intent.path("sourceCaptureId").asText())
                ||number(row.get("variant_index"))!=intent.path("variant").asInt()||intent.path("requestedBy").asText().isBlank()
                ||!intent.path("revisionId").asText().matches("[a-f0-9-]{36}"))throw invalid("重设计记录身份不完整");
        indices(intent);
        return new Revision((String)row.get("id"),original,report,intent);
    }
    private static List<Integer> indices(JsonNode intent){
        var result=new ArrayList<Integer>();for(var index:intent.path("detailIndices")){
            if(!index.isIntegralNumber()||index.asInt()<1||result.contains(index.asInt()))throw invalid("重设计序号无效");result.add(index.asInt());
        }
        if(result.isEmpty()||result.size()>4)throw invalid("一次重设计需指定1至4张详情");return List.copyOf(result);
    }
    private static List<String> detailKeys(JsonNode manifest){
        if(!manifest.isArray()||manifest.isEmpty()||!manifest.get(0).path("detailKeys").isArray())throw invalid("原商品详情清单无效");
        var result=new ArrayList<String>();for(var key:manifest.get(0).path("detailKeys")){if(!key.isTextual()||key.asText().isBlank())throw invalid("详情素材无效");result.add(key.asText());}return result;
    }
    private static String archiveKey(String org,String key){
        String prefix=sha(org)+"/";if(!key.startsWith(prefix)||!key.substring(prefix.length()).matches("[a-f0-9]{64}"))throw invalid("图片归档不属于当前组织");return key.substring(prefix.length());
    }
    private static int number(Object value){return value instanceof Number number?number.intValue():-1;}
    static String sha(String value){return CommerceImageArchive.sha(value.getBytes(StandardCharsets.UTF_8));}
    private static void requireOne(int rows){if(rows!=1)throw new IllegalStateException("重设计状态已变化，刷新事务已撤销");}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("详情重设计刷新停止："+reason);}
    private record Revision(String jobId,String original,ObjectNode report,ObjectNode intent){}
}
