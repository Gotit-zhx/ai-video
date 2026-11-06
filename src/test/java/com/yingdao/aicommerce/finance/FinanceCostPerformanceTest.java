package com.yingdao.aicommerce.finance;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceCostPerformanceTest {
    @Test void oneBatchSharesOnlyItsCompanyGroupCatalogAndNextBatchReadsTheNewVersion(){
        var db=mock(JdbcTemplate.class);var json=new ObjectMapper();var revision=new AtomicInteger(1);
        var reads=new HashMap<String,Integer>();
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);Object[] args=(Object[])call.getRawArguments()[1];
            if(sql.contains("FROM finance_connections"))return List.of(Map.of("group_id","group","region","PH","timezone","Asia/Manila"));
            if(sql.contains("FROM finance_cost_books")){
                String org=args[0].toString();reads.merge(org,1,Integer::sum);
                return List.of(Map.of("id",org+"-v"+revision.get(),"data_json",json.writeValueAsString(Map.of("costs",List.of(
                        Map.of("sku","PACK","warehouse","海外仓-菲律宾","prices",Map.of("2026-10",revision.get())))))));
            }
            if(sql.contains("FROM finance_cost_settings"))return List.of(Map.of("data_json",json.writeValueAsString(Map.of("shopMarker",args[1]))));
            return List.of();
        });
        var costs=new FinanceOrderCosts(new FinanceRepository(db,json));
        var batch=new HashMap<FinanceOrderCosts.CatalogKey,FinanceOrderCosts.Catalog>();
        var first=costs.context("org-a","shop-one",batch);revision.set(2);
        var second=costs.context("org-a","shop-two",batch);var other=costs.context("org-b","shop-one",batch);
        assertSame(first.costs(),second.costs());assertEquals("org-a-v1",second.bookId());
        assertEquals("shop-one",first.settings().get("shopMarker"));assertEquals("shop-two",second.settings().get("shopMarker"));
        assertNotSame(first.costs(),other.costs());assertEquals("org-b-v2",other.bookId());
        assertEquals(Map.of("org-a",1,"org-b",1),reads);
        var next=costs.context("org-a","shop-one",new HashMap<>());
        assertEquals("org-a-v2",next.bookId());assertEquals(2,reads.get("org-a"));
    }

    @Test void reusedLookupPreservesAmountsGiftsMissingPricesAndOrderIsolation(){
        var catalog=new ArrayList<Map<String,Object>>();
        for(int i=0;i<500;i++)catalog.add(Map.of("sku","SKU-"+i,"warehouse","海外仓-菲律宾","prices",Map.of("2026-10",decimal("4.125"))));
        var context=new FinanceOrderCosts.Context("shop","group","PH","Asia/Manila","book",catalog,Map.of(),Map.of());
        var lookup=FinanceOrderCosts.lookup(context);
        var order=Map.<String,Object>of("platform","TIKTOK_SHOP","status","COMPLETED","record_date","2026-10-21");
        for(int i=0;i<30;i++){
            var raw=Map.<String,Object>of("line_items",List.of(
                    Map.of("id","gift","product_id","p","sku_id","gift","seller_sku","GIFT","quantity",2),
                    Map.of("id","paid","product_id","p","sku_id","paid","seller_sku","SKU-"+i,"quantity",3)));
            var result=FinanceOrderCosts.calculate(order,raw,context,lookup);
            assertEquals(0,decimal("12.375").compareTo(decimal(result.get("amountCny"))));
            assertEquals("READY",result.get("status"));assertEquals(2,result.get("lineCount"));
            assertEquals(result,FinanceOrderCosts.calculate(order,raw,context));
        }
        var missing=FinanceOrderCosts.calculate(order,Map.of("line_items",List.of(Map.of("id","missing","seller_sku","UNKNOWN","quantity",1))),context,lookup);
        assertNull(missing.get("amountCny"));assertEquals("INCOMPLETE",missing.get("status"));
        assertEquals(500,lookup.codes().size());assertEquals(500,lookup.normalized().size());
    }
}
