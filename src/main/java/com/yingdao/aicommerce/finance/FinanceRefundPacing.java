package com.yingdao.aicommerce.finance;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One durable refund request budget for the shared TikTok application, across all shops. */
final class FinanceRefundPacing {
    static boolean applies(Map<String,Object> state){return "TIKTOK_SHOP".equals(state.get("platform"))&&"REFUND".equals(state.get("channel"));}
    static boolean throttled(Throwable failure){
        String message=text(failure.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("http 429")||message.contains("36009002")||message.contains("too many requests");
    }
    // Called within the short shop-claim transaction; no waiting and no HTTP under this lock.
    static boolean reserve(FinanceRepository repo,Map<String,Object> state){
        if(!applies(state))return true;
        var rows=repo.jdbc().queryForList("""
            SELECT interval_seconds FROM finance_api_pacing WHERE platform='TIKTOK_SHOP' AND channel='REFUND'
              AND next_allowed_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED
            """);
        if(rows.isEmpty())return false;
        repo.jdbc().update("""
            UPDATE finance_api_pacing SET next_allowed_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL interval_seconds SECOND)
            WHERE platform='TIKTOK_SHOP' AND channel='REFUND'
            """);
        return true;
    }
    static Instant cooldown(FinanceRepository repo){
        return repo.transaction(()->{
            var current=repo.jdbc().queryForMap("SELECT interval_seconds,throttle_count FROM finance_api_pacing WHERE platform='TIKTOK_SHOP' AND channel='REFUND' FOR UPDATE");
            long count=number(current.get("throttle_count"))+1;
            int interval=(int)Math.min(60,Math.max(5,number(current.get("interval_seconds")))*2);
            long seconds=Math.min(3600,300L*(1L<<Math.min(4,count-1)));
            Instant until=Instant.now().plusSeconds(seconds);
            repo.jdbc().update("""
                UPDATE finance_api_pacing SET interval_seconds=?,throttle_count=?,success_count=0,next_allowed_at=GREATEST(next_allowed_at,?)
                WHERE platform='TIKTOK_SHOP' AND channel='REFUND'
                """,interval,count,Timestamp.from(until));
            return until;
        });
    }
    static void succeeded(FinanceRepository repo,Map<String,Object> state){
        if(!applies(state))return;
        repo.jdbc().update("""
            UPDATE finance_api_pacing SET
              interval_seconds=IF(success_count>=59,GREATEST(5,interval_seconds DIV 2),interval_seconds),
              throttle_count=IF(success_count>=59,0,throttle_count),
              success_count=IF(success_count>=59,0,success_count+1)
            WHERE platform='TIKTOK_SHOP' AND channel='REFUND'
            """);
    }
    static int refreshSeconds(String shop){return 900+Math.floorMod(shop.hashCode(),121);}
}
