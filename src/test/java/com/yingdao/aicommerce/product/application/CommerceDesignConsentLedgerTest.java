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
    @Test void changedEndpointWithSameModelIdRequiresNewDesign(){
        setup();var original=ledger.record("d",List.of("i"),model,user);when(consents.get("org","d")).thenReturn(original);
        var changed=new ModelConfigService.RuntimeModelConnection(model.id(),"https://different.example",model.modelId(),model.apiFormat(),model.provider(),model.apiKey());
        assertThrows(BusinessException.class,()->ledger.record("d",List.of("i"),changed,user));
    }
    @Test void keyRotationDoesNotPersistCredentialsOrChangeFingerprint(){
        setup();var original=ledger.record("d",List.of("i"),model,user);when(consents.get("org","d")).thenReturn(original);
        var rotated=new ModelConfigService.RuntimeModelConnection(model.id(),model.baseUrl(),model.modelId(),model.apiFormat(),model.provider(),"rotated-test-secret");
        assertSame(original,ledger.record("d",List.of("i"),rotated,user));
        assertEquals(64,original.modelConfigurationSha256().length());
        assertFalse(new ObjectMapper().writeValueAsString(original).contains("rotated-test-secret"));
    }
    @Test void legacyConsentIsNotSilentlyAssumedToMatch(){
        setup();when(consents.get("org","d")).thenReturn(new CommerceDesignConsent("old","org","d",1,"[\"i\"]",model.id(),model.modelId(),"u",Instant.now(),null));
        assertThrows(BusinessException.class,()->ledger.record("d",List.of("i"),model,user));
        verify(consents,never()).insert(any());
    }
    @Test void changedModelCannotReusePreviousCostConfirmation(){
        setup();var original=ledger.record("d",List.of("i"),model,user);when(consents.get("org","d")).thenReturn(original);
        var changed=new ModelConfigService.RuntimeModelConnection("m",model.baseUrl(),"different-image-model",model.apiFormat(),model.provider(),model.apiKey());
        assertThrows(BusinessException.class,()->ledger.record("d",List.of("i"),changed,user));
        verify(consents,times(1)).insert(any());
    }
}
