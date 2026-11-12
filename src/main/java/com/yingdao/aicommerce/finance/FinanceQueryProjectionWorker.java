package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.*;

/** Separate event draining prevents a slow backfill from postponing committed invalidations. */
@Component
public final class FinanceQueryProjectionWorker {
    private static final Duration DEFAULT_WARM_INTERVAL=Duration.ofHours(1);
    private final FinanceQueryProjections projections;
    private final Clock clock;
    private Instant lastDefaultWarm;
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"finance-query-projection");t.setDaemon(true);return t;});
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceQueryProjectionWorker(FinanceQueryProjections projections){this(projections,Clock.systemUTC());}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock){this.projections=projections;this.clock=clock;}
    @PostConstruct public void start(){
        workers.scheduleWithFixedDelay(this::tickEvents,20,1,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->run(projections::buildNext),21,1,TimeUnit.SECONDS);
    }
    /** The existing event lane warms on its first eligible tick, hourly, and immediately at a business-month boundary. */
    void tickEvents(){run(()->{
        projections.drainEvents();
        Instant now=clock.instant();
        if(lastDefaultWarm==null||!now.isBefore(lastDefaultWarm.plus(DEFAULT_WARM_INTERVAL))
                ||!FinanceCalendar.previousMonth(now).equals(FinanceCalendar.previousMonth(lastDefaultWarm))){
            projections.warmDefaults(now);
            // Advance only after every company/platform request succeeds; INSERT IGNORE makes a partial retry safe.
            lastDefaultWarm=now;
        }
    });}
    private void run(Runnable action){
        try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(action);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance projection worker deferred: {}",failure.getClass().getSimpleName());}
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
