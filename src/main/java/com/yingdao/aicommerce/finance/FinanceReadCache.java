package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Shared, tenant-scoped snapshots. A bounded stale response never blocks on a refresh. */
@Component
public class FinanceReadCache {
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SAVE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                redis.call('set',KEYS[1],ARGV[1],'EX',ARGV[2])
                redis.call('zadd',KEYS[2],ARGV[3],KEYS[1])
                redis.call('zremrangebyscore',KEYS[2],'-inf',tonumber(ARGV[3])-tonumber(ARGV[2])*1000)
                local extra=redis.call('zcard',KEYS[2])-64
                if extra>0 then
                    local old=redis.call('zrange',KEYS[2],0,extra-1)
                    for _,key in ipairs(old) do redis.call('del',key);redis.call('zrem',KEYS[2],key) end
                end
                redis.call('expire',KEYS[2],tonumber(ARGV[2])+60)
                return 1
                """,Long.class);
    private static final long FRESH_MS=15_000,MAX_STALE_MS=60_000;
    private static final Duration RETENTION=Duration.ofMinutes(5);
    private final StringRedisTemplate redis;private final ObjectMapper json;private final LongSupplier clock;
    private final Executor executor;private final ExecutorService ownedExecutor;
    private final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Scope,UUID> dirtyScopes=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceReadCache(StringRedisTemplate redis,ObjectMapper json){
        this(redis,json,System::currentTimeMillis,new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"finance-cache-refresh");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy()),true);
    }
    FinanceReadCache(StringRedisTemplate redis,ObjectMapper json,LongSupplier clock,Executor executor,boolean owned){
        this.redis=redis;this.json=json;this.clock=clock;this.executor=executor;
        ownedExecutor=owned?(ExecutorService)executor:null;
    }
    public Map<String,Object> get(FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load){
        String address=address(key);
        if(address==null)return load.get();
        var cached=read(address);
        if(cached!=null){
            long age=Math.max(0,clock.getAsLong()-number(cached.get("built")));
            if(age<=MAX_STALE_MS){
                String revision;
                try{revision=dataRevision(key.scope());}catch(RuntimeException unavailable){return load.get();}
                boolean stale=age>FRESH_MS||!revision.equals(Objects.toString(cached.get("dataRevision"),"0"));
                if(stale)background(address,key,load);
                return response(cached,stale);
            }
        }
        return compute(address,key,load);
    }
    public void prime(FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load){
        String address=address(key);if(address==null)return;
        var cached=read(address);
        String revision;
        try{revision=dataRevision(key.scope());}catch(RuntimeException unavailable){return;}
        if(cached==null||clock.getAsLong()-number(cached.get("built"))>FRESH_MS
                ||!revision.equals(Objects.toString(cached.get("dataRevision"),"0")))background(address,key,load);
    }
    /** Job heartbeat is read outside the amount snapshot so a stopped job never keeps animating. */
    public Map<String,Object> settlementCalculation(Scope scope,List<String> shops){
        if(shops.isEmpty())return Map.of();
        try{
            String value=redis.opsForValue().get("heyi:finance:calculation:{"+scope.organizationId()+"}:settlements");
            if(value==null||value.length()>131072)return Map.of();
            var job=map(json.readValue(value,Map.class));var selected=rows(job.get("shops")).stream()
                    .filter(row->shops.contains(text(row.get("shopId")))).toList();
            if(selected.isEmpty())return Map.of();
            long heartbeat=number(job.get("heartbeat")),age=clock.getAsLong()-heartbeat;
            boolean complete=selected.stream().allMatch(row->Boolean.TRUE.equals(row.get("complete")));
            String state=complete?"COMPLETE":text(job.get("status"));
            if(state.equals("RUNNING")&&(heartbeat<=0||age>30_000||age< -5_000))state="INTERRUPTED";
            if(!Set.of("RUNNING","COMPLETE","FAILED","INTERRUPTED").contains(state))return Map.of();
            long processed=selected.stream().mapToLong(row->number(row.get("records"))+number(row.get("bills"))).sum();
            long total=selected.stream().mapToLong(row->number(row.get("total"))).sum();
            return Map.of("status",state,"processed",processed,"total",total,
                    "scope","所选店铺的历史结算与账单","heartbeat",heartbeat);
        }catch(RuntimeException unavailable){return Map.of();}
    }
    public void invalidate(Scope scope){
        UUID change=UUID.randomUUID();dirtyScopes.put(scope,change);
        try{redis.opsForValue().increment(revisionKey(scope));dirtyScopes.remove(scope,change);}
        catch(RuntimeException failure){
            // The database change already committed. Bypass old snapshots until invalidation
            // succeeds; never report a successful configuration save as a failed operation.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance snapshot invalidation deferred: {}",failure.getClass().getSimpleName());
        }
    }
    /** Background cost batches keep a bounded, explicitly stale response available.
     * Configuration/authorization/deletion changes still use hard invalidation. */
    public void refresh(Scope scope){
        try{redis.opsForValue().increment(dataRevisionKey(scope));}
        catch(RuntimeException unavailable){invalidate(scope);}
    }
    private String address(FinanceSnapshotCache.Key key){
        try{
            UUID change=dirtyScopes.get(key.scope());
            if(change!=null){
                redis.opsForValue().increment(revisionKey(key.scope()));
                dirtyScopes.remove(key.scope(),change);
            }
            String revision=Objects.toString(redis.opsForValue().get(revisionKey(key.scope())),"0");
            return prefix(key.scope())+revision+":"+digest(key.filter()+"|"+key.cny()+"|"+key.snapshot()+"|"+key.live());
        }catch(RuntimeException unavailable){return null;}
    }
    static String prefix(Scope scope){return "heyi:finance:snapshot:v14-qianyi-refunds:{"+scope.organizationId()+":"+scope.userId()+"}:";}
    private static String revisionKey(Scope scope){return prefix(scope)+"revision";}
    private static String dataRevisionKey(Scope scope){return prefix(scope)+"data-revision";}
    private String dataRevision(Scope scope){return Objects.toString(redis.opsForValue().get(dataRevisionKey(scope)),"0");}
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private Map<String,Object> read(String address){
        try{
            String value=redis.opsForValue().get(address);
            if(value==null||value.length()>524_288)return null;
            var envelope=map(json.readerFor(Map.class)
                    .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value));
            return envelope.get("data") instanceof Map<?,?>&&envelope.get("built") instanceof Number?envelope:null;
        }catch(RuntimeException unavailable){return null;}
    }
    private static Map<String,Object> response(Map<String,Object> envelope,boolean refreshing){
        var result=new LinkedHashMap<>(map(envelope.get("data")));
        result.put("cache",Map.of("generatedAt",Instant.ofEpochMilli(number(envelope.get("built"))).toString(),"refreshing",refreshing));
        return result;
    }
    private void background(String address,FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load){
        var future=new CompletableFuture<Map<String,Object>>();
        if(pending.putIfAbsent(address,future)!=null)return;
        try{executor.execute(()->complete(address,key,load,future));}
        catch(RejectedExecutionException busy){pending.remove(address,future);future.completeExceptionally(busy);}
    }
    private Map<String,Object> compute(String address,FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load){
        var future=new CompletableFuture<Map<String,Object>>();
        var existing=pending.putIfAbsent(address,future);
        if(existing==null)complete(address,key,load,future);else future=existing;
        try{return future.join();}
        catch(CompletionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    private void complete(String address,FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load,
                          CompletableFuture<Map<String,Object>> future){
        try{
            String revision=null;
            try{revision=dataRevision(key.scope());}catch(RuntimeException unavailable){/* Serve the database result. */}
            var loaded=load.get();
            // Built time is the time the query finishes, not when it entered a busy queue.
            // Capture the data revision before querying: a batch committed during the
            // read must trigger another refresh, never be labelled already included.
            var envelope=Map.<String,Object>of("built",clock.getAsLong(),"data",loaded,
                    "dataRevision",revision==null?"unavailable":revision);
            try{
                String value=json.writeValueAsString(envelope);
                if(revision!=null&&value.getBytes(StandardCharsets.UTF_8).length<=524_288){
                    save(address,key.scope(),value);
                    // The UI resolves an automatic currency / FX snapshot after its first response.
                    // Save that equivalent key too, without collapsing different explicit filters.
                    var data=map(envelope.get("data"));var f=map(data.get("filters"));
                    String currency=text(f.get("currency")),snapshot=key.cny()?text(map(data.get("fx")).get("id")):key.snapshot();
                    var alias=new FinanceSnapshotCache.Key(key.scope(),new Filter(key.filter().start(),key.filter().end(),
                            key.filter().shopId(),key.filter().platform(),currency,key.filter().granularity(),key.filter().groupId()),key.cny(),snapshot,key.live());
                    // Preserve the original revision so an in-flight refresh cannot repopulate an invalidated scope.
                    int separator=address.lastIndexOf(':');
                    save(address.substring(0,separator+1)+digest(alias.filter()+"|"+alias.cny()+"|"+alias.snapshot()+"|"+alias.live()),key.scope(),value);
                }
            }catch(RuntimeException unavailable){/* A cache failure must not hide a successful database read. */}
            boolean refreshing=true;
            try{refreshing=!Objects.equals(revision,dataRevision(key.scope()));}catch(RuntimeException unavailable){/* Retry on the next refresh. */}
            future.complete(response(envelope,refreshing));
        }catch(Throwable failure){future.completeExceptionally(failure);}
        finally{pending.remove(address,future);}
    }
    private void save(String address,Scope scope,String value){
        redis.execute(SAVE,List.of(address,prefix(scope)+"entries"),value,Long.toString(RETENTION.toSeconds()),Long.toString(clock.getAsLong()));
    }
    @PreDestroy public void stop(){if(ownedExecutor!=null)ownedExecutor.shutdownNow();}
}
