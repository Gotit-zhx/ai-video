package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceRefundPacingIntegrationTest extends FinanceConfigurationIntegrationTest {
    String refundShop(String external){
        String shop=groupedShop(owner,external,service.saveGroup(owner,null,"退款同步测试"));
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)");
        db.update("UPDATE finance_sync_states SET next_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE shop_id=? AND channel='REFUND'",shop);
        return shop;
    }
    @Test void durableBudgetCoversAllShopsAndRestartsWithoutBlockingOrders(){
        var refund=Map.<String,Object>of("platform","TIKTOK_SHOP","channel","REFUND");
        assertTrue(repo.transaction(()->FinanceRefundPacing.reserve(repo,refund)));
        assertFalse(repo.transaction(()->FinanceRefundPacing.reserve(repo,refund)));
        assertTrue(repo.transaction(()->FinanceRefundPacing.reserve(repo,Map.of("platform","TIKTOK_SHOP","channel","ORDER_LIVE"))));
        Instant first=FinanceRefundPacing.cooldown(repo);
        assertTrue(first.isAfter(Instant.now().plusSeconds(290)));
        assertEquals(10,db.queryForObject("SELECT interval_seconds FROM finance_api_pacing WHERE platform='TIKTOK_SHOP' AND channel='REFUND'",Integer.class));
        assertFalse(repo.transaction(()->FinanceRefundPacing.reserve(new FinanceRepository(db,new tools.jackson.databind.ObjectMapper()),refund)));
        Instant second=FinanceRefundPacing.cooldown(repo);assertTrue(second.isAfter(first.plusSeconds(290)));
        db.update("UPDATE finance_api_pacing SET success_count=59 WHERE platform='TIKTOK_SHOP' AND channel='REFUND'");FinanceRefundPacing.succeeded(repo,refund);
        assertEquals(10,db.queryForObject("SELECT interval_seconds FROM finance_api_pacing WHERE platform='TIKTOK_SHOP' AND channel='REFUND'",Integer.class));
        assertTrue(db.queryForObject("SELECT next_allowed_at>UTC_TIMESTAMP(6) FROM finance_api_pacing WHERE platform='TIKTOK_SHOP' AND channel='REFUND'",Boolean.class),"recovery does not bypass an existing cooldown");
    }
    @Test void refund429PreservesCursorAndDefersSharedLane(){
        String shop=refundShop("refund");
        String checkpoint="{\"from\":1788200000,\"to\":1788400000,\"until\":1788500000,\"cursor\":\"same-page\",\"coverage\":\"2026-09-01\"}";
        db.update("UPDATE finance_sync_states SET checkpoint_json=? WHERE shop_id=? AND channel='REFUND'",checkpoint,shop);
        var marketplace=mock(FinanceMarketplace.class);
        when(marketplace.step(any(),anyMap(),anyMap())).thenThrow(bad("店铺官方接口 HTTP 429：code 36009002"));
        var sync=new FinanceSync(repo,marketplace);
        try{
            sync.tick();
            var row=db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel='REFUND'",shop);
            assertEquals("RATE_LIMITED",row.get("status"));
            assertEquals("same-page",repo.read(text(row.get("checkpoint_json"))).get("cursor"));
            assertTrue(instant(row.get("next_at")).isAfter(Instant.now().plusSeconds(290)));
            assertFalse(text(row.get("error_message")).contains("HTTP 429"));
        }finally{sync.stop();}
    }
    @Test void successfulRefundUsesWatermarkOverlapAndStaggeredRefresh(){
        String shop=refundShop("incremental");Instant last=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        db.update("UPDATE finance_sync_states SET last_success_at=? WHERE shop_id=? AND channel='REFUND'",Timestamp.from(last),shop);
        var marketplace=mock(FinanceMarketplace.class);var checkpoints=new ArrayList<Map<String,Object>>();
        when(marketplace.step(any(),anyMap(),anyMap())).thenAnswer(call->{checkpoints.add(new HashMap<>(call.getArgument(2)));return new FinanceMarketplace.Step(call.getArgument(2),true);});
        var sync=new FinanceSync(repo,marketplace);
        try{
            sync.tick();assertEquals(last.minusSeconds(300).getEpochSecond(),number(checkpoints.getFirst().get("from")));
            var row=db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel='REFUND'",shop);
            assertEquals(number(checkpoints.getFirst().get("until")),instant(row.get("last_success_at")).getEpochSecond());
            assertTrue(instant(row.get("next_at")).isAfter(Instant.now().plusSeconds(890)));
            assertEquals("SUCCESS",row.get("status"));
        }finally{sync.stop();}
    }
}
