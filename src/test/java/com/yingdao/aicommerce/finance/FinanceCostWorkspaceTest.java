package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceCostWorkspaceTest {
    @Test void monthlyCostSheetsKeepBundlePricesAndRecognizeSingleDigitMonths(){
        byte[] data="月份,,202510,20261,20269\n仓库,型号,期末单价,期末单价,期末单价\n菲律宾仓,PACK,1,2,3\n,PACK*5,7,11,16\n".getBytes(StandardCharsets.UTF_8);
        var rows=FinanceCostWorkspace.parseCosts(data,"cost.csv");
        assertEquals(2,rows.size());assertEquals("菲律宾仓",rows.get(1).get("warehouse"));
        assertEquals(new BigDecimal("16"),map(rows.get(1).get("prices")).get("2026-09"));
        assertEquals(Set.of("2025-10","2026-01","2026-09"),map(rows.getFirst().get("prices")).keySet());
        assertThrows(BusinessException.class,()->FinanceCostWorkspace.parseCosts("仓库,型号,月份,单价\nA,P,2026-09,2\nA,P,2026-09,3\n".getBytes(),"cost.csv"));
        assertThrows(BusinessException.class,()->FinanceCostWorkspace.parseCosts("仓库,型号,月份,单价\nA,P,2026-09,[FORMULA]\n".getBytes(),"cost.csv"));
    }
    @Test void suppliedWorkbookCanBeCheckedLocallyWithoutCommittingPrivateData()throws Exception{
        String path=System.getenv("FINANCE_COST_SOURCE");if(path==null)return;
        var rows=FinanceCostWorkspace.parseCosts(Files.readAllBytes(Path.of(path)),Path.of(path).getFileName().toString());
        assertEquals(126,rows.size());assertEquals(12,map(rows.getFirst().get("prices")).size());
    }
    @Test void everyMutationRequiresFinanceManagementAndSameOrigin(){
        var access=mock(OrganizationAccessPolicy.class);
        var service=new FinanceService(mock(FinanceRepository.class),access,mock(FinanceShopAuthorization.class),mock(FinanceFx.class));
        var controller=new FinanceCostController(mock(FinanceCostWorkspace.class),service);
        var viewer=new PlatformPrincipal("viewer","org","org","viewer","viewer","",true,List.of("STAFF"),List.of("finance:view"));
        var request=new MockHttpServletRequest();request.setMethod("GET");controller.authorize(viewer,request);
        request.setMethod("POST");assertThrows(BusinessException.class,()->controller.authorize(viewer,request));
        var manager=new PlatformPrincipal("manager","org","org","manager","manager","",true,List.of("STAFF"),List.of("finance:manage"));
        controller.authorize(manager,request);request.addHeader("Origin","https://foreign.example");assertThrows(BusinessException.class,()->controller.authorize(manager,request));
        assertThrows(BusinessException.class,()->controller.authorize(null,request));
    }
}
