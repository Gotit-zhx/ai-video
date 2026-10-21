package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Component
public class FinanceSync {
    final FinanceRepository repo;final FinanceMarketplace marketplace;
    private final java.util.concurrent.atomic.AtomicBoolean verified=new java.util.concurrent.atomic.AtomicBoolean();
    private final FinanceSyncClaims claims;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newScheduledThreadPool(4,r->{var t=new Thread(r,"finance-shop-sync");t.setDaemon(true);return t;});
    private final java.util.concurrent.ScheduledExecutorService heartbeat=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-sync-lease");t.setDaemon(true);return t;});
    public FinanceSync(FinanceRepository repo,FinanceMarketplace marketplace){this.repo=repo;this.marketplace=marketplace;this.claims=new FinanceSyncClaims(repo);}
    @PostConstruct public void start(){for(int slot=0;slot<4;slot++)worker.scheduleWithFixedDelay(()->{try{tick();if(verified.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).info("Finance shop sync ready");}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance sync dispatcher temporarily unavailable",e);}},30_000+slot*250,1000,java.util.concurrent.TimeUnit.MILLISECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();heartbeat.shutdownNow();}
    public void tick(){
        var claim=claims.claim();if(claim==null)return;
        var row=claim.row();Scope s=new Scope(text(row.get("organization_id")),text(row.get("user_id")));
        Object[] keys={s.organizationId(),s.userId(),row.get("shop_id"),row.get("channel"),claim.token()};
        String where="organization_id=? AND user_id=? AND shop_id=? AND channel=? AND lease_token=?";
        var renewal=heartbeat.scheduleWithFixedDelay(()->{
            try{repo.jdbc().update("UPDATE finance_sync_states SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE "+where+" AND status='RUNNING'",keys);}
            catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance lease renewal deferred: {}",failure.getClass().getSimpleName());}
        },30,30,java.util.concurrent.TimeUnit.SECONDS);
        try{
            Map<String,Object> cp=row.get("checkpoint_json")==null?new LinkedHashMap<>():new LinkedHashMap<>(repo.read(text(row.get("checkpoint_json"))));
            Instant now=Instant.now();ZoneId zone=ZoneId.of(text(row.get("timezone")));
            boolean fresh=cp.isEmpty();
            if(fresh){
                LocalDate today=now.atZone(zone).toLocalDate();boolean initial=row.get("last_success_at")==null;
                Instant last=initial?null:instant(row.get("last_success_at"));
                boolean nightly=last!=null&&last.atZone(zone).toLocalDate().isBefore(today);
                boolean live="ORDER_LIVE".equals(row.get("channel"));
                LocalDate start=live?(initial?today:today.minusDays(1)):initial?today.minusDays(89):nightly?today.minusDays(30):today.minusDays(1);
                if("PENDING".equals(row.get("channel"))){
                    start=today.minusDays(89);
                    var oldest=repo.jdbc().queryForList("SELECT MIN(record_date) day FROM finance_records WHERE organization_id=? AND user_id=? AND shop_id=? AND kind='PENDING'",
                            s.organizationId(),s.userId(),row.get("shop_id"));
                    if(!oldest.isEmpty()&&oldest.getFirst().get("day")!=null){
                        LocalDate day=LocalDate.parse(text(oldest.getFirst().get("day")));if(day.isBefore(start))start=day;
                    }
                }
                long until=now.getEpochSecond();
                long from=start.atStartOfDay(zone).toEpochSecond();
                // Order APIs filter by update time. A five-minute overlap retains late updates
                // without scanning yesterday's entire order book on every live refresh.
                if(live&&!initial)from=last.minusSeconds(300).getEpochSecond();
                else if("ORDER".equals(row.get("channel"))&&!initial&&!nightly)from=Math.max(from,last.minusSeconds(300).getEpochSecond());
                cp.put("from",from);cp.put("to",Math.min(until,from+7*86400L));
                cp.put("until",until);cp.put("coverage",start.toString());cp.put("run",UUID.randomUUID().toString());
            }
            var prioritized=recentSettlementFirst(cp,row.get("last_success_at")==null,text(row.get("channel")),zone);
            if(fresh||!prioritized.equals(cp)){
                cp=prioritized;
                // Persist before requests; retries retain both the recent cursor and historical resume point.
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=? WHERE "+where,repo.write(cp),keys[0],keys[1],keys[2],keys[3],keys[4]);
            }
            // A fixed bounded range is retained across pages; retry never loses the current cursor.
            var step=nextWindow(cp,marketplace.step(s,row,cp));Map<String,Object> next=new LinkedHashMap<>(step.checkpoint());
            boolean done=step.done();
            if(done){
                boolean live="ORDER_LIVE".equals(row.get("channel"));
                int seconds=live?60:Set.of("SETTLEMENT","PENDING").contains(text(row.get("channel")))?3600:600;
                // The live watermark is the request boundary, not completion time:
                // orders arriving while a long page chain runs must be fetched next time.
                Instant completedThrough=live?Instant.ofEpochSecond(number(cp.get("until"))):Instant.now();
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=NULL,status='SUCCESS',error_message=NULL,failures=0,"
                        +"lease_until=NULL,lease_token=NULL,last_success_at=?,coverage_start=COALESCE(coverage_start,?),next_at=? WHERE "+where,
                        Timestamp.from(completedThrough),cp.get("coverage"),Timestamp.from(Instant.now().plusSeconds(seconds)),keys[0],keys[1],keys[2],keys[3],keys[4]);
            }else{
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=?,status='SYNCING',error_message=NULL,failures=0,"
                        +"lease_until=NULL,lease_token=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND) WHERE "+where,
                        repo.write(next),keys[0],keys[1],keys[2],keys[3],keys[4]);
            }
        }catch(Exception failure){
            long attempts=number(row.get("failures"))+1;long seconds=Math.min(21600,60L*(1L<<Math.min(8,attempts)));
            if(text(failure.getMessage()).contains("交易数量不完整")){
                // The upstream collection changed during pagination. Replay this fixed range, with upsert IDs.
                String paths="PENDING".equals(row.get("channel"))?"'$.cursor','$.pendingCount'":"'$.transactionCursor','$.transactionCount'";
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=JSON_REMOVE(checkpoint_json,"+paths+") WHERE "+where,keys);
            }
            String message=text(failure.getMessage()).replaceAll("(?i)(token|secret|app_key|sign)[=:][^\\s,]+","$1=[已隐藏]");
            if(message.length()>700)message=message.substring(0,700);
            repo.jdbc().update("UPDATE finance_sync_states SET status='ERROR',error_message=?,failures=failures+1,lease_until=NULL,lease_token=NULL,next_at=? WHERE "+where,
                    message,Timestamp.from(Instant.now().plusSeconds(seconds)),keys[0],keys[1],keys[2],keys[3],keys[4]);
        }finally{renewal.cancel(false);claim.close();}
    }
    static Map<String,Object> recentSettlementFirst(Map<String,Object> cp,boolean initial,String channel,ZoneId zone){
        if(!initial||!Set.of("SETTLEMENT","ORDER").contains(channel)||cp.containsKey("backfillResume")
                ||number(cp.get("recentWindowDays"))>=30
                ||number(cp.get("to"))>=number(cp.get("until")))return cp;
        long until=number(cp.get("until"));
        long recent=Instant.ofEpochSecond(until).atZone(zone).toLocalDate().minusDays(29).atStartOfDay(zone).toEpochSecond();
        recent=Math.max(recent,number(cp.get("to")));
        if(recent>=until)return cp;
        // Retain an in-flight page/bill exactly as it was, then resume it after recent data is visible.
        var resume=new LinkedHashMap<>(cp);resume.put("until",recent);resume.put("recentFirst",true);
        resume.put("recentWindowDays",30);
        var next=new LinkedHashMap<String,Object>();
        next.put("from",recent);next.put("to",Math.min(recent+7*86400L,until));next.put("until",until);next.put("coverage",cp.get("coverage"));
        next.put("run",UUID.randomUUID().toString());next.put("recentFirst",true);next.put("backfillResume",resume);
        next.put("recentWindowDays",30);
        return next;
    }
    static FinanceMarketplace.Step nextWindow(Map<String,Object> cp,FinanceMarketplace.Step step){
        if(!step.done())return step;
        if(number(cp.get("to"))<number(cp.get("until"))){
            long from=number(cp.get("to"));var next=new LinkedHashMap<String,Object>();
            next.put("from",from);next.put("to",Math.min(from+7*86400L,number(cp.get("until"))));
            next.put("until",cp.get("until"));next.put("coverage",cp.get("coverage"));next.put("run",UUID.randomUUID().toString());
            if(Boolean.TRUE.equals(cp.get("recentFirst")))next.put("recentFirst",true);
            if(cp.containsKey("recentWindowDays"))next.put("recentWindowDays",cp.get("recentWindowDays"));
            if(cp.containsKey("backfillResume"))next.put("backfillResume",cp.get("backfillResume"));
            return new FinanceMarketplace.Step(next,false);
        }
        if(cp.get("backfillResume") instanceof Map<?,?>)
            return new FinanceMarketplace.Step(new LinkedHashMap<>(map(cp.get("backfillResume"))),false);
        return step;
    }
}
