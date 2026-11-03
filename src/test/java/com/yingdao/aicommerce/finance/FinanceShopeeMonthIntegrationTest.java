package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceShopeeMonthIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void missingPassFetchesOnlyAbsentIncompleteOrChangedPayoutsAndReplayIsIdempotent(){
        String shop=connect(owner,false);
        var client=mock(ShopeeOfficialClient.class);
        var market=new FinanceMarketplace(auth,client,null,repo);
        var all=new ArrayList<Map<String,Object>>();
        long stamp=Instant.parse("2026-10-02T04:00:00Z").getEpochSecond();
        for(String id:List.of("known","missing","partial","changed")){
            all.add(Map.of("order_sn",id,"actual_payout_time",stamp,"currency","PHP","released_amount","90"));
            if(!id.equals("missing")){
                repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT",id,id,id,"订单结算","SETTLED",
                    LocalDate.of(2026,10,2),"PHP",new BigDecimal(id.equals("changed")?"89":"90"),null,null,null,null,"API",
                    Map.of("order_income",Map.of("cost_of_goods_sold",100,"escrow_amount",90,"commission_fee",10))));
                repo.save(owner,new Entry(shop,"SHOPEE","BILL",id,id,id,"订单释放收入",id.equals("partial")?"PARTIAL":"SETTLED",
                    LocalDate.of(2026,10,2),"PHP",new BigDecimal("90"),null,null,null,null,"API",Map.of()));
            }
        }
        when(client.shopGet(eq("/api/v2/payment/get_income_detail"),any(),anyMap()))
            .thenReturn(Map.of("response",Map.of("list",all)));
        when(client.shopPost(eq("/api/v2/payment/get_escrow_detail_batch"),any(),anyMap())).thenAnswer(invocation->{
            var q=(Map<?,?>)invocation.getArgument(2);String ids=text(q.get("order_sn_list"));
            assertFalse(ids.contains("known"));assertTrue(ids.contains("missing")&&ids.contains("partial")&&ids.contains("changed"));
            return Map.of("response",List.of("missing","partial","changed").stream().map(id->Map.of("escrow_detail",
                Map.of("order_sn",id,"order_income",Map.of("cost_of_goods_sold",100,"escrow_amount",90,"commission_fee",10)))).toList());
        });
        var state=Map.<String,Object>of("shop_id",shop,"channel","SETTLEMENT","timezone","Asia/Manila");
        var cp=Map.<String,Object>of("from",stamp-86400,"to",stamp+86400,"missingSettlementPass",true);
        var first=market.step(owner,state,cp);assertTrue(first.done());
        assertEquals(3,number(first.checkpoint().get("missingRowsFetched")));
        assertEquals(4,number(first.checkpoint().get("missingRowsChecked")));
        var again=market.step(owner,state,cp);assertTrue(again.done());
        assertEquals(0,number(again.checkpoint().get("missingRowsFetched")));
        verify(client,times(1)).shopPost(eq("/api/v2/payment/get_escrow_detail_batch"),any(),anyMap());
        verify(client,never()).shopGet(eq("/api/v2/payment/get_escrow_detail"),any(),anyMap());
        verify(client,times(2)).shopGet(eq("/api/v2/payment/get_income_detail"),any(),argThat(q->number(q.get("page_size"))==30));
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=? AND kind IN ('BILL','SETTLEMENT')",Integer.class,shop));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=? AND kind='FEE'",Integer.class,shop));
    }
    @Test void incompleteBatchDoesNotWriteAnyPayoutOrAdvanceItsCursor(){
        String shop=connect(owner,false);var client=mock(ShopeeOfficialClient.class);
        long stamp=Instant.parse("2026-10-02T04:00:00Z").getEpochSecond();
        var rows=List.of("one","two").stream().map(id->Map.of("order_sn",id,"actual_payout_time",stamp,
            "currency","PHP","released_amount",90)).toList();
        when(client.shopGet(eq("/api/v2/payment/get_income_detail"),any(),anyMap()))
            .thenReturn(Map.of("response",Map.of("list",rows,"next_page",Map.of("cursor","after"))));
        when(client.shopPost(eq("/api/v2/payment/get_escrow_detail_batch"),any(),anyMap()))
            .thenReturn(Map.of("response",List.of(Map.of("escrow_detail",Map.of("order_sn","one","order_income",Map.of("cost_of_goods_sold",100))))));
        var cp=Map.<String,Object>of("from",stamp-86400,"to",stamp+86400,"missingSettlementPass",true,"cursor","before");
        var market=new FinanceMarketplace(auth,client,null,repo);
        assertThrows(RuntimeException.class,()->market.step(owner,Map.of("shop_id",shop,"channel","SETTLEMENT","timezone","Asia/Manila"),cp));
        assertEquals("before",cp.get("cursor"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,shop));
    }
    @Test void monthlyResyncCorrectsBothLedgersAndFeesWithoutDuplicatingOrdersOrCosts(){
        String shop=connect(owner,false),order="261011C2BRMC5X";
        var income=new LinkedHashMap<String,Object>(Map.of(
            "original_price",0,"seller_discount",0,"original_cost_of_goods_sold",298,
            "seller_return_refund",-197,"cost_of_goods_sold",0,"escrow_amount",-11,
            "escrow_amount_after_adjustment","217.97","total_adjustment_amount","228.97",
            "seller_order_processing_fee",11));
        var raw=Map.<String,Object>of("order_income",income);
        var entry=new Entry(shop,"SHOPEE","SETTLEMENT",order,order,order,"订单结算","SETTLED",
            LocalDate.of(2026,10,14),"PHP",new BigDecimal("217.97"),null,null,null,null,"API",raw);
        repo.save(owner,entry);
        var client=mock(ShopeeOfficialClient.class);
        var incomeRow=Map.of("order_sn",order,"actual_payout_time",Instant.parse("2026-10-14T04:00:00Z").getEpochSecond(),
            "currency","PHP","released_amount","-11");
        when(client.shopGet(eq("/api/v2/payment/get_income_detail"),any(),anyMap()))
            .thenReturn(Map.of("response",Map.of("list",List.of(incomeRow))));
        when(client.shopGet(eq("/api/v2/payment/get_escrow_detail"),any(),anyMap()))
            .thenReturn(Map.of("response",raw));
        var market=new FinanceMarketplace(auth,client,null,repo);
        var state=Map.<String,Object>of("shop_id",shop,"channel","SETTLEMENT","timezone","Asia/Manila");
        var cp=Map.<String,Object>of("from",Instant.parse("2026-10-07T16:00:00Z").getEpochSecond(),
            "to",Instant.parse("2026-10-14T16:00:00Z").getEpochSecond());
        for(int i=0;i<2;i++){
            assertTrue(market.step(owner,state,cp).done());
            var rows=db.queryForList("SELECT kind,amount,revenue,record_date,status FROM finance_records WHERE shop_id=? AND kind IN ('BILL','SETTLEMENT')",shop);
            assertEquals(2,rows.size());
            for(var row:rows){
                assertEquals(0,new BigDecimal("-11").compareTo(decimal(row.get("amount"))));
                assertEquals(0,BigDecimal.ZERO.compareTo(decimal(row.get("revenue"))));
                assertEquals("2026-10-14",text(row.get("record_date")));assertEquals("SETTLED",row.get("status"));
            }
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=? AND kind='FEE'",Integer.class,shop));
            assertEquals(0,new BigDecimal("11").compareTo(db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE shop_id=? AND kind='FEE'",BigDecimal.class,shop)));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs WHERE shop_id=?",Integer.class,shop),
            "replaying a payout must not create another order or its cost job");
        var saved=repo.read(db.queryForObject("SELECT raw_json FROM finance_records WHERE shop_id=? AND kind='SETTLEMENT'",String.class,shop));
        assertEquals("217.97",text(at(saved,"order_income.escrow_amount_after_adjustment")));
        assertEquals("-11",text(saved.get("_finance_released_amount")));
    }
}
