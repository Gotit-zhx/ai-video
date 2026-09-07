package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceBatchReviewServiceTest {
    final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final CommerceBatchReviewMapper reviews=mock(CommerceBatchReviewMapper.class);
    final CommerceBatchPreflightService preflight=mock(CommerceBatchPreflightService.class);
    final ObjectMapper json=new ObjectMapper();
    final CommerceBatchReviewService service=new CommerceBatchReviewService(batches,templates,reviews,preflight,json);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:publish"));
    void setup(){
        when(batches.lock("org","b")).thenReturn(new CommerceBatch("b","org","name","snapshot","key","hash","DRAFT","u",Instant.now()));
        when(batches.items("org","b")).thenReturn(List.of(new CommerceBatchItem("i","org","b","c",1,"s","shop",1,"DRAFT",Instant.now())));
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,1,50,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE);
        when(templates.snapshot("org","snapshot")).thenReturn(new CommerceTemplateVersion("snapshot","org","template",1,"WORKFLOW","name",json.writeValueAsString(config),"u",Instant.now()));
        when(preflight.check("b",user)).thenReturn(new CommerceBatchPreflightService.Report("b",Instant.now(),false,1,1,List.of(),List.of()));
    }
    CommerceBatchReviewService.Request request(long version,String production){return new CommerceBatchReviewService.Request(true,List.of(new CommerceBatchReviewService.Evidence("i",version,"授权记录A","供应商SKU1","人工复核记录Q",production)));}
    @Test void recordsHumanEvidenceWithoutExecuting(){setup();var r=service.submit("b",request(1,"生产单P"),user);assertEquals("u",r.reviewedBy());assertEquals("snapshot",r.templateSnapshotId());verify(reviews).insert(r);}
    @Test void podRequiresProductionEvidence(){setup();assertThrows(BusinessException.class,()->service.submit("b",request(1,""),user));verify(reviews,never()).insert(any());}
    @Test void staleVersionRejected(){setup();assertThrows(BusinessException.class,()->service.submit("b",request(2,"P"),user));verify(reviews,never()).insert(any());}
    @Test void preflightFailureBlocksReview(){setup();when(preflight.check("b",user)).thenReturn(new CommerceBatchPreflightService.Report("b",Instant.now(),false,1,0,List.of(),List.of()));assertThrows(BusinessException.class,()->service.submit("b",request(1,"P"),user));}
    @Test void unknownBatchCannotBeReviewed(){assertThrows(BusinessException.class,()->service.submit("b",request(1,"P"),user));verifyNoInteractions(reviews);}
    @Test void normalizedDatabaseJsonRemainsIdempotent(){
        setup();
        var existing=new CommerceBatchReview("r","org","b","snapshot","""
            [{"productionEvidence": "P", "qualityReview": "人工复核记录Q", "supplySku": "供应商SKU1",
              "rightsEvidence": "授权记录A", "captureVersion": 1, "itemId": "i"}]
            ""","u",Instant.now());
        when(reviews.get("org","b")).thenReturn(existing);
        assertSame(existing,service.submit("b",request(1,"P"),user));
        verify(reviews,never()).insert(any());
        assertThrows(BusinessException.class,()->service.submit("b",request(1,"changed"),user));
    }
}
