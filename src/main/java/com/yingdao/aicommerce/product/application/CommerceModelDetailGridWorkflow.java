package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** Durable group generation followed by atomic local splitting; existing successful photos are immutable. */
@Service
public class CommerceModelDetailGridWorkflow {
    @FunctionalInterface public interface Submission {
        Result submit(String kind,String prompt,List<String> references,Map<String,Object> existing);
    }
    @org.springframework.beans.factory.annotation.Autowired private CommerceModelDetailStyleGuide styleGuide;
    @org.springframework.beans.factory.annotation.Autowired private CommerceDetailRestyleRevision revisions;
    private final JdbcTemplate db;private final CommerceImageArchive archive;private final ObjectMapper json;
    private final TransactionTemplate tx;private final CommerceModelDetailWorkflow review;
    private java.util.function.LongSupplier now=System::currentTimeMillis;
    public CommerceModelDetailGridWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,
            TransactionTemplate tx,CommerceModelDetailWorkflow review){this.db=db;this.archive=archive;this.json=json;this.tx=tx;this.review=review;}

    public Result execute(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,int variant,
            List<Map<String,Object>> jobs,Function<String,String> styleLoader,Submission submit){
        if(source.detailImageIds().isEmpty())return null;
        var groups=jobs.stream().filter(j->same(j,source,variant)&&CommerceModelDetailGridPolicy.isKind((String)j.get("kind"))&&!"RETRIED".equals(j.get("status"))).toList();
        for(var group:groups){
            String product=CommerceModelDetailGridPolicy.requiresTemplateProduct((String)group.get("prompt"))
                    ?CommerceModelDetailWorkflow.templateProductKey(source,variant,jobs):CommerceModelDetailWorkflow.productKey(source,variant,jobs);
            var indices=validateGroup(group,source,product);var refs=references(group);
            String state=(String)group.get("status");
            if("READY".equals(state)){
                if("DONE".equals(json.valueToTree(check(group)).path("gridSplit").path("status").asText())){
                    if(CommerceModelDetailGridPolicy.usesSourceStyle((String)group.get("prompt"))){
                        var result=reviewGroup(run,source,variant,group,indices,refs,jobs);if(result!=null)return result;
                    }
                    continue;
                }
                return split(run,source,variant,group,indices,refs);
            }
            if("FAILED".equals(state)&&retryEligible(group))return retryGroup(run,group);
            if("FAILED".equals(state))return new Result(false,"该详情组未返回可恢复结果，已结束自动处理；已保存图片和请求记录保留","FAILED");
            return submit.submit((String)group.get("kind"),(String)group.get("prompt"),refs,group);
        }
        // Retain historical individual requests and quality corrections with their original request body.
        var missing=new ArrayList<Integer>();
        for(int index=1;index<=source.detailImageIds().size();index++){
            var child=child(jobs,source,variant,index);
            if(child==null||("FAILED".equals(child.get("status"))&&retryEligible(child))){missing.add(index);continue;}
            if("FAILED".equals(child.get("status")))return new Result(false,"第"+index+"张详情无法自动恢复，已结束等待；其余成功图片保留","FAILED");
            var refs=references(child);
            String product=CommerceModelDetailGridPolicy.TEMPLATE_VERSION.equals(origin(child).path("algorithm").asText())
                    ?CommerceModelDetailWorkflow.templateProductKey(source,variant,jobs):CommerceModelDetailWorkflow.productKey(source,variant,jobs);
            if(refs.size()!=2||!product.equals(refs.getFirst()))throw new IllegalArgumentException("详情图固定商品参考不一致");
            if(!"READY".equals(child.get("status")))return submit.submit((String)child.get("kind"),(String)child.get("prompt"),refs,child);
            if(CommerceModelDetailGridPolicy.isSourceStyleVersion(origin(child).path("algorithm").asText())){
                var report=check(child);
                if(!Boolean.TRUE.equals(report.get("passed"))||!json.valueToTree(report).path("sourceStyleMatch").path("passed").asBoolean())
                    return new Result(false,"详情原图设计检查尚未完成，已归档结果保留","FAILED");
            }else {var result=review.review(run,source,variant,index,child,product,refs.get(1));if(result!=null)return result;}
        }
        if(missing.isEmpty())return null;
        String product=styleGuide==null?CommerceModelDetailWorkflow.productKey(source,variant,jobs)
                :CommerceModelDetailWorkflow.templateProductKey(source,variant,jobs);
        var indices=List.copyOf(missing.subList(0,Math.min(4,missing.size())));
        var refs=new ArrayList<String>();refs.add(product);
        for(int index:indices){
            var prior=child(jobs,source,variant,index);
            // A terminal failed attempt already owns immutable style bytes. Do not re-download them.
            refs.add(prior==null?styleLoader.apply(source.detailImageIds().get(index-1)):references(prior).get(1));
        }
        if(styleGuide==null)return submit.submit(CommerceModelDetailGridPolicy.kind(indices.getFirst()),CommerceModelDetailGridPolicy.prompt(indices),List.copyOf(refs),null);
        String primaryColor=source.colorTargets().isEmpty()?null:source.colorTargets().getFirst().key();
        var main=jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))
                &&j.get("capture_version") instanceof Number version&&version.longValue()==source.version()
                &&j.get("variant_index") instanceof Number v&&v.intValue()==variant&&"MOCKUP_FRONT".equals(j.get("kind"))
                &&Objects.equals(primaryColor,j.get("color_key"))&&product.equals(j.get("storage_key"))).findFirst().orElseThrow();
        var checkpoint=check(main);Object observed=checkpoint.get("sourceStylePreparation");var pending=json.valueToTree(observed);
        String binding=sha(json.writeValueAsString(List.of(source.captureId(),source.version(),variant,indices,refs)));
        if(pending.has("inputHash")&&!binding.equals(pending.path("inputHash").asText()))
            return new Result(false,"原详情解析检查点与固定参考图不一致，图片保留","FAILED");
        var waiting=styleWait(run,pending,"原详情设计解析");if(waiting!=null)return waiting;
        var guides=new ArrayList<CommerceModelDetailStyleGuide.Guide>();
        if(pending.has("guides")) {
            if(!pending.path("guides").isArray()||pending.path("guides").size()>indices.size())throw new IllegalStateException("原详情解析检查点无效");
            for(var saved:pending.path("guides")) {
                var guide=CommerceModelDetailStyleGuide.validateFrozen(json.treeToValue(saved,CommerceModelDetailStyleGuide.Guide.class));
                if(!refs.get(guides.size()+1).endsWith("/"+guide.sourceSha256())||!sha(run.organizationId()).equals(guide.organizationSha256()))
                    throw new IllegalStateException("原详情解析缓存与当前公司或参考图不一致");
                try {styleGuide.requireCurrentModel(run.organizationId(),guide);}
                catch(CommerceModelDetailStyleGuide.UnavailableException changed){
                    var stopped=styleFailure(pending,changed);stopped.put("inputHash",binding);stopped.put("guides",pending.path("guides"));
                    saveStyleCheckpoint(run,main,"sourceStylePreparation",observed,stopped);return styleFailureResult(stopped,"原详情设计解析");
                }
                guides.add(guide);
            }
        }
        if(guides.size()<indices.size()) {
            var reserved=styleReservation(run,pending);reserved.put("inputHash",binding);reserved.put("guides",List.copyOf(guides));
            saveStyleCheckpoint(run,main,"sourceStylePreparation",observed,reserved);observed=reserved;
            try {
                guides.add(styleGuide.prepare(run.organizationId(),bytes(run.organizationId(),refs.get(guides.size()+1))));
                var next=new LinkedHashMap<String,Object>();next.put("inputHash",binding);next.put("guides",List.copyOf(guides));next.put("status","PREPARING");
                // Commit each successful guide so the next worker never repeats completed analysis.
                saveStyleCheckpoint(run,main,"sourceStylePreparation",observed,next);
                if(guides.size()<indices.size())return Result.progress("已解析原详情摄影设计 "+guides.size()+"/"+indices.size()+"，继续下一张");
                observed=next;
            } catch(CommerceModelDetailStyleGuide.UnavailableException unavailable){
                var next=styleFailure(json.valueToTree(reserved),unavailable);next.put("inputHash",binding);next.put("guides",List.copyOf(guides));
                saveStyleCheckpoint(run,main,"sourceStylePreparation",observed,next);
                return styleFailureResult(next,"原详情设计解析");
            }
        }
        saveStyleCheckpoint(run,main,"sourceStylePreparation",observed,null);
        return submit.submit(CommerceModelDetailGridPolicy.kind(indices.getFirst()),CommerceModelDetailGridPolicy.stylePrompt(indices,
                new CommerceModelDetailGridPolicy.StylePlan(List.copyOf(guides),1,"")),List.copyOf(refs),null);
    }

    private Result split(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,int variant,
            Map<String,Object> parent,List<Integer> indices,List<String> refs){
        var checkpoint=json.valueToTree(check(parent)).path("gridSplit");
        if(checkpoint.path("nextAttemptAt").asLong()>System.currentTimeMillis())return Result.progress("四宫格结果已保存，等待自动重试切分");
        final byte[] bytes;
        try {bytes=Base64.getDecoder().decode(archive.read(run.organizationId(),(String)parent.get("storage_key")).dataBase64());}
        catch(RuntimeException unavailable){return splitFailure(run,parent);}
        final List<byte[]> cropped;
        try {cropped=CommerceModelDetailGridComposer.split(bytes,indices.size());}
        catch(IllegalArgumentException invalid){
            markFailure(run,parent,"GRID_LAYOUT_INVALID","四宫格尺寸或图片格式不正确，自动重新生成该组");
            return Result.progress("四宫格结果不符合切分要求，保留原图并自动重试该组");
        }catch(RuntimeException unavailable){return splitFailure(run,parent);}
        final List<CommerceImageArchive.Archived> cells;
        try {cells=cropped.stream().map(b->archive.store(run.organizationId(),b)).toList();}
        catch(RuntimeException unavailable){return splitFailure(run,parent);}
        try {tx.executeWithoutResult(status->{
            lock(run);var fresh=job(run,(String)parent.get("id"));
            if(!"READY".equals(fresh.get("status"))||!Objects.equals(parent.get("storage_key"),fresh.get("storage_key")))throw new IllegalStateException("四宫格状态已变化");
            var report=check(fresh);
            if("DONE".equals(json.valueToTree(report).path("gridSplit").path("status").asText()))return;
            var all=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? FOR UPDATE",run.id(),run.organizationId());
            for(int slot=0;slot<indices.size();slot++){
                int index=indices.get(slot);var old=child(all,source,variant,index);
                if(old!=null){
                    if(!"FAILED".equals(old.get("status"))||!retryEligible(old))throw new IllegalStateException("该详情已有活动结果，不能由四宫格覆盖");
                    if(db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND status='FAILED'",old.get("id"))!=1)throw new IllegalStateException("原详情状态已变化");
                }
                var image=cells.get(slot);String request=sha(parent.get("request_key")+":cell:"+index);
                var origin=Map.of("algorithm",CommerceModelDetailGridPolicy.usesSourceStyle((String)parent.get("prompt"))?CommerceModelDetailGridPolicy.sourceStyleVersion((String)parent.get("prompt")):CommerceModelDetailGridPolicy.VERSION,"parentJobId",parent.get("id"),"parentSha256",parent.get("content_sha256"),"slot",slot,"detailIndex",index,"sourceDetailImageId",source.detailImageIds().get(index-1));
                int inserted=db.update("""
                    INSERT INTO commerce_batch_artwork_jobs
                    (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,
                     model_config_id,model_fingerprint,reference_keys,result_json,storage_key,content_sha256,message,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,'READY',?,?,?,?,?,?,?, ?,NOW(6),NOW(6))
                    """,UUID.randomUUID().toString(),run.organizationId(),run.id(),source.captureId(),source.version(),CommerceModelDetailPolicy.kind(index),variant,request,
                    CommerceModelDetailPolicy.prompt(index),parent.get("model_config_id"),parent.get("model_fingerprint"),json.writeValueAsString(List.of(refs.getFirst(),refs.get(slot+1))),
                    json.writeValueAsString(origin),image.storageKey(),image.sha256(),"四宫格第"+(slot+1)+"格已切分归档，待商品一致性检查");
                if(inserted!=1)throw new IllegalStateException("详情切分记录未保存");
            }
            report.put("gridSplit",Map.of("status","DONE","detailIndices",indices,"childCount",indices.size()));saveCheck(parent,report);
        });}catch(RuntimeException unavailable){return splitFailure(run,parent);}
        return Result.progress("四宫格已切分并保存 "+indices.size()+" 张独立详情，继续逐张核对新衣服");
    }

    private Result splitFailure(CommerceBatchExecution run,Map<String,Object> parent){
        int attempt=json.valueToTree(check(parent)).path("gridSplit").path("attempts").asInt(0)+1;
        tx.executeWithoutResult(status->{lock(run);var report=check(parent);report.put("gridSplit",Map.of("status","RETRY_PENDING","attempts",attempt,"nextAttemptAt",System.currentTimeMillis()+Math.min(120000,15000L*attempt)));saveCheck(parent,report);});
        return attempt<4?Result.progress("四宫格已生成，自动重试本地切分/归档（"+attempt+"/4），不重复生图"):
                new Result(false,"四宫格原图已保存，但本地切分/归档连续失败；结果可恢复，请检查存储服务","FAILED");
    }

    private Result retryGroup(CommerceBatchExecution run,Map<String,Object> parent){
        return tx.execute(status->{
            lock(run);var fresh=job(run,(String)parent.get("id"));
            if(!"FAILED".equals(fresh.get("status"))||!retryEligible(fresh))return Result.progress("四宫格状态已变化，重新读取进度");
            var chain=db.queryForList("SELECT id,status FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND capture_id=? AND capture_version=? AND kind=? AND variant_index=? FOR UPDATE",
                    run.id(),run.organizationId(),parent.get("capture_id"),parent.get("capture_version"),parent.get("kind"),parent.get("variant_index"));
            int attempts=CommerceModelDetailGridPolicy.usesSourceStyle((String)parent.get("prompt"))
                    ?db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND capture_id=? AND capture_version=? AND kind=? AND variant_index=? AND prompt=?",Integer.class,run.id(),run.organizationId(),parent.get("capture_id"),parent.get("capture_version"),parent.get("kind"),parent.get("variant_index"),parent.get("prompt")):chain.size();
            if(attempts>=CommerceModelDetailGridPolicy.MAX_ATTEMPTS)return new Result(false,"该详情组已自动尝试3次仍未取得可用结果；已完成图片和错误记录均保留，可恢复处理","FAILED");
            long failedAt=json.valueToTree(check(fresh)).path("generationRecovery").path("checkedAt").asLong(0);
            long delay=Math.min(120000L,15000L*(1L<<Math.min(3,Math.max(0,chain.size()-1))));
            if(failedAt>0&&System.currentTimeMillis()<failedAt+delay)return Result.progress("四宫格服务暂时繁忙，等待退避后自动重试；成功图片保留");
            assertMutable(run);
            String next=sha(parent.get("request_key")+":grid-retry");
            int inserted=db.update("""
                INSERT INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
                SELECT ?,organization_id,execution_id,capture_id,capture_version,kind,variant_index,?,'PREPARED',prompt,model_config_id,model_fingerprint,reference_keys,?,NOW(6),NOW(6)
                FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND status='FAILED'
                """,UUID.randomUUID().toString(),next,"正在自动恢复四宫格，第"+(chain.size()+1)+"/3次",parent.get("id"),run.id(),run.organizationId());
            if(inserted!=1||db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND status='FAILED'",parent.get("id"))!=1)throw new IllegalStateException("四宫格重试状态已变化");
            return Result.progress("已安排四宫格自动恢复，第"+(chain.size()+1)+"/3次，成功图片保留");
        });
    }
    private Result reviewGroup(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,int variant,
            Map<String,Object> parent,List<Integer> indices,List<String> refs,List<Map<String,Object>> jobs){
        var plan=CommerceModelDetailGridPolicy.stylePlan((String)parent.get("prompt"));
        var rejected=new ArrayList<Integer>();var reasons=new ArrayList<String>();
        for(int slot=0;slot<indices.size();slot++){
            int index=indices.get(slot);var child=child(jobs,source,variant,index);
            if(child==null||!"READY".equals(child.get("status")))throw new IllegalStateException("四宫格切分详情尚未完整");
            var origin=origin(child);
            if(!Objects.equals(parent.get("id"),origin.path("parentJobId").asText()))throw new IllegalStateException("四宫格详情归属已变化");
            var identity=review.reviewOnly(run,source,variant,index,child,refs.getFirst(),refs.get(slot+1));
            if("REVIEW_UNAVAILABLE".equals(identity.status()))return new Result(false,"详情商品一致性检查连续不可用，结果已保存，未重复生成","FAILED");
            if("REVIEW_RETRY_PENDING".equals(identity.status()))return Result.progress("四宫格已归档，自动重试详情商品检查，不重新生图");
            if("REJECTED".equals(identity.status())){rejected.add(slot);reasons.add("原详情"+index+"新商品不一致："+identity.report().get("reason"));continue;}
            var report=check(child);var prior=json.valueToTree(report).path("sourceStyleMatch");var guide=plan.guides().get(slot);
            if(prior.has("passed")){
                if(!guide.sourceSha256().equals(prior.path("sourceSha256").asText())
                    ||!Objects.equals(child.get("content_sha256"),prior.path("outputSha256").asText())
                    ||!CommerceModelDetailStyleGuide.sha(guide).equals(prior.path("guideSha256").asText())
                    ||(CommerceModelDetailGridPolicy.requiresTemplateProduct((String)parent.get("prompt"))
                        &&!CommerceModelDetailGridPolicy.RENDER_POLICY.equals(prior.path("renderPolicy").asText())))throw new IllegalStateException("详情原图设计检查绑定不一致");
                if(!prior.path("passed").asBoolean()){rejected.add(slot);reasons.add("原详情"+index+"构图不一致："+prior.path("reason").asText());}
                continue;
            }
            var comparisonBinding=new ArrayList<Object>(List.of(refs.get(slot+1),child.get("storage_key"),CommerceModelDetailStyleGuide.sha(guide)));
            if(CommerceModelDetailGridPolicy.requiresTemplateProduct((String)parent.get("prompt")))comparisonBinding.add(CommerceModelDetailGridPolicy.RENDER_POLICY);
            String binding=sha(json.writeValueAsString(comparisonBinding));
            if(prior.has("inputHash")&&!binding.equals(prior.path("inputHash").asText()))throw new IllegalStateException("详情设计检查点与原图、成品或设计说明不一致");
            var waiting=styleWait(run,prior,"详情原图设计检查");if(waiting!=null)return waiting;
            var reserved=styleReservation(run,prior);reserved.put("inputHash",binding);
            saveStyleCheckpoint(run,child,"sourceStyleMatch",report.get("sourceStyleMatch"),reserved);
            try{
                var match=CommerceModelDetailGridPolicy.requiresTemplateProduct((String)parent.get("prompt"))
                        ?styleGuide.compare(run.organizationId(),bytes(run.organizationId(),refs.get(slot+1)),bytes(run.organizationId(),(String)child.get("storage_key")),guide,CommerceModelDetailGridPolicy.RENDER_POLICY)
                        :styleGuide.compare(run.organizationId(),bytes(run.organizationId(),refs.get(slot+1)),bytes(run.organizationId(),(String)child.get("storage_key")),guide);
                saveStyleCheckpoint(run,child,"sourceStyleMatch",reserved,json.convertValue(match,Map.class));
                return Result.progress("已核对原详情"+index+"的场景、姿态与景别，继续逐格检查");
            }catch(CommerceModelDetailStyleGuide.UnavailableException unavailable){
                var next=styleFailure(json.valueToTree(reserved),unavailable);next.put("inputHash",binding);
                saveStyleCheckpoint(run,child,"sourceStyleMatch",reserved,next);
                return styleFailureResult(next,"详情原图设计检查");
            }
        }
        if(rejected.isEmpty())return null;
        if(plan.attempt()>=CommerceModelDetailGridPolicy.MAX_ATTEMPTS)return new Result(false,"四宫格已自动纠正3轮，仍有详情未同时匹配新图案与原构图；结果和原因已保存","FAILED");
        var nextIds=rejected.stream().map(indices::get).toList();var nextGuides=rejected.stream().map(plan.guides()::get).toList();
        var nextRefs=new ArrayList<String>();nextRefs.add(refs.getFirst());rejected.forEach(slot->nextRefs.add(refs.get(slot+1)));
        String reason=String.join("；",reasons);if(reason.length()>4000)reason=reason.substring(0,4000);
        String nextPrompt=CommerceModelDetailGridPolicy.stylePrompt(nextIds,new CommerceModelDetailGridPolicy.StylePlan(nextGuides,plan.attempt()+1,reason),CommerceModelDetailGridPolicy.sourceStyleVersion((String)parent.get("prompt")));
        return tx.execute(st->{
            lock(run);assertMutable(run);
            var fresh=job(run,(String)parent.get("id"));if(!"READY".equals(fresh.get("status")))throw new IllegalStateException("四宫格质量纠正状态已变化");
            for(int slot:rejected){
                var child=child(jobs,source,variant,indices.get(slot));var current=job(run,(String)child.get("id"));
                if(!"READY".equals(current.get("status"))||!Objects.equals(current.get("storage_key"),child.get("storage_key")))throw new IllegalStateException("待纠正详情已变化");
                var report=check(current);report.put("generationRecovery",Map.of("detailRetryEligible",true,"terminalOperation",true,"reasonCode","SOURCE_STYLE_OR_PRODUCT_MISMATCH","checkedAt",System.currentTimeMillis()));saveCheck(current,report);
                if(db.update("UPDATE commerce_batch_artwork_jobs SET status='FAILED',message=?,updated_at=NOW(6) WHERE id=? AND status='READY'","已安排原图设计四宫格纠正，归档原图保留",current.get("id"))!=1)throw new IllegalStateException("详情纠正状态未保存");
            }
            String id=UUID.randomUUID().toString(),key=sha(parent.get("request_key")+":source-style-correction:"+nextIds);
            int inserted=db.update("""
                INSERT INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
                SELECT ?,organization_id,execution_id,capture_id,capture_version,?,variant_index,?,'PREPARED',?,model_config_id,model_fingerprint,?,?,NOW(6),NOW(6)
                FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND status='READY'
                """,id,CommerceModelDetailGridPolicy.kind(nextIds.getFirst()),key,nextPrompt,json.writeValueAsString(nextRefs),"原详情设计四宫格纠正，第"+(plan.attempt()+1)+"/3轮",parent.get("id"),run.id(),run.organizationId());
            if(inserted!=1||db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND status='READY'",parent.get("id"))!=1)throw new IllegalStateException("四宫格纠正检查点保存失败");
            return Result.progress("已将"+nextIds.size()+"张未通过检查的详情合并四宫格纠正，保留其余合格图");
        });
    }
    private Result styleWait(CommerceBatchExecution run,tools.jackson.databind.JsonNode checkpoint,String label){
        if("RUNNING".equals(checkpoint.path("status").asText())&&sha(Objects.toString(run.leaseToken(),"")).equals(checkpoint.path("ownerLeaseHash").asText()))
            return Result.unknown(label+"已有当前执行中的分析请求，等待原请求完整结束，未重复提交");
        if("FAILED".equals(checkpoint.path("status").asText()))return styleFailureResult(json.convertValue(checkpoint,Map.class),label);
        if(checkpoint.path("nextAttemptAt").asLong()>now.getAsLong())return Result.progress(label+"等待退避后自动重试；已保存结果保留");
        return null;
    }
    private Map<String,Object> styleReservation(CommerceBatchExecution run,tools.jackson.databind.JsonNode prior){
        var next=new LinkedHashMap<String,Object>();next.put("status","RUNNING");
        next.put("attempts",Math.min(Integer.MAX_VALUE,Math.max(0L,prior.path("attempts").asLong())+1));
        next.put("invalidAttempts",Math.max(0,prior.path("invalidAttempts").asInt()));next.put("startedAt",now.getAsLong());
        // A restart cooldown, never a request deadline. A live execution retains its lease through worker heartbeats.
        next.put("nextAttemptAt",now.getAsLong()+230000);next.put("ownerLeaseHash",sha(Objects.toString(run.leaseToken(),"")));
        next.put("reasonCode","STYLE_ANALYSIS_IN_PROGRESS");return next;
    }
    private Map<String,Object> styleFailure(tools.jackson.databind.JsonNode prior,CommerceModelDetailStyleGuide.UnavailableException failure){
        long attempts=Math.max(1L,prior.path("attempts").asLong());
        int invalid=Math.min(3,Math.max(0,prior.path("invalidAttempts").asInt())+(failure.invalidResponse()?1:0));
        boolean retry=failure.retryable()||(failure.invalidResponse()&&invalid<3);
        long delay=attempts==1?15000:attempts==2?45000:attempts==3?120000:300000;
        var next=new LinkedHashMap<String,Object>();next.put("status",retry?"RETRY_PENDING":"FAILED");next.put("attempts",attempts);
        next.put("invalidAttempts",invalid);next.put("reasonCode",failure.reasonCode());next.put("retryable",failure.retryable());
        next.put("invalidResponse",failure.invalidResponse());next.put("nextAttemptAt",retry?now.getAsLong()+delay:0);next.put("checkedAt",now.getAsLong());
        return next;
    }
    private Result styleFailureResult(Map<String,Object> checkpoint,String label){
        var state=json.valueToTree(checkpoint);String code=state.path("reasonCode").asText("STYLE_ANALYSIS_UNAVAILABLE");
        if(!code.matches("[A-Z_0-9]{3,80}"))code="STYLE_ANALYSIS_UNAVAILABLE";
        if("FAILED".equals(state.path("status").asText()))return new Result(false,label+(state.path("invalidResponse").asBoolean()?"连续返回无效结果":"未满足模型配置或输入要求")+"（"+code+"）；图片与成功检查保留，未追加生图","FAILED");
        return Result.progress(label+(state.path("invalidResponse").asBoolean()?"返回结果无效，正在有限重试":"连接暂不可用，退避后将自动继续")+"（"+code+"，已尝试"+state.path("attempts").asLong()+"次）；已保存结果保留");
    }
    private void saveStyleCheckpoint(CommerceBatchExecution run,Map<String,Object> expected,String field,Object observed,Object replacement){
        tx.executeWithoutResult(status->{
            lock(run);var fresh=job(run,(String)expected.get("id"));
            if(!"READY".equals(fresh.get("status"))||!List.of("storage_key","content_sha256","reference_keys","capture_id","capture_version","kind","variant_index").stream().allMatch(key->Objects.equals(expected.get(key),fresh.get(key))))
                throw new IllegalStateException("详情素材状态已变化，未覆盖原检查结果");
            var report=check(fresh);
            if(!json.readTree(json.writeValueAsString(observed)).equals(json.readTree(json.writeValueAsString(report.get(field)))))throw new IllegalStateException("详情检查点已变化，未覆盖较新的检查结果");
            if(replacement==null)report.remove(field);else report.put(field,replacement);
            saveCheck(fresh,report);
        });
    }
    private void assertMutable(CommerceBatchExecution run){
        if(!db.queryForList("SELECT item_id FROM commerce_batch_publications WHERE execution_id=? AND organization_id=? FOR UPDATE",run.id(),run.organizationId()).isEmpty()){
            if(revisions==null)throw new IllegalStateException("发布素材已经固定，不能重做详情");
            revisions.assertMutable(run);
        }
    }
    private tools.jackson.databind.JsonNode origin(Map<String,Object> child){
        Object value=child.get("result_json");
        if(value==null)return json.valueToTree(Map.of("algorithm",Objects.toString(child.get("detail_algorithm"),""),"parentJobId",Objects.toString(child.get("detail_parent_job_id"),"")));
        return json.readTree(Objects.toString(value,"{}"));
    }
    private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
    public void markFailure(CommerceBatchExecution run,Map<String,Object> parent,String code,String message){
        tx.executeWithoutResult(status->{lock(run);var fresh=job(run,(String)parent.get("id"));
            if(!Set.of("READY","RESULT_RECEIVED","INVALID_IMAGE_REJECTED","REJECTED").contains(fresh.get("status")))throw new IllegalStateException("四宫格失败状态已变化");
            var report=check(fresh);report.put("generationRecovery",Map.of("detailRetryEligible",true,"terminalOperation",true,"reasonCode",code,"checkedAt",System.currentTimeMillis()));saveCheck(fresh,report);
            if(db.update("UPDATE commerce_batch_artwork_jobs SET status='FAILED',message=?,updated_at=NOW(6) WHERE id=?",message,parent.get("id"))!=1)throw new IllegalStateException("四宫格失败状态未保存");});
    }
    private List<Integer> validateGroup(Map<String,Object> group,CommerceBatchCreativePlanValidator.Selection source,String product){
        var indices=CommerceModelDetailGridPolicy.indices((String)group.get("prompt"));var refs=references(group);
        if(!CommerceModelDetailGridPolicy.kind(indices.getFirst()).equals(group.get("kind"))||indices.getLast()>source.detailImageIds().size()
                ||refs.size()!=indices.size()+1||!product.equals(refs.getFirst()))throw new IllegalArgumentException("四宫格与固定商品或原详情清单不一致");
        return indices;
    }
    private void lock(CommerceBatchExecution run){
        if(db.queryForList("SELECT id FROM commerce_batch_executions WHERE id=? AND organization_id=? AND created_by=? AND stage='MOCKUP' AND status='RUNNING' AND lease_token=? AND lease_until>NOW(6) FOR UPDATE",
                run.id(),run.organizationId(),run.createdBy(),run.leaseToken()).isEmpty())throw new IllegalStateException("详情处理租约已变化，保留已归档结果");
    }
    private Map<String,Object> job(CommerceBatchExecution run,String id){return db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE",id,run.id(),run.organizationId());}
    private Map<String,Object> check(Map<String,Object> job){
        var rows=db.queryForList("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?",job.get("id"));
        return rows.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(json.readValue((String)rows.getFirst().get("report_json"),Map.class));
    }
    private void saveCheck(Map<String,Object> job,Map<String,Object> report){if(db.update("INSERT INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6)) ON DUPLICATE KEY UPDATE report_json=VALUES(report_json)",job.get("id"),json.writeValueAsString(report))<1)throw new IllegalStateException("四宫格恢复检查点未保存");}
    private boolean retryEligible(Map<String,Object> job){var recovery=json.valueToTree(check(job)).path("generationRecovery");return recovery.path("detailRetryEligible").asBoolean(false)&&recovery.path("terminalOperation").asBoolean(false);}
    private List<String> references(Map<String,Object> job){return json.readValue(Objects.toString(job.get("reference_keys"),"[]"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
    private static Map<String,Object> child(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,int variant,int index){
        var matches=jobs.stream().filter(j->same(j,source,variant)&&CommerceModelDetailPolicy.kind(index).equals(j.get("kind"))&&!"RETRIED".equals(j.get("status"))).toList();
        if(matches.size()>1)throw new IllegalArgumentException("同一详情存在重复活动任务");return matches.isEmpty()?null:matches.getFirst();
    }
    private static boolean same(Map<String,Object> job,CommerceBatchCreativePlanValidator.Selection source,int variant){return source.captureId().equals(job.get("capture_id"))&&job.get("capture_version") instanceof Number version&&version.longValue()==source.version()&&job.get("variant_index") instanceof Number v&&v.intValue()==variant&&job.get("color_key")==null;}
    private static String sha(String value){return CommerceImageArchive.sha(value.getBytes(StandardCharsets.UTF_8));}
}
