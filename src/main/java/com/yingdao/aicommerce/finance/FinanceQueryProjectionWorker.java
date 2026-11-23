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
    private int eventTurn,buildTurn;
    private final ScheduledExecutorService workers;
    private Semaphore computeSlots;
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceQueryProjectionWorker(FinanceQueryProjections projections){this(projections,Clock.systemUTC());}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock){this(projections,clock,System::nanoTime);}
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock,LongSupplier nanoTime){
        this(projections,clock,nanoTime,Executors.newScheduledThreadPool(projections.publishedPreviousMonthEnabled()?5:4,r->{var t=new Thread(r,"finance-query-projection");t.setDaemon(true);return t;}));
    }
    FinanceQueryProjectionWorker(FinanceQueryProjections projections,Clock clock,LongSupplier nanoTime,ScheduledExecutorService workers){this.projections=projections;this.clock=clock;this.nanoTime=nanoTime;this.workers=workers;}
    @org.springframework.beans.factory.annotation.Value("${app.finance.projection-compute-slots:0}")
    void configureComputeSlots(int slots){
        if(slots<0||slots>3)throw new IllegalArgumentException("Finance projection compute slots must be between 0 and 3");
        computeSlots=slots==0?null:new Semaphore(slots,true);
    }

    @PostConstruct public void start(){
        workers.schedule(this::scheduledEvents,20,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->runCompute(this::tickBuild),21,1,TimeUnit.SECONDS);
        // Each daily reader needs its own refresh cadence. The same compute
        // permits still cap concurrent database work.
        workers.scheduleWithFixedDelay(()->runCompute(()->projections.advanceDashboardOrderFacts(clock.instant())),
            22,1,TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(()->runCompute(()->projections.advanceDashboardLedgerDays(clock.instant())),
            22,1,TimeUnit.SECONDS);
        if(projections.publishedPreviousMonthEnabled())
            workers.scheduleWithFixedDelay(()->runCompute(()->projections.refreshPublishedPreviousMonth(clock.instant())),
                23,2,TimeUnit.SECONDS);
    }
    void tickBuild(){
        var now=clock.instant();
        // Leave a build slot for history even when recent changes never stop.
        if(++buildTurn%4==0){
            if(!projections.buildHistorical(now)&&stillOpen())projections.buildRecent(now);
        }else if(!projections.buildRecent(now)&&stillOpen())projections.buildHistorical(now);
    }
    private static boolean stillOpen(){
        return !Thread.currentThread().isInterrupted()&&!com.yingdao.aicommerce.ops.MaintenanceGate.instance().closed();
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
            if(++eventTurn%4==0)drained=projections.drainEvents();
            else{
                drained=projections.drainRecentEvents();
                if(drained==0&&stillOpen())drained=projections.drainEvents();
            }
            if(drained<FinanceQueryProjections.EVENT_BATCH||nanoTime.getAsLong()-started>=EVENT_BUDGET_NANOS||!stillOpen())break;
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
    private void runCompute(Runnable action){
        var slots=computeSlots;
        if(slots==null){run(action);return;}
        if(!stillOpen())return;
        try{slots.acquire();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        // Wait outside the maintenance gate. An event drainer always has its own
        // thread and never waits for a compute slot.
        try{run(action);}finally{slots.release();}
    }
    private void run(Runnable action){
        if(FinanceLedgerIndexWindow.preparingIndex())return;
        try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(action);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance projection worker deferred: {}",failure.getClass().getSimpleName());}
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
