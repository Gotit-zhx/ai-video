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
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-shop-sync");t.setDaemon(true);return t;});
    public FinanceSync(FinanceRepository repo,FinanceMarketplace marketplace){this.repo=repo;this.marketplace=marketplace;}
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->{try{tick();if(verified.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).info("Finance shop sync ready");}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance sync dispatcher temporarily unavailable",e);}},45,10,java.util.concurrent.TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    public void tick(){
        var due=repo.jdbc().queryForList("""
            SELECT st.*,f.timezone,f.currency FROM finance_sync_states st JOIN finance_shops f
            ON f.organization_id=st.organization_id AND f.user_id=st.user_id AND f.shop_id=st.shop_id
            JOIN shops sh ON sh.id=f.shop_id AND sh.organization_id=f.organization_id
            WHERE f.enabled=TRUE AND sh.status='ACTIVE' AND st.next_at<=UTC_TIMESTAMP(6)
              AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6)) ORDER BY st.next_at LIMIT 1
            """);
        if(due.isEmpty())return;var row=due.getFirst();Scope s=new Scope(text(row.get("organization_id")),text(row.get("user_id")));
        Object[] keys={s.organizationId(),s.userId(),row.get("shop_id"),row.get("channel")};
        String where="organization_id=? AND user_id=? AND shop_id=? AND channel=?";
        int claimed=repo.jdbc().update("UPDATE finance_sync_states SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),status='RUNNING' WHERE "+where
                +" AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",keys);
        if(claimed!=1)return;
        try{
            Map<String,Object> cp=row.get("checkpoint_json")==null?new LinkedHashMap<>():new LinkedHashMap<>(repo.read(text(row.get("checkpoint_json"))));
            Instant now=Instant.now();ZoneId zone=ZoneId.of(text(row.get("timezone")));
            if(cp.isEmpty()){
                LocalDate today=now.atZone(zone).toLocalDate();boolean initial=row.get("last_success_at")==null;
                Instant last=initial?null:instant(row.get("last_success_at"));
                boolean nightly=last!=null&&last.atZone(zone).toLocalDate().isBefore(today);
                LocalDate start=initial?today.minusDays(89):nightly?today.minusDays(30):today.minusDays(1);
                if("PENDING".equals(row.get("channel"))){
                    start=today.minusDays(89);
                    var oldest=repo.jdbc().queryForList("SELECT MIN(record_date) day FROM finance_records WHERE organization_id=? AND user_id=? AND shop_id=? AND kind='PENDING'",
                            s.organizationId(),s.userId(),row.get("shop_id"));
                    if(!oldest.isEmpty()&&oldest.getFirst().get("day")!=null){
                        LocalDate day=LocalDate.parse(text(oldest.getFirst().get("day")));if(day.isBefore(start))start=day;
                    }
                }
                long until=now.getEpochSecond();
                cp.put("from",start.atStartOfDay(zone).toEpochSecond());cp.put("to",Math.min(until,start.plusDays(7).atStartOfDay(zone).toEpochSecond()));
                cp.put("until",until);cp.put("coverage",start.toString());cp.put("run",UUID.randomUUID().toString());
                // Persist a fresh range before requests so timeouts/restarts retry exactly that range.
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=? WHERE "+where,repo.write(cp),keys[0],keys[1],keys[2],keys[3]);
            }
            // A fixed bounded range is retained across pages; retry never loses the current cursor.
            var step=marketplace.step(s,row,cp);Map<String,Object> next=new LinkedHashMap<>(step.checkpoint());
            boolean done=step.done();
            if(done&&number(cp.get("to"))<number(cp.get("until"))){
                long from=number(cp.get("to"));next=new LinkedHashMap<>();
                next.put("from",from);next.put("to",Math.min(from+7*86400L,number(cp.get("until"))));
                next.put("until",cp.get("until"));next.put("coverage",cp.get("coverage"));done=false;
                next.put("run",UUID.randomUUID().toString());
            }
            if(done){
                int seconds=Set.of("SETTLEMENT","PENDING").contains(text(row.get("channel")))?3600:600;
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=NULL,status='SUCCESS',error_message=NULL,failures=0,"
                        +"lease_until=NULL,last_success_at=UTC_TIMESTAMP(6),coverage_start=COALESCE(coverage_start,?),next_at=? WHERE "+where,
                        cp.get("coverage"),Timestamp.from(now.plusSeconds(seconds)),keys[0],keys[1],keys[2],keys[3]);
            }else{
                repo.jdbc().update("UPDATE finance_sync_states SET checkpoint_json=?,status='SYNCING',error_message=NULL,failures=0,"
                        +"lease_until=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 SECOND) WHERE "+where,
                        repo.write(next),keys[0],keys[1],keys[2],keys[3]);
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
            repo.jdbc().update("UPDATE finance_sync_states SET status='ERROR',error_message=?,failures=failures+1,lease_until=NULL,next_at=? WHERE "+where,
                    message,Timestamp.from(Instant.now().plusSeconds(seconds)),keys[0],keys[1],keys[2],keys[3]);
        }
    }
}
