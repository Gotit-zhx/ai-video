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
    @Test void skuFormattingKeepsBundleIdentityAndRejectsAmbiguousNormalizedCodes(){
        for(String code:List.of("(YUXI-BPants-XL) *2","（YUXI-BPants-XL） × 2"," ( YUXI-BPants-XL *2 ) ","YUXI-BPants-XL＊2"))
            assertEquals("YUXI-BPants-XL*2",FinanceCostWorkspace.normalizedSku(code));
        assertEquals("PACK(A)",FinanceCostWorkspace.normalizedSku("PACK(A)"),"internal variant labels remain meaningful");
        assertNotEquals(FinanceCostWorkspace.normalizedSku("PACK*2"),FinanceCostWorkspace.normalizedSku("PACK*3"));
        var codes=Set.of("PACK","PACK*2");
        var normalized=Map.of("PACK",Set.of("PACK"),"PACK*2",Set.of("PACK*2"));
        assertEquals("PACK*2",FinanceCostWorkspace.matchingCostCode("(PACK) × 2",codes,normalized));
        assertEquals("",FinanceCostWorkspace.matchingCostCode("(PACK)*3",codes,normalized),"never auto-multiply a base price");
        var ambiguous=Map.of("PACK*2",Set.of("PACK*2","(PACK)*2"));
        assertEquals("",FinanceCostWorkspace.matchingCostCode("（PACK） × 2",ambiguous.get("PACK*2"),ambiguous));
        assertEquals("PACK*2",FinanceCostWorkspace.matchingCostCode("PACK*2",ambiguous.get("PACK*2"),ambiguous));
        assertEquals("PACK*2",FinanceCostWorkspace.matchSku("(PACK)*2",codes,normalized).code());
        assertNull(FinanceCostWorkspace.matchSku("(PACK)*2",codes,normalized).bundleQuantity(),"independent bundle model wins");
        var bundle=FinanceCostWorkspace.matchSku("(PACK) *3",codes,normalized);
        assertEquals("PACK",bundle.code());assertEquals(3,bundle.bundleQuantity());
        assertNull(FinanceCostWorkspace.matchSku("(UNKNOWN)*2",codes,normalized));
        assertNull(FinanceCostWorkspace.matchSku("（PACK） × 2",Set.of("PACK","PACK*2","(PACK)*2"),ambiguous),"ambiguous full bundle cannot fall back to base");
    }
    @Test void monthlyCostSheetsKeepBundlePricesAndRecognizeSingleDigitMonths(){
        byte[] data="月份,,202510,20261,20269\n仓库,型号,期末单价,期末单价,期末单价\n菲律宾仓,PACK,1,2,3\n,PACK*5,7,11,16\n".getBytes(StandardCharsets.UTF_8);
        var rows=FinanceCostWorkspace.parseCosts(data,"cost.csv");
        assertEquals(2,rows.size());assertEquals("菲律宾仓",rows.get(1).get("warehouse"));
        assertEquals(new BigDecimal("16"),map(rows.get(1).get("prices")).get("2026-09"));
        assertEquals(Set.of("2025-10","2026-01","2026-09"),map(rows.getFirst().get("prices")).keySet());
        assertThrows(BusinessException.class,()->FinanceCostWorkspace.parseCosts("仓库,型号,月份,单价\nA,P,2026-09,2\nA,P,2026-09,3\n".getBytes(),"cost.csv"));
        assertThrows(BusinessException.class,()->FinanceCostWorkspace.parseCosts("仓库,型号,月份,单价\nA,P,2026-09,[FORMULA]\n".getBytes(),"cost.csv"));
    }
    @Test void identicalMonthlyCostsAreKeptOnceWithoutDiscardingOtherMonthsOrWarehouses(){
        byte[] data=("月份,,20261,20262,20263\n仓库,型号,期末单价,期末单价,期末单价\n"
                +"菲律宾仓,PACK-A+PACK-B+PACK-C,12.34567891,0,\n"
                +",PACK-A+PACK-B+PACK-C,12.345678910,0.00,9.87654321\n"
                +"泰国仓,PACK-A+PACK-B+PACK-C,9,,\n").getBytes(StandardCharsets.UTF_8);
        var rows=FinanceCostWorkspace.parseCosts(data,"cost.csv");
        assertEquals(2,rows.size());
        var prices=map(rows.getFirst().get("prices"));
        assertEquals(3,prices.size());
        assertEquals(new BigDecimal("12.34567891"),prices.get("2026-01"));
        assertEquals(BigDecimal.ZERO,prices.get("2026-02"));
        assertEquals(new BigDecimal("9.87654321"),prices.get("2026-03"));
        assertEquals(new BigDecimal("9"),map(rows.get(1).get("prices")).get("2026-01"));
    }
    @Test void identicalLongFormCostsAreKeptOnceButConflictingPricesStillRequireReview(){
        byte[] identical="仓库,型号,月份,单价\nA,P,2026-09,2.00\nA,P,2026-09,2\n".getBytes(StandardCharsets.UTF_8);
        var rows=FinanceCostWorkspace.parseCosts(identical,"cost.csv");
        assertEquals(1,rows.size());
        assertEquals(Map.of("2026-09",new BigDecimal("2.00")),map(rows.getFirst().get("prices")));
        var conflict=assertThrows(BusinessException.class,()->FinanceCostWorkspace.parseCosts(
                "仓库,型号,月份,单价\nA,P,2026-09,2\nA,P,2026-09,3\n".getBytes(StandardCharsets.UTF_8),"cost.csv"));
        assertTrue(conflict.getMessage().contains("单价不一致"));
        assertTrue(conflict.getMessage().contains("第 3 行"));
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
