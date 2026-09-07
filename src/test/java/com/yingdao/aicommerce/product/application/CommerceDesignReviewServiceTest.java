package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignReviewServiceTest {
    final CommerceDesignMapper designs=mock(CommerceDesignMapper.class);
    final CommerceDesignOutputMapper outputs=mock(CommerceDesignOutputMapper.class);
    final CommerceDesignReviewMapper reviews=mock(CommerceDesignReviewMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceDesignReviewService service=new CommerceDesignReviewService(designs,outputs,reviews,captures,archive,new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:view","product:edit"));
    void setup(){when(designs.get("org","d")).thenReturn(new CommerceDesignDraft("d","org","c",1,"k","h",1,"STOCK_MARKETING","b","r","[]","DRAFT","u",Instant.now()));var output=new CommerceDesignOutput("o","org","d","a","key","hash","image/png",1,"PENDING_REVIEW",Instant.now());when(outputs.byDesign("org","d")).thenReturn(output);when(outputs.lock("org","o")).thenReturn(output);when(outputs.reviewed(eq("org"),eq("o"),anyString())).thenReturn(1);}
    CommerceDesignReviewService.Request request(String decision,String hash,boolean checked){return new CommerceDesignReviewService.Request(hash,decision,new CommerceDesignReviewService.Checks(checked,checked,checked,checked),"人工复核记录",true);}
    @Test void approveRecordsReviewerAndChecksArchive(){setup();var review=service.review("d",request("APPROVE","hash",true),user);assertEquals("u",review.reviewedBy());verify(archive).read("org","key");verify(outputs).reviewed("org","o","QUALITY_APPROVED");}
    @Test void failedChecksCannotBeApproved(){setup();assertThrows(BusinessException.class,()->service.review("d",request("APPROVE","hash",false),user));verifyNoInteractions(reviews);}
    @Test void rejectionIsRecordedWithoutRequiringPassingChecks(){setup();service.review("d",request("REJECT","hash",false),user);verify(outputs).reviewed("org","o","REJECTED");}
    @Test void staleImageHashRejected(){setup();assertThrows(BusinessException.class,()->service.review("d",request("APPROVE","changed",true),user));verify(reviews,never()).insert(any());}
    @Test void foreignDesignCannotRevealOutput(){assertThrows(BusinessException.class,()->service.view("d",user));verifyNoInteractions(outputs,archive);}
}
