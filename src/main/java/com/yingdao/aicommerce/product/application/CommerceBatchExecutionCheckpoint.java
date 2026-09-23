package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits a fenced stage result and its audit record in the same transaction. */
@Service
public class CommerceBatchExecutionCheckpoint {
 private static final int MAX_ATTEMPTS=3;
 private final CommerceBatchExecutionMapper runs;
 private final TransactionTemplate transactions;
 public CommerceBatchExecutionCheckpoint(CommerceBatchExecutionMapper runs,PlatformTransactionManager manager){
  this.runs=runs;
  transactions=new TransactionTemplate(manager);
  transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public boolean finish(String id,String token,String executedStage,String nextStage,String status,String message,Instant now){
  long started=System.nanoTime();
  for(int attempt=1;;attempt++){
   // The model stage has already finished. Retry only this atomic checkpoint after
   // its deadlocked transaction rolls back, using the original result and lease token.
   Instant checkedAt=attempt==1?now:now.plusNanos(System.nanoTime()-started);
   try{return Boolean.TRUE.equals(transactions.execute(ignored->finishOnce(id,token,executedStage,nextStage,status,message,checkedAt)));}
   catch(PessimisticLockingFailureException conflict){
    if(attempt>=MAX_ATTEMPTS)throw conflict;
    try{Thread.sleep(50L*attempt);}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw conflict;}
   }
  }
 }
 private boolean finishOnce(String id,String token,String executedStage,String nextStage,String status,String message,Instant now){
  if(runs.finish(id,token,nextStage,status,message,now)!=1)return false;
  var stored=runs.byId(id);
  runs.event(UUID.randomUUID().toString(),id,executedStage,stored==null?status:stored.status(),stored==null?message:stored.message(),now);
  return true;
 }
}
