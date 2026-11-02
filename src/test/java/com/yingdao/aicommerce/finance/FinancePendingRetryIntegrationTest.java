package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinancePendingRetryIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void completeDistinctReplayRemovesOnlyStalePendingRows(){
        String shop=connect(owner,false);service.configure(owner,shop,true,"PHP","Asia/Manila");
        record(owner,shop,"PENDING","stale",new java.math.BigDecimal("10"),Map.of("_finance_sync","failed-pass"));
        var tk=mock(com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient.class);
        var reader=new FinanceMarketplace(auth,mock(com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient.class),tk,repo);
        var connection=auth.connection(owner,shop);
        long start=filter.start().atStartOfDay(java.time.ZoneId.of("Asia/Manila")).toEpochSecond();
        var cp=new LinkedHashMap<String,Object>(Map.of("from",start,"to",start+86400,"run","failed-pass"));
        var item=Map.of("id","current","order_id","current-order","type","ORDER","currency","PHP","order_create_time",start+3600,"est_settlement_amount","8");
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("transactions",List.of(item),"total_count",1)));
        assertThrows(RuntimeException.class,()->reader.tik(owner,connection,"PENDING","Asia/Manila",cp));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='PENDING'",Integer.class));
        cp.put("run","new-pass");
        assertTrue(reader.tik(owner,connection,"PENDING","Asia/Manila",cp).done());
        assertEquals("current",db.queryForObject("SELECT external_id FROM finance_records WHERE kind='PENDING'",String.class));
    }
    @Test void inconsistentPendingReplayRotatesSeenMarkerAndKeepsItsExactWindow(){
        String shop=connect(owner,false);service.configure(owner,shop,true,"PHP","Asia/Manila");
        db.update("""
            INSERT INTO finance_sync_states(organization_id,user_id,shop_id,channel)
            VALUES(?,?,?,'PENDING')
            """,owner.organizationId(),owner.userId(),shop);
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)");
        var cp=new LinkedHashMap<String,Object>(Map.of("from",1788220800L,"to",1788825600L,"until",1793548800L,
            "coverage","2026-09-01","run","failed-pass","cursor","legacy-cursor","pendingCount",40,"pendingPageSize",100));
        db.update("UPDATE finance_sync_states SET checkpoint_json=?,next_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND channel='PENDING'",repo.write(cp),shop);
        var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenThrow(bad("待结算交易数量不完整，已保留进度等待重试"));
        var worker=new FinanceSync(repo,market);
        try{
            worker.tick();
            var row=db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel='PENDING'",shop);
            var next=repo.read(text(row.get("checkpoint_json")));
            assertEquals("ERROR",row.get("status"));
            assertFalse(next.containsKey("cursor"));assertFalse(next.containsKey("pendingCount"));
            assertFalse(text(next.get("run")).isBlank());assertNotEquals("failed-pass",next.get("run"));
            for(String field:List.of("from","to","until"))assertEquals(number(cp.get(field)),number(next.get(field)));
            assertEquals(cp.get("coverage"),next.get("coverage"));
            assertEquals(100,number(next.get("pendingPageSize")));
            assertEquals(1,number(row.get("failures")));
        }finally{worker.stop();}
    }
}
