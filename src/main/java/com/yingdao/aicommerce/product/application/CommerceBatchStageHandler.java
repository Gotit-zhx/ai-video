package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
/** Implementations must persist external submission intent and enforce confirmed budgets before side effects. */
public interface CommerceBatchStageHandler {
 CommerceBatchExecution.Stage stage();
 Result execute(CommerceBatchExecution execution,PlatformPrincipal principal);
 record Result(boolean complete,String message,String pendingStatus) {
  public Result(boolean complete,String message){this(complete,message,"WAITING_INPUT");}
  public Result {if(!java.util.Set.of("WAITING_INPUT","QUEUED","UNKNOWN","PAUSED","FAILED").contains(pendingStatus))throw new IllegalArgumentException("无效的阶段状态");}
  public static Result progress(String message){return new Result(false,message,"QUEUED");}
  public static Result unknown(String message){return new Result(false,message,"UNKNOWN");}
 }
}
