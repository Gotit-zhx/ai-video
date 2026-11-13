package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static com.yingdao.aicommerce.finance.FinanceData.companyScope;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceCostContextTest {
    @Test void contextSelectsAnOwnedShopWithoutReadingTheCostCatalogueOrOrderJson() throws Exception {
        var db=mock(JdbcTemplate.class);
        var repo=new FinanceRepository(db,new ObjectMapper());
        var service=mock(FinanceService.class);
        var scope=companyScope("org");
        when(service.shops(scope)).thenReturn(List.of(
                Map.of("id","shop","group_id","group","group_name","测试组","platform","SHOPEE","region","PH")));
        var workspace=new FinanceCostWorkspace(repo,service);

        Method contextMethod=assertDoesNotThrow(()->FinanceCostWorkspace.class.getMethod("context",FinanceData.Scope.class,String.class,String.class),
                "cost workspace must expose the lightweight context read");
        @SuppressWarnings("unchecked")
        var context=(Map<String,Object>)contextMethod.invoke(workspace,scope,"shop","");

        assertEquals(true,context.get("live"));
        assertEquals(false,context.get("catalogLoaded"));
        assertEquals("shop",context.get("shopId"));
        assertEquals("group",context.get("groupId"));
        assertEquals("测试组",context.get("groupName"));
        assertEquals(false,context.get("settingsLoaded"));
        verify(service).shops(scope);
        verifyNoInteractions(db);
    }

    @Test void controllerExposesTheLightweightContextContract() throws Exception {
        Method endpoint=assertDoesNotThrow(()->FinanceCostController.class.getMethod("costContext",String.class,String.class,
                        com.yingdao.aicommerce.auth.PlatformPrincipal.class),
                "controller must expose the lightweight context endpoint");
        var mapping=endpoint.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/costs/context"},mapping.value());
    }
}
