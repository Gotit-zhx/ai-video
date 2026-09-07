package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceTemplateServiceTest {
    private final CommerceTemplateMapper mapper=mock(CommerceTemplateMapper.class);
    private final ObjectMapper json=new ObjectMapper();
    private final CommerceTemplateService service=new CommerceTemplateService(mapper,new CommerceBatchPlanner(),json);
    private final PlatformPrincipal user=new PlatformPrincipal("u1","org1","org","login","User","x",true,
            List.of(),List.of("product:view","product:edit"));
    private CommerceBatchPlanner.Request config() {
        return new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,
                CommerceBatchPlanner.Platform.TIKTOK_SHOP,"PH",30,10,1,6,50,
                true,true,true,new BigDecimal("1000"),7,new BigDecimal("6.5"));
    }
    @Test void createsImmutableVersionAndClearsBatchDeclarations() {
        var v=service.save(new CommerceTemplateService.SaveRequest(null,0,"PH测款",config()),user);
        assertEquals(1,v.version()); assertFalse(v.configuration().rightsConfirmed());
        assertFalse(v.configuration().supplyConfirmed()); assertFalse(v.configuration().productionFilesConfirmed());
        verify(mapper).insert(argThat(t->t.organizationId().equals("org1") && t.createdBy().equals("u1") && t.version()==1));
    }
    @Test void onlyReadsCurrentOrganization() {
        when(mapper.list("org1")).thenReturn(List.of());
        assertTrue(service.list(user).isEmpty()); verify(mapper).list("org1");
    }
    @Test void foreignTemplateCannotBeUpdated() {
        String id=UUID.randomUUID().toString();
        var error=assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(id,1,"test",config()),user));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatus());
        verify(mapper).lockRoot("org1",id); verify(mapper,never()).insert(any());
    }
    @Test void staleVersionRejectedWithoutOverwritingHistory() {
        String id=UUID.randomUUID().toString();
        var current=new CommerceTemplateVersion(UUID.randomUUID().toString(),"org1",id,2,"WORKFLOW","test",
                json.writeValueAsString(config()),"u1",Instant.now());
        when(mapper.lockRoot("org1",id)).thenReturn(current);
        when(mapper.latest("org1",id)).thenReturn(current);
        var e=assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(id,1,"test",config()),user));
        assertEquals(HttpStatus.CONFLICT,e.getStatus()); verify(mapper,never()).insert(any());
    }
    @Test void matchingVersionAppendsSnapshot() {
        String id=UUID.randomUUID().toString();
        var current=new CommerceTemplateVersion(UUID.randomUUID().toString(),"org1",id,1,"WORKFLOW","test",
                json.writeValueAsString(config()),"u1",Instant.now());
        when(mapper.lockRoot("org1",id)).thenReturn(current); when(mapper.latest("org1",id)).thenReturn(current);
        var v=service.save(new CommerceTemplateService.SaveRequest(id,1,"new",config()),user);
        assertEquals(2,v.version()); assertEquals(id,v.templateId()); assertNotEquals(current.id(),v.snapshotId());
    }
    @Test void readOnlyUserCannotSave() {
        var viewer=new PlatformPrincipal("u1","org1","org","login","User","x",true,List.of(),List.of("product:view"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(null,0,"test",config()),viewer)).getStatus());
        verifyNoInteractions(mapper);
    }
}
