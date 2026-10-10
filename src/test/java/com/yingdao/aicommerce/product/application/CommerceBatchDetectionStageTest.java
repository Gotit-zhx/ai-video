package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CommerceBatchDetectionStageTest {
 final CommerceBatchDetectionService checks=mock(CommerceBatchDetectionService.class);
 final CommerceBatchDetectionStage stage=new CommerceBatchDetectionStage(checks);
 final PlatformPrincipal user=new PlatformPrincipal("u","org","c","u","u","",true,List.of(),List.of("product:publish"));
 final CommerceBatchExecution run=new CommerceBatchExecution("run","org","batch","u","DETECT","RUNNING","","lease",Instant.now().plusSeconds(30),Instant.now(),Instant.now());
 void report(List<String> issues){when(checks.check("batch",user)).thenReturn(new CommerceBatchDetectionService.Report("batch",Instant.now(),List.of(new CommerceBatchDetectionService.Item("capture",1,"sample",2,3,issues)),false));}
 @Test void validDataAdvancesButDoesNotClaimVisualApproval(){report(List.of());var result=stage.execute(run,user);assertTrue(result.complete());assertTrue(result.message().contains("不代表视觉质量"));assertEquals(CommerceBatchExecution.Stage.EXTRACT,stage.stage().next());}
 @Test void changedSourceStopsWithActionableIssue(){report(List.of("来源版本已变化，需重新固定批次来源"));var result=stage.execute(run,user);assertFalse(result.complete());assertTrue(result.message().contains("来源版本已变化"));}
 @Test void publicationFieldsDoNotBlockExtraction(){report(List.of("SKU 价格","商品图片未处理"));assertTrue(stage.execute(run,user).complete());}
 @Test void missingImagesStopsExtraction(){when(checks.check("batch",user)).thenReturn(new CommerceBatchDetectionService.Report("batch",Instant.now(),List.of(new CommerceBatchDetectionService.Item("capture",1,"sample",0,3,List.of())),false));assertFalse(stage.execute(run,user).complete());}
 @Test void emptyBatchCannotPass(){when(checks.check("batch",user)).thenReturn(new CommerceBatchDetectionService.Report("batch",Instant.now(),List.of(),false));assertFalse(stage.execute(run,user).complete());}
 @Test void differentCompanyCannotReadBatch(){var other=new PlatformPrincipal("u","other","c","u","u","",true,List.of(),List.of("product:publish"));assertFalse(stage.execute(run,other).complete());verifyNoInteractions(checks);}
 @Test void accessFailureIsNotConvertedToSuccess(){when(checks.check("batch",user)).thenThrow(new IllegalStateException("access changed"));assertThrows(IllegalStateException.class,()->stage.execute(run,user));}
}
