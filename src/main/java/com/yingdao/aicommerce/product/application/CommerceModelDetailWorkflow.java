package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** One retained model photo per frozen source detail and variant; colors share these detail photos. */
@Service
public class CommerceModelDetailWorkflow {
    public record ReviewOnly(String status,Map<String,Object> report,boolean cached) {
        public ReviewOnly { report=Collections.unmodifiableMap(new LinkedHashMap<>(report)); }
    }
    private static final List<String> FLAGS=List.of("hasModel","preservesGarment","preservesPrint","preservesColor","coherentPose","usableComposition");
    private final JdbcTemplate db;private final CommerceImageArchive archive;private final ObjectMapper json;
    private final CommerceModelDetailReview reviewer;private final CommerceModelDetailRetry retry;
    private java.util.function.LongSupplier now=System::currentTimeMillis;
    public CommerceModelDetailWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,
                                      CommerceModelDetailReview reviewer,CommerceModelDetailRetry retry) {
        this.db=db;this.archive=archive;this.json=json;this.reviewer=reviewer;this.retry=retry;
    }

    /** Returns a bound decision for grouped corrections; this path never submits an individual retry. */
    public ReviewOnly reviewOnly(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,int variant,int index,
                  Map<String,Object> job,String productKey,String styleKey) {
        requireJob(job,source,variant,index);
        if(!references(job,json).equals(List.of(productKey,styleKey))
                ||!CommerceModelDetailPolicy.supportedPrompt(Objects.toString(job.get("prompt"),""),index))
            throw new IllegalArgumentException("模特详情图与当前新衣服或来源详情参考不一致，原图保留");
        String outputKey=(String)job.get("storage_key");
        var saved=reviewState(run,job);var prior=saved.value();
        boolean templatePolicy=CommerceModelDetailGridPolicy.TEMPLATE_VERSION.equals(origin(job,db,json,run.organizationId()).path("algorithm").asText());
        if(templatePolicy && prior.has("passed") && !CommerceModelDetailReview.REVIEW_POLICY_VERSION.equals(prior.path("reviewPolicyVersion").asText())) {
            // Keep the obsolete evidence, but it cannot approve a new template-authoritative render.
            var fresh=json.createObjectNode();fresh.set("previousIdentityReview",prior);
            if(prior.has("sourceStyleMatch"))fresh.set("sourceStyleMatch",prior.get("sourceStyleMatch"));
            prior=fresh;
        }
        if(prior.has("passed")) {
            requireBoundReport(prior,source,variant,index,productKey,styleKey,outputKey);
            boolean passed=prior.path("passed").asBoolean();
            if(passed)requireReport(prior,source,variant,index,productKey,styleKey,outputKey);
            return new ReviewOnly(passed?"PASSED":"REJECTED",json.convertValue(prior,Map.class),true);
        }
        for(String flag:FLAGS)if(prior.has(flag))throw new IllegalArgumentException("详情商品检查记录缺少完整结论，原结果保留");
        String binding=CommerceImageArchive.sha(json.writeValueAsBytes(List.of(run.organizationId(),job.get("id"),source.captureId(),source.version(),variant,index,source.detailImageIds().get(index-1),productKey,styleKey,outputKey)));
        if(prior.has("reviewInputHash")&&!binding.equals(prior.path("reviewInputHash").asText()))throw new IllegalArgumentException("详情商品检查点与固定商品、原图或成品不一致");
        if(prior.path("reviewFailureTerminal").asBoolean())return new ReviewOnly("REVIEW_UNAVAILABLE",json.convertValue(prior,Map.class),true);
        String owner=CommerceImageArchive.sha(Objects.toString(run.leaseToken(),"").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(("REVIEW_RUNNING".equals(prior.path("status").asText())&&owner.equals(prior.path("ownerLeaseHash").asText()))
                ||prior.path("nextAttemptAt").asLong()>now.getAsLong())return new ReviewOnly("REVIEW_RETRY_PENDING",json.convertValue(prior,Map.class),true);
        int count=(int)Math.min(Integer.MAX_VALUE,Math.max(0L,prior.path("readAttempts").asLong())+1);
        var reserved=new LinkedHashMap<String,Object>(json.convertValue(prior,Map.class));
        reserved.put("status","REVIEW_RUNNING");reserved.put("readAttempts",count);reserved.put("reviewInputHash",binding);reserved.put("ownerLeaseHash",owner);
        // Restart cooldown only: the live worker keeps its lease and waits for a complete Pi result.
        reserved.put("nextAttemptAt",now.getAsLong()+230000);reserved.put("startedAt",now.getAsLong());
        var reservation=save(run,job,saved,reserved);
        final Map<String,Object> reviewed;
        try {reviewed=reviewer.review(run.organizationId(),bytes(run.organizationId(),productKey),bytes(run.organizationId(),styleKey),bytes(run.organizationId(),outputKey));}
        catch(CommerceModelDetailReview.ReviewUnavailableException unavailable) {
            int invalid=Math.min(3,Math.max(0,prior.path("invalidReadAttempts").asInt())+(unavailable.invalidResponse()?1:0));
            boolean resume=unavailable.retryable()||(unavailable.invalidResponse()&&invalid<3);
            var pending=new LinkedHashMap<String,Object>(reserved);pending.put("status",resume?"REVIEW_RETRY_PENDING":"REVIEW_UNAVAILABLE");
            pending.put("invalidReadAttempts",invalid);pending.put("reviewFailureTerminal",!resume);pending.put("reasonCode",unavailable.reasonCode());
            pending.put("retryable",unavailable.retryable());pending.put("invalidResponse",unavailable.invalidResponse());
            pending.put("nextAttemptAt",resume?now.getAsLong()+(count==1?15000:count==2?45000:count==3?120000:300000):0);
            save(run,job,reservation,pending);return new ReviewOnly((String)pending.get("status"),pending,false);
        }
        var report=new LinkedHashMap<String,Object>(json.convertValue(prior,Map.class));
        for(String field:List.of("status","readAttempts","reviewInputHash","ownerLeaseHash","nextAttemptAt","startedAt","invalidReadAttempts","reviewFailureTerminal","reasonCode","retryable","invalidResponse"))report.remove(field);
        report.putAll(reviewed);report.put("sourceDetailImageId",source.detailImageIds().get(index-1));report.put("detailIndex",index);
        report.put("variant",variant);report.put("captureId",source.captureId());
        var node=json.valueToTree(report);requireBoundReport(node,source,variant,index,productKey,styleKey,outputKey);
        if(templatePolicy&&!CommerceModelDetailReview.REVIEW_POLICY_VERSION.equals(node.path("reviewPolicyVersion").asText()))throw new IllegalStateException("模板版型检查未返回当前验收规则，结果保留");
        boolean passed=Boolean.TRUE.equals(report.get("passed"));if(passed)requireReport(node,source,variant,index,productKey,styleKey,outputKey);
        save(run,job,reservation,report);
        return new ReviewOnly(passed?"PASSED":"REJECTED",report,false);
    }

    Result review(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,int variant,int index,
                  Map<String,Object> job,String productKey,String styleKey) {
        var outcome=reviewOnly(run,source,variant,index,job,productKey,styleKey);
        if("REVIEW_RETRY_PENDING".equals(outcome.status()))return Result.progress("模特详情图已归档，等待自动继续商品检查；不重复生图");
        if("REVIEW_UNAVAILABLE".equals(outcome.status())){
            String code=Objects.toString(outcome.report().get("reasonCode"),"IDENTITY_UNAVAILABLE");if(!code.matches("[A-Z_0-9]{3,80}"))code="IDENTITY_UNAVAILABLE";
            return new Result(false,"详情商品检查未取得有效结论（"+code+"），请检查模型接入或返回格式；图片保留","FAILED");
        }
        if("REJECTED".equals(outcome.status())){
            var next=retry.retry(run,job,outcome.report());
            return next.queued()?Result.progress(next.message()):new Result(false,next.message()+"；详情图检查："+outcome.report().get("reason"));
        }
        return outcome.cached()?null:Result.progress("模特实穿详情图已通过商品一致性检查 · V"+variant+" · "+index+"/"+source.detailImageIds().size());
    }

    static String productKey(CommerceBatchCreativePlanValidator.Selection source,int variant,List<Map<String,Object>> jobs) {
        String color=source.colorTargets().isEmpty()?null:source.colorTargets().getFirst().key();
        for(String kind:List.of("DRAPED_FRONT","MOCKUP_FRONT","MOCKUP")) {
            var matches=jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&kind.equals(j.get("kind"))
                    &&j.get("capture_version") instanceof Number version&&version.longValue()==source.version()
                    &&j.get("variant_index") instanceof Number v&&v.intValue()==variant&&Objects.equals(color,j.get("color_key"))&&"READY".equals(j.get("status"))).toList();
            if(matches.size()>1)throw new IllegalArgumentException("模特详情图对应的新款主图不唯一");
            if(matches.size()==1){String key=Objects.toString(matches.getFirst().get("storage_key"),"");
                if(key.isBlank())throw new IllegalArgumentException("新衣服主图尚未完成归档");return key;}
        }
        throw new IllegalArgumentException("新衣服主图尚未完成，不能先生成模特详情图");
    }

    /** A deterministic template composite is authoritative; generated draping is never a garment-cut reference. */
    static String templateProductKey(CommerceBatchCreativePlanValidator.Selection source,int variant,List<Map<String,Object>> jobs) {
        String color=source.colorTargets().isEmpty()?null:source.colorTargets().getFirst().key();
        for(String kind:List.of("MOCKUP_FRONT")) {
            var matches=jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&kind.equals(j.get("kind"))
                    &&j.get("capture_version") instanceof Number version&&version.longValue()==source.version()
                    &&j.get("variant_index") instanceof Number v&&v.intValue()==variant&&Objects.equals(color,j.get("color_key"))&&"READY".equals(j.get("status"))).toList();
            if(matches.size()>1)throw new IllegalArgumentException("详情图对应的模板套版主图不唯一");
            if(matches.size()==1) {String key=Objects.toString(matches.getFirst().get("storage_key"),"");
                if(key.isBlank())throw new IllegalArgumentException("模板套版主图尚未归档");return key;}
        }
        throw new IllegalArgumentException("模板套版主图尚未完成，不能用搭放图替代版型参考");
    }

    /** Publication validates every frozen ordinal; missing or unreviewed outputs never disappear silently. */
    public static List<String> requiredKeys(CommerceBatchCreativePlanValidator.Selection source,int variant,
            List<Map<String,Object>> jobs,JdbcTemplate db,ObjectMapper json,CommerceImageArchive archive,String org) {
        if(source.detailImageIds().isEmpty())return List.of();
        var result=new ArrayList<String>();
        for(int i=1;i<=source.detailImageIds().size();i++) {
            final String kind=CommerceModelDetailPolicy.kind(i);
            var matches=jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&kind.equals(j.get("kind"))
                    &&j.get("variant_index") instanceof Number v&&v.intValue()==variant&&j.get("color_key")==null&&!"RETRIED".equals(j.get("status"))).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("模特实穿详情图尚未齐全 · V"+variant+" · "+i+"/"+source.detailImageIds().size());
            var job=matches.getFirst();requireJob(job,source,variant,i);var refs=references(job,json);
            boolean templatePolicy=CommerceModelDetailGridPolicy.TEMPLATE_VERSION.equals(origin(job,db,json,org).path("algorithm").asText());
            String product=templatePolicy?templateProductKey(source,variant,jobs):productKey(source,variant,jobs);
            if(refs.size()!=2||!product.equals(refs.getFirst())||!CommerceModelDetailPolicy.supportedPrompt(Objects.toString(job.get("prompt"),""),i))
                throw new IllegalArgumentException("模特详情图引用的新款商品不一致");
            String key=(String)job.get("storage_key");var report=check(db,json,org,job);
            requireReport(report,source,variant,i,product,refs.get(1),key);
            if(templatePolicy&&!CommerceModelDetailReview.REVIEW_POLICY_VERSION.equals(report.path("reviewPolicyVersion").asText()))
                throw new IllegalArgumentException("详情图尚未通过当前模板版型检查");
            requireSourceStyle(job,report,source,variant,i,product,refs.get(1),key,db,json,org);
            refs.forEach(ref->archive.read(org,ref));archive.read(org,key);result.add(key);
        }
        return List.copyOf(result);
    }

    private static void requireJob(Map<String,Object> job,CommerceBatchCreativePlanValidator.Selection source,int variant,int index) {
        if(job==null||!"READY".equals(job.get("status"))||!source.captureId().equals(job.get("capture_id"))
                ||!CommerceModelDetailPolicy.kind(index).equals(job.get("kind"))||!(job.get("capture_version") instanceof Number version)||version.longValue()!=source.version()
                ||!(job.get("variant_index") instanceof Number v)||v.intValue()!=variant||job.get("color_key")!=null
                ||!(job.get("storage_key") instanceof String key)||key.isBlank())throw new IllegalArgumentException("模特实穿详情图尚未完成归档或来源版本不一致");
    }
    private static void requireSourceStyle(Map<String,Object> job,JsonNode report,CommerceBatchCreativePlanValidator.Selection source,
            int variant,int index,String product,String style,String output,JdbcTemplate db,ObjectMapper json,String org) {
        var origin=origin(job,db,json,org);
        if(!CommerceModelDetailGridPolicy.isSourceStyleVersion(origin.path("algorithm").asText()))return;
        String parentId=origin.path("parentJobId").asText();
        int slot=origin.path("slot").asInt(-1);
        if(parentId.isBlank()||!origin.path("slot").isIntegralNumber()||slot<0||slot>=4)
            throw new IllegalArgumentException("原详情风格检查缺少固定四宫格来源");
        var parents=db.queryForList("SELECT p.prompt,p.kind,p.capture_id,p.capture_version,p.variant_index,p.reference_keys,p.content_sha256 FROM commerce_batch_artwork_jobs p JOIN commerce_batch_artwork_jobs c ON p.execution_id=c.execution_id AND p.organization_id=c.organization_id WHERE p.id=? AND c.id=? AND c.organization_id=?",
                parentId,job.get("id"),org);
        if(parents.size()!=1)throw new IllegalArgumentException("原详情风格检查缺少固定四宫格来源");
        var parent=parents.getFirst();String prompt=Objects.toString(parent.get("prompt"),"");
        if(!CommerceModelDetailGridPolicy.usesSourceStyle(prompt)||!CommerceModelDetailGridPolicy.sourceStyleVersion(prompt).equals(origin.path("algorithm").asText()))throw new IllegalArgumentException("详情图来源不是已固定的原图设计方案");
        var indices=CommerceModelDetailGridPolicy.indices(prompt);var guides=CommerceModelDetailGridPolicy.styles(prompt);
        var references=references(parent,json);
        if(slot>=indices.size()||indices.get(slot)!=index||slot>=guides.size()
                ||!CommerceModelDetailGridPolicy.kind(indices.getFirst()).equals(parent.get("kind"))
                ||!source.captureId().equals(parent.get("capture_id"))
                ||!(parent.get("capture_version") instanceof Number version)||version.longValue()!=source.version()
                ||!(parent.get("variant_index") instanceof Number v)||v.intValue()!=variant
                ||references.size()!=indices.size()+1||!product.equals(references.getFirst())||!style.equals(references.get(slot+1))
                ||!Objects.equals(parent.get("content_sha256"),origin.path("parentSha256").asText()))
            throw new IllegalArgumentException("原详情风格检查与固定来源、序号或新商品不一致");
        var match=report.path("sourceStyleMatch");
        if((CommerceModelDetailGridPolicy.requiresTemplateProduct(prompt)&&!CommerceModelDetailGridPolicy.RENDER_POLICY.equals(match.path("renderPolicy").asText()))
                ||List.of("passed","matchesShotType","matchesPose","matchesSetting").stream().anyMatch(flag->!match.path(flag).isBoolean()||!match.path(flag).asBoolean())
                ||!style.substring(style.lastIndexOf('/')+1).equals(guides.get(slot).sourceSha256())
                ||!style.substring(style.lastIndexOf('/')+1).equals(match.path("sourceSha256").asText())
                ||!output.substring(output.lastIndexOf('/')+1).equals(match.path("outputSha256").asText())
                ||!CommerceModelDetailStyleGuide.sha(guides.get(slot)).equals(match.path("guideSha256").asText()))
            throw new IllegalArgumentException("详情图尚未通过原图景别、姿态和场景一致性检查");
    }
    private static JsonNode origin(Map<String,Object> job,JdbcTemplate db,ObjectMapper json,String org) {
        Object saved=job.get("result_json");
        if(saved==null) {
            var rows=db.queryForList("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=? AND organization_id=?",job.get("id"),org);
            if(!rows.isEmpty())saved=rows.getFirst().get("result_json");
        }
        return saved==null?json.createObjectNode():json.readTree(saved.toString());
    }
    private static void requireReport(JsonNode r,CommerceBatchCreativePlanValidator.Selection source,int variant,int index,String product,String style,String output) {
        requireBoundReport(r,source,variant,index,product,style,output);
        if(!r.path("passed").asBoolean()||FLAGS.stream().anyMatch(f->!r.path(f).asBoolean()))
            throw new IllegalArgumentException("模特实穿详情图尚未通过版型、印花、颜色与真实穿着检查");
    }
    private static void requireBoundReport(JsonNode r,CommerceBatchCreativePlanValidator.Selection source,int variant,int index,String product,String style,String output) {
        if(!r.path("passed").isBoolean()||FLAGS.stream().anyMatch(f->!r.path(f).isBoolean())
                ||!CommerceModelDetailPolicy.PROMPT_VERSION.equals(r.path("contractVersion").asText())||r.path("reason").asText().isBlank()
                ||!source.captureId().equals(r.path("captureId").asText())||r.path("variant").asInt()!=variant||r.path("detailIndex").asInt()!=index
                ||!source.detailImageIds().get(index-1).equals(r.path("sourceDetailImageId").asText())
                ||!product.endsWith("/"+r.path("productSha256").asText())||!style.endsWith("/"+r.path("styleSha256").asText())||!output.endsWith("/"+r.path("outputSha256").asText()))
            throw new IllegalArgumentException("模特实穿详情图尚未通过版型、印花、颜色与真实穿着检查");
    }
    private static JsonNode check(JdbcTemplate db,ObjectMapper json,String org,Map<String,Object> job) {
        var rows=db.queryForList("SELECT c.report_json FROM commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id WHERE j.id=? AND j.organization_id=?",job.get("id"),org);
        return rows.isEmpty()?json.createObjectNode():json.readTree(Objects.toString(rows.getFirst().get("report_json"),"{}"));
    }
    private static List<String> references(Map<String,Object> job,ObjectMapper json){return json.readValue(Objects.toString(job.get("reference_keys"),"[]"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
    private record ReviewState(boolean exists,JsonNode value){}
    private ReviewState reviewState(CommerceBatchExecution run,Map<String,Object> job){
        var rows=db.queryForList("SELECT c.report_json FROM commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id WHERE j.id=? AND j.execution_id=? AND j.organization_id=?",job.get("id"),run.id(),run.organizationId());
        return rows.isEmpty()?new ReviewState(false,json.createObjectNode()):new ReviewState(true,json.readTree(Objects.toString(rows.getFirst().get("report_json"),"{}")));
    }
    private ReviewState save(CommerceBatchExecution run,Map<String,Object> job,ReviewState observed,Map<String,Object> report) {
        String value=json.writeValueAsString(report);
        String guard="j.id=? AND j.execution_id=? AND j.organization_id=? AND j.status='READY' AND j.kind=? AND j.capture_id=? AND j.capture_version=? AND j.variant_index=? AND j.color_key IS NULL"
                +" AND j.storage_key=? AND j.content_sha256=? AND j.prompt=? AND j.reference_keys=CAST(? AS JSON)"
                +" AND e.organization_id=? AND e.created_by=? AND e.stage='MOCKUP' AND e.status='RUNNING' AND e.lease_token=? AND e.lease_until>NOW(6)";
        var args=new ArrayList<Object>();Collections.addAll(args,value,job.get("id"),run.id(),run.organizationId(),job.get("kind"),job.get("capture_id"),job.get("capture_version"),job.get("variant_index"),job.get("storage_key"),job.get("content_sha256"),job.get("prompt"),job.get("reference_keys"),run.organizationId(),run.createdBy(),run.leaseToken());
        String sql;
        if(!observed.exists())sql="INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) SELECT j.id,?,NOW(6) FROM commerce_batch_artwork_jobs j JOIN commerce_batch_executions e ON e.id=j.execution_id WHERE "+guard;
        else {sql="UPDATE commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id JOIN commerce_batch_executions e ON e.id=j.execution_id SET c.report_json=? WHERE "+guard+" AND c.report_json=CAST(? AS JSON)";args.add(json.writeValueAsString(observed.value()));}
        if(db.update(sql,args.toArray())!=1)throw new IllegalStateException("模特详情图检查租约、素材或检查点已变化；图片保留，等待恢复");
        return new ReviewState(true,json.readTree(value));
    }
    private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
}
