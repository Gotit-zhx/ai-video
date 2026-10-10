package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceBatchExecutionCheckpointTest {
 @Test void staleLeaseCannotAppendSuccessEvent(){
  var h=new Harness();
  assertFalse(h.finish());
  verify(h.mapper,never()).event(any(),any(),any(),any(),any(),any());
  verify(h.manager).commit(h.transactions.getFirst());
 }

 @Test void auditNamesExecutedStageRatherThanNextStage(){
  var h=new Harness();h.accept();
  assertTrue(h.finish());
  verify(h.mapper).event(anyString(),eq("r"),eq("CAPTURE"),eq("QUEUED"),eq("done"),eq(h.now));
  verify(h.manager).commit(h.transactions.getFirst());
 }

 @Test void updateDeadlockRollsBackBeforeRetryingOnlyTheSameCheckpointInANewTransaction(){
  var h=new Harness();
  when(h.mapper.finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),any()))
    .thenThrow(new CannotAcquireLockException("deadlock")).thenReturn(1);
  assertTrue(h.finish());assertEquals(2,h.transactions.size());
  var order=inOrder(h.manager,h.mapper);
  order.verify(h.manager).getTransaction(any());
  order.verify(h.mapper).finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),eq(h.now));
  order.verify(h.manager).rollback(h.transactions.get(0));
  order.verify(h.manager).getTransaction(any());
  order.verify(h.mapper).finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),argThat(time->time.isAfter(h.now)));
  order.verify(h.mapper).byId("r");
  order.verify(h.mapper).event(anyString(),eq("r"),eq("CAPTURE"),eq("QUEUED"),eq("done"),any());
  order.verify(h.manager).commit(h.transactions.get(1));
  verify(h.mapper,times(1)).event(anyString(),anyString(),anyString(),anyString(),anyString(),any());
 }

 @Test void auditDeadlockRollsBackTheStateUpdateAndRetriesStateAndAuditTogether(){
  var h=new Harness();h.accept();
  when(h.mapper.event(anyString(),anyString(),anyString(),anyString(),anyString(),any()))
    .thenThrow(new CannotAcquireLockException("audit lock conflict")).thenReturn(1);
  assertTrue(h.finish());assertEquals(2,h.transactions.size());
  verify(h.mapper,times(2)).finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),any());
  verify(h.manager).rollback(h.transactions.get(0));verify(h.manager).commit(h.transactions.get(1));
 }

 @Test void lockRetryStopsWhenTheOriginalLeaseNoLongerOwnsTheExecution(){
  var h=new Harness();
  when(h.mapper.finish(anyString(),anyString(),anyString(),anyString(),anyString(),any()))
    .thenThrow(new CannotAcquireLockException("deadlock")).thenReturn(0);
  assertFalse(h.finish());assertEquals(2,h.transactions.size());
  verify(h.manager).rollback(h.transactions.get(0));verify(h.manager).commit(h.transactions.get(1));
  verify(h.mapper,never()).event(any(),any(),any(),any(),any(),any());
 }

 @Test void repeatedLockConflictsStopAfterThreeFreshTransactionsAndAdvanceLeaseTime(){
  var h=new Harness();var conflict=new CannotAcquireLockException("persistent deadlock");
  when(h.mapper.finish(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenThrow(conflict);
  assertSame(conflict,assertThrows(CannotAcquireLockException.class,h::finish));
  assertEquals(3,h.transactions.size());
  for(var transaction:h.transactions)verify(h.manager).rollback(transaction);
  verify(h.manager,never()).commit(any());verify(h.mapper,never()).event(any(),any(),any(),any(),any(),any());
  var times=ArgumentCaptor.forClass(Instant.class);
  verify(h.mapper,times(3)).finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),times.capture());
  assertEquals(h.now,times.getAllValues().getFirst());
  assertTrue(times.getAllValues().get(1).isAfter(h.now));
  assertTrue(times.getAllValues().get(2).isAfter(times.getAllValues().get(1)));
 }

 @Test void otherDatabaseFailuresAreNotRetried(){
  var h=new Harness();h.accept();var failure=new DataIntegrityViolationException("invalid audit record");
  when(h.mapper.event(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenThrow(failure);
  assertSame(failure,assertThrows(DataIntegrityViolationException.class,h::finish));
  assertEquals(1,h.transactions.size());verify(h.manager).rollback(h.transactions.getFirst());
  verify(h.mapper,times(1)).finish(anyString(),anyString(),anyString(),anyString(),anyString(),any());
  verify(h.manager,never()).commit(any());
 }

 @Test void interruptionStopsRetryAndPreservesTheThreadInterruptFlag(){
  var h=new Harness();var failure=new CannotAcquireLockException("deadlock");
  when(h.mapper.finish(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenThrow(failure);
  Thread.currentThread().interrupt();
  try {
   assertSame(failure,assertThrows(CannotAcquireLockException.class,h::finish));
   assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,h.transactions.size());
  } finally {Thread.interrupted();}
 }

 private static final class Harness {
  final CommerceBatchExecutionMapper mapper=mock(CommerceBatchExecutionMapper.class);
  final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
  final List<TransactionStatus> transactions=new ArrayList<>();
  final Instant now=Instant.parse("2026-09-22T04:00:00Z");
  final CommerceBatchExecutionCheckpoint checkpoint;
  Harness(){
   when(manager.getTransaction(any())).thenAnswer(call->{
    TransactionDefinition definition=call.getArgument(0);
    assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
    var status=new SimpleTransactionStatus();transactions.add(status);return status;
   });
   checkpoint=new CommerceBatchExecutionCheckpoint(mapper,manager);
  }
  void accept(){when(mapper.finish(eq("r"),eq("lease"),eq("DETECT"),eq("QUEUED"),eq("done"),any())).thenReturn(1);}
  boolean finish(){return checkpoint.finish("r","lease","CAPTURE","DETECT","QUEUED","done",now);}
 }
}
