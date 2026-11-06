package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the same disposable MySQL fixture as the finance release regression suite. */
class FinanceActualCostsIntegrationTest extends FinanceConfigurationIntegrationTest {
    String group,shop;FinanceActualCosts actual;
    Filter period(int month){return new Filter(LocalDate.of(2026,month,1),LocalDate.of(2026,month,1).plusMonths(1).minusDays(1),shop,"","PHP","day",group);}
    void fixture(){
        group=service.saveGroup(owner,null,"实际成本");shop=groupedShop(owner,"actual",group);actual=new FinanceActualCosts(repo);
        repo.transaction(()->new FinanceCostWorkspace(repo,service).importCosts(owner,"u",group,
            "仓库,型号,202608,202609\n海外仓-菲律宾,PACK,10,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"cost.csv"));

    }
    void addOrder(String id,String status){
        if(!id.equals("unshipped-cancelled"))publishedLabels(shop,"1.95",id);
        repo.save(owner,new Entry(shop,"SHOPEE","ORDER",id,id,"","ORDER",status,LocalDate.of(2026,8,10),"PHP",
            decimal("100"),null,null,null,null,"API",Map.of("ship_time",1786406400,"item_list",List.of(
                Map.of("item_id","product","model_id","sku","model_sku","PACK","model_quantity_purchased",2)))));
        repo.save(owner,dated(shop,"SETTLEMENT",id+"-first",id,"bill",LocalDate.of(2026,9,2),"80",
            Map.of("order_income",Map.of("original_price",100,"commission_fee",20))));
        repo.save(owner,dated(shop,"SETTLEMENT",id+"-refund",id,"bill2",LocalDate.of(2026,9,5),"-80",Map.of()));
        repo.save(owner,dated(shop,"SETTLEMENT",id+"-oct",id,"bill3",LocalDate.of(2026,10,4),"5",Map.of()));
    }
    Map<String,Object> shipment(String order,String amount){
        return Map.of("orderId",order,"lines",List.of(Map.of("sourceRow","千易订单数据!12","shipmentId","ERP-"+order,
            "status","已出库","sku","ACTUAL-M","warehouse","PH","name","实际商品","quantity",1,"unitPriceCny",amount,"amountCny",amount)));
    }
    Map<String,Object> receipt(String order,String date){
        return Map.of("orderId",order,"receiptId","RECEIPT-"+order,"sku","ACTUAL-M","warehouse","PH","receiptTime",date+" 15:45:59",
            "status","已完成","logisticsStatus","退回已签收","quantity",1,"amountCny","12.345678","sourceRow","售后数据!35");
    }
    Map<String,Object> evidence(List<Map<String,Object>> orders,List<Map<String,Object>> returns){
        return Map.of("sourceName","erp.xlsx","sourceHash","a".repeat(64),"orders",orders,"returns",returns);
    }
    Map<String,Object> importData(Map<String,Object> data){return repo.transaction(()->actual.importEvidence(owner,"u",shop,data));}
    void compute(){
        for(int i=0;i<20;i++){
            repo.transaction(()->{var worker=new FinanceOrderCosts(repo);worker.seedAndFill();worker.processBatch(100);return null;});
            if(db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Long.class)==0&&db.queryForObject("SELECT COUNT(*) FROM finance_cost_rebuilds WHERE status IN ('READY','LABELS')",Long.class)==0)return;
        }
        fail("Cost queue did not drain");
    }
    Map<String,Object> total(int month){return map(new FinanceCostReport(repo).summary(owner,period(month),"SETTLED_ORDER").get("total"));}
    void equalMoney(String expected,Object actual){assertNotNull(actual);assertEquals(0,decimal(expected).compareTo(decimal(actual)));}
    @Test void actualShipmentsOverrideCancelledSkuAndQuantityWithoutDuplicatingRefundCosts()throws Exception{
        fixture();addOrder("shipped-cancelled","CANCELLED");addOrder("sample","COMPLETED");addOrder("standard","COMPLETED");addOrder("unshipped-cancelled","CANCELLED");
        compute();equalMoney("40",total(9).get("amount_cny"));
        var data=evidence(List.of(shipment("shipped-cancelled","37.24"),shipment("sample","18.18")),List.of());
        assertEquals(2,number(importData(data).get("orders")));compute();
        equalMoney("75.42",total(9).get("amount_cny"));equalMoney("5.85",total(9).get("label_cost_cny"));
        assertEquals(2,number(total(9).get("actual_count")));assertEquals(1,number(total(9).get("excluded_count")));
        equalMoney("0",total(10).get("amount_cny"));equalMoney("0",total(10).get("label_cost_cny"));
        assertEquals(0,number(importData(data).get("orders")),"same-file import is a no-op");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        var before=db.queryForList("SELECT order_record_id,amount_cny,cost_basis,version FROM finance_order_costs ORDER BY order_record_id");
        addOrder("shipped-cancelled","CANCELLED");
        repo.transaction(()->{new FinanceOrderCosts(repo).requestShop(owner.organizationId(),shop);return null;});compute();
        equalMoney("75.42",total(9).get("amount_cny"));
        assertEquals(before,db.queryForList("SELECT order_record_id,amount_cny,cost_basis,version FROM finance_order_costs ORDER BY order_record_id"),"platform resync and rebuild preserve actual evidence");
        var bytes=new FinanceCostExport(repo).export(owner,period(9),"SETTLED_ORDER","");
        var summary=FinanceCostExportTest.sheet(bytes,1);
        equalMoney("75.42",FinanceCostExportTest.summaryValue(summary,"已结算商品成本合计（人民币）"));
        assertEquals("2",FinanceCostExportTest.summaryValue(summary,"实际出库成本订单"));
        assertTrue(FinanceCostExportTest.sheet(bytes,3).getDocumentElement().getTextContent().contains("ERP-shipped-cancelled"));
    }
    @Test void legacyReturnReceiptsRemainAuditableButOnlyQianyiCreditsAffectReports()throws Exception{
        fixture();addOrder("returned","CANCELLED");
        var data=evidence(List.of(shipment("returned","37.24")),List.of(receipt("returned","2026-10-28")));
        assertEquals(1,number(importData(data).get("receipts")));compute();
        equalMoney("0",total(9).get("return_cost_cny"));equalMoney("0",total(10).get("return_cost_cny"));
        assertTrue(actual.returns(owner,period(10),"returned").isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_return_cost_receipts",Integer.class),"old evidence retained without contributing to totals");
        var report=new FinanceCostReport(repo);var summary=report.dashboardSummary(owner,period(10),report.dashboardFacts(owner,period(10)));
        assertEquals("QIANYI",summary.get("returnSource"));assertEquals(false,summary.get("returnProcessing"));assertEquals(false,summary.get("returnCostReversalEnabled"),"return evidence is diagnostic and does not alter operating profit");
        var totals=List.of(new LinkedHashMap<String,Object>(Map.of("kind","SETTLEMENT","records",1,"known_amounts",1,"amount",decimal("80"),"revenue",decimal("100"),"known_revenues",1)));
        var profit=FinanceProfitReport.calculate(new ArrayList<>(totals),summary,"CNY",null);
        assertNotNull(profit.get("amount"));assertEquals(false,profit.get("returnCostsPending"));
        service.removeShop(owner,shop);finishDeletion(shop);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_return_cost_receipts",Integer.class));
    }
    @Test void malformedOrAmbiguousEvidenceRollsBackWholeBatch(){
        fixture();addOrder("good","COMPLETED");
        var good=shipment("good","37.24");
        assertThrows(IllegalStateException.class,()->actual.importEvidence(owner,"u",shop,evidence(List.of(good),List.of())));
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->importData(evidence(List.of(good,shipment("missing","1")),List.of())));
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->importData(evidence(List.of(good,good),List.of())));
        var invalid=new LinkedHashMap<>(receipt("good","2026-10-28"));invalid.put("status","处理中");
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->importData(evidence(List.of(good),List.of(invalid))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_actual_order_costs",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_return_cost_receipts",Integer.class));
        var line=new LinkedHashMap<>(rows(good.get("lines")).getFirst());line.put("amountCny","1");
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->FinanceActualCosts.normalize(Map.of("orderId","good","lines",List.of(line)),"file","a".repeat(64)));
        line.put("amountCny","37.24");line.put("quantity","0.5");
        assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,()->FinanceActualCosts.normalize(Map.of("orderId","good","lines",List.of(line)),"file","a".repeat(64)));
    }
}
