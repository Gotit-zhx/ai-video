package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Non-billable source-data gate. Visual/content checks remain separate downstream gates. */
@Component
public class CommerceBatchDetectionStage implements CommerceBatchStageHandler {
 private final CommerceBatchDetectionService detection;
 public CommerceBatchDetectionStage(CommerceBatchDetectionService detection){this.detection=detection;}
 @Override public CommerceBatchExecution.Stage stage(){return CommerceBatchExecution.Stage.DETECT;}
 @Override public Result execute(CommerceBatchExecution run,PlatformPrincipal user){
  if(run==null||user==null||!Objects.equals(run.organizationId(),user.organizationId()))return new Result(false,"任务与当前公司不匹配");
  var report=detection.check(run.batchId(),user);
  if(report.items().isEmpty())return new Result(false,"批次没有可检查的来源商品");
  var issues=report.items().stream().filter(item->item.imageCount()==0||item.issues().contains("来源版本已变化，需重新固定批次来源")).toList();
  if(!issues.isEmpty()){
   var first=issues.getFirst();String message="提取前检查未通过："+issues.size()+" 个商品需处理。来源 "+first.captureId()+"："+(first.imageCount()==0?"缺少来源图片":"来源版本已变化，需重新固定批次来源");
   return new Result(false,message.length()>900?message.substring(0,900):message);
  }
  return new Result(true,"已核对 "+report.items().size()+" 个商品的固定来源版本与来源图片，自动进入印花提取。价格、SKU与图片处理等发布条件留待发布前检查；此检查不代表视觉质量、知识产权或发布审批通过。");
 }
}
