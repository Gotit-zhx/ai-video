package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceBatchDesignAssetServiceTest {
    final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
    final CommerceBatchDesignAssetMapper assets=mock(CommerceBatchDesignAssetMapper.class);
    final CommerceBatchReviewMapper batchReviews=mock(CommerceBatchReviewMapper.class);
    final CommerceDesignMapper designs=mock(CommerceDesignMapper.class);
    final CommerceDesignOutputMapper outputs=mock(CommerceDesignOutputMapper.class);
    final CommerceDesignReviewMapper reviews=mock(CommerceDesignReviewMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final CommerceImageArchive archive=mock(CommerceImageArchive.class);
    final CommerceBatchDesignAssetService service=new CommerceBatchDesignAssetService(batches,assets,batchReviews,designs,outputs,reviews,templates,captures,archive,new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:view","product:publish"));
    final Instant now=Instant.now();
    final String hash="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    CommerceBatchDesignAssetService.Request request(){return new CommerceBatchDesignAssetService.Request("item","design",hash,1,true);}
    void setup(){
        when(batches.lock("org","batch")).thenReturn(new CommerceBatch("batch","org","name","template","key","hash","DRAFT","u",now));
        when(batches.items("org","batch")).thenReturn(List.of(new CommerceBatchItem("item","org","batch","capture",4,"shop","shop",1,"DRAFT",now)));
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(4L);
        when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(),List.of(),Map.of(),Map.of()));
        when(designs.get("org","design")).thenReturn(new CommerceDesignDraft("design","org","capture",4,"key","hash",1,"STOCK_MARKETING","brief","rights","[]","DRAFT","u",now));
        when(templates.snapshot("org","template")).thenReturn(new CommerceTemplateVersion("template","org","t",1,"WORKFLOW","name","{\"mode\":\"STOCK_MARKETING\"}","u",now));
        when(outputs.byDesign("org","design")).thenReturn(new CommerceDesignOutput("output","org","design","attempt","storage",hash,"image/png",5,"QUALITY_APPROVED",now));
        when(reviews.get("org","output")).thenReturn(new CommerceDesignReview("review","org","output",hash,"APPROVE","{}","notes","u",now));
        when(archive.read("org","storage")).thenReturn(new CommerceDesignImageGateway.Reference("image/png","aGVsbG8="));
    }
    @Test void bindsVerifiedContentAndReviewerWithoutPublishing(){setup();var asset=service.bind("batch",request(),user);assertEquals(hash,asset.contentSha256());assertEquals("review",asset.reviewId());assertEquals("item",asset.itemId());verify(assets).insert(asset);}
    @Test void identicalRetryReturnsOriginalBinding(){setup();var first=service.bind("batch",request(),user);when(assets.list("org","batch")).thenReturn(List.of(first));assertSame(first,service.bind("batch",request(),user));verify(assets,times(1)).insert(any());}
    @Test void reviewedBatchCannotGainNewAssets(){setup();when(batchReviews.get("org","batch")).thenReturn(new CommerceBatchReview("r","org","batch","template","[]","u",now));assertThrows(BusinessException.class,()->service.bind("batch",request(),user));verify(assets,never()).insert(any());}
    @Test void modifiedArchiveIsRejected(){setup();when(archive.read("org","storage")).thenReturn(new CommerceDesignImageGateway.Reference("image/png","Y2hhbmdlZA=="));assertThrows(BusinessException.class,()->service.bind("batch",request(),user));verify(assets,never()).insert(any());}
    @Test void wrongVersionIsRejected(){setup();when(designs.get("org","design")).thenReturn(new CommerceDesignDraft("design","org","capture",3,"key","hash",1,"STOCK_MARKETING","brief","rights","[]","DRAFT","u",now));assertThrows(BusinessException.class,()->service.bind("batch",request(),user));verifyNoInteractions(archive);}
    @Test void approvalMustMatchImageHash(){setup();when(reviews.get("org","output")).thenReturn(new CommerceDesignReview("r","org","output","wrong","APPROVE","{}","notes","u",now));assertThrows(BusinessException.class,()->service.bind("batch",request(),user));verifyNoInteractions(archive);}
    @Test void foreignBatchDoesNotLeakExistence(){assertThrows(BusinessException.class,()->service.bind("batch",request(),user));verifyNoInteractions(designs,outputs,assets);}
    @Test void explicitConfirmationRequired(){assertThrows(BusinessException.class,()->service.bind("batch",new CommerceBatchDesignAssetService.Request("item","design",hash,1,false),user));verifyNoInteractions(batches);}
}
