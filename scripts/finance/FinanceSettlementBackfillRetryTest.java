package com.yingdao.aicommerce.finance;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.dao.CannotAcquireLockException;

/** Standalone checks for the maintenance runner; no connection to production is required. */
public final class FinanceSettlementBackfillRetryTest {
    public static void main(String[] args)throws Exception{
        var calls=new AtomicInteger();var pauses=new AtomicInteger();
        int result=FinanceSettlementBackfill.retry(()->{
            if(calls.incrementAndGet()==1)throw new TransactionTimedOutException("rollback");
            return 5;
        },ms->pauses.incrementAndGet());
        require(result==5&&calls.get()==2&&pauses.get()==1,"timeout resumes in a new attempt");

        calls.set(0);pauses.set(0);
        try{
            FinanceSettlementBackfill.retry(()->{calls.incrementAndGet();throw new CannotAcquireLockException("busy");},ms->pauses.incrementAndGet());
            throw new AssertionError("retry limit must fail");
        }catch(CannotAcquireLockException expected){
            require(calls.get()==5&&pauses.get()==4,"lock retry is bounded");
        }

        calls.set(0);
        try{
            FinanceSettlementBackfill.retry(()->{calls.incrementAndGet();throw new IllegalArgumentException("missing raw field");},ms->{throw new AssertionError("permanent error must not wait");});
            throw new AssertionError("permanent failure must propagate");
        }catch(IllegalArgumentException expected){require(calls.get()==1,"data errors are not retried");}

        calls.set(0);
        try{
            FinanceSettlementBackfill.retry(()->{calls.incrementAndGet();throw new CannotAcquireLockException("busy");},ms->{throw new InterruptedException("stop");});
            throw new AssertionError("interruption must stop the worker");
        }catch(InterruptedException expected){require(calls.get()==1,"stop cannot retry writes");}
        System.out.println("4 settlement recovery retry checks passed");
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
