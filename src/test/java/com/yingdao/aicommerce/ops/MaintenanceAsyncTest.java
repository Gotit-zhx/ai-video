package com.yingdao.aicommerce.ops;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceAsyncTest {
    @Test void queuedAndCancelledFuturesRemainCountedUntilTheirActualWorkEnds() throws Exception {
        int before=MaintenanceGate.instance().activeWorkers();
        var pool=new ThreadPoolTaskExecutor();pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(1);
        MaintenanceGate.initialize(pool);pool.afterPropertiesSet(); // actual @Bean lifecycle
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var running=pool.submit(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var queued=pool.submit(()->fail("A cancelled queued future must not run its body"));
            assertEquals(before+2,MaintenanceGate.instance().activeWorkers());
            queued.cancel(false);running.cancel(false);
            assertEquals(before+2,MaintenanceGate.instance().activeWorkers(),"Future completion is not task completion");
            assertThrows(RejectedExecutionException.class,()->pool.execute(()->{}));
            assertEquals(before+2,MaintenanceGate.instance().activeWorkers(),"Rejected work must release its count");
        } finally {
            release.countDown();pool.getThreadPoolExecutor().shutdown();
            assertTrue(pool.getThreadPoolExecutor().awaitTermination(3,TimeUnit.SECONDS));
        }
        assertEquals(before,MaintenanceGate.instance().activeWorkers());
    }
    @Test void callerRunsPolicyAndRawRejectionsDoNotLeakOrDoubleRelease() throws Exception {
        int before=MaintenanceGate.instance().activeWorkers();
        var pool=new ThreadPoolTaskExecutor();pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(0);
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());MaintenanceGate.initialize(pool);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            pool.execute(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(2,TimeUnit.SECONDS));
            pool.execute(()->assertEquals(before+2,MaintenanceGate.instance().activeWorkers()));
            assertEquals(before+1,MaintenanceGate.instance().activeWorkers());
            assertThrows(RejectedExecutionException.class,()->MaintenanceGate.execute(task->{throw new RejectedExecutionException();},()->{}));
            assertEquals(before+1,MaintenanceGate.instance().activeWorkers());
        } finally {
            release.countDown();pool.getThreadPoolExecutor().shutdown();
            assertTrue(pool.getThreadPoolExecutor().awaitTermination(3,TimeUnit.SECONDS));
        }
        assertEquals(before,MaintenanceGate.instance().activeWorkers());
    }
    @Test void rawAcceptedWorkIsCountedBeforeTheExecutorStartsIt() {
        int before=MaintenanceGate.instance().activeWorkers();Runnable[] accepted=new Runnable[1];
        MaintenanceGate.execute(task->accepted[0]=task,()->{});
        assertEquals(before+1,MaintenanceGate.instance().activeWorkers());
        accepted[0].run();accepted[0].run();
        assertEquals(before,MaintenanceGate.instance().activeWorkers());
    }
}
