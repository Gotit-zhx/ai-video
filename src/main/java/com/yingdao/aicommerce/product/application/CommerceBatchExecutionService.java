package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;
@Service public class CommerceBatchExecutionService {
 @org.springframework.beans.factory.annotation.Autowired private CommerceBatchPublicationOptions publicationOptions;
 private final CommerceBatchExecutionMapper runs;private final CommerceBatchMapper batches;private final ProductCaptureService captures;private final CommerceBatchModelLimitService limits;private final CommerceBatchCreativePlanService plans;
 public CommerceBatchExecutionService(CommerceBatchExecutionMapper runs,CommerceBatchMapper batches,ProductCaptureService captures){this(runs,batches,captures,null);}
 public CommerceBatchExecutionService(CommerceBatchExecutionMapper runs,CommerceBatchMapper batches,ProductCaptureService captures,CommerceBatchModelLimitService limits){this(runs,batches,captures,limits,null);}
 @org.springframework.beans.factory.annotation.Autowired
 public CommerceBatchExecutionService(CommerceBatchExecutionMapper runs,CommerceBatchMapper batches,ProductCaptureService captures,CommerceBatchModelLimitService limits,CommerceBatchCreativePlanService plans){this.runs=runs;this.batches=batches;this.captures=captures;this.limits=limits;this.plans=plans;}
 public CommerceBatchModelLimitService.Limit limit(String batch,PlatformPrincipal user){var run=get(batch,user);return run==null||limits==null?new CommerceBatchModelLimitService.Limit(0,0,false):limits.get(run.id(),user.organizationId());}
 public CommerceBatchExecution get(String batch,PlatformPrincipal user){access(user);if(batches.get(user.organizationId(),batch)==null)throw error("批次不存在");verifyItemAccess(batch,user);return runs.get(user.organizationId(),batch);}
 private void verifyItemAccess(String batch,PlatformPrincipal user){for(var item:batches.items(user.organizationId(),batch))captures.get(item.captureId(),user);}
 @Transactional public CommerceBatchExecution start(String batch,boolean confirmed,PlatformPrincipal user){
  return start(batch,confirmed,0,user);
 }
 @Transactional public CommerceBatchExecution start(String batch,boolean confirmed,int maxModelCalls,PlatformPrincipal user){
  return start(batch,confirmed,maxModelCalls,0,user);
 }
 @Transactional public CommerceBatchExecution start(String batch,boolean confirmed,int maxModelCalls,int creativePlanVersion,PlatformPrincipal user){
  CommerceBatchModelLimitService.validate(maxModelCalls);
  access(user);if(!confirmed)throw error("请明确确认启动批次");var found=batches.lock(user.organizationId(),batch);if(found==null)throw error("批次不存在");verifyItemAccess(batch,user);var existing=runs.get(user.organizationId(),batch);if(existing!=null)return existing;
  if(!"DRAFT".equals(found.status()))throw error("仅草稿批次可启动");var items=batches.items(user.organizationId(),batch);if(items.isEmpty())throw error("批次没有商品");
  for(var item:items)if(captures.get(item.captureId(),user).capture().getVersion()!=item.captureVersion())throw error("来源版本已变化，请重新准备批次");
  if(plans==null)throw error("创作配置服务不可用，不能启动");
  if(publicationOptions!=null)publicationOptions.requireReady(batch,user);
  var plan=plans.prepareStart(batch,creativePlanVersion,user);
  var now=Instant.now();var row=new CommerceBatchExecution(UUID.randomUUID().toString(),user.organizationId(),batch,user.userId(),"CAPTURE","QUEUED","已排队；未接入的阶段会暂停，不会跳过",null,null,now,now);runs.insert(row);plans.bind(row.id(),plan);if(limits!=null)limits.initialize(row.id(),user.organizationId(),maxModelCalls);runs.event(UUID.randomUUID().toString(),row.id(),row.stage(),row.status(),row.message()+"；创作配置 v"+plan.version()+"；模型调用上限 "+maxModelCalls+" 次（非金额预算）",now);return row;
 }
 @Transactional public CommerceBatchExecution control(String batch,String action,PlatformPrincipal user){get(batch,user);int changed=action.equals("resume")?runs.resume(user.organizationId(),batch,Instant.now()):action.equals("pause")?runs.pause(user.organizationId(),batch,Instant.now()):action.equals("recover")?runs.recover(user.organizationId(),batch,Instant.now()):0;if(changed!=1)throw error("当前状态不能执行此操作；存在未决模型提交时不能重试");var row=runs.get(user.organizationId(),batch);runs.event(UUID.randomUUID().toString(),row.id(),row.stage(),row.status(),row.message(),Instant.now());return row;}
 private static void access(PlatformPrincipal user){if(user==null||user.organizationId()==null||!user.permissionCodes().contains("product:publish"))throw new BusinessException(HttpStatus.FORBIDDEN,"无批次执行权限");}
 private static BusinessException error(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
