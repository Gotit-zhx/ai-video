package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceAdTemplateServiceTest {
    final CommerceTemplateMapper mapper=mock(CommerceTemplateMapper.class);
    final CommerceAdTemplateService service=new CommerceAdTemplateService(mapper,new CommerceAdEvaluator(),new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","name","x",true,List.of(),List.of("product:view","product:edit"));
    final CommerceAdTemplateService.Policy policy=new CommerceAdTemplateService.Policy("PHP",7,1000,5,new BigDecimal("7000"),new BigDecimal("5"),new BigDecimal("6.5"),new BigDecimal("9"));
    @Test void savesPolicyWithoutPerformanceOrAccountData(){var v=service.save(new CommerceAdTemplateService.Save(null,0,"规则",policy),user);assertEquals(policy,v.policy());assertEquals(1,v.version());verify(mapper).insert(argThat(r->r.kind().equals("AD_TEST")&&!r.configJson().contains("attributedGmv")));}
    @Test void tenantAndKindFilterApplied(){service.list(user);verify(mapper).listKind("org","AD_TEST");}
    @Test void foreignTemplateCannotBeUpdated(){assertThrows(BusinessException.class,()->service.save(new CommerceAdTemplateService.Save(UUID.randomUUID().toString(),1,"x",policy),user));verify(mapper,never()).insert(any());}
    @Test void workflowCannotBecomeAdTemplate(){String id=UUID.randomUUID().toString();var row=new CommerceTemplateVersion("s","org",id,1,"WORKFLOW","x","{}","u",Instant.now());when(mapper.lockRoot("org",id)).thenReturn(row);when(mapper.latest("org",id)).thenReturn(row);assertThrows(BusinessException.class,()->service.save(new CommerceAdTemplateService.Save(id,1,"x",policy),user));verify(mapper,never()).insert(any());}
    @Test void staleVersionCannotOverwrite(){String id=UUID.randomUUID().toString();var row=new CommerceTemplateVersion("s","org",id,2,"AD_TEST","x","{}","u",Instant.now());when(mapper.lockRoot("org",id)).thenReturn(row);when(mapper.latest("org",id)).thenReturn(row);assertThrows(BusinessException.class,()->service.save(new CommerceAdTemplateService.Save(id,1,"x",policy),user));verify(mapper,never()).insert(any());}
}
