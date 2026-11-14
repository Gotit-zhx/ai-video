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
    private final ScheduledExecutorService workers;
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceQueryProjectionWorker(FinanceQueryProjections projections){this(projections,Clock.systemUTC());}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock){this(projections,clock,System::nanoTime);}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock,LongSupplier nanoTime){
        this(projections,clock,nanoTime,Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"finance-query-projection");t.setDaemon(true);return t;}));
    }
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock,LongSupplier nanoTime,ScheduledExecutorService workers){this.projections=projections;this.clock=clock;this.nanoTime=nanoTime;this.workers=workers;}
    @PostConstruct public void start(){
        workers.schedule(this::scheduledEvents,20,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->run(projections::buildNext),21,1,TimeUnit.SECONDS);
    }
    /** One self-scheduling lane: committed full batches catch up without extending a transaction or tick. */
    private void scheduledEvents(){
        if(workers.isShutdown()||Thread.currentThread().isInterrupted())return;
        boolean full=tickEvents();
        if(workers.isShutdown()||Thread.currentThread().isInterrupted())return;
        try{workers.schedule(this::scheduledEvents,full?100:1000,TimeUnit.MILLISECONDS);}
        catch(RejectedExecutionException stopped){if(!workers.isShutdown())throw stopped;}
    }
    /** The existing event lane warms on its first eligible tick, hourly, and immediately at a business-month boundary. */
    boolean tickEvents(){var full=new boolean[]{false};run(()->{
        long started=nanoTime.getAsLong();
        // Each batch retains its own bounded transaction. Fast catch-up shares a
        // short tick; slow work yields, and maintenance never starts another batch.
        int drained=0;
        for(int batch=0;batch<MAX_EVENT_BATCHES;batch++){
            drained=projections.drainEvents();
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
        full[0]=drained==FinanceQueryProjections.EVENT_BATCH;
    });return full[0];}
    private void run(Runnable action){
        try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(action);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance projection worker deferred: {}",failure.getClass().getSimpleName());}
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
