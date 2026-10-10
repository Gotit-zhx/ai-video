package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Retires only local derivatives; fixed model outputs, source colors and budgets remain intact. */
@Service
public class CommerceBatchLocalRebuildService {
 public record Rebuilt(CommerceBatchExecution execution,int retiredArtifacts,boolean reused) {}
 private static final Set<String> STOPPED=Set.of("WAITING_INPUT","PAUSED","FAILED");
 private static final Set<String> UNRESOLVED=Set.of("PREPARED","PENDING","QUEUED","RUNNING","SUBMITTING","UNKNOWN","RESULT_RECEIVED");
 private final JdbcTemplate db;
 private final CommerceBatchExecutionService executions;
 private final CommerceBatchCreativePlanMapper plans;
 private final CommerceTemplateMapper templates;
 private final ObjectMapper json;
 @org.springframework.beans.factory.annotation.Autowired
 private CommerceBatchModelLimitService limits;

 public CommerceBatchLocalRebuildService(JdbcTemplate db,CommerceBatchExecutionService executions,
   CommerceBatchCreativePlanMapper plans,CommerceTemplateMapper templates,ObjectMapper json){
  this.db=db;this.executions=executions;this.plans=plans;this.templates=templates;this.json=json;
 }

 @Transactional
 public Rebuilt rebuild(String batch,String requestKey,PlatformPrincipal user){
  if(user==null||user.organizationId()==null||user.organizationId().isBlank()
    ||!user.permissionCodes().contains("product:edit")||!user.permissionCodes().contains("product:publish"))
   throw new BusinessException(HttpStatus.FORBIDDEN,"本地重算需要商品编辑与发布权限");
  String key=requestKey==null||requestKey.isBlank()?"local-rebuild-default-v1":requestKey.trim();
  if(!key.matches("[A-Za-z0-9._:-]{8,128}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"本地重算请求编号须为8至128位字母、数字或 . _ : -");
  var accessible=executions.get(batch,user); // Checks the tenant, batch and every source product.
  if(accessible==null||!user.organizationId().equals(accessible.organizationId()))throw conflict("批次执行不存在");
  var rows=db.query("SELECT * FROM commerce_batch_executions WHERE id=? AND organization_id=? AND batch_id=? FOR UPDATE",
    EXECUTION_ROW,accessible.id(),user.organizationId(),batch);
  if(rows.isEmpty())throw conflict("批次执行不存在");
  var run=rows.getFirst();
  String eventId=eventId(run.organizationId(),run.id(),key);
  if(!db.queryForList("SELECT id FROM commerce_batch_execution_events WHERE id=? AND execution_id=? FOR UPDATE",eventId,run.id()).isEmpty())
   return new Rebuilt(run,0,true);
  if(!STOPPED.contains(run.status()))throw conflict("当前批次正在推进或已结束，不能重复重算；请读取当前执行进度");
  if(run.leaseToken()!=null||run.leaseUntil()!=null)throw conflict("批次仍有执行租约，不能中断在途任务重算");

  // Even a placeholder with no capture/workflow yet means the publication manifest is frozen.
  var publications=db.queryForList("SELECT item_id,variant_index,capture_id,workflow_id FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? FOR UPDATE",user.organizationId(),run.id());
  if(!publications.isEmpty())throw conflict("本批已经固定发布草稿或提交记录，不能替换发布素材；原商品与任务保持不变");
  var jobs=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? FOR UPDATE",run.id(),user.organizationId());
  if(jobs.stream().anyMatch(job->UNRESOLVED.contains(Objects.toString(job.get("status"),""))))
   throw conflict("仍有未决模型任务或待归档结果，暂不能本地重算");

  var fixed=plans.forExecution(user.organizationId(),run.id());
  if(fixed==null)throw conflict("缺少固定创作配置，不能本地重算");
  var plan=json.readValue(fixed.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
  if(!CommerceBatchCreativePlanValidator.localPhotoOutput(plan)||!CommerceBatchCreativePlanValidator.sourceColorOutput(plan))
   throw conflict("仅支持固定 SKU 颜色的本地套版重算，不会重新调用生图模型");
  var template=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());
  if(template==null||!"PRINT_LAYOUT".equals(template.kind()))throw conflict("固定套版模板不存在");
  var config=json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class);
  requireLocalInputs(plan,config,jobs);

  int retired=0;
  for(var job:jobs){
   String kind=Objects.toString(job.get("kind"),"");
   if(!"READY".equals(job.get("status"))||!CommerceLocalPhotoWorkflow.POLICY.equals(job.get("model_config_id"))
     ||!(kind.startsWith("MATTE")||kind.startsWith("MOCKUP")||Set.of("DRAPE_BLANK","DRAPED_FRONT").contains(kind)))continue;
   String oldKey=Objects.toString(job.get("request_key"),"");
   if(oldKey.isBlank()||job.get("id")==null)throw conflict("旧套版任务缺少请求编号，不能安全重算");
   String retiredKey=retiredRequestKey(oldKey,Objects.toString(job.get("id")),eventId);
   if(db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',request_key=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status='READY' AND model_config_id='LOCAL_PHOTO_V1' AND request_key=?",
     retiredKey,job.get("id"),run.id(),user.organizationId(),oldKey)!=1)throw conflict("套版任务状态已变化，请刷新后重试");
   retired++;
  }
  Instant now=Instant.now();
  String message="已安排本地重算透明母版与商品套版，保留 "+retired+" 张旧图及检查记录；沿用原印花生成结果和颜色底图，不增加生图调用";
  if(db.update("UPDATE commerce_batch_executions SET stage='MOCKUP',status='QUEUED',message=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND organization_id=? AND status IN ('WAITING_INPUT','PAUSED','FAILED') AND lease_token IS NULL AND lease_until IS NULL",
    message,Timestamp.from(now),run.id(),user.organizationId())!=1)throw conflict("批次状态已变化，未启动重算");
  if(db.update("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(?,?,'MOCKUP','QUEUED',?,?)",
    eventId,run.id(),message,Timestamp.from(now))!=1)throw conflict("本地重算记录未保存，请重试原请求");
  return new Rebuilt(new CommerceBatchExecution(run.id(),run.organizationId(),run.batchId(),run.createdBy(),
    "MOCKUP","QUEUED",message,null,null,run.createdAt(),now),retired,false);
 }

 /** Explicitly replace a rejected pose master after all platform submissions are known absent.
  * Exact mockups, prints, source colors and model details remain fixed. */
 @Transactional
 public Rebuilt repairNeckline(String batch,String requestKey,PlatformPrincipal user){
  if(user==null||user.organizationId()==null||!user.permissionCodes().contains("product:edit")
    ||!user.permissionCodes().contains("product:publish"))
   throw new BusinessException(HttpStatus.FORBIDDEN,"修复领口需要商品编辑与发布权限");
  String key=Objects.toString(requestKey,"").strip();
  if(!key.matches("[A-Za-z0-9._:-]{8,128}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"修复请求编号无效");
  var accessible=executions.get(batch,user);
  if(accessible==null||!user.organizationId().equals(accessible.organizationId()))throw conflict("批次执行不存在");
  var rows=db.query("SELECT * FROM commerce_batch_executions WHERE id=? AND organization_id=? AND batch_id=? FOR UPDATE",
    EXECUTION_ROW,accessible.id(),user.organizationId(),batch);
  if(rows.isEmpty())throw conflict("批次执行不存在");
  var run=rows.getFirst();
  if(!Objects.equals(run.createdBy(),user.userId()))
   throw new BusinessException(HttpStatus.FORBIDDEN,"请由批次启动者修复领口");
  String eventId=eventId(run.organizationId(),run.id(),"neckline:"+key);
  if(!db.queryForList("SELECT id FROM commerce_batch_execution_events WHERE id=? AND execution_id=? FOR UPDATE",eventId,run.id()).isEmpty())
   return new Rebuilt(run,0,true);
  if(!STOPPED.contains(run.status())||!Set.of("MOCKUP","REVIEW","PUBLISH").contains(run.stage())
    ||run.leaseToken()!=null||run.leaseUntil()!=null)
   throw conflict("批次仍在执行或有执行租约，不能替换母图");
  if(!db.queryForList("SELECT item_id,variant_index,capture_id,workflow_id FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? FOR UPDATE",
    user.organizationId(),run.id()).isEmpty())
   throw conflict("本批已有发布素材或平台任务，不能替换母图");
  var jobs=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? FOR UPDATE",
    run.id(),user.organizationId());
  if(jobs.stream().anyMatch(job->UNRESOLVED.contains(Objects.toString(job.get("status"),""))))
   throw conflict("仍有未决生图请求，不能创建第二个母图任务");
  var fixed=plans.forExecution(user.organizationId(),run.id());
  if(fixed==null)throw conflict("固定创作配置不存在");
  var plan=json.readValue(fixed.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
  if(!CommerceBatchCreativePlanValidator.drapeOutput(plan)||plan.sources().size()!=1)
   throw conflict("该修复入口仅支持单来源自然搭放批次");
  var source=plan.sources().getFirst();
  var masters=jobs.stream().filter(job->source.captureId().equals(job.get("capture_id"))
    &&CommerceDrapeReuseWorkflow.MASTER.equals(job.get("kind"))).toList();
  var current=masters.stream().filter(job->"READY".equals(job.get("status"))).toList();
  if(masters.size()>=CommerceDrapeMasterRetry.MAX_ATTEMPTS||current.size()!=1)
   throw conflict("母图已达重试上限或存在多个在用版本，不新增模型调用");
  var old=current.getFirst();
  if(!(old.get("capture_version") instanceof Number version)||version.longValue()!=source.version()
    ||!CommerceDrapeMasterRetry.supportedPrompt(Objects.toString(old.get("prompt"),""))
    ||Objects.toString(old.get("model_config_id"),"").isBlank()
    ||Objects.toString(old.get("model_fingerprint"),"").isBlank()
    ||Objects.toString(old.get("request_key"),"").isBlank())
   throw conflict("旧母图来源版本或冻结模型配置不匹配");
  List<String> references=json.readValue(Objects.toString(old.get("reference_keys"),""),
    json.getTypeFactory().constructCollectionType(List.class,String.class));
  var guides=jobs.stream().filter(job->"READY".equals(job.get("status"))
    &&CommerceDrapeReuseWorkflow.GUIDE.equals(job.get("kind"))
    &&source.captureId().equals(job.get("capture_id"))
    &&job.get("capture_version") instanceof Number guideVersion
    &&guideVersion.longValue()==source.version()
    &&references!=null&&!references.isEmpty()
    &&references.getFirst().equals(job.get("storage_key"))).toList();
  if(references==null||references.size()!=3||references.stream().anyMatch(ref->ref==null||ref.isBlank())
    ||guides.size()!=1)
   throw conflict("母图与固定模板定位图不一致");
  List<String> guideReferences=json.readValue(Objects.toString(guides.getFirst().get("reference_keys"),""),
    json.getTypeFactory().constructCollectionType(List.class,String.class));
  if(guideReferences==null||guideReferences.size()!=3||!references.get(2).equals(guideReferences.get(2)))
   throw conflict("母图与固定透明印花不一致");
  var derivatives=jobs.stream().filter(job->source.captureId().equals(job.get("capture_id"))
    &&"READY".equals(job.get("status"))&&Set.of(CommerceDrapeReuseWorkflow.BLANK,CommerceDrapeReuseWorkflow.OUTPUT).contains(job.get("kind"))).toList();
  if(derivatives.stream().anyMatch(job->!CommerceLocalPhotoWorkflow.POLICY.equals(job.get("model_config_id"))
    ||Objects.toString(job.get("request_key"),"").isBlank()
    ||!(job.get("capture_version") instanceof Number derivativeVersion)
    ||derivativeVersion.longValue()!=source.version()))
   throw conflict("搭放成品缺少可追溯的本地处理记录");
  if(derivatives.size()!=1+plan.variantsPerPrint()*source.colorTargets().size()
    ||derivatives.stream().filter(job->CommerceDrapeReuseWorkflow.BLANK.equals(job.get("kind"))
      &&job.get("variant_index") instanceof Number index&&index.intValue()==0).count()!=1)
   throw conflict("搭放图数量不完整，不能替换母图");
  for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(var color:source.colorTargets()){
   final int currentVariant=variant;
   if(derivatives.stream().filter(job->CommerceDrapeReuseWorkflow.OUTPUT.equals(job.get("kind"))
     &&job.get("variant_index") instanceof Number index&&index.intValue()==currentVariant
     &&color.key().equals(job.get("color_key"))).count()!=1)
    throw conflict("搭放图的款色绑定不完整，不能替换母图");
  }
  if(limits==null)throw conflict("模型调用上限服务不可用");
  String nextKey=CommerceImageArchive.sha((Objects.toString(old.get("request_key"),"")+":neckline-repair:"+eventId)
    .getBytes(StandardCharsets.UTF_8));
  if(!limits.reserve(run.id(),user.organizationId(),nextKey,"MOCKUP"))
   throw conflict("原批次模型调用上限已用完，母图保持原样");
  String nextId=UUID.randomUUID().toString();
  if(db.update("""
    INSERT INTO commerce_batch_artwork_jobs
    (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,request_key,
     status,prompt,model_config_id,model_fingerprint,reference_keys,message,created_at,updated_at)
    SELECT ?,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,color_label,?,
           'PREPARED',?,model_config_id,model_fingerprint,reference_keys,?,NOW(6),NOW(6)
    FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? AND kind='DRAPE_MASTER' AND status='READY'
    """,nextId,nextKey,CommerceDrapeReuseWorkflow.PROMPT_DETAIL_V4,
    "领口与固定模板不一致，准备重新生成母图；旧图留档",old.get("id"),run.id(),user.organizationId())!=1)
   throw conflict("母图状态已变化，修复未提交");
  if(db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status='READY' AND kind='DRAPE_MASTER'",
    old.get("id"),run.id(),user.organizationId())!=1)throw conflict("母图状态已变化，修复未提交");
  int retired=0;
  for(var derivative:derivatives){
   String oldKey=(String)derivative.get("request_key");
   String retiredKey=retiredRequestKey(oldKey,Objects.toString(derivative.get("id")),eventId);
   if(db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',request_key=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status='READY' AND model_config_id='LOCAL_PHOTO_V1' AND request_key=?",
     retiredKey,derivative.get("id"),run.id(),user.organizationId(),oldKey)!=1)
    throw conflict("旧搭放成品状态已变化，修复未提交");
   retired++;
  }
  Instant now=Instant.now();
  String message="已安排重新生成领口一致的搭放母图；保留原印花、颜色、精确套版和 "+retired+" 张旧搭放图；重新验收后再上传草稿";
  if(db.update("UPDATE commerce_batch_executions SET stage='MOCKUP',status='QUEUED',message=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND organization_id=? AND stage=? AND status=? AND lease_token IS NULL AND lease_until IS NULL",
    message,Timestamp.from(now),run.id(),user.organizationId(),run.stage(),run.status())!=1)
   throw conflict("批次状态已变化，修复未提交");
  if(db.update("INSERT INTO commerce_batch_execution_events(id,execution_id,stage,status,message,created_at) VALUES(?,?,'MOCKUP','QUEUED',?,?)",
    eventId,run.id(),message,Timestamp.from(now))!=1)throw conflict("修复事件未保存");
  return new Rebuilt(new CommerceBatchExecution(run.id(),run.organizationId(),run.batchId(),run.createdBy(),
    "MOCKUP","QUEUED",message,null,null,run.createdAt(),now),retired,false);
 }

 static void requireLocalInputs(CommerceBatchCreativePlanValidator.Plan plan,
   CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs){
  if(plan.sources()==null||plan.sources().isEmpty()||plan.variantsPerPrint()<1||plan.variantsPerPrint()>20)throw conflict("固定来源或款式数量无效");
  boolean surface=CommerceSurfaceTemplateInputs.enabled(config);
  var groups=surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)?CommerceSurfaceTemplateInputs.artworkGroups(config):List.of("planar");
  if(groups.isEmpty())throw conflict("固定印花组为空");
  var missing=new ArrayList<String>();
  for(var source:plan.sources()){
   if(!ready(jobs,source,"EXTRACT",0,null,false))missing.add(source.captureId()+"：原印花提取结果缺失");
   for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(int group=0;group<groups.size();group++){
    String kind=surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)?"VARIATE_G"+(group+1):"VARIATE";
    if(!ready(jobs,source,kind,variant,null,false))missing.add(source.captureId()+"："+kind+" / V"+variant+" 未归档");
   }
   if(source.colorTargets().isEmpty())missing.add(source.captureId()+"：固定颜色缺失");
   for(var color:source.colorTargets())if(!ready(jobs,source,"BASE_COLOR",0,color.key(),true))missing.add(source.captureId()+"："+color.label()+" 的颜色底图未归档");
   if(!surface&&config.baseImages().get("FRONT")==null&&!ready(jobs,source,"BLANK",0,null,false))missing.add(source.captureId()+"：去印花底图未归档");
  }
  if(!missing.isEmpty())throw conflict("本地重算前置素材不完整："+String.join("；",missing.subList(0,Math.min(8,missing.size())))+"；不会补调模型或重新生成颜色底图");
 }
 private static boolean ready(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,String color,boolean local){
  return jobs.stream().filter(job->source.captureId().equals(job.get("capture_id"))&&kind.equals(job.get("kind"))
    &&job.get("variant_index") instanceof Number n&&n.intValue()==variant&&Objects.equals(color,job.get("color_key"))
    &&job.get("capture_version") instanceof Number version&&version.longValue()==source.version()
    &&"READY".equals(job.get("status"))&&job.get("storage_key") instanceof String storage&&!storage.isBlank()
    &&(!local||CommerceLocalPhotoWorkflow.POLICY.equals(job.get("model_config_id")))).count()==1;
 }
 static String eventId(String org,String run,String key){return UUID.nameUUIDFromBytes((org+":"+run+":local-rebuild:"+key).getBytes(StandardCharsets.UTF_8)).toString();}
 static String retiredRequestKey(String oldKey,String jobId,String eventId){return CommerceImageArchive.sha((oldKey+":local-rebuild:"+eventId+":"+jobId).getBytes(StandardCharsets.UTF_8));}
 private static final RowMapper<CommerceBatchExecution> EXECUTION_ROW=(row,index)->{
  Timestamp lease=row.getTimestamp("lease_until");
  return new CommerceBatchExecution(row.getString("id"),row.getString("organization_id"),row.getString("batch_id"),row.getString("created_by"),
    row.getString("stage"),row.getString("status"),row.getString("message"),row.getString("lease_token"),lease==null?null:lease.toInstant(),row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant());
 };
 private static BusinessException conflict(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
