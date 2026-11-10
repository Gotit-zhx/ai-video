package com.yingdao.aicommerce.finance;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the disposable database fixture, including the persisted pre-fix result. */
class FinanceCountryWarehouseIntegrationTest extends FinanceActualCostsIntegrationTest {
    void requeue()throws Exception{
        String sql=java.nio.file.Files.readString(java.nio.file.Path.of("scripts/maintenance/requeue-finance-country-warehouse.sql"))
            .replaceAll("(?m)^--.*$","");
        db.execute(sql);
    }
    String catalog(Scope scope,String name){
        String group=service.saveGroup(scope,null,name);
        repo.transaction(()->new FinanceCostWorkspace(repo,service).importCosts(scope,scope.userId(),group,
            "仓库,型号,202610\n菲律宾,PACK,7\n泰国,PACK,100\n马来西亚,PACK,200\n".getBytes(StandardCharsets.UTF_8),"cost.csv"));
        return group;
    }
    String staleOrder(Scope scope,String shop,String code){
        var entry=new Entry(shop,"SHOPEE","ORDER",code,code,"","ORDER","COMPLETED",LocalDate.of(2026,10,21),"PHP",
            decimal("100"),null,null,null,null,"API",Map.of("item_list",List.of(
                Map.of("item_id","product","model_id",code,"model_sku",code,"model_quantity_purchased",2))));
        repo.save(scope,entry);
        var engine=new FinanceOrderCosts(repo);var context=engine.context(scope.organizationId(),shop);
        // An unrecognized country reproduces the pre-fix empty warehouse with the same source/order.
        var stale=new FinanceOrderCosts.Context(shop,context.groupId(),"",context.timezone(),context.bookId(),context.costs(),Map.of(),Map.of());
        var result=FinanceOrderCosts.calculate(Map.of("platform","SHOPEE","status","COMPLETED","record_date",entry.date()),entry.raw(),stale);
        assertEquals("INCOMPLETE",result.get("status"));assertEquals("",result.get("warehouse"));
        String record=id(scope,entry);
        db.update("""
            INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,source_hash,data_json)
            VALUES(?,?,?,?,?,'INCOMPLETE',?,?)
            """,record,scope.organizationId(),shop,code,entry.date(),FinanceOrderCosts.sourceHash(repo,entry.raw(),entry.status()),repo.write(result));
        db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",record);
        db.update("""
            INSERT INTO finance_cost_rebuilds(organization_id,shop_id,status,cursor_id) VALUES(?,?,'DONE','past')
            ON DUPLICATE KEY UPDATE status='DONE',cursor_id='past'
            """,scope.organizationId(),shop);
        return record;
    }
    @Test void requeueAutomaticallyRebuildsStaleCostsWithoutMakingMissingSkusFree()throws Exception{
        String group=catalog(owner,"国家仓"),shop=groupedShop(owner,"country-auto",group);
        String known=staleOrder(owner,shop,"PACK"),missing=staleOrder(owner,shop,"MISSING");
        var before=db.queryForList("SELECT id,amount,raw_json FROM finance_records ORDER BY id");
        int audit=db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit",Integer.class);
        requeue();
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        assertEquals("",db.queryForObject("SELECT cursor_id FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        compute();
        var saved=db.queryForMap("SELECT * FROM finance_order_costs WHERE order_record_id=?",known);
        assertEquals("READY",saved.get("status"));equalMoney("14",saved.get("amount_cny"));
        assertEquals("菲律宾",repo.read(text(saved.get("data_json"))).get("warehouse"));
        saved=db.queryForMap("SELECT * FROM finance_order_costs WHERE order_record_id=?",missing);
        assertEquals("INCOMPLETE",saved.get("status"));assertNull(saved.get("amount_cny"));
        assertEquals(before,db.queryForList("SELECT id,amount,raw_json FROM finance_records ORDER BY id"));
        assertEquals(audit,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit",Integer.class));
        assertEquals("菲律宾",new FinanceCostWorkspace(repo,service).costs(owner,shop).get("suggestedWarehouse"));
    }
    @Test void requeuePreservesExplicitWarehousesRemovedShopsAndCompanyScope()throws Exception{
        String group=catalog(owner,"边界"),auto=groupedShop(owner,"auto",group),explicit=groupedShop(owner,"explicit",group),
            resolved=groupedShop(owner,"resolved",group),removed=groupedShop(owner,"removed",group);
        staleOrder(owner,auto,"PACK");staleOrder(owner,explicit,"PACK");String resolvedId=staleOrder(owner,resolved,"PACK");staleOrder(owner,removed,"PACK");
        db.update("INSERT INTO finance_cost_settings(organization_id,shop_id,data_json,updated_by) VALUES(?,?,?,?)",owner.organizationId(),explicit,repo.write(Map.of("warehouse","泰国")),owner.userId());
        db.update("UPDATE finance_order_costs SET data_json=JSON_SET(data_json,'$.warehouse','菲律宾') WHERE order_record_id=?",resolvedId);
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        String otherGroup=catalog(other,"他公司"),otherShop=groupedShop(other,"other-country",otherGroup);
        String otherId=staleOrder(other,otherShop,"PACK");
        // Other-company settings with a matching shop ID cannot override this company's auto shop.
        db.update("INSERT INTO finance_cost_settings(organization_id,shop_id,data_json,updated_by) VALUES(?,?,?,?)",other.organizationId(),auto,repo.write(Map.of("warehouse","泰国")),other.userId());
        requeue();
        for(String unchanged:List.of(explicit,removed))assertEquals("DONE",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,unchanged));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,auto));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,resolved));
        compute();
        var saved=db.queryForMap("SELECT organization_id,amount_cny FROM finance_order_costs WHERE order_record_id=?",otherId);
        assertEquals(other.organizationId(),saved.get("organization_id"));equalMoney("14",saved.get("amount_cny"));
        assertEquals("泰国",new FinanceCostWorkspace(repo,service).costs(owner,explicit).get("suggestedWarehouse"));
    }
    @Test void formerlyPreferredOverseasWarehouseIsRecheckedWhenCountryWarehousesAreAmbiguous()throws Exception{
        String group=catalog(owner,"多仓"),shop=groupedShop(owner,"ambiguous",group);
        repo.transaction(()->new FinanceCostWorkspace(repo,service).importCosts(owner,"u",group,
            "仓库,型号,202610\n海外仓-菲律宾,PACK,8\n".getBytes(StandardCharsets.UTF_8),"second.csv"));
        String record=staleOrder(owner,shop,"PACK");
        db.update("""
            UPDATE finance_order_costs SET status='READY',amount_cny=16,known_amount_cny=16,
              data_json=JSON_SET(data_json,'$.status','READY','$.warehouse','海外仓-菲律宾','$.amountCny',16)
            WHERE order_record_id=?
            """,record);
        requeue();
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        compute();
        var result=db.queryForMap("SELECT status,amount_cny FROM finance_order_costs WHERE order_record_id=?",record);
        assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amount_cny"));
        assertEquals("",new FinanceCostWorkspace(repo,service).costs(owner,shop).get("suggestedWarehouse"));
    }
    @Test void allShopPendingExportIncludesOtherGroupsAndEveryPageButNeverAnotherCompany()throws Exception{
        String groupA=catalog(owner,"分组一"),groupB=catalog(owner,"分组二"),foreignGroup=catalog(other,"其他公司");
        String shopA=groupedShop(owner,"pending-a",groupA),shopB=groupedShop(owner,"pending-b",groupB),foreign=groupedShop(other,"pending-private",foreignGroup);
        for(var shop:List.of(shopA,shopB,foreign)){
            Scope scope=shop.equals(foreign)?other:owner;int count=shop.equals(shopA)?51:1;
            for(int i=0;i<count;i++){
                String code=i==0?"shared-missing":"missing-"+i;
                staleOrder(scope,shop,code);
                repo.save(scope,new Entry(shop,"SHOPEE","SETTLEMENT","settled-"+code,code,"bill","结算","SETTLED",filter.start(),
                    shop.equals(shopB)?"THB":"PHP",decimal("100"),null,null,null,null,"API",Map.of()));
            }
        }
        var allShops=new Filter(filter.start(),filter.end(),"","","","day","");
        var report=new FinanceCostReport(repo);
        var first=report.orders(owner,allShops,"SETTLED_ORDER","",1,"PENDING");
        var second=report.orders(owner,allShops,"SETTLED_ORDER","",2,"PENDING");
        assertEquals(52,number(first.get("total")));assertEquals(50,rows(first.get("items")).size());assertEquals(2,rows(second.get("items")).size());
        var seen=new HashSet<String>();
        for(var page:List.of(first,second))for(var row:rows(page.get("items")))seen.add(text(row.get("shop_id")));
        assertEquals(Set.of(shopA,shopB),seen);
        byte[] bytes=new FinanceCostExport(repo).export(owner,allShops,"SETTLED_ORDER","","PENDING");
        var orders=FinanceCostExportTest.sheet(bytes,2);
        assertEquals(53,orders.getElementsByTagName("row").getLength());
        String content=orders.getDocumentElement().getTextContent();
        assertTrue(content.contains("pending-a"));assertTrue(content.contains("pending-b"));assertFalse(content.contains("pending-private"));
        assertTrue(content.contains("PHP"));assertTrue(content.contains("THB"));
    }
}
