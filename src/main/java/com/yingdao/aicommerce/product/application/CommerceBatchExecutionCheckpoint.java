package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a fenced stage result and its audit record in the same transaction. */
@Service
public class CommerceBatchExecutionCheckpoint {
 private final CommerceBatchExecutionMapper runs;
 public CommerceBatchExecutionCheckpoint(CommerceBatchExecutionMapper runs){this.runs=runs;}
 @Transactional
 public boolean finish(String id,String token,String executedStage,String nextStage,String status,String message,Instant now){
  if(runs.finish(id,token,nextStage,status,message,now)!=1)return false;
  var stored=runs.byId(id);
  runs.event(UUID.randomUUID().toString(),id,executedStage,stored==null?status:stored.status(),stored==null?message:stored.message(),now);
  return true;
 }
}
