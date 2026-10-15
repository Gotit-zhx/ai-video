package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.CommerceDesignMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignServiceTest {
    final CommerceDesignMapper mapper=mock(CommerceDesignMapper.class);
    final ProductCaptureService captures=mock(ProductCaptureService.class);
    final CommerceDesignService service=new CommerceDesignService(mapper,captures,new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:view","product:edit"));
    CommerceDesignService.Request request(int variants){return new CommerceDesignService.Request("capture",1,"STOCK_MARKETING",variants,"仅改变背景，保留衣服结构","授权记录1");}
    void setup(long version,boolean images){var capture=mock(ProductCapture.class);when(capture.getVersion()).thenReturn(version);when(captures.get("capture",user)).thenReturn(new ProductCaptureService.CaptureDetail(capture,List.of(),images?List.of(new ProductCaptureImage("org","capture","https://example.com/image.jpg","MAIN","",0,"image/jpeg","{}")):List.of(),List.of(),Map.of(),Map.of()));}
    @Test void tenDraftsPinSourceWithoutClaimingGeneratedOutput(){setup(1,true);var rows=service.create(request(10),"request_123",user);assertEquals(10,rows.size());assertEquals(10,rows.stream().map(CommerceDesignDraft::id).distinct().count());assertTrue(rows.stream().allMatch(r->r.status().equals("DRAFT")&&r.captureVersion()==1&&r.sourceImagesJson().contains("originalUrl")));verify(mapper,times(10)).insert(any());}
    @Test void replayReturnsSameDrafts(){setup(1,true);var rows=service.create(request(2),"request_123",user);when(mapper.byRequest("org","request_123")).thenReturn(rows);assertEquals(rows,service.create(request(2),"request_123",user));verify(mapper,times(2)).insert(any());}
    @Test void reusedKeyCannotChangeVariantCount(){setup(1,true);var rows=service.create(request(2),"request_123",user);when(mapper.byRequest("org","request_123")).thenReturn(rows);assertThrows(BusinessException.class,()->service.create(request(3),"request_123",user));}
    @Test void staleProductRejected(){setup(2,true);assertThrows(BusinessException.class,()->service.create(request(2),"request_123",user));verify(mapper,never()).insert(any());}
    @Test void missingReferenceRejected(){setup(1,false);assertThrows(BusinessException.class,()->service.create(request(2),"request_123",user));verify(mapper,never()).insert(any());}
    @Test void variantLimitEnforced(){assertThrows(BusinessException.class,()->service.create(request(11),"request_123",user));verifyNoInteractions(captures,mapper);}
    @Test void replayReadsOnlyThePrivateCopyAfterOriginalCollectionWasDeleted(){
        setup(1,true);
        var privateCapture=mock(ProductCapture.class);when(privateCapture.getId()).thenReturn("private-copy");
        when(privateCapture.getVersion()).thenReturn(0L);
        var sourceCopies=mock(CommerceSourceCopyService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"sourceCopies",sourceCopies);
        when(sourceCopies.copy(anyString(),eq("capture"),eq(user),eq("FISSION"))).thenReturn(privateCapture);
        when(captures.get("private-copy",user)).thenReturn(new ProductCaptureService.CaptureDetail(privateCapture,List.of(),
                List.of(new ProductCaptureImage("org","private-copy","https://example.com/image.jpg","MAIN","",0,"image/jpeg","{}")),
                List.of(),Map.of(),Map.of()));
        var rows=service.create(request(2),"request_123",user);
        when(mapper.byRequest("org","request_123")).thenReturn(rows);
        when(captures.get("capture",user)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"Deleted"));
        clearInvocations(captures);
        assertEquals(rows,service.create(request(2),"request_123",user));
        verify(captures,never()).get("capture",user);
        verify(captures).get("private-copy",user);
    }
}
