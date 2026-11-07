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
    private final java.util.concurrent.atomic.AtomicBoolean repairRegistered=new java.util.concurrent.atomic.AtomicBoolean();
    private final FinanceSyncClaims claims;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newScheduledThreadPool(4,r->{var t=new Thread(r,"finance-shop-sync");t.setDaemon(true);return t;});
    private final java.util.concurrent.ScheduledExecutorService heartbeat=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-sync-lease");t.setDaemon(true);return t;});
    public FinanceSync(FinanceRepository repo,FinanceMarketplace marketplace){this.repo=repo;this.marketplace=marketplace;this.claims=new FinanceSyncClaims(repo);}
    @PostConstruct public void start(){for(int slot=0;slot<4;slot++)worker.scheduleWithFixedDelay(()->{try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::dispatch);if(verified.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).info("Finance shop sync ready");}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance sync dispatcher temporarily unavailable",e);}},30_000+slot*250,1000,java.util.concurrent.TimeUnit.MILLISECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();heartbeat.shutdownNow();}
    public void tick(){runNext();}
    // Fill existing worker capacity without an extra idle second after every
    // completed page. Each new claim rechecks priority, shop delay and API budgets.
    void dispatch(){for(int i=0;i<4&&!Thread.currentThread().isInterrupted();i++)if(!runNext())return;}
    private boolean runNext(){
        if(!repairRegistered.get()){
            repo.jdbc().update("""
                INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel)
                SELECT organization_id,user_id,id,'ORDER_REPAIR' FROM finance_connections
                WHERE enabled=TRUE AND status='ACTIVE'
                """);
            repairRegistered.set(true);
        }
        var claim=claims.claim();if(claim==null)return false;
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
            if(fresh&&"ORDER_REPAIR".equals(row.get("channel"))){
                cp.put("from",0);cp.put("to",0);cp.put("until",0);
                cp.put("coverage",now.atZone(zone).toLocalDate().toString());
            }else if(fresh){
                LocalDate today=now.atZone(zone).toLocalDate();boolean initial=row.get("last_success_at")==null;
                Instant last=initial?null:instant(row.get("last_success_at"));
                boolean nightly=last!=null&&last.atZone(zone).toLocalDate().isBefore(today);
                boolean live="ORDER_LIVE".equals(row.get("channel"));
                LocalDate start=live?(initial?today:today.minusDays(1)):FinanceSyncPlan.start(
                    today,initial,nightly,row.get("priority_requested_at")!=null,text(row.get("channel")));
                if("SETTLEMENT".equals(row.get("channel"))){
                    var pending=repo.jdbc().queryForObject("""
                        SELECT MIN(record_date) FROM finance_records
                        WHERE organization_id=? AND shop_id=? AND kind='BILL' AND source='API' AND status='DATE_PENDING'
                        """,java.sql.Date.class,s.organizationId(),row.get("shop_id"));
                    // Keep delayed completions discoverable even beyond the routine lookback.
                    if(pending!=null&&pending.toLocalDate().minusDays(1).isBefore(start))start=pending.toLocalDate().minusDays(1);
                }
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
                else if("REFUND".equals(row.get("channel"))&&!initial)from=last.minusSeconds(300).getEpochSecond();
                else if("ORDER".equals(row.get("channel"))&&!initial&&!nightly&&row.get("priority_requested_at")==null)
                    from=Math.max(from,last.minusSeconds(300).getEpochSecond());
                cp.put("from",from);cp.put("to",Math.min(until,from+7*86400L));
                cp.put("until",until);cp.put("coverage",start.toString());cp.put("run",UUID.randomUUID().toString());
            }
            LocalDate targetMonth=now.atZone(zone).toLocalDate().withDayOfMonth(1).minusMonths(1);
            boolean needsMonth=!targetMonth.toString().equals(text(row.get("completed_month")));
            var prioritized=FinanceSyncPlan.previousMonthFirst(cp,text(row.get("channel")),zone,now,needsMonth);
            prioritized=FinanceSyncPlan.missingSettlementsFirst(prioritized,text(row.get("platform")),
                text(row.get("channel")),zone,now,needsMonth);
            if(fresh||!prioritized.equals(cp)){
                cp=prioritized;
                // Persist before requests; retries retain the month plan and exact historical resume point.
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=? WHERE "+where,repo.write(cp),keys[0],keys[1],keys[2],keys[3],keys[4]);
            }
            // A fixed bounded range is retained across pages; retry never loses the current cursor.
            var page=marketplace.step(s,row,cp);
            LocalDate completedMonth=FinanceSyncPlan.completedMonth(cp,page,zone);
            var step=nextWindow(cp,page);Map<String,Object> next=new LinkedHashMap<>(step.checkpoint());
            FinanceRefundPacing.succeeded(repo,row);
            boolean done=step.done();
            if(done){
                boolean live="ORDER_LIVE".equals(row.get("channel"));
                int seconds=live?60:"REFUND".equals(row.get("channel"))?FinanceRefundPacing.refreshSeconds(text(row.get("shop_id"))):
                    "ORDER_REPAIR".equals(row.get("channel"))?21600:Set.of("SETTLEMENT","PENDING").contains(text(row.get("channel")))?3600:600;
                // The live watermark is the request boundary, not completion time:
                // orders arriving while a long page chain runs must be fetched next time.
                Instant completedThrough=live||"REFUND".equals(row.get("channel"))?Instant.ofEpochSecond(number(cp.get("until"))):Instant.now();
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=NULL,status='SUCCESS',error_message=NULL,failures=0,"
                        +"lease_until=NULL,lease_token=NULL,priority_requested_at=NULL,last_progress_at=UTC_TIMESTAMP(6),"
                        +"last_success_at=?,coverage_start=COALESCE(coverage_start,?),next_at=?,"
                        +"completed_month=COALESCE(?,completed_month),month_completed_at=CASE WHEN ? IS NOT NULL THEN UTC_TIMESTAMP(6) ELSE month_completed_at END WHERE "+where,
                        Timestamp.from(completedThrough),cp.get("coverage"),Timestamp.from(Instant.now().plusSeconds(seconds)),
                        completedMonth,completedMonth,keys[0],keys[1],keys[2],keys[3],keys[4]);
            }else{
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=?,status='SYNCING',error_message=NULL,failures=0,"
                        +"lease_until=NULL,lease_token=NULL,last_progress_at=UTC_TIMESTAMP(6),next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND),"
                        +"completed_month=COALESCE(?,completed_month),month_completed_at=CASE WHEN ? IS NOT NULL THEN UTC_TIMESTAMP(6) ELSE month_completed_at END WHERE "+where,
                        repo.write(next),completedMonth,completedMonth,keys[0],keys[1],keys[2],keys[3],keys[4]);
            }
        }catch(Exception failure){
            long attempts=number(row.get("failures"))+1;long seconds=Math.min(21600,60L*(1L<<Math.min(8,attempts)));
            boolean limited=FinanceRefundPacing.applies(row)&&FinanceRefundPacing.throttled(failure);
            Instant retry=limited?FinanceRefundPacing.cooldown(repo):Instant.now().plusSeconds(seconds);
            if(text(failure.getMessage()).contains("交易数量不完整")){
                // The upstream collection changed during pagination. Replay this fixed range, with upsert IDs.
                if("PENDING".equals(row.get("channel"))){
                    // A successful replay must remove transactions that settled
                    // after the failed pass, rather than retaining its old marks.
                    repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=JSON_SET(JSON_REMOVE(checkpoint_json,'$.cursor','$.pendingCount'),'$.run',?) WHERE "+where,
                        UUID.randomUUID().toString(),keys[0],keys[1],keys[2],keys[3],keys[4]);
                }else repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=JSON_REMOVE(checkpoint_json,'$.transactionCursor','$.transactionCount') WHERE "+where,keys);
            }
            String message=publicError(failure);
            if(limited)message="TikTok 退款接口触发限流，全部店铺已共享降频并等待重试；原时间范围与分页进度保留";
            if(message.length()>700)message=message.substring(0,700);
            repo.jdbc().update("UPDATE finance_sync_states SET status=?,error_message=?,failures=failures+1,lease_until=NULL,lease_token=NULL,next_at=? WHERE "+where,
                    limited?"RATE_LIMITED":"ERROR",message,Timestamp.from(retry),keys[0],keys[1],keys[2],keys[3],keys[4]);
        }finally{renewal.cancel(false);claim.close();}
        return true;
    }
    static String publicError(Throwable failure){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        boolean database=false;
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause()){
            database|=cause instanceof org.springframework.dao.DataAccessException;
            if(cause instanceof java.sql.SQLException sql){
                int code=sql.getErrorCode();
                if(code==1114||code==1021)
                    return "数据库存储空间不足（"+code+"），同步进度已保留，空间恢复后自动重试";
                if(code==1205||code==1213)
                    return "数据库暂忙（"+code+"），同步进度已保留，稍后自动重试";
                return "数据库读写暂未完成"+(code>0?"（"+code+"）":"")+"，同步进度已保留，稍后自动重试";
            }
        }
        if(database)return "数据库连接或读写暂未完成，同步进度已保留，稍后自动重试";
        return text(failure.getMessage()).replaceAll("(?i)(token|secret|app_key|sign)[=:][^\\s,]+","$1=[已隐藏]");
    }
    static FinanceMarketplace.Step nextWindow(Map<String,Object> cp,FinanceMarketplace.Step step){
        return FinanceSyncPlan.nextWindow(cp,step);
    }
}
