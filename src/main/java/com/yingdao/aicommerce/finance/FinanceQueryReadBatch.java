package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** A short admission window for identical reads that have yet to open a database snapshot. */
final class FinanceQueryReadBatch<K,V> {
    @FunctionalInterface interface Admission { void await() throws InterruptedException; }
    private final ConcurrentHashMap<K,CompletableFuture<V>> admitting=new ConcurrentHashMap<>();
    private final Admission admission;

    FinanceQueryReadBatch(){this(()->Thread.sleep(50));}
    FinanceQueryReadBatch(Admission admission){this.admission=admission;}

    V read(K key,Supplier<V> load){
        var result=new CompletableFuture<V>();
        var current=admitting.putIfAbsent(key,result);
        if(current!=null)return await(current);
        try{
            try{admission.await();}
            finally{admitting.remove(key,result);}
            // Seal the batch before starting the transaction. A caller arriving
            // after this point needs a snapshot that includes its own prior commits.
            V value=load.get();
            result.complete(value);
            return value;
        }catch(InterruptedException interrupted){
            var failure=interrupted();
            result.completeExceptionally(failure);
            throw failure;
        }catch(RuntimeException|Error failure){
            result.completeExceptionally(failure);
            throw failure;
        }
    }

    private V await(CompletableFuture<V> result){
        try{return result.get();}
        catch(InterruptedException interrupted){throw interrupted();}
        catch(ExecutionException failed){
            if(failed.getCause() instanceof RuntimeException failure)throw failure;
            if(failed.getCause() instanceof Error failure)throw failure;
            throw new IllegalStateException(failed.getCause());
        }
    }

    private static BusinessException interrupted(){
        Thread.currentThread().interrupt();
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"财务查询等待已中断");
    }
}
