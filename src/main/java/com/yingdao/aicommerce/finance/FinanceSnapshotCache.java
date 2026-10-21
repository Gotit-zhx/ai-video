package com.yingdao.aicommerce.finance;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Small, owner-scoped read cache. Concurrent identical reads share one computation. */
final class FinanceSnapshotCache {
    record Key(Scope scope,Filter filter,boolean cny,String snapshot,boolean live){}
    private static final class Entry {
        final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
        long expires=Long.MAX_VALUE;
    }
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final LongSupplier clock;private final long ttl;private final int capacity;
    FinanceSnapshotCache(){this(System::nanoTime,TimeUnit.SECONDS.toNanos(5),32);}
    FinanceSnapshotCache(LongSupplier clock,long ttl,int capacity){this.clock=clock;this.ttl=ttl;this.capacity=capacity;}
    Map<String,Object> get(Key key,Supplier<Map<String,Object>> load){
        Entry entry;boolean owner=false;
        synchronized(entries){
            long now=clock.getAsLong();
            entries.entrySet().removeIf(e->e.getValue().result.isDone()&&e.getValue().expires<=now);
            entry=entries.get(key);
            if(entry==null){
                if(entries.size()>=capacity){
                    var oldest=entries.entrySet().iterator();
                    while(oldest.hasNext()){if(oldest.next().getValue().result.isDone()){oldest.remove();break;}}
                }
                entry=new Entry();owner=true;
                // Bound retained memory even if many distinct requests are in flight.
                if(entries.size()<capacity)entries.put(key,entry);
            }
        }
        if(owner){
            try{
                var value=Collections.unmodifiableMap(new LinkedHashMap<>(load.get()));
                synchronized(entries){entry.expires=clock.getAsLong()+ttl;}
                entry.result.complete(value);
            }catch(Throwable failure){
                entry.result.completeExceptionally(failure);
                synchronized(entries){entries.remove(key,entry);}
            }
        }
        try{return entry.result.join();}
        catch(CompletionException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw e;}
    }
    void invalidate(Scope scope){synchronized(entries){entries.keySet().removeIf(key->key.scope().equals(scope));}}
}
