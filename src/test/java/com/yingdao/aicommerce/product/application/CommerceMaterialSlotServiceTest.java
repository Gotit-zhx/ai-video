package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceMaterialSlotServiceTest {
 final CommerceDesignMapper designs=mock(CommerceDesignMapper.class);
 final CommerceDesignOutputMapper outputs=mock(CommerceDesignOutputMapper.class);
 final CommerceMaterialSlotService service=new CommerceMaterialSlotService(designs,outputs);
 final PlatformPrincipal user=new PlatformPrincipal("u","org","c","u","name","",true,List.of(),List.of("product:view"));
 CommerceDesignDraft row(String id,int index){return new CommerceDesignDraft(id,"org","source",3,"request","hash",index,"POD_DESIGN","brief","rights","[]","DRAFT","u",Instant.EPOCH);}
 @Test void slotIncludesAllVariantsAndActualArchiveStatus(){
  var first=row("a",1);when(designs.get("org","a")).thenReturn(first);when(designs.byRequest("org","request")).thenReturn(List.of(row("b",2),first));
  when(outputs.byDesign("org","b")).thenReturn(new CommerceDesignOutput("o","org","b","attempt","key","hash","image/png",10,"PENDING_REVIEW",Instant.EPOCH));
  var slot=service.get("a",user);assertEquals(2,slot.images().size());assertEquals("NOT_GENERATED",slot.images().getFirst().status());assertEquals("PENDING_REVIEW",slot.images().get(1).status());assertEquals("a",slot.id());
 }
 @Test void foreignSlotIsNotVisible(){assertThrows(BusinessException.class,()->service.get("foreign",user));verifyNoInteractions(outputs);}
 @Test void paginationIsByCompleteRequestNotByIndividualImages(){when(designs.slotIds("org",null,20,21)).thenReturn(List.of());assertEquals(1,service.list(1,user).page());verify(designs).slotIds("org",null,20,21);}
 @Test void noUnboundedOrNegativePage(){assertThrows(BusinessException.class,()->service.list(-1,user));verifyNoInteractions(designs);}
}
