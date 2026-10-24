package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Component
public class FinanceShopDeletionWorker {
    private final FinanceRepository repo;private final FinanceService service;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{
        var t=new Thread(r,"finance-shop-deletion");t.setDaemon(true);return t;
    });
    public FinanceShopDeletionWorker(FinanceRepository repo,FinanceService service){this.repo=repo;this.service=service;}
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(this::tick,30,1,TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    public void tick(){
        // Bounded work prevents large histories from monopolizing the connection pool.
        for(int i=0;i<4&&!Thread.currentThread().isInterrupted();i++){
            String[] attempted=new String[2];
            long[] checkpoint=new long[2];
            try{
                String organization=repo.transaction(()->new FinanceShopDeletion(repo).batch(job->{
                    attempted[0]=text(job.get("organization_id"));attempted[1]=text(job.get("shop_id"));
                    checkpoint[0]=number(job.get("stage"));checkpoint[1]=number(job.get("deleted_rows"));
                }));
                if(organization==null)return;
                if(!organization.isEmpty())service.invalidateAfterCommit(companyScope(organization));
            }catch(RuntimeException error){
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance shop deletion deferred: {}",error.getClass().getSimpleName());
                // Only this rolled-back job is delayed. Committed batches remain resumable.
                if(attempted[0]!=null)try{repo.jdbc().update("""
                    UPDATE finance_shop_deletions SET status='RETRY',failures=failures+1,
                      next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND),updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=? AND shop_id=? AND status<>'COMPLETE' AND stage=? AND deleted_rows=?
                    """,attempted[0],attempted[1],checkpoint[0],checkpoint[1]);}catch(RuntimeException ignored){/* Retry the dispatcher on its next tick. */}
                return;
            }
        }
    }
}
