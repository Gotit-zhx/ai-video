package com.yingdao.aicommerce.finance;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
final class FinanceWorkerCapacity {
    record Load(double cpu,double heap,double freeMemory,int waitingForDb,int remainingDb,int reservedDb) {
        boolean databaseBusy(){return waitingForDb>0||remainingDb<reservedDb;}
        boolean pressured(){return cpu>0.80||heap>0.80||freeMemory>=0&&freeMemory<0.15||databaseBusy();}
    }

    final class Permit implements AutoCloseable {
        private final boolean history;
        private boolean released;

        private Permit(boolean history){this.history=history;}
        boolean historyAllowed(){return history;}

        @Override public void close(){
            synchronized(FinanceWorkerCapacity.this){
                if(!released){released=true;active--;}
            }
        }
    }

    private final boolean enabled;
    private final int ceiling;
    private final Supplier<Load> sample;
    private final LongSupplier clock;
    private int limit,active,quiet;
    private long lastSample;
    private boolean hasSample,databaseBusy,historyAllowed=true;

    @org.springframework.beans.factory.annotation.Autowired
    FinanceWorkerCapacity(DataSource dataSource,io.micrometer.core.instrument.MeterRegistry metrics,
                          @Value("${APP_FINANCE_ADAPTIVE_WORKERS:false}") boolean enabled,
                          @Value("${APP_FINANCE_MAX_PULLS:7}") int ceiling){
        this(enabled,Math.clamp(ceiling,1,16),()->load(dataSource),System::nanoTime);
        io.micrometer.core.instrument.Gauge.builder("finance.background.active",this,FinanceWorkerCapacity::active).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("finance.background.limit",this,FinanceWorkerCapacity::limit).register(metrics);
        io.micrometer.core.instrument.Gauge.builder("finance.background.history.allowed",this,
                capacity->capacity.historyAllowed()?1:0).register(metrics);
    }

    FinanceWorkerCapacity(boolean enabled,int ceiling,Supplier<Load> sample,LongSupplier clock){
        this.enabled=enabled;this.ceiling=ceiling;this.sample=sample;this.clock=clock;
        this.limit=Math.min(3,ceiling);
    }

    static FinanceWorkerCapacity unrestricted(){return new FinanceWorkerCapacity(false,16,()->null,System::nanoTime);}

    synchronized Permit acquire(){
        if(enabled){
            long now=clock.getAsLong();
            if(!hasSample||now-lastSample>=TimeUnit.SECONDS.toNanos(2))refresh(now);
            if(databaseBusy||active>=limit)return null;
        }
        active++;
        return new Permit(historyAllowed);
    }

    synchronized Permit acquireBulk(){
        var permit=acquire();
        if(permit!=null&&!permit.historyAllowed()){permit.close();return null;}
        return permit;
    }

    private synchronized int active(){return active;}
    private synchronized int limit(){return enabled?limit:ceiling;}
    private synchronized boolean historyAllowed(){return historyAllowed;}

    private void refresh(long now){
        Load load=sample.get();
        databaseBusy=load.databaseBusy();
        historyAllowed=!load.pressured();
        if(!historyAllowed){limit=Math.max(1,limit/2);quiet=0;}
        else if(++quiet>=3){limit=Math.min(ceiling,limit+1);quiet=0;}
        hasSample=true;lastSample=now;
    }

    private static double availableMemory(com.sun.management.OperatingSystemMXBean system){
        // Unallocated pages are not the same as available memory on systems with reclaimable caches.
        // On hosts without an available-memory signal, use the JVM heap and CPU guard instead.
        if(!"Linux".equals(System.getProperty("os.name")))return -1;
        try{
            long total=-1,available=-1;
            for(String line:Files.readAllLines(Path.of("/proc/meminfo"))){
                if(line.startsWith("MemTotal:"))total=Long.parseLong(line.substring(9).trim().split("\\s+")[0])*1024L;
                if(line.startsWith("MemAvailable:"))available=Long.parseLong(line.substring(13).trim().split("\\s+")[0])*1024L;
            }
            if(total<=0||available<0)return -1;
            long constrained=system.getTotalMemorySize();
            if(constrained>0&&constrained<total*0.98){
                long free=system.getFreeMemorySize();
                if(free>=0)return Math.min((double)available/total,(double)free/constrained);
            }
            return (double)available/total;
        }catch(IOException|NumberFormatException unavailable){return -1;}
    }

    private static Load load(DataSource dataSource){
        int waiting=0,remaining=Integer.MAX_VALUE,reserved=0;
        if(dataSource instanceof HikariDataSource hikari&&hikari.getHikariPoolMXBean()!=null){
            var pool=hikari.getHikariPoolMXBean();
            waiting=pool.getThreadsAwaitingConnection();
            remaining=hikari.getMaximumPoolSize()-pool.getActiveConnections();
            reserved=Math.max(1,(hikari.getMaximumPoolSize()+3)/4);
        }
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double heap=memory.getMax()>0?(double)memory.getUsed()/memory.getMax():-1;
        var system=ManagementFactory.getOperatingSystemMXBean();
        if(system instanceof com.sun.management.OperatingSystemMXBean os){
            return new Load(os.getCpuLoad(),heap,availableMemory(os),waiting,remaining,reserved);
        }
        return new Load(-1,heap,-1,waiting,remaining,reserved);
    }
}
