package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceSyncPriorityIntegrationTest extends FinanceConfigurationIntegrationTest {
    String shop(String name){
        String id=groupedShop(owner,name,service.saveGroup(owner,null,"Priority "+name));
        db.update("INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel) VALUES('org','u',?,'ORDER_REPAIR')",id);
        return id;
    }
    void request(String shop){repo.transaction(()->{service.sync(owner,shop);return true;});}
    void onlyOrdersDue(){
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)");
        db.update("UPDATE finance_sync_states SET next_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE channel='ORDER'");
    }
    Map<String,Object> state(String shop,String channel){
        return db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel=?",shop,channel);
    }
    @Test void priorityBeatsOlderAutomaticWorkAndRepeatedClickKeepsFifoAndCompletedChannels(){
        String automatic=shop("automatic"),first=shop("first"),second=shop("second");
        onlyOrdersDue();request(first);
        db.update("UPDATE finance_sync_states SET priority_requested_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE shop_id=?",first);
        request(second);onlyOrdersDue();
        var requested=state(first,"ORDER").get("priority_requested_at");
        db.update("UPDATE finance_sync_states SET priority_requested_at=NULL,status='SUCCESS' WHERE shop_id=? AND channel='REFUND'",first);
        request(first);
        assertEquals(requested,state(first,"ORDER").get("priority_requested_at"));
        assertNull(state(first,"REFUND").get("priority_requested_at"));
        var claims=new FinanceSyncClaims(repo);
        try(var one=claims.claim();var two=claims.claim()){
            assertEquals(first,one.row().get("shop_id"));assertEquals(second,two.row().get("shop_id"));
            assertNull(claims.claim(),"same platform cannot exceed two simultaneous claims");
        }
        assertNull(state(automatic,"ORDER").get("lease_token"));
    }
    @Test void requestPreservesCheckpointFailuresCooldownAndCompanyBoundary(){
        String id=shop("preserve");
        db.update("UPDATE finance_sync_states SET checkpoint_json='{\"cursor\":\"page-7\"}',failures=3,status='RATE_LIMITED',error_message='cooldown',next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE) WHERE shop_id=? AND channel='REFUND'",id);
        var before=state(id,"REFUND");request(id);var after=state(id,"REFUND");
        for(String key:List.of("checkpoint_json","failures","error_message","next_at","status"))assertEquals(before.get(key),after.get(key),key);
        assertNotNull(after.get("priority_requested_at"));
        assertThrows(RuntimeException.class,()->service.sync(other,id));
        repo.transaction(()->{service.sync(new Scope("org","colleague"),id);return true;});
        assertEquals(after.get("priority_requested_at"),state(id,"REFUND").get("priority_requested_at"));
        db.update("UPDATE finance_connections SET enabled=FALSE WHERE id=?",id);
        assertThrows(RuntimeException.class,()->service.sync(owner,id));
    }
    @Test void prioritySurvivesPagesWindowsAndHistoricalResumeUntilWholeScanCompletes(){
        String id=shop("pages");request(id);onlyOrdersDue();
        Map<String,Object> resume=Map.of("from",100L,"to",200L,"until",200L,"coverage","2026-08-01","recentWindowDays",30);
        Map<String,Object> cp=Map.of("from",300L,"to",400L,"until",500L,"coverage","2026-08-01","recentWindowDays",30,"cursor","page-2","backfillResume",resume);
        db.update("UPDATE finance_sync_states SET checkpoint_json=? WHERE shop_id=? AND channel='ORDER'",repo.write(cp),id);
        var market=mock(FinanceMarketplace.class);var calls=new ArrayList<Map<String,Object>>();
        when(market.step(any(),anyMap(),anyMap())).thenAnswer(call->{
            var value=new LinkedHashMap<String,Object>(call.getArgument(2));calls.add(new LinkedHashMap<>(value));
            if(calls.size()==1){value.put("cursor","page-3");return new FinanceMarketplace.Step(value,false);}
            return new FinanceMarketplace.Step(value,true);
        });
        var sync=new FinanceSync(repo,market);
        try{
            for(int i=0;i<4;i++){
                db.update("UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND channel='ORDER'",id);
                sync.tick();var current=state(id,"ORDER");assertNotNull(current.get("last_progress_at"));
                if(i<3)assertNotNull(current.get("priority_requested_at"));
                else {assertNull(current.get("priority_requested_at"));assertNull(current.get("checkpoint_json"));assertEquals("SUCCESS",current.get("status"));}
            }
            assertEquals("page-2",calls.get(0).get("cursor"));assertEquals("page-3",calls.get(1).get("cursor"));
            assertEquals(400,number(calls.get(2).get("from")));assertEquals(100,number(calls.get(3).get("from")));
            assertNotNull(state(id,"REFUND").get("priority_requested_at"),"other requested channels still pending");
        }finally{sync.stop();}
    }
    @Test void priorityErrorRetainsRequestAndRetriesWithoutDiscardingProgress(){
        String id=shop("retry");request(id);onlyOrdersDue();
        Map<String,Object> cp=Map.of("from",100L,"to",200L,"until",200L,"coverage","2026-08-01","cursor","keep");
        db.update("UPDATE finance_sync_states SET checkpoint_json=? WHERE shop_id=? AND channel='ORDER'",repo.write(cp),id);
        var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenThrow(bad("temporary"));
        var sync=new FinanceSync(repo,market);
        try{
            sync.tick();var failed=state(id,"ORDER");
            assertEquals("ERROR",failed.get("status"));assertNotNull(failed.get("priority_requested_at"));
            assertEquals("keep",repo.read(text(failed.get("checkpoint_json"))).get("cursor"));
            assertNull(failed.get("last_progress_at"));assertTrue(instant(failed.get("next_at")).isAfter(Instant.now()));
            request(id);assertEquals(failed.get("next_at"),state(id,"ORDER").get("next_at"));
        }finally{sync.stop();}
    }
    @Test void busyShopAndSharedRateLimitDoNotBlockOtherEligibleWork(){
        String busy=shop("busy"),limited=shop("limited"),ready=shop("ready");request(busy);request(limited);
        onlyOrdersDue();
        db.update("UPDATE finance_sync_states SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),lease_token='active',status='RUNNING' WHERE shop_id=? AND channel='REFUND'",busy);
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",limited);
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY) WHERE shop_id=?",limited);
        db.update("UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6) WHERE shop_id=? AND channel='REFUND'",limited);
        FinanceRefundPacing.cooldown(repo);
        try(var claim=new FinanceSyncClaims(repo).claim()){
            assertEquals(ready,claim.row().get("shop_id"));
        }
        var rows=service.syncStates(owner);
        assertEquals("WAITING_SHOP",rows.stream().filter(r->busy.equals(r.get("shop_id"))&&"ORDER".equals(r.get("channel"))).findFirst().orElseThrow().get("execution_state"));
        assertEquals("API_WAIT",rows.stream().filter(r->limited.equals(r.get("shop_id"))&&"REFUND".equals(r.get("channel"))).findFirst().orElseThrow().get("execution_state"));
    }
    @Test void crowdedPlatformCannotHideOtherPlatformsAvailableSlots(){
        var ids=new ArrayList<String>();
        for(int i=0;i<35;i++)ids.add(shop("crowded-"+i));
        String tk=shop("other-platform");
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",tk);
        onlyOrdersDue();
        db.update("UPDATE finance_sync_states SET priority_requested_at=? WHERE shop_id<>? AND channel='ORDER'",Timestamp.from(Instant.now().minusSeconds(60)),tk);
        var claims=new FinanceSyncClaims(repo);
        try(var first=claims.claim();var second=claims.claim();var third=claims.claim()){
            assertEquals("SHOPEE",first.row().get("platform"));assertEquals("SHOPEE",second.row().get("platform"));
            assertEquals(tk,third.row().get("shop_id"));
        }
    }
    @Test void dispatcherImmediatelyFillsExistingCapacityWithBoundedWork(){
        for(int i=0;i<5;i++)shop("dispatch-"+i);
        onlyOrdersDue();
        db.update("UPDATE finance_sync_states SET checkpoint_json='{\"from\":100,\"to\":200,\"until\":200,\"coverage\":\"2026-08-01\"}' WHERE channel='ORDER'");
        var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenAnswer(call->new FinanceMarketplace.Step(call.getArgument(2),true));
        var sync=new FinanceSync(repo,market);
        try{
            sync.dispatch();verify(market,times(4)).step(any(),anyMap(),anyMap());
            assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE channel='ORDER' AND status='SUCCESS'",Integer.class));
            sync.dispatch();verify(market,times(5)).step(any(),anyMap(),anyMap());
        }finally{sync.stop();}
    }
    @Test void pendingBillCountsRemainCompanyScopedAndOnlyAppearOnSettlement(){
        String id=shop("bill-count");
        record(owner,id,"BILL","pending-one",java.math.BigDecimal.ONE,Map.of());
        record(owner,id,"BILL","pending-two",java.math.BigDecimal.ONE,Map.of());
        db.update("UPDATE finance_records SET status='DATE_PENDING' WHERE shop_id=?",id);
        String foreign=connect(other,false);
        record(other,foreign,"BILL","foreign",java.math.BigDecimal.ONE,Map.of());
        db.update("UPDATE finance_records SET status='DATE_PENDING' WHERE shop_id=?",foreign);
        var rows=service.syncStates(owner);
        assertTrue(rows.stream().noneMatch(r->foreign.equals(r.get("shop_id"))));
        for(var row:rows)assertEquals("SETTLEMENT".equals(row.get("channel"))?2:0,number(row.get("pending_date_bills")));
    }
}
