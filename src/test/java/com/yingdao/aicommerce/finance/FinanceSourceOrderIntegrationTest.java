package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-order visibility must not expand settlement accounting. */
class FinanceSourceOrderIntegrationTest extends FinanceDashboardCacheIntegrationTest {
    @Test void allOrdersIncludeCancelledUnsettledAndCrossMonthWithoutChangingAccounting() throws Exception {
        String group=service.saveGroup(owner,null,"source-orders"),shop=groupedShop(owner,"source-th",group);
        db.update("UPDATE finance_connections SET timezone='Asia/Bangkok',currency='THB' WHERE id=?",shop);
        var october=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),shop,"SHOPEE","","day",group);
        for(int n=0;n<12;n++){
            String order="SOURCE-"+n;
            var e=dated(shop,"ORDER",order,order,"",LocalDate.of(2026,10,10+n),"100",Map.of());
            repo.save(owner,new Entry(e.shopId(),e.platform(),e.kind(),e.externalId(),e.orderId(),e.statementId(),
                    e.category(),n<7?"CANCELLED":"COMPLETED",e.date(),"THB",e.amount(),e.revenue(),
                    e.impressions(),e.clicks(),e.conversions(),e.source(),e.raw()));
            if(n>=7)repo.save(owner,dated(shop,"SETTLEMENT","SET-"+n,order,"BILL-"+n,
                    n==11?LocalDate.of(2026,11,1):LocalDate.of(2026,10,25),"0",Map.of()));
        }
        // A second settlement must neither duplicate the source order nor shift its first date.
        repo.save(owner,dated(shop,"SETTLEMENT","later","SOURCE-7","later-bill",LocalDate.of(2026,11,3),"3",Map.of()));
        var before=repo.dashboardBuckets(owner,october,false);
        assertEquals(4,number(repo.details(owner,october,"ORDER",1,50).get("total")));
        var result=repo.details(owner,october,"ORDER",1,50,"",true);
        assertEquals(12,number(result.get("total")));
        var items=rows(result.get("items"));
        assertEquals(12,items.stream().map(r->r.get("order_id")).distinct().count());
        assertEquals(7,items.stream().filter(r->"CANCELLED".equals(r.get("status"))).count());
        assertEquals(7,items.stream().filter(r->r.get("first_settlement_date")==null).count());
        assertTrue(items.stream().allMatch(r->text(r.get("record_date")).startsWith("2026-10-")));
        var november=items.stream().filter(r->"SOURCE-11".equals(r.get("order_id"))).findFirst().orElseThrow();
        assertEquals("2026-11-01",text(november.get("first_settlement_date")));
        var cancelled=items.stream().filter(r->"SOURCE-0".equals(r.get("order_id"))).findFirst().orElseThrow();
        assertNull(cancelled.get("product_cost_cny"));
        assertEquals(cancelled.get("record_date"),repo.detail(owner,text(cancelled.get("id")),true).get("record_date"));
        assertNull(repo.detail(owner,text(cancelled.get("id"))).get("record_date"),"legacy settled detail retains its basis");
        assertEquals(1,number(repo.details(owner,october,"ORDER",1,50,"SOURCE-0",true).get("total")));
        assertEquals(0,number(repo.details(owner,october,"ORDER",1,50,"SOURCE-0").get("total")));
        assertEquals(0,number(repo.details(other,october,"ORDER",1,50,"",true).get("total")));
        assertEquals(before,repo.dashboardBuckets(owner,october,false),"source reads cannot alter any accounting bucket");
        var params=new HashMap<>(Map.of("start","2026-10-01","end","2026-10-31","shopId",shop,"groupId",group,
                "view","ORDER","kind","ORDER","orderScope","ALL","format","csv"));
        var exported=FinanceWorkbook.read(controller().export(params,principal()).getBody(),"orders.csv");
        assertEquals(13,exported.size());
        assertTrue(exported.getFirst().contains("下单日期（店铺时区）"));
        assertTrue(exported.getFirst().contains("首次平台结算日期"));
        params.put("orderScope","SETTLED");
        assertEquals(5,FinanceWorkbook.read(controller().export(params,principal()).getBody(),"orders.csv").size());
        repo.save(owner,dated(shop,"ORDER","prior-month","prior-month","",LocalDate.of(2026,9,20),"10",Map.of()));
        assertEquals(12,number(repo.details(owner,october,"ORDER",1,50,"",true).get("total")));
        assertEquals(1,number(repo.details(owner,october,"ORDER",1,50,"prior-month",true).get("total")),
                "exact order numbers search all dates while ordinary listing keeps the selected month");
        params.put("orderScope","ALL");params.put("numbers","prior-month");
        assertEquals(2,FinanceWorkbook.read(controller().export(params,principal()).getBody(),"orders.csv").size());
    }

    @Test void rawPagesKeepShopGroupPlatformCurrencyAndTenantBoundaries(){
        String group=service.saveGroup(owner,null,"source-group"),shop=groupedShop(owner,"source-a",group);
        String groupB=service.saveGroup(owner,null,"source-other"),shopB=groupedShop(owner,"source-b",groupB);
        for(String id:List.of(shop,shopB))for(int n=0;n<4;n++)
            repo.save(owner,dated(id,"ORDER","same-"+n,"same-"+n,"",LocalDate.of(2026,10,2+n),"10",Map.of()));
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","SHOPEE","PHP","day",group);
        assertEquals(4,number(repo.details(owner,f,"ORDER",1,2,"",true).get("total")));
        var a=rows(repo.details(owner,f,"ORDER",1,2,"",true).get("items"));
        var b=rows(repo.details(owner,f,"ORDER",2,2,"",true).get("items"));
        assertEquals(List.of("same-3","same-2","same-1","same-0"),
                java.util.stream.Stream.concat(a.stream(),b.stream()).map(r->text(r.get("order_id"))).toList());
        assertTrue(a.stream().allMatch(r->shop.equals(r.get("shop_id"))));
        var mismatch=new Filter(f.start(),f.end(),shopB,"SHOPEE","PHP","day",group);
        assertEquals(0,number(repo.details(owner,mismatch,"ORDER",1,50,"",true).get("total")));
        assertEquals(0,number(repo.details(owner,new Filter(f.start(),f.end(),shop,"TIKTOK_SHOP","PHP","day"),"ORDER",1,50,"",true).get("total")));
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shopB);
        repo.save(owner,new Entry(shopB,"TIKTOK_SHOP","ORDER","TK-CANCEL","TK-CANCEL","","订单","CANCELLED",
                LocalDate.of(2026,10,5),"PHP",decimal("12"),null,null,null,null,"API",Map.of()));
        var tk=new Filter(f.start(),f.end(),"","TIKTOK_SHOP","","day",groupB);
        assertEquals(1,number(repo.details(owner,tk,"ORDER",1,50,"",true).get("total")));
        assertEquals(1,number(repo.details(owner,tk,"ORDER",1,50,"TK-CANCEL",true).get("total")));
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",shop);
        assertEquals(0,number(repo.details(owner,f,"ORDER",1,50,"",true).get("total")));
    }
}
