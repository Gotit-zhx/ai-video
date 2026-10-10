package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.CommerceBatchMapper;
import org.springframework.stereotype.Component;
@Component public class CommerceBatchCaptureStage implements CommerceBatchStageHandler {
 private final CommerceBatchMapper batches;private final ProductCaptureService captures;
 public CommerceBatchCaptureStage(CommerceBatchMapper batches,ProductCaptureService captures){this.batches=batches;this.captures=captures;}
 public CommerceBatchExecution.Stage stage(){return CommerceBatchExecution.Stage.CAPTURE;}
 public Result execute(CommerceBatchExecution run,PlatformPrincipal user){var batch=batches.get(run.organizationId(),run.batchId());if(batch==null||!"DRAFT".equals(batch.status()))return new Result(false,"批次状态已变化");var items=batches.items(run.organizationId(),run.batchId());if(items.isEmpty())return new Result(false,"批次无来源商品");for(var item:items){var detail=captures.get(item.captureId(),user);if(detail.capture().getVersion()!=item.captureVersion())return new Result(false,"来源版本已变化");if(detail.images().isEmpty())return new Result(false,"来源商品缺少图片");}return new Result(true,"已恢复并核对批次内已采集商品；下一阶段自动处理");}
}
