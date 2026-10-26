package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceOrderChargesTest {
    @Test void sourceAmountNeverBecomesRmbAndDuplicateHeadersRemainPositional(){
        var rows=FinanceOrderCharges.parse(("订单编号,店铺,实际订单金额,代刷费CNY,结算月份,结算月份\n"
            +"260803D3AD6AN6,Yuxi Mall.TH,608,7,#N/A,\n").getBytes(StandardCharsets.UTF_8),"cost.csv");
        assertEquals(new BigDecimal("608"),rows.getFirst().get("principalNative"));
        assertEquals(new BigDecimal("7"),rows.getFirst().get("serviceFeeCny"));
        assertEquals(true,rows.getFirst().get("dateWarning"));
        assertEquals(6,((java.util.List<?>)rows.getFirst().get("headers")).size());
    }
    @Test void missingAmountsAndDuplicateOrdersCannotBeSilentlyImported(){
        for(String line:java.util.List.of("a,Shop,100,\n","a,Shop,100,-7\n","a,Shop,100,7\n"+"a,Shop,100,7\n","1.234E+17,Shop,100,7\n"))
            assertThrows(RuntimeException.class,()->FinanceOrderCharges.parse(("订单编号,店铺,实际订单金额,代刷费CNY\n"+line).getBytes(StandardCharsets.UTF_8),"cost.csv"));
    }
    @Test void suppliedWorkbookPreservesAllTwentyFiveRecords()throws Exception{
        String configured=System.getenv("FINANCE_QA_COST_FILE");
        Assumptions.assumeTrue(configured!=null);
        var rows=FinanceOrderCharges.parse(Files.readAllBytes(Path.of(configured)),"母婴刷单费.xlsx");
        assertEquals(25,rows.size());
        assertEquals(0,rows.stream().map(r->decimal(r.get("serviceFeeCny"))).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(new BigDecimal("175")));
        assertEquals(1,rows.stream().filter(r->Boolean.TRUE.equals(r.get("dateWarning"))).count());
    }
}
