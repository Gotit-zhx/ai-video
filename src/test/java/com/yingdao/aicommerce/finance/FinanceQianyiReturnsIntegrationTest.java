package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceQianyiReturnsIntegrationTest extends FinanceQianyiIntegrationTest {
    @Test void filteredTotalsCoverAllPagesAndKeepUnknownCostsAndCurrenciesSeparate() {
        qianyi();integration.save(owner,shop,form());addOrder("586208660231587825","COMPLETED");
        var first=FinanceQianyiEvidenceTest.receipt();
        first.put("refundStatus","FINISH");first.put("orderRefundAmount","2.5");first.put("currency","PHP");
        page(first,true);publish();
        var sources=new ArrayList<Map<String,Object>>();
        for(int i=1;i<53;i++){
            var source=new LinkedHashMap<>(first);source.put("returnNumber","R"+String.format("%03d",i));
            sources.add(source);
        }
        var refund=new LinkedHashMap<>(first);refund.put("returnNumber","REFUND");refund.put("type","RETURN");
        refund.put("currency","MYR");refund.put("orderRefundAmount","9");sources.add(refund);
        var unknown=new LinkedHashMap<>(first);unknown.put("returnNumber","UNKNOWN");unknown.remove("currency");
        unknown.put("orderRefundAmount","");unknown.put("refundStatus","WAIT_PAYMENT");sources.add(unknown);
        var unpriced=new LinkedHashMap<>(unknown);unpriced.put("returnNumber","UNKNOWN-CURRENCY");unpriced.put("orderRefundAmount","40");sources.add(unpriced);
        integration.acceptPage(lease(true),"test-lease",new FinanceQianyiClient.Page(sources,sources.size()),Map.of(),true);
        var read=new FinanceQianyiReturns(repo,service);
        var filter=new HashMap<String,String>(Map.of("type","SALES_RETURN","refundStatus","FINISH","page","2",
            "dateField","RECEIPT","start","2026-09-01","end","2026-09-30"));
        var result=read.list(owner,filter);var summary=map(result.get("summary"));
        assertEquals(53,number(result.get("total")));assertEquals(3,rows(result.get("items")).size());
        assertEquals(53,number(summary.get("records")));
        assertFalse(summary.containsKey("confirmed_cost_cny"));
        assertFalse(summary.containsKey("pending_count"));
        var money=rows(summary.get("refundTotals"));assertEquals(1,money.size());assertEquals("PHP",money.getFirst().get("currency"));
        equalMoney("132.5",money.getFirst().get("amount"));
        filter.put("refundStatus","WAIT_PAYMENT");
        summary=map(read.list(owner,filter).get("summary"));assertNull(summary.get("confirmed_cost_cny"));
        money=rows(summary.get("refundTotals"));assertEquals("UNKNOWN",money.getFirst().get("currency"));
        assertNull(money.getFirst().get("amount"));assertEquals(1,number(money.getFirst().get("known_count")));
        filter.put("type","RETURN");filter.put("refundStatus","FINISH");
        money=rows(map(read.list(owner,filter).get("summary")).get("refundTotals"));
        assertEquals("MYR",money.getFirst().get("currency"));equalMoney("9",money.getFirst().get("amount"));
        filter.put("start","2026-10-01");filter.put("end","2026-10-31");
        assertEquals(0,number(read.list(owner,filter).get("total")));
        assertEquals(0,number(map(read.list(other,Map.of()).get("summary")).get("records")));
    }
    @Test void exportUsesTheSameNativeFiltersAndKeepsLongOrderNumbersAsText() throws Exception {
        qianyi();integration.save(owner,shop,form());
        var a=FinanceQianyiEvidenceTest.receipt();a.put("refundStatus","FINISH");a.put("orderRefundAmount","8.25");a.put("currency","PHP");page(a,false);
        var b=new LinkedHashMap<>(a);b.put("returnNumber","EXCLUDED");b.put("refundStatus","WAIT_PAYMENT");
        integration.acceptPage(lease(false),"test-lease",new FinanceQianyiClient.Page(List.of(b),1),Map.of(),true);
        var read=new FinanceQianyiReturns(repo,service);
        var bytes=read.export(owner,Map.of("type","SALES_RETURN","refundStatus","FINISH"));
        var entries=new HashMap<String,String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())
                entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var detail=entries.get("xl/worksheets/sheet2.xml");
        assertTrue(detail.contains("586208660231587825"));assertTrue(detail.contains("8.25"));
        assertTrue(detail.contains("退货单"));assertFalse(detail.contains("EXCLUDED"));
        assertTrue(detail.contains("t=\"inlineStr\""));assertTrue(entries.get("xl/worksheets/sheet1.xml").contains("千易售后接口"));
        assertFalse(detail.contains("冲回"));assertFalse(detail.contains("成本状态"));
        assertThrows(RuntimeException.class,()->read.export(other,Map.of("shopId",shop)));
    }
    @Test void disabledCreditsKeepSourceDocumentsAndDoNotScanOrPublishReturnTotals(){
        qianyi();integration.save(owner,shop,form());addOrder("586208660231587825","COMPLETED");
        page(FinanceQianyiEvidenceTest.receipt(),true);publish();
        var summary=new LinkedHashMap<String,Object>(Map.of("total",new LinkedHashMap<>(),"shops",new ArrayList<>()));
        actual.attachReturns(owner,period(9),summary);
        equalMoney("0",map(summary.get("total")).get("return_cost_cny"));
        assertEquals(false,summary.get("returnProcessing"));
        assertEquals(false,summary.get("returnCostReversalEnabled"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_return_costs",Integer.class));
        assertEquals(1,number(new FinanceQianyiReturns(repo,service).list(owner,Map.of()).get("total")));
        db.update("UPDATE finance_qianyi_connections SET lease_token=NULL,lease_until=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)");
        db.update("UPDATE finance_qianyi_returns SET cost_status='PENDING',next_at=UTC_TIMESTAMP(6)");
        var worker=new FinanceQianyiWorker(integration);
        try{worker.tick();assertEquals("PENDING",document().get("cost_status"));}
        finally{worker.stop();}
    }
}
