package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignGenerationServiceTest {
    final CommerceDesignMapper designs=mock(CommerceDesignMapper.class);
    final CommerceDesignAttemptMapper attempts=mock(CommerceDesignAttemptMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final CommerceDesignReferencePreparer preparer=mock(CommerceDesignReferencePreparer.class);
    final CommerceDesignGenerationCoordinator coordinator=mock(CommerceDesignGenerationCoordinator.class);
    final CommerceDesignResultCollector collector=mock(CommerceDesignResultCollector.class);
    final ModelConfigService models=mock(ModelConfigService.class);
    final CommerceDesignConsentLedger consents=mock(CommerceDesignConsentLedger.class);
    final CommerceDesignGenerationService service=new CommerceDesignGenerationService(designs,attempts,captures,preparer,coordinator,collector,models,new ObjectMapper(),consents,false);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:view","product:edit"));
    void setup(String status){when(designs.get("org","d")).thenReturn(new CommerceDesignDraft("d","org","c",1,"key","hash",1,"STOCK_MARKETING","brief","rights","[]","DRAFT","u",Instant.now()));if(status!=null)when(attempts.byDesign("org","d")).thenReturn(new CommerceDesignAttempt("a","org","d",status,"[{\"imageId\":\"i\",\"storageKey\":\"key\"}]","{}",null,Instant.now(),Instant.now()));}
    CommerceDesignGenerationService.Request request(){return new CommerceDesignGenerationService.Request(List.of("i"),true);}
    @Test void disabledByDefaultDoesNotFetchOrGenerate(){setup(null);assertThrows(BusinessException.class,()->service.generate("d",request(),user));verifyNoInteractions(preparer,coordinator,models,collector);}
    @Test void uncertainAttemptIsNotResubmitted(){setup("UNKNOWN");assertEquals("UNKNOWN",service.generate("d",request(),user).status());verifyNoInteractions(preparer,coordinator,models,collector);}
    @Test void completedProviderResultCanRetryDownloadEvenWhenGenerationDisabled(){setup("RESULT_RECEIVED");service.generate("d",request(),user);verify(collector).collect("org","a");verifyNoInteractions(preparer,coordinator,models);}
    @Test void changedReferencesCannotReuseAttempt(){setup("UNKNOWN");assertThrows(BusinessException.class,()->service.generate("d",new CommerceDesignGenerationService.Request(List.of("other"),true),user));verifyNoInteractions(preparer,coordinator,collector);}
    @Test void explicitConfirmationRequired(){setup(null);assertThrows(BusinessException.class,()->service.generate("d",new CommerceDesignGenerationService.Request(List.of("i"),false),user));verifyNoInteractions(preparer,coordinator,collector);}
    @Test void enabledNewDesignPreparesGeneratesThenCollects(){
        setup(null);var enabled=new CommerceDesignGenerationService(designs,attempts,captures,preparer,coordinator,collector,models,new ObjectMapper(),consents,true);
        var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(1L);when(captures.get("c",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),List.of(),List.of(),Map.of(),Map.of()));
        when(models.imageConnection("org")).thenReturn(Optional.of(new ModelConfigService.RuntimeModelConnection("m","url","model","format","provider","test")));
        var refs=List.of(new CommerceDesignGenerationCoordinator.PreparedReference("i","key"));when(preparer.prepare(any(),anyList())).thenReturn(refs);
        var received=new CommerceDesignAttempt("a","org","d","RESULT_RECEIVED","[]","{}",null,Instant.now(),Instant.now());when(attempts.byDesign("org","d")).thenReturn(null,received,received);
        enabled.generate("d",request(),user);var order=inOrder(preparer,coordinator,collector);order.verify(preparer).prepare(any(),eq(List.of("i")));order.verify(coordinator).execute(any(),eq(refs));order.verify(collector).collect("org","a");
    }
}
