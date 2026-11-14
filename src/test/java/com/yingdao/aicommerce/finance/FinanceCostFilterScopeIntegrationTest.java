package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real report and workbook boundaries for the order page's all/group/shop selections. */
class FinanceCostFilterScopeIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void selectedGroupShopAndPendingHaveTheSameOrdersAcrossPagesAndExport() throws Exception {
        String ga=service.saveGroup(owner,null,"甲组"),gb=service.saveGroup(owner,null,"乙组"),empty=service.saveGroup(owner,null,"空组");
        String a1=groupedShop(owner,"scope-a1",ga),a2=groupedShop(owner,"scope-a2",ga),b1=groupedShop(owner,"scope-b1",gb);
        String foreign=groupedShop(other,"scope-foreign",service.saveGroup(other,null,"其他公司"));
        var a1Pending=new TreeSet<String>();
        for(int i=0;i<55;i++){String order="A1-pending-"+i;a1Pending.add(order);settled(owner,a1,order);}
        for(String order:List.of("A2-pending-1","A2-pending-2"))settled(owner,a2,order);
        settled(owner,b1,"B1-pending");settled(other,foreign,"foreign-pending");
        var ready=new Entry(a1,"SHOPEE","ORDER","ready","A1-ready","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",Map.of());
        repo.save(owner,ready);settled(owner,a1,"A1-ready");
        db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json) VALUES(?,?,?,?,?,'READY',10,10,?,?)",
            id(owner,ready),owner.organizationId(),a1,"A1-ready",filter.start(),"a".repeat(64),"{\"lines\":[]}");
        db.update("DELETE FROM finance_order_cost_jobs");db.update("UPDATE finance_cost_rebuilds SET status='DONE'");

        var groupPending=new TreeSet<>(a1Pending);groupPending.addAll(List.of("A2-pending-1","A2-pending-2"));
        var allPending=new TreeSet<>(groupPending);allPending.add("B1-pending");
        assertSelection(owner,selected("",""),"PENDING","",allPending,"0");
        assertSelection(owner,selected("",ga),"PENDING","",groupPending,"0");
        assertSelection(owner,selected(a1,ga),"PENDING","",a1Pending,"0");
        assertSelection(owner,selected(b1,""),"PENDING","",Set.of("B1-pending"),"0");
        assertSelection(owner,selected("",empty),"PENDING","",Set.of(),"0");
        assertSelection(owner,selected("",""),"PENDING","A2-pending-2",Set.of("A2-pending-2"),"0");
        var a1All=new TreeSet<>(a1Pending);a1All.add("A1-ready");
        assertSelection(owner,selected(a1,ga),"ALL","",a1All,"10");
        assertSelection(other,selected("",""),"PENDING","",Set.of("foreign-pending"),"0");
    }

    private Filter selected(String shop,String group){return new Filter(filter.start(),filter.end(),shop,"","","day",group);}
    private void settled(Scope scope,String shop,String order){
        repo.save(scope,new Entry(shop,"SHOPEE","SETTLEMENT","settled-"+order,order,"","结算","SETTLED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",Map.of()));
    }
    private void assertSelection(Scope scope,Filter query,String status,String numbers,Set<String> expected,String known) throws Exception {
        var reports=new FinanceCostReport(repo);var actual=new TreeSet<String>();Map<String,Object> first=null;
        for(int page=1;;page++){
            var result=reports.orders(scope,query,"SETTLED_ORDER","",page,status,numbers);
            assertEquals(expected.size(),number(result.get("total")));
            if(first==null)first=result;
            else assertEquals(map(first.get("costAmountSummary")),map(result.get("costAmountSummary")));
            for(var row:rows(result.get("items")))assertTrue(actual.add(text(row.get("order_id"))),"pages must not duplicate an order");
            if(page*50>=expected.size())break;
        }
        assertEquals(expected,actual);
        assertEquals(0,decimal(known).compareTo(decimal(map(first.get("costAmountSummary")).get("known_amount_cny"))));
        byte[] file=new FinanceCostExport(repo).export(scope,query,"SETTLED_ORDER","",status,numbers);
        var orders=FinanceCostExportTest.sheet(file,2);var exported=new TreeSet<String>();
        assertEquals(expected.size()+1,orders.getElementsByTagName("row").getLength());
        for(int row=2;row<=expected.size()+1;row++)assertTrue(exported.add(FinanceCostExportTest.value(orders,"C"+row)));
        assertEquals(expected,exported,"the workbook includes all pages of precisely the selected scope");
    }
}
