package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Prepare the default overview and today's screen before a user's first visit. */
@Component
public class FinanceCacheWarmer {
    private final FinanceRepository repo;private final FinanceService service;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{
        var t=new Thread(r,"finance-cache-prewarm");t.setDaemon(true);return t;});
    public FinanceCacheWarmer(FinanceRepository repo,FinanceService service){this.repo=repo;this.service=service;}
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(()->{
        try{
            for(var row:repo.jdbc().queryForList("SELECT DISTINCT organization_id FROM finance_connections WHERE enabled=TRUE AND status='ACTIVE' ORDER BY organization_id LIMIT 32"))
                service.prewarm(companyScope(text(row.get("organization_id"))));
        }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance snapshot prewarm deferred: {}",failure.getClass().getSimpleName());}
    }),10,30,TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
}
