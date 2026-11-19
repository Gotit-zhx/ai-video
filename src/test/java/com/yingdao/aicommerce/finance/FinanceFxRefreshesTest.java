package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.yingdao.aicommerce.ops.MaintenanceGate;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FinanceFxRefreshesTest {
    @TempDir Path temporary;
    @Test void activeAndQueuedKeysDeduplicateAndSaturationNeverRunsOnCaller()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var completed=new CountDownLatch(1);
        try(var jobs=new FinanceFxRefreshes(1,1,Clock.systemUTC(),Duration.ofSeconds(30))){
            try{
                assertTrue(jobs.submit("slow",()->{entered.countDown();FinanceFxAsyncIntegrationTest.await(release);}));
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertFalse(jobs.submit("slow",()->fail("Active key must deduplicate")));
                assertTrue(jobs.submit("queued",completed::countDown));
                assertFalse(jobs.submit("queued",()->fail("Queued key must deduplicate")));
                assertFalse(jobs.submit("overflow",()->fail("Saturated executor must never run on caller")));
            }finally{release.countDown();}
            assertTrue(completed.await(2,TimeUnit.SECONDS));
        }
    }
    @Test void actualRefreshRemainsTrackedUntilItsSuccessOrFailureFinallyFinishes()throws Exception{
        var gate=MaintenanceGate.instance();assertFalse(gate.closed(),"The local unit-test gate must be open");
        int baseline=gate.activeWorkers();
        for(boolean fail:new boolean[]{false,true}){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var jobs=new FinanceFxRefreshes(1,1,Clock.systemUTC(),Duration.ofSeconds(30))){
                try{
                    assertTrue(jobs.submit("tracked",()->{entered.countDown();FinanceFxAsyncIntegrationTest.await(release);if(fail)throw new IllegalStateException("synthetic provider failure");}));
                    assertTrue(entered.await(2,TimeUnit.SECONDS));
                    assertEquals(baseline+1,gate.activeWorkers(),"A blocked provider/JDBC runnable must be visible to the maintenance safe-point check");
                }finally{release.countDown();}
            }
            assertEquals(baseline,gate.activeWorkers(),"The permit must close on success and failure");
        }
    }
    @Test void gateClosedQueuedWorkSkipsProviderAndRetriesWhenTheGateReopens()throws Exception{
        var constructor=MaintenanceGate.class.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        Path marker=temporary.resolve("fx-maintenance.json");var gate=constructor.newInstance(marker);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var dispatched=new CountDownLatch(2);var calls=new AtomicInteger();
        Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);
        try(var jobs=new FinanceFxRefreshes(1,1,clock,Duration.ofSeconds(30),task->{
            try(var permit=gate.enter(true)){if(permit!=null)task.run();}finally{dispatched.countDown();}
        })){
            try{
                assertTrue(jobs.submit("running",()->{entered.countDown();FinanceFxAsyncIntegrationTest.await(release);}));
                assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(1,gate.activeWorkers());
                assertTrue(jobs.submit("queued",calls::incrementAndGet));
                Files.writeString(marker,"{}");assertTrue(gate.closed());assertEquals(1,gate.activeWorkers(),"Closing the gate must retain an already running refresh permit");
            }finally{release.countDown();}
            assertTrue(dispatched.await(2,TimeUnit.SECONDS));assertEquals(0,gate.activeWorkers());assertEquals(0,calls.get(),"Queued work must never enter provider or JDBC callbacks after maintenance closes");
            Files.delete(marker);var retried=new CountDownLatch(1);
            FinanceFxAsyncIntegrationTest.eventually(()->jobs.submit("queued",()->{calls.incrementAndGet();retried.countDown();}),Boolean.TRUE::equals);
            assertTrue(retried.await(2,TimeUnit.SECONDS));assertEquals(1,calls.get(),"A skipped refresh must be retryable without pretending an attempt succeeded or advancing the clock");
        }
        assertEquals(0,gate.activeWorkers());
    }
    @Test void shutdownInterruptDoesNotRemoveThePermitBeforeWorkActuallyReturns()throws Exception{
        var gate=MaintenanceGate.instance();int baseline=gate.activeWorkers();
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var jobs=new FinanceFxRefreshes(1,1,Clock.systemUTC(),Duration.ofSeconds(30));
        try(var closer=Executors.newSingleThreadExecutor()){
            try{
                assertTrue(jobs.submit("driver",()->{
                    entered.countDown();boolean finished=false;
                    while(!finished)try{release.await();finished=true;}catch(InterruptedException ignored){interrupted.countDown();}
                }));
                assertTrue(entered.await(2,TimeUnit.SECONDS));var closing=closer.submit(jobs::close);
                assertTrue(interrupted.await(2,TimeUnit.SECONDS));
                assertEquals(baseline+1,gate.activeWorkers(),"An interrupted JDBC-like callback that is still running must remain visible to the safe-point coordinator");
                release.countDown();closing.get(2,TimeUnit.SECONDS);
            }finally{release.countDown();jobs.close();}
        }
        assertEquals(baseline,gate.activeWorkers());
    }
    @Test void failedAndEmptyWorkCanRetryAfterBoundedCooldown()throws Exception{
        var instant=new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        try(var jobs=new FinanceFxRefreshes(1,2,clock,Duration.ofSeconds(30))){
            for(String key:new String[]{"failure","empty"}){
                assertTrue(jobs.submit(key,()->{if(key.equals("failure"))throw new IllegalStateException("unavailable");}));
                var finished=new CountDownLatch(1);assertTrue(jobs.submit("barrier-"+key,finished::countDown));assertTrue(finished.await(2,TimeUnit.SECONDS));
                assertFalse(jobs.submit(key,()->fail("Cooldown must suppress repeated provider attempts")));
                instant.updateAndGet(value->value.plusSeconds(30));
                var retried=new CountDownLatch(1);assertTrue(jobs.submit(key,retried::countDown));assertTrue(retried.await(2,TimeUnit.SECONDS));
            }
        }
    }
}
