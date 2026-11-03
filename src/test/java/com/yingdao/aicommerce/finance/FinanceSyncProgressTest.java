package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSyncProgressTest {
    final Instant now=Instant.parse("2026-11-03T02:00:00Z");
    Map<String,Object> row(String shop,String channel,String status){
        return new LinkedHashMap<>(Map.of("shop_id",shop,"channel",channel,"status",status,"platform","SHOPEE","enabled",true,
            "connection_status","ACTIVE","next_at",now.minusSeconds(60)));
    }
    @Test void distinguishesLeaseQueueCooldownAndSchedulesWithoutMutatingSource(){
        var running=row("one","ORDER","RUNNING");running.put("lease_until",now.plusSeconds(60));
        var waiting=row("one","REFUND","SYNCING");
        var retry=row("two","ORDER","ERROR");retry.put("next_at",now.plusSeconds(120));retry.put("failures",2);
        var complete=row("three","ORDER","SUCCESS");complete.put("next_at",now.plusSeconds(120));complete.put("last_success_at",now.minusSeconds(10));
        var queued=row("four","ORDER","RUNNING");queued.put("lease_until",now.minusSeconds(1));
        var output=FinanceSyncProgress.describe(List.of(running,waiting,retry,complete,queued),List.of(),now);
        assertEquals(List.of("RUNNING","WAITING_SHOP","RETRY_WAIT","SUCCESS","QUEUED"),output.stream().map(r->r.get("execution_state")).toList());
        assertEquals("ORDER",output.get(1).get("blocking_channel"));assertEquals(1,output.get(4).get("queue_position"));
        assertEquals(false,output.get(0).get("initial_sync_complete"));assertEquals(true,output.get(3).get("initial_sync_complete"));
        assertFalse(running.containsKey("execution_state"));
    }
    @Test void priorityRanksArePerShopPerPlatformAndPropagateToFinishedChannels(){
        var a=row("a","ORDER","SYNCING");a.put("priority_requested_at",now.minusSeconds(20));
        var aDone=row("a","REFUND","SUCCESS");aDone.put("next_at",now.plusSeconds(60));
        var b=row("b","ORDER","READY");b.put("priority_requested_at",now.minusSeconds(10));
        var tk=row("tk","ORDER","READY");tk.put("platform","TIKTOK_SHOP");tk.put("priority_requested_at",now.minusSeconds(5));
        var auto=row("auto","ORDER","READY");auto.put("next_at",now.minusSeconds(86400));
        var output=FinanceSyncProgress.describe(List.of(auto,b,tk,a,aDone),List.of(),now);
        assertEquals(Arrays.asList(3,2,1,1,null),output.stream().map(r->r.get("queue_position")).toList());
        assertEquals(1,output.get(4).get("priority_queue_position"));assertEquals(1,output.get(4).get("priority_pending_channels"));
    }
    @Test void rateLimitBudgetAndUtcDateSerializationAreExplicit(){
        var limited=row("a","REFUND","RATE_LIMITED");limited.put("next_at",LocalDateTime.ofInstant(now.plusSeconds(120),ZoneOffset.UTC));
        var api=row("b","REFUND","READY");
        var paused=row("c","REFUND","READY");paused.put("enabled",false);
        var output=FinanceSyncProgress.describe(List.of(limited,api,paused),
            List.of(Map.of("platform","SHOPEE","channel","REFUND","next_allowed_at",now.plusSeconds(300))),now);
        assertEquals(List.of("RATE_LIMITED","API_WAIT","DISABLED"),output.stream().map(r->r.get("execution_state")).toList());
        assertEquals(now.plusSeconds(120),output.getFirst().get("next_at"));
        assertEquals(now.plusSeconds(300),output.get(1).get("platform_retry_at"));
    }
}
