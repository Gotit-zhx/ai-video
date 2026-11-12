package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** Separate event draining prevents a slow backfill from postponing committed invalidations. */
@Component
public final class FinanceQueryProjectionWorker {
    private final FinanceQueryProjections projections;
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"finance-query-projection");t.setDaemon(true);return t;});
    public FinanceQueryProjectionWorker(FinanceQueryProjections projections){this.projections=projections;}
    @PostConstruct public void start(){
        workers.scheduleWithFixedDelay(()->run(projections::drainEvents),20,1,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->run(projections::buildNext),21,1,TimeUnit.SECONDS);
    }
    private void run(Runnable action){
        try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(action);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance projection worker deferred: {}",failure.getClass().getSimpleName());}
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
