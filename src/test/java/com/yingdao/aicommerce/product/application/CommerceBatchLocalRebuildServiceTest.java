package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.web.CommerceBatchLocalRebuildController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceBatchLocalRebuildServiceTest {
 private static final String RUN_LOCK="SELECT * FROM commerce_batch_executions WHERE id=? AND organization_id=? AND batch_id=? FOR UPDATE";
 private static final String EVENT_LOCK="SELECT id FROM commerce_batch_execution_events WHERE id=? AND execution_id=? FOR UPDATE";
 private static final String PUBLICATIONS="SELECT item_id,variant_index,capture_id,workflow_id FROM commerce_batch_publications WHERE organization_id=? AND execution_id=? FOR UPDATE";
 private static final String JOBS="SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? FOR UPDATE";
 private static final String RETIRE="UPDATE commerce_batch_artwork_jobs SET status='RETRIED',request_key=?,updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status='READY' AND model_config_id='LOCAL_PHOTO_V1' AND request_key=?";
 private static final String KEY="commerce-local-rebuild-v2-batch";
 private static final String EVENT=CommerceBatchLocalRebuildService.eventId("org","run",KEY);
 private final JdbcTemplate db=mock(JdbcTemplate.class);
 private final CommerceBatchExecutionService executions=mock(CommerceBatchExecutionService.class);
 private final CommerceBatchCreativePlanMapper plans=mock(CommerceBatchCreativePlanMapper.class);
 private final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
 private final ObjectMapper json=new ObjectMapper();
 private final CommerceBatchLocalRebuildService service=new CommerceBatchLocalRebuildService(db,executions,plans,templates,json);
 private final PlatformPrincipal user=user("org",List.of("product:edit","product:publish"));
 private final List<Map<String,Object>> jobs=new ArrayList<>();
 private CommerceBatchCreativePlanValidator.Plan plan;

 @BeforeEach void setup(){
  when(executions.get("batch",user)).thenReturn(run("WAITING_INPUT","PUBLISH",null,null));
  locked(run("WAITING_INPUT","PUBLISH",null,null));
  when(db.queryForList(JOBS,"run","org")).thenReturn(jobs);
  when(db.update(anyString(),any(Object[].class))).thenReturn(1);
  var color=new CommerceSourceColorResolver.Color("black","Black",List.of("sku"),"source-image",true);
  var source=new CommerceBatchCreativePlanValidator.Selection("source",9,"source-image",List.of(),List.of(color));
  plan=new CommerceBatchCreativePlanValidator.Plan("template",List.of(source),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS","LOCAL_PHOTO_V1");
  plan(plan);
  template(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(),Map.of("FRONT","f".repeat(64))));
  jobs.add(job("extract","EXTRACT",0,null,"READY","AI_IMAGE"));
  jobs.add(job("variate","VARIATE",1,null,"READY","AI_IMAGE"));
  jobs.add(job("base","BASE_COLOR",0,"black","READY","LOCAL_PHOTO_V1"));
  jobs.add(job("matte","MATTE",1,null,"READY","LOCAL_PHOTO_V1"));
  jobs.add(job("mockup","MOCKUP",1,"black","READY","LOCAL_PHOTO_V1"));
 }

 @Test void permissionsAndSourceAccessAreCheckedBeforeMutation(){
  for(var candidate:List.of(user("org",List.of()),user("org",List.of("product:edit")),user("org",List.of("product:publish")),user(null,List.of("product:edit","product:publish"))))
   assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,candidate)).getStatus());
  assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,null)).getStatus());
  verifyNoInteractions(executions,db,plans,templates);
  when(executions.get("batch",user)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN,"source inaccessible"));
  assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user));verifyNoInteractions(db);
 }

 @Test void missingTenantExecutionCannotBeRebuilt(){
  when(db.query(eq(RUN_LOCK),org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),eq("run"),eq("org"),eq("batch"))).thenReturn(List.of());
  assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user));verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void activeCompletedAndUncertainExecutionsAreNeverInterrupted(){
  for(String status:List.of("QUEUED","RUNNING","PAUSING","COMPLETED","UNKNOWN","WAITING_CAPABILITY")){
   locked(run(status,"MOCKUP",null,null));
   assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user),status).getStatus());
  }
  locked(run("PAUSED","MOCKUP","stale-token",Instant.now().minusSeconds(5)));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("租约"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void evenAnEmptyPublicationPlaceholderProtectsFrozenListingImages(){
  when(db.queryForList(PUBLICATIONS,"org","run")).thenReturn(List.of(Map.of("item_id","item","variant_index",1)));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("发布草稿"));
  verify(db,never()).queryForList(JOBS,"run","org");verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void unsettledModelJobsPreventRebuildWithoutTouchingBudgets(){
  for(String status:List.of("PREPARED","SUBMITTING","UNKNOWN","RESULT_RECEIVED")){
   var pending=job("pending","VARIATE",2,null,status,"AI_IMAGE");jobs.add(pending);
   assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("未决模型"));jobs.remove(pending);
  }
  verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void requiresExistingVariationsAndEveryColorBaseRatherThanGeneratingMissingInputs(){
  jobs.removeIf(job->"BASE_COLOR".equals(job.get("kind")));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("颜色底图"));
  jobs.add(job("base","BASE_COLOR",0,"black","READY","LOCAL_PHOTO_V1"));jobs.removeIf(job->"VARIATE".equals(job.get("kind")));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("VARIATE"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void planarTemplateWithoutUploadedBaseMustAlreadyHaveItsBlankImage(){
  template(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of()));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("去印花底图"));
  jobs.add(job("blank","BLANK",0,null,"READY","AI_IMAGE"));
  assertEquals("MOCKUP",service.rebuild("batch",KEY,user).execution().stage());
 }

 @Test void rejectsNonLocalPlanAndStaleSourceVersion(){
  plan(new CommerceBatchCreativePlanValidator.Plan("template",plan.sources(),1,null,"SOURCE_PRODUCT","SOURCE_SKU_COLORS",null));
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("仅支持"));
  plan(plan);jobs.getFirst().put("capture_version",8L);
  assertTrue(assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user)).getMessage().contains("提取结果缺失"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void retiresOnlyLocalReadyMatteAndMockupWhilePreservingAllOriginalAssets()throws Exception{
  jobs.add(job("prior","MATTE",1,null,"RETRIED","LOCAL_PHOTO_V1"));
  jobs.add(job("remote","MOCKUP_BACK",1,"black","READY","AI_IMAGE"));
  var result=service.rebuild("batch",KEY,user);
  assertEquals(2,result.retiredArtifacts());assertFalse(result.reused());assertEquals("MOCKUP",result.execution().stage());assertEquals("QUEUED",result.execution().status());assertNull(result.execution().leaseToken());
  for(String id:List.of("matte","mockup"))verify(db).update(RETIRE,CommerceBatchLocalRebuildService.retiredRequestKey("request-"+id,id,EVENT),id,"run","org","request-"+id);
  var queries=ArgumentCaptor.forClass(String.class);verify(db,atLeastOnce()).update(queries.capture(),any(Object[].class));
  assertEquals(4,queries.getAllValues().size());
  for(String sql:queries.getAllValues()){
   assertFalse(sql.contains("commerce_batch_model_"));assertFalse(sql.contains("commerce_batch_artwork_checks"));assertFalse(sql.contains("storage_key="));assertFalse(sql.contains("result_json="));assertFalse(sql.startsWith("DELETE"));
  }
  var order=inOrder(executions,db,plans,templates);
  order.verify(executions).get("batch",user);
  order.verify(db).query(eq(RUN_LOCK),org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),eq("run"),eq("org"),eq("batch"));
  order.verify(db).queryForList(EVENT_LOCK,EVENT,"run");order.verify(db).queryForList(PUBLICATIONS,"org","run");order.verify(db).queryForList(JOBS,"run","org");
  assertNotNull(CommerceBatchLocalRebuildService.class.getMethod("rebuild",String.class,String.class,PlatformPrincipal.class).getAnnotation(Transactional.class));
 }

 @Test void theSameKeyReturnsCurrentRunAfterAdvancementWithoutRequeuingOrRetiringAgain(){
  when(db.queryForList(EVENT_LOCK,EVENT,"run")).thenReturn(List.of(Map.of("id",EVENT)));
  for(String status:List.of("QUEUED","RUNNING","WAITING_INPUT","COMPLETED")){
   var current=run(status,"REVIEW",status.equals("RUNNING")?"live-token":null,null);locked(current);
   var result=service.rebuild("batch",KEY,user);assertEquals(current,result.execution());assertTrue(result.reused());assertEquals(0,result.retiredArtifacts());
  }
  verify(db,never()).queryForList(PUBLICATIONS,"org","run");verify(db,never()).queryForList(JOBS,"run","org");verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void changedJobStateAbortsBeforeRequeueAndRetirementKeysStayUnique(){
  when(db.update(eq(RETIRE),anyString(),eq("matte"),eq("run"),eq("org"),eq("request-matte"))).thenReturn(0);
  assertThrows(BusinessException.class,()->service.rebuild("batch",KEY,user));
  verify(db,never()).update(startsWith("UPDATE commerce_batch_executions"),any(Object[].class));
  String key=CommerceBatchLocalRebuildService.retiredRequestKey("old","job",EVENT);assertTrue(key.matches("[a-f0-9]{64}"));
  assertEquals(key,CommerceBatchLocalRebuildService.retiredRequestKey("old","job",EVENT));
  assertNotEquals(key,CommerceBatchLocalRebuildService.retiredRequestKey("old","other-job",EVENT));
  assertNotEquals(key,CommerceBatchLocalRebuildService.retiredRequestKey("old","job","next-event"));
 }

 @Test void controllerPassesIdempotencyKeyAndReturnsTheExecutionEnvelope(){
  var mocked=mock(CommerceBatchLocalRebuildService.class);var expected=new CommerceBatchLocalRebuildService.Rebuilt(run("QUEUED","MOCKUP",null,null),2,false);
  when(mocked.rebuild("batch",KEY,user)).thenReturn(expected);
  var response=new CommerceBatchLocalRebuildController(mocked).rebuild("batch",KEY,user);assertTrue(response.success());assertSame(expected,response.data());
  assertEquals("/api/v1/commerce-batches/{batch}/execution/rebuild-local",CommerceBatchLocalRebuildController.class.getAnnotation(RequestMapping.class).value()[0]);
 }

 private void locked(CommerceBatchExecution run){when(db.query(eq(RUN_LOCK),org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),eq("run"),eq("org"),eq("batch"))).thenReturn(List.of(run));}
 private void plan(CommerceBatchCreativePlanValidator.Plan plan){when(plans.forExecution("org","run")).thenReturn(new CommerceBatchCreativePlanMapper.Row("plan","org","batch",1,json.writeValueAsString(plan),"user",Instant.now()));}
 private void template(CommercePrintTemplateService.Configuration config){when(templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","template-id",1,"PRINT_LAYOUT","Template",json.writeValueAsString(config),"user",Instant.now()));}
 private static Map<String,Object> job(String id,String kind,int variant,String color,String status,String provider){var job=new LinkedHashMap<String,Object>();job.put("id",id);job.put("capture_id","source");job.put("capture_version",9L);job.put("kind",kind);job.put("variant_index",variant);job.put("color_key",color);job.put("status",status);job.put("model_config_id",provider);job.put("storage_key","archive/"+id);job.put("request_key","request-"+id);return job;}
 private static CommerceBatchExecution run(String status,String stage,String token,Instant lease){var now=Instant.parse("2026-09-15T08:00:00Z");return new CommerceBatchExecution("run","org","batch","user",stage,status,"existing",token,lease,now,now);}
 private static PlatformPrincipal user(String org,List<String> permissions){return new PlatformPrincipal("user",org,"code","user","User","",true,List.of(),permissions);}
}
