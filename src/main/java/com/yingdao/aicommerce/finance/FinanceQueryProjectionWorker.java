package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Separate event draining prevents a slow backfill from postponing committed invalidations. */
@Component
public final class FinanceQueryProjectionWorker {
    private static final Duration DEFAULT_WARM_INTERVAL=Duration.ofHours(1);
    private static final int MAX_EVENT_BATCHES=8;
    private static final long EVENT_BUDGET_NANOS=Duration.ofMillis(250).toNanos();
    private final FinanceQueryProjections projections;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private Instant lastDefaultWarm;
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"finance-query-projection");t.setDaemon(true);return t;});
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceQueryProjectionWorker(FinanceQueryProjections projections){this(projections,Clock.systemUTC());}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock){this(projections,clock,System::nanoTime);}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock,LongSupplier nanoTime){this.projections=projections;this.clock=clock;this.nanoTime=nanoTime;}
    @PostConstruct public void start(){
        workers.scheduleWithFixedDelay(this::tickEvents,20,1,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->run(projections::buildNext),21,1,TimeUnit.SECONDS);
    }
    /** The existing event lane warms on its first eligible tick, hourly, and immediately at a business-month boundary. */
    void tickEvents(){run(()->{
        long started=nanoTime.getAsLong();
        // Each batch retains its own bounded transaction. Fast catch-up shares a
        // short tick; slow work yields, and maintenance never starts another batch.
        for(int batch=0;batch<MAX_EVENT_BATCHES;batch++){
            int drained=projections.drainEvents();
            if(drained<FinanceQueryProjections.EVENT_BATCH||nanoTime.getAsLong()-started>=EVENT_BUDGET_NANOS
                    ||com.yingdao.aicommerce.ops.MaintenanceGate.instance().closed()||Thread.currentThread().isInterrupted())break;
        }
        if(com.yingdao.aicommerce.ops.MaintenanceGate.instance().closed()||Thread.currentThread().isInterrupted())return;
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
