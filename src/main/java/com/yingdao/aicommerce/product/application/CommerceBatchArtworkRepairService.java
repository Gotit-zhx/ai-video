package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Non-generative, immutable corrections for finished runs; originals remain available. */
@Service
public class CommerceBatchArtworkRepairService {
 public record Request(String captureId,List<CommerceArtworkLocalEdit.Region> cleanupAreas){}
 private final JdbcTemplate db;private final CommerceBatchExecutionService executions;private final CommerceBatchCreativePlanMapper plans;private final CommerceTemplateMapper templates;private final CommerceImageArchive archive;private final ObjectMapper json;
 public CommerceBatchArtworkRepairService(JdbcTemplate db,CommerceBatchExecutionService executions,CommerceBatchCreativePlanMapper plans,CommerceTemplateMapper templates,CommerceImageArchive archive,ObjectMapper json){this.db=db;this.executions=executions;this.plans=plans;this.templates=templates;this.archive=archive;this.json=json;}
 public CommerceDesignImageGateway.Reference source(String batch,String capture,PlatformPrincipal user){var run=executions.get(batch,user);if(run==null)throw bad("批次未启动");var jobs=db.queryForList("SELECT reference_keys FROM commerce_batch_artwork_jobs WHERE organization_id=? AND execution_id=? AND capture_id=? AND kind='EXTRACT' AND status='READY'",user.organizationId(),run.id(),capture);if(jobs.size()!=1)throw bad("缺少固定原图");return archive.read(user.organizationId(),refs(jobs.getFirst()).getFirst());}
 @Transactional public int repair(String batch,Request request,PlatformPrincipal user) {
  if(user==null||!user.permissionCodes().contains("product:edit"))throw new BusinessException(HttpStatus.FORBIDDEN,"无图片编辑权限");
  var run=executions.get(batch,user);if(run==null||!"COMPLETED".equals(run.status())||!"REVIEW".equals(run.stage()))throw bad("仅能修复已完成检查的创作批次");
  if(request==null)throw bad("缺少修复配置");
  var record=plans.forExecution(user.organizationId(),run.id());var plan=json.readValue(record.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
  if(CommerceBatchCreativePlanValidator.localPhotoOutput(plan))throw bad("本地套版已保留固定底图与透明母版；请修改模板位置后新建批次，旧的矩形修复不会覆盖这些结果");
  if(plan.sources().stream().noneMatch(s->s.captureId().equals(request.captureId())))throw bad("来源不属于该批次");
  var template=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());var config=json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class);
  if(CommerceSurfaceTemplateInputs.enabled(config))throw bad("三维曲面套版保留独立的UV精确位置图；二维矩形修复不能用于当前模板，请核对各面候选或重新发起创作");
  var jobs=db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE organization_id=? AND execution_id=? AND capture_id=? AND status='READY'",user.organizationId(),run.id(),request.captureId());
  var blanks=jobs.stream().filter(j->"BLANK".equals(j.get("kind"))).toList();
  byte[] base;int count=0;
  if(config.baseImages().get("FRONT")==null){
   try{CommerceArtworkLocalEdit.validate(request.cleanupAreas());}catch(IllegalArgumentException e){throw bad(e.getMessage());}
   if(blanks.size()!=1)throw bad("缺少已归档的去印花候选");var blank=blanks.getFirst();
   base=CommerceArtworkLocalEdit.preserve(bytes(user,refs(blank).getFirst()),bytes(user,(String)blank.get("storage_key")),request.cleanupAreas());
   save(run.id(),blank,base,request,null,user);count++;
  }else base=bytes(user,CommerceImageArchive.sha(user.organizationId().getBytes(StandardCharsets.UTF_8))+"/"+config.baseImages().get("FRONT"));
  var mockups=jobs.stream().filter(j->"MOCKUP".equals(j.get("kind"))).toList();if(mockups.size()!=plan.variantsPerPrint())throw bad("套版候选尚未全部归档");
  for(var job:mockups){var fixed=CommerceArtworkLocalEdit.preserve(base,bytes(user,(String)job.get("storage_key")),CommerceArtworkLocalEdit.placement(config.areas()));var report=CommerceMockupPositionCheck.check(base,bytes(user,refs(job).get(1)),fixed,config.areas());save(run.id(),job,fixed,request,report,user);count++;}
  return count;
 }
 private void save(String run,Map<String,Object> job,byte[] image,Request request,Object report,PlatformPrincipal user){
  var fields=new LinkedHashMap<String,Object>();fields.put("algorithm","LOCAL_MASK_HARMONIC_V2");fields.put("request",request);fields.put("sourceJobId",job.get("id"));fields.put("sourceSha256",job.get("content_sha256"));String policy=json.writeValueAsString(fields);
  String key=CommerceImageArchive.sha(policy.getBytes(StandardCharsets.UTF_8));var stored=archive.store(user.organizationId(),image);
  db.update("INSERT IGNORE INTO commerce_batch_artwork_repairs(id,organization_id,execution_id,job_id,request_key,storage_key,content_sha256,policy_json,report_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,NOW(6))",UUID.randomUUID().toString(),user.organizationId(),run,job.get("id"),key,stored.storageKey(),stored.sha256(),policy,report==null?null:json.writeValueAsString(report));
 }
 private byte[] bytes(PlatformPrincipal user,String key){return Base64.getDecoder().decode(archive.read(user.organizationId(),key).dataBase64());}
 private List<String> refs(Map<String,Object> job){return json.readValue((String)job.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
 private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
