package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FinanceQueryReadBatchTest {
    @Test void callersAdmittedBeforeTheSnapshotShareOneCurrentRead() throws Exception {
        var opened=new CountDownLatch(1);
        var seal=new CountDownLatch(1);
        var joining=new CountDownLatch(1);
        var caller=new AtomicReference<Thread>();
        var reads=new AtomicInteger();
        var ledger=new AtomicInteger(10);
        var batch=new FinanceQueryReadBatch<String,Integer>(()->{
            opened.countDown();
            assertTrue(seal.await(5,TimeUnit.SECONDS));
        });

        try(var workers=Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->batch.read("company/PHP",()->{
                reads.incrementAndGet();return ledger.get();
            }));
            try{
                assertTrue(opened.await(5,TimeUnit.SECONDS));
                var second=workers.submit(()->{
                    caller.set(Thread.currentThread());
                    joining.countDown();
                    return batch.read("company/PHP",()->fail("Identical caller should share the sealed snapshot"));
                });
                assertTrue(joining.await(5,TimeUnit.SECONDS));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(caller.get().getState()!=Thread.State.WAITING&&System.nanoTime()<deadline)Thread.sleep(1);
                assertEquals(Thread.State.WAITING,caller.get().getState());
                ledger.set(21);
                seal.countDown();

                assertEquals(21,first.get(5,TimeUnit.SECONDS));
                assertEquals(21,second.get(5,TimeUnit.SECONDS));
                assertEquals(1,reads.get());
            }finally{seal.countDown();}
        }
    }

    @Test void aCallerArrivingAfterSnapshotStartSeesItsOwnPriorCommit() throws Exception {
        var started=new CountDownLatch(1);
        var finish=new CountDownLatch(1);
        var ledger=new AtomicInteger(10);
        var batch=new FinanceQueryReadBatch<String,Integer>(()->{});

        try(var workers=Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->batch.read("company/PHP",()->{
                int captured=ledger.get();
                started.countDown();
                awaitFinish(finish);
                return captured;
            }));
            try{
                assertTrue(started.await(5,TimeUnit.SECONDS));
                ledger.set(21);
                var second=workers.submit(()->batch.read("company/PHP",ledger::get));
                assertEquals(21,second.get(5,TimeUnit.SECONDS));
            }finally{finish.countDown();}
            assertEquals(10,first.get(5,TimeUnit.SECONDS));
        }
    }

    private static void awaitFinish(CountDownLatch finish){
        try{assertTrue(finish.await(5,TimeUnit.SECONDS));}
        catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            fail(interrupted);
        }
    }
}
