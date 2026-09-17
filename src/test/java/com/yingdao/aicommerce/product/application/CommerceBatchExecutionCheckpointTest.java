package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceBatchExecutionCheckpointTest {
 @Test void staleLeaseCannotAppendSuccessEvent(){
  var mapper=mock(CommerceBatchExecutionMapper.class);var now=Instant.now();
  assertFalse(new CommerceBatchExecutionCheckpoint(mapper).finish("r","old","CAPTURE","DETECT","QUEUED","done",now));
  verify(mapper,never()).event(any(),any(),any(),any(),any(),any());
 }
 @Test void auditNamesExecutedStageRatherThanNextStage(){
  var mapper=mock(CommerceBatchExecutionMapper.class);var now=Instant.now();
  when(mapper.finish("r","lease","DETECT","QUEUED","done",now)).thenReturn(1);
  assertTrue(new CommerceBatchExecutionCheckpoint(mapper).finish("r","lease","CAPTURE","DETECT","QUEUED","done",now));
  verify(mapper).event(anyString(),eq("r"),eq("CAPTURE"),eq("QUEUED"),eq("done"),eq(now));
 }
}
