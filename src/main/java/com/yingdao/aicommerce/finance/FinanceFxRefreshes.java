package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public-reference refresh work only. Saturation leaves callers explicitly missing/stale. */
final class FinanceFxRefreshes implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final Duration retryAfter;
    private final Consumer<Runnable> runIfOpen;
    private final Set<String> active=new HashSet<>();
    private final LinkedHashMap<String,Instant> completed=new LinkedHashMap<>();
    FinanceFxRefreshes(){this(2,32,Clock.systemUTC(),Duration.ofSeconds(30));}
    FinanceFxRefreshes(int workers,int capacity,Clock clock,Duration retryAfter){this(workers,capacity,clock,retryAfter,MaintenanceGate::runIfOpen);}
    FinanceFxRefreshes(int workers,int capacity,Clock clock,Duration retryAfter,Consumer<Runnable> runIfOpen){
        this.clock=clock;this.retryAfter=retryAfter;this.runIfOpen=runIfOpen;
        executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),task->{
            var thread=new Thread(task,"finance-fx-refresh");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    synchronized boolean submit(String key,Runnable refresh){
        Instant previous=completed.get(key);
        if(active.contains(key)||previous!=null&&previous.plus(retryAfter).isAfter(clock.instant()))return false;
        active.add(key);
        try{
            executor.execute(()->{
                boolean[] started={false};
                try{runIfOpen.accept(()->{started[0]=true;refresh.run();});}
                catch(RuntimeException unavailable){/* Retry on a later read; never publish a successful refresh. */}
                finally{synchronized(this){
                    active.remove(key);
                    // Maintenance skips did not attempt a provider refresh and can retry after reopening.
                    if(started[0])completed.put(key,clock.instant());
                    while(completed.size()>128)completed.remove(completed.keySet().iterator().next());
                }}
            });
            return true;
        }catch(RejectedExecutionException saturated){active.remove(key);return false;}
    }
    @Override public void close(){
        executor.shutdownNow();
        try{executor.awaitTermination(5,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
