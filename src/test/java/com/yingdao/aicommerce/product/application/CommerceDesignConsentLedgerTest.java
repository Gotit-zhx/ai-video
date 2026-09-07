package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDesignConsentLedgerTest {
    final CommerceDesignMapper designs=mock(CommerceDesignMapper.class);final CommerceDesignConsentMapper consents=mock(CommerceDesignConsentMapper.class);
    final CommerceDesignConsentLedger ledger=new CommerceDesignConsentLedger(designs,consents,new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:edit"));
    final ModelConfigService.RuntimeModelConnection model=new ModelConfigService.RuntimeModelConnection("m","https://model.example","image-model","IMAGES","provider","secret-not-stored");
    void setup(){when(designs.lock("org","d")).thenReturn(new CommerceDesignDraft("d","org","c",1,"k","h",1,"STOCK_MARKETING","b","r","[{\"imageId\":\"i\"},{\"imageId\":\"other\"}]","DRAFT","u",Instant.now()));}
    @Test void recordsActorAndModelWithoutCredentials(){setup();var c=ledger.record("d",List.of("i"),model,user);assertEquals("u",c.confirmedBy());assertEquals("image-model",c.modelId());assertFalse(new ObjectMapper().writeValueAsString(c).contains("secret-not-stored"));verify(consents).insert(c);}
    @Test void retryPreservesOriginalConfirmation(){setup();var c=ledger.record("d",List.of("i"),model,user);when(consents.get("org","d")).thenReturn(c);assertSame(c,ledger.record("d",List.of("i"),model,user));verify(consents,times(1)).insert(any());}
    @Test void changedReferencesRequireNewDesign(){setup();var c=ledger.record("d",List.of("i"),model,user);when(consents.get("org","d")).thenReturn(c);assertThrows(BusinessException.class,()->ledger.record("d",List.of("other"),model,user));}
    @Test void foreignDesignCannotRecordConsent(){assertThrows(BusinessException.class,()->ledger.record("d",List.of("i"),model,user));verifyNoInteractions(consents);}
}
