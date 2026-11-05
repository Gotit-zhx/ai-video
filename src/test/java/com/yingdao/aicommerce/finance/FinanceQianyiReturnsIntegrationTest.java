package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceQianyiReturnsIntegrationTest extends FinanceQianyiIntegrationTest {
    @Test void receiptScanFinishesWithEmptyResumeAndRetainsRealResumePage(){
        qianyi();integration.save(owner,shop,form());
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        when(upstream.query(any(),eq("QUERY_RETURN_ORDER_LIST"),anyMap()))
            .thenReturn(new FinanceQianyiClient.Page(List.of(),0));
        var worker=new FinanceQianyiWorker(integration);
        try{
            for(var resume:List.of(Map.<String,Object>of(),
                    Map.<String,Object>of("from","2026-08-01","until","2026-10-31","page",8,"sourceVersion",3))){
                var scan=new LinkedHashMap<String,Object>(Map.of("from",today.toString(),"until",today.toString(),
                    "page",1,"sourceVersion",3,"receiptScan",true,"backfillResume",resume));
                db.update("UPDATE finance_qianyi_connections SET checkpoint_json=?,source_version=3 WHERE shop_id=?",repo.write(scan),shop);
                worker.readReturns(lease(false),"test-lease");
                var current=integration.connection(owner,shop);
                if(resume.isEmpty()){
                    assertEquals("SUCCESS",current.get("status"));assertNull(current.get("checkpoint_json"));
                }else{
                    assertEquals("SYNCING",current.get("status"));
                    assertEquals(resume,repo.read(text(current.get("checkpoint_json"))));
                }
                assertNotNull(current.get("last_receipt_scan_at"));
            }
        }finally{worker.stop();}
    }
    @Test void markerOnlyFailedCheckpointRecoversWithoutDeletingSourceRows(){
        qianyi();integration.save(owner,shop,form());
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        db.update("""
            UPDATE finance_qianyi_connections SET source_version=3,last_success_at=UTC_TIMESTAMP(6),
              last_receipt_scan_at=UTC_TIMESTAMP(6),checkpoint_json='{"sourceVersion":3,"sourcesChanged":true}',
              status='ERROR',failures=22 WHERE shop_id=?
            """,shop);
        when(upstream.query(any(),eq("QUERY_RETURN_ORDER_LIST"),anyMap())).thenAnswer(call->{
            var params=map(call.getArgument(2));
            assertEquals(today.minusDays(2).toString(),params.get("updateTimeFrom"));
            assertEquals(1,number(params.get("page")));
            return new FinanceQianyiClient.Page(List.of(),0);
        });
        var worker=new FinanceQianyiWorker(integration);
        try{
            worker.readReturns(lease(false),"test-lease");
            var current=integration.connection(owner,shop);
            assertEquals("SUCCESS",current.get("status"));assertNull(current.get("checkpoint_json"));
            assertEquals(0,number(current.get("failures")));
        }finally{worker.stop();}
    }
    @Test void returnedCostCoversAllPagesMatchesExportAndNeverRestoresProfitCredits() throws Exception {
        qianyi();integration.save(owner,shop,form());
        var sources=new ArrayList<Map<String,Object>>();
        for(int i=0;i<53;i++){
            var s=FinanceQianyiReceiptTest.qualified();s.put("returnNumber","COST-"+String.format("%03d",i));
            s.put("currency","PHP");s.put("orderRefundAmount","100");
            var lines=new ArrayList<Map<String,Object>>();
            lines.add(Map.of("sku","PACK","quantity",1,"orderSkuId","one","selected",1));
            if(i==52)lines.add(Map.of("sku","NO-COST","quantity",1,"orderSkuId","two","selected",1));
            s.put("returnSkuList",lines);sources.add(s);
        }
        var unqualified=new LinkedHashMap<>(sources.getFirst());unqualified.put("returnNumber","NOT-SIGNED");
        unqualified.remove(FinanceQianyiReceipt.FIELD);sources.add(unqualified);
        // Batch the fixture so a high-latency QA tunnel does not consume a sync transaction's deadline.
        db.batchUpdate("""
            INSERT INTO finance_qianyi_returns(organization_id,shop_id,return_number,account_key,order_id,source_updated_at,source_hash,data_json)
            VALUES(?,?,?,'qa-account',?,?,?,?)
            """,sources.stream().map(s->new Object[]{owner.organizationId(),shop,s.get("returnNumber"),s.get("onlineOrderNumber"),
                s.get("updateTime"),hash(repo.write(s)),repo.write(s)}).toList());
        var read=new FinanceQianyiReturns(repo,service);
        var filter=new HashMap<String,String>(Map.of("shopId",shop,"start","2026-09-01","end","2026-09-30","page","2"));
        var list=read.list(owner,filter);var total=map(map(list.get("summary")).get("returnCost"));
        equalMoney("530",total.get("knownAmountCny"));assertNull(total.get("amountCny"));
        assertEquals(52,number(total.get("completeRecords")));assertEquals(1,number(total.get("pendingRecords")));
        assertEquals(3,rows(list.get("items")).size());assertFalse(rows(list.get("items")).getFirst().containsKey("data_json"));
        equalMoney("5300",rows(map(list.get("summary")).get("refundTotals")).getFirst().get("amount"));
        var sheet=FinanceCostExportTest.sheet(read.export(owner,filter),1);
        equalMoney("530",FinanceCostExportTest.summaryValue(sheet,"退货成本已匹配金额（CNY）"));
        assertEquals("1",FinanceCostExportTest.summaryValue(sheet,"退货成本待补齐单据"));
        var detail=map(read.detail(owner,shop,"COST-052").get("returnCost"));
        assertNull(detail.get("amountCny"));equalMoney("10",detail.get("knownAmountCny"));
        filter.put("numbers","COST-000");filter.put("page","1");
        total=map(map(read.list(owner,filter).get("summary")).get("returnCost"));equalMoney("10",total.get("amountCny"));
        filter.put("receiptScope","ALL");filter.put("numbers","NOT-SIGNED");
        list=read.list(owner,filter);total=map(map(list.get("summary")).get("returnCost"));
        equalMoney("0",total.get("amountCny"));assertFalse(rows(list.get("items")).getFirst().containsKey("returnCost"));
        total=map(map(read.list(other,Map.of("start","2026-09-01","end","2026-09-30")).get("summary")).get("returnCost"));
        equalMoney("0",total.get("amountCny"));assertThrows(RuntimeException.class,()->read.detail(other,shop,"COST-000"));
        var summary=new LinkedHashMap<String,Object>(Map.of("total",new LinkedHashMap<>()));
        actual.attachReturns(owner,period(9),summary);equalMoney("0",map(summary.get("total")).get("return_cost_cny"));
        assertEquals(false,summary.get("returnCostReversalEnabled"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_return_costs",Integer.class));
    }
    @Test void filteredTotalsCoverAllPagesAndKeepUnknownCostsAndCurrenciesSeparate() {
        qianyi();integration.save(owner,shop,form());addOrder("586208660231587825","COMPLETED");
        var first=FinanceQianyiReceiptTest.qualified();
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
        filter.put("type","RETURN");filter.put("refundStatus","FINISH");filter.put("receiptScope","ALL");
        money=rows(map(read.list(owner,filter).get("summary")).get("refundTotals"));
        assertTrue(money.isEmpty(),"refund-only documents can be inspected but cannot enter receipt statistics");
        assertEquals(1,number(read.list(owner,filter).get("total")));
        filter.put("start","2026-10-01");filter.put("end","2026-10-31");
        assertEquals(0,number(read.list(owner,filter).get("total")));
        assertEquals(0,number(map(read.list(other,Map.of()).get("summary")).get("records")));
    }
    @Test void exportUsesTheSameNativeFiltersAndKeepsLongOrderNumbersAsText() throws Exception {
        qianyi();integration.save(owner,shop,form());
        var a=FinanceQianyiReceiptTest.qualified();a.put("refundStatus","FINISH");a.put("orderRefundAmount","8.25");a.put("currency","PHP");page(a,false);
        var b=new LinkedHashMap<>(a);b.put("returnNumber","EXCLUDED");b.put("refundStatus","WAIT_PAYMENT");
        integration.acceptPage(lease(false),"test-lease",new FinanceQianyiClient.Page(List.of(b),1),Map.of(),true);
        var read=new FinanceQianyiReturns(repo,service);
        var bytes=read.export(owner,Map.of("type","SALES_RETURN","refundStatus","FINISH","start","2026-09-01","end","2026-09-30"));
        var entries=new HashMap<String,String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())
                entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var detail=entries.get("xl/worksheets/sheet2.xml");
        assertTrue(detail.contains("586208660231587825"));assertTrue(detail.contains("8.25"));
        assertTrue(detail.contains("退货单"));assertFalse(detail.contains("EXCLUDED"));
        assertTrue(detail.contains("收货时间（北京时间）"));assertTrue(detail.contains("退回已签收"));
        assertTrue(detail.contains("t=\"inlineStr\""));assertTrue(entries.get("xl/worksheets/sheet1.xml").contains("千易售后及物流接口"));
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
        assertEquals(1,number(new FinanceQianyiReturns(repo,service).list(owner,Map.of("receiptScope","ALL","start","","end","")).get("total")));
        db.update("UPDATE finance_qianyi_connections SET lease_token=NULL,lease_until=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)");
        db.update("UPDATE finance_qianyi_returns SET cost_status='PENDING',next_at=UTC_TIMESTAMP(6)");
        var worker=new FinanceQianyiWorker(integration);
        try{worker.tick();assertEquals("PENDING",document().get("cost_status"));}
        finally{worker.stop();}
    }
    @Test void allThreeReceiptConditionsAndChinaMonthBoundariesApplyToListSummaryAndExport() throws Exception {
        qianyi();integration.save(owner,shop,form());
        long first=java.time.LocalDate.of(2026,10,1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long end=java.time.LocalDate.of(2026,11,1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        var sources=new ArrayList<Map<String,Object>>();
        for(int i=0;i<8;i++){
            var s=FinanceQianyiReceiptTest.qualified();s.put("returnNumber","CASE-"+i);s.put("currency","PHP");s.put("orderRefundAmount","10");
            s.put("receiveTime",first);s.put("refundStatus","WAIT_PAYMENT");
            if(i==1)s.put("receiveTime",first-1);
            if(i==2)s.put("receiveTime",end);
            if(i==3){s.remove("receiveTime");s.put("finishTime",first);}
            if(i==4)s.put("returnStatus","WAIT_STORAGE");
            if(i==5)s.put(FinanceQianyiReceipt.FIELD,Map.of("source","QIANYI_TRACKING","state","IN_TRANSIT"));
            if(i==6)s.remove(FinanceQianyiReceipt.FIELD);
            if(i==7)s.put("isDeleted",1);
            sources.add(s);
        }
        integration.acceptPage(lease(false),"test-lease",new FinanceQianyiClient.Page(sources,8),Map.of(),true);
        var read=new FinanceQianyiReturns(repo,service);
        var filter=new LinkedHashMap<String,String>(Map.of("start","2026-10-01","end","2026-10-31","dateField","CREATED"));
        var list=read.list(owner,filter);assertEquals(1,number(list.get("total")));assertEquals("RECEIPT",list.get("dateField"));
        assertEquals("CASE-0",rows(list.get("items")).getFirst().get("return_number"));
        assertEquals(1,number(map(list.get("summary")).get("records")));
        assertEquals(1,number(map(list.get("summary")).get("awaitingLogistics")));
        equalMoney("10",rows(map(list.get("summary")).get("refundTotals")).getFirst().get("amount"));
        var sheet=FinanceCostExportTest.sheet(read.export(owner,filter),2);
        assertEquals(2,sheet.getElementsByTagName("row").getLength());
        assertTrue(sheet.getDocumentElement().getTextContent().contains("CASE-0"));
        assertFalse(sheet.getDocumentElement().getTextContent().contains("CASE-2"));
        filter.put("receiptScope","ALL");
        list=read.list(owner,filter);assertEquals(5,number(list.get("total")));
        assertEquals(1,number(map(list.get("summary")).get("records")));
        filter.put("numbers","CASE-6");assertEquals(1,number(read.list(owner,filter).get("total")));
        assertTrue(rows(map(read.list(owner,filter).get("summary")).get("refundTotals")).isEmpty());
        assertEquals(0,number(read.list(other,filter).get("total")));
    }
    @Test void enrichmentReusesTerminalProofAndDoesNotTreatForwardDeliveryOrMissingPackageAsSigned(){
        qianyi();integration.save(owner,shop,form());var s=FinanceQianyiReceiptTest.source();
        org.mockito.Mockito.when(upstream.query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("QUERY_FIRST_LRG_TRACKING_PACKAGE"),org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(new FinanceQianyiClient.Page(List.of(FinanceQianyiReceiptTest.parcel("Your package has been returned to the seller.",1788291000000L)),1));
        var config=lease(false);var response=new FinanceQianyiClient.Page(List.of(s),1);
        var enriched=FinanceQianyiReceipt.enrich(integration,config,response);
        integration.acceptPage(config,"test-lease",enriched,Map.of(),true);
        assertEquals("SIGNED",document().get("return_receipt_state"));
        org.mockito.Mockito.clearInvocations(upstream);
        assertEquals(enriched,FinanceQianyiReceipt.enrich(integration,lease(false),response));
        org.mockito.Mockito.verifyNoInteractions(upstream);
        s.put("updateTime",1788570000000L);
        org.mockito.Mockito.when(upstream.query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("QUERY_FIRST_LRG_TRACKING_PACKAGE"),org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(new FinanceQianyiClient.Page(List.of(FinanceQianyiReceiptTest.parcel("Delivered to buyer.",1788291000000L)),1));
        assertEquals("UNKNOWN",map(FinanceQianyiReceipt.enrich(integration,lease(false),response).items().getFirst().get(FinanceQianyiReceipt.FIELD)).get("state"));
        org.mockito.Mockito.when(upstream.query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("QUERY_FIRST_LRG_TRACKING_PACKAGE"),org.mockito.ArgumentMatchers.anyMap()))
            .thenThrow(bad("千易接口触发限流"));
        assertEquals("UNKNOWN",map(FinanceQianyiReceipt.enrich(integration,lease(false),response).items().getFirst().get(FinanceQianyiReceipt.FIELD)).get("state"));
    }
    @Test void receiptScanUsesUpstreamReceiveRangeAndResumesTheSavedPageWithoutReset(){
        qianyi();integration.save(owner,shop,form());
        var resume=new LinkedHashMap<String,Object>(Map.of("from","2026-10-21","until","2026-10-29","page",4,"sourceVersion",3));
        var cp=Map.of("from","2026-10-31","until","2026-10-31","page",1,"sourceVersion",3,"receiptScan",true,"backfillResume",resume);
        db.update("UPDATE finance_qianyi_connections SET source_version=2,checkpoint_json=? WHERE shop_id=?",repo.write(cp),shop);
        org.mockito.Mockito.when(upstream.query(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("QUERY_RETURN_ORDER_LIST"),org.mockito.ArgumentMatchers.anyMap()))
            .thenAnswer(call->{
                Map<String,Object> parameters=call.getArgument(2);
                assertEquals("2026-10-31 00:00:00",parameters.get("receiveTimeFrom"));
                assertEquals("2026-10-31 23:59:59",parameters.get("receiveTimeTo"));
                assertEquals("FINISH",parameters.get("returnStatus"));assertEquals(List.of("SALES_RETURN"),parameters.get("typeList"));
                assertFalse(parameters.containsKey("withoutType"));assertFalse(parameters.containsKey("updateTimeFrom"));
                return new FinanceQianyiClient.Page(List.of(),0);
            });
        var worker=new FinanceQianyiWorker(integration);
        try{worker.readReturns(lease(false),"test-lease");}finally{worker.stop();}
        var after=integration.connection(owner,shop);var saved=repo.read(text(after.get("checkpoint_json")));
        for(String k:List.of("from","until"))assertEquals(resume.get(k),saved.get(k));
        assertEquals(4,number(saved.get("page")));assertNotNull(after.get("last_receipt_scan_at"));
        assertEquals(2,number(after.get("source_version")),"source replay is not certified before completion");
    }
    @Test void addingLogisticsEvidenceDoesNotRemoveHistoricalCostsOrRestoreProfitReversal(){
        qianyi();integration.save(owner,shop,form());addOrder("586208660231587825","COMPLETED");
        var s=FinanceQianyiReceiptTest.source();page(s,true);publish();
        var before=db.queryForMap("SELECT id,amount_cny,data_json FROM finance_qianyi_return_costs");
        s.put(FinanceQianyiReceipt.FIELD,FinanceQianyiReceipt.evidence(s,List.of(FinanceQianyiReceiptTest.parcel("Your package has been returned to the seller.",1788291000000L))));
        page(s,true);
        assertEquals(before,db.queryForMap("SELECT id,amount_cny,data_json FROM finance_qianyi_return_costs"));
        var totals=new LinkedHashMap<String,Object>(Map.of("total",new LinkedHashMap<>(),"shops",new ArrayList<>()));
        actual.attachReturns(owner,period(9),totals);
        equalMoney("0",map(totals.get("total")).get("return_cost_cny"));
        assertEquals(false,totals.get("returnCostReversalEnabled"));
    }
}
