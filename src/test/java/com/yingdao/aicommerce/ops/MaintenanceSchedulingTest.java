package com.yingdao.aicommerce.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceSchedulingTest {
    @TempDir Path temporary;

    @Test void fixedDelayResumesAfterStartingDuringMaintenance() throws Exception {
        resumes((scheduler,task) -> scheduler.scheduleWithFixedDelay(task,Duration.ofMillis(20)));
    }

    @Test void fixedRateResumesAfterStartingDuringMaintenance() throws Exception {
        resumes((scheduler,task) -> scheduler.scheduleAtFixedRate(task,Duration.ofMillis(20)));
    }

    @Test void triggerResumesAfterStartingDuringMaintenance() throws Exception {
        resumes((scheduler,task) -> scheduler.schedule(task,
                context -> context.getClock().instant().plusMillis(20)));
    }

    @Test void annotatedUploadWorkerResumesAfterMaintenanceAtApplicationStartup() throws Exception {
        Path marker=temporary.resolve("startup-maintenance.json");Files.writeString(marker,"{}");
        var gate=new MaintenanceGate(marker);var probe=new ScheduledProbe();
        try (var context=new AnnotationConfigApplicationContext()) {
            context.registerBean("taskScheduler",ThreadPoolTaskScheduler.class,() -> MaintenanceScheduling.scheduler(gate));
            context.registerBean(ScheduledProbe.class,() -> probe);
            context.register(SchedulingEnabled.class);context.refresh();
            var scheduler=context.getBean(ThreadPoolTaskScheduler.class);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(scheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount()<2 && System.nanoTime()<deadline)
                Thread.sleep(5);
            assertEquals(2,probe.resumed.getCount());
            Files.delete(marker);
            assertTrue(probe.resumed.await(2,TimeUnit.SECONDS),"The real @Scheduled upload poll must resume without a restart");
        }
    }

    @Configuration @EnableScheduling static class SchedulingEnabled {}
    static class ScheduledProbe {
        final CountDownLatch resumed=new CountDownLatch(2);
        @Scheduled(initialDelay=10,fixedDelay=20) public void poll() { resumed.countDown(); }
    }

    private void resumes(BiFunction<ThreadPoolTaskScheduler,Runnable,ScheduledFuture<?>> schedule) throws Exception {
        Path marker=temporary.resolve("maintenance.json");Files.writeString(marker,"{}");
        var gate=new MaintenanceGate(marker);
        var scheduler=MaintenanceScheduling.scheduler(gate);scheduler.initialize();
        var resumed=new CountDownLatch(2);
        try {
            var future=schedule.apply(scheduler,() -> {
                assertTrue(gate.activeWorkers()>0);
                resumed.countDown();
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(scheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount()<2 && System.nanoTime()<deadline)
                Thread.sleep(5);
            assertEquals(2,resumed.getCount(),"Maintenance must block business work");
            assertEquals(0,gate.activeWorkers());
            Files.delete(marker);
            assertTrue(resumed.await(2,TimeUnit.SECONDS),"Recurring work must resume after the maintenance marker is removed");
            assertNotNull(future);future.cancel(false);
        } finally { scheduler.shutdown(); }
    }
}
