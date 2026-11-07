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
                local extra=redis.call('zcard',KEYS[2])-tonumber(ARGV[4])
                if extra>0 then
                    local old=redis.call('zrange',KEYS[2],0,extra-1)
                    for _,key in ipairs(old) do redis.call('del',key);redis.call('zrem',KEYS[2],key) end
                end
                redis.call('expire',KEYS[2],tonumber(ARGV[2])+60)
                return 1
                """,Long.class);
    private static final long FRESH_MS=15_000,MAX_STALE_MS=60_000;
    private static final long HISTORICAL_FRESH_MS=60_000,HISTORICAL_MAX_STALE_MS=180_000;
    private static final Duration RETENTION=Duration.ofMinutes(5);
    private static final int RESPONSE_LIMIT=524_288,FACTS_LIMIT=16*1024*1024,FACTS_STORED_LIMIT=2*1024*1024;
    private static final int RESPONSE_DECODED_LIMIT=4*1024*1024;
    private final StringRedisTemplate redis;private final ObjectMapper json;private final LongSupplier clock;
    private final Executor executor;private final ExecutorService ownedExecutor;
    private final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Scope,UUID> dirtyScopes=new ConcurrentHashMap<>();
    private record LocalFacts(Map<String,Object> envelope,int decodedBytes){}
    private final LinkedHashMap<String,LocalFacts> localFacts=new LinkedHashMap<>(8,.75f,true);
    private int localFactsBytes;
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
        if(address==null)return withoutFactRevision(load.get());
        var cached=read(address);
        if(cached!=null){
            long age=Math.max(0,clock.getAsLong()-number(cached.get("built")));
            if(age<=maxStaleMillis(key)){
                String revision;
                try{revision=dataRevision(key.scope());}catch(RuntimeException unavailable){return withoutFactRevision(load.get());}
                boolean stale=age>freshMillis(key)||!revision.equals(Objects.toString(cached.get("dataRevision"),"0"));
                if(stale&&refreshDue(key,age))background(address,key,load);
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
        long age=cached==null?Long.MAX_VALUE:Math.max(0,clock.getAsLong()-number(cached.get("built")));
        if(cached==null||(!historical(key)||age>=freshMillis(key))&&(age>freshMillis(key)
                ||!revision.equals(Objects.toString(cached.get("dataRevision"),"0"))))background(address,key,load);
    }
    private boolean historical(FinanceSnapshotCache.Key key){
        var currentMonth=Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().withDayOfMonth(1);
        return !key.live()&&key.filter().end().isBefore(currentMonth);
    }
    private long freshMillis(FinanceSnapshotCache.Key key){return historical(key)?HISTORICAL_FRESH_MS:FRESH_MS;}
    private long maxStaleMillis(FinanceSnapshotCache.Key key){return historical(key)?HISTORICAL_MAX_STALE_MS:MAX_STALE_MS;}
    private boolean refreshDue(FinanceSnapshotCache.Key key,long age){return !historical(key)||age>=FRESH_MS;}
    /** Current/live facts last fifteen seconds; completed months last sixty seconds.
     * Soft changes retain the original revision and timestamp; configuration changes
     * still fence the address immediately. Transforming a snapshot never renews it. */
    Map<String,Object> facts(FinanceSnapshotCache.Key key,Supplier<Map<String,Object>> load){
        String address=factsAddress(key);
        if(address==null)return load.get();
        var cached=freshFacts(address,key);
        if(cached!=null)return cached;
        var future=new CompletableFuture<Map<String,Object>>();
        var existing=pending.putIfAbsent(address,future);
        if(existing!=null){
            try{return existing.join();}
            catch(CompletionException failure){
                if(failure.getCause() instanceof RuntimeException r)throw r;
                if(failure.getCause() instanceof Error e)throw e;
                throw failure;
            }
        }
        try{
            cached=freshFacts(address,key);
            if(cached!=null){future.complete(cached);return cached;}
            String revision;
            try{revision=dataRevision(key.scope());}catch(RuntimeException unavailable){revision="unavailable";}
            var loaded=load.get();
            var envelope=Map.<String,Object>of("built",clock.getAsLong(),"data",loaded,"dataRevision",revision);
            try{
                byte[] raw=json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
                String value=raw.length<=FACTS_LIMIT?compressFacts(raw):null;
                if(value!=null&&value.length()<=FACTS_STORED_LIMIT){
                    redis.execute(SAVE,List.of(address,prefix(key.scope())+"facts-entries"),value,Long.toString(RETENTION.toSeconds()),
                            Long.toString(clock.getAsLong()),"16");
                    rememberFacts(address,decodeEnvelope(raw),raw.length);
                }
            }catch(RuntimeException unavailable){/* The successful consistent database read remains usable. */}
            var stamped=stampFacts(envelope);future.complete(stamped);return stamped;
        }catch(Throwable failure){
            future.completeExceptionally(failure);
            if(failure instanceof RuntimeException r)throw r;
            if(failure instanceof Error e)throw e;
            throw new CompletionException(failure);
        }finally{pending.remove(address,future);}
    }
    Map<String,Object> findFacts(FinanceSnapshotCache.Key key){
        String address=factsAddress(key);if(address==null)return null;
        var running=pending.get(address);
        var prior=running==null?null:localFacts(address,maxStaleMillis(key));
        if(prior!=null)return stampFacts(prior);
        var cached=freshFacts(address,key);if(cached!=null)return cached;
        if(running==null)return null;
        // A narrower request can use the previous bounded snapshot while the
        // company refresh runs, instead of joining its long database query.
        cached=availableFacts(address,maxStaleMillis(key));if(cached!=null)return cached;
        // This is an optional wider snapshot, not the requested query's own
        // single flight. A cold shop request must not wait for a company scan.
        return null;
    }
    private String factsAddress(FinanceSnapshotCache.Key key){
        String address=address(key);if(address==null)return null;
        // Soft data changes must not start a second expensive scan while this one
        // is running. The envelope keeps its original revision for stale marking.
        return address+":facts";
    }
    private Map<String,Object> freshFacts(String address,FinanceSnapshotCache.Key key){
        return availableFacts(address,freshMillis(key));
    }
    private Map<String,Object> availableFacts(String address,long maxAge){
        var local=localFacts(address,maxAge);if(local!=null)return stampFacts(local);
        var cached=read(address,FACTS_STORED_LIMIT,true);
        if(cached==null||cached.get("dataRevision")==null
                ||Math.max(0,clock.getAsLong()-number(cached.get("built")))>maxAge)return null;
        // Cost/label workers can commit while a monthly read is running. Requiring
        // their newest revision here made a just-finished snapshot unusable for
        // currency/shop changes and restarted the same SQL after almost every batch.
        // Its revision remains attached so the response explicitly reports staleness.
        return stampFacts(cached);
    }
    private Map<String,Object> localFacts(String address,long maxAge){
        synchronized(localFacts){
            var entry=localFacts.get(address);
            if(entry==null||Math.max(0,clock.getAsLong()-number(entry.envelope().get("built")))>maxAge)return null;
            return entry.envelope();
        }
    }
    private Map<String,Object> decodeEnvelope(byte[] raw){
        return map(json.readerFor(Map.class).with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(raw));
    }
    private void rememberFacts(String address,Map<String,Object> envelope,int bytes){
        if(bytes>FACTS_LIMIT||envelope.get("dataRevision")==null)return;
        var entry=new LocalFacts(map(immutable(envelope)),bytes);
        synchronized(localFacts){
            var previous=localFacts.put(address,entry);if(previous!=null)localFactsBytes-=previous.decodedBytes();
            localFactsBytes+=bytes;
            while(localFacts.size()>4||localFactsBytes>FACTS_LIMIT){
                var iterator=localFacts.entrySet().iterator();var oldest=iterator.next();
                localFactsBytes-=oldest.getValue().decodedBytes();iterator.remove();
            }
        }
    }
    private static Object immutable(Object value){
        if(value instanceof Map<?,?> source){
            var copy=new LinkedHashMap<String,Object>();source.forEach((key,item)->copy.put(text(key),immutable(item)));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List<?> source)return Collections.unmodifiableList(source.stream().map(FinanceReadCache::immutable).toList());
        return value;
    }
    private static Map<String,Object> stampFacts(Map<String,Object> envelope){
        var data=new LinkedHashMap<>(map(envelope.get("data")));
        data.put("_financeDataRevision",envelope.get("dataRevision"));
        data.put("_financeFactsBuilt",envelope.get("built"));return data;
    }
    private static Map<String,Object> withoutFactRevision(Map<String,Object> data){
        var copy=new LinkedHashMap<>(data);
        copy.remove("_financeDataRevision");copy.remove("_financeFactsBuilt");return copy;
    }
    private static String compressFacts(byte[] raw){
        try{
            var bytes=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(raw);}
            return "gz:"+Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(java.io.IOException unavailable){return null;}
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
            return prefix(key.scope())+revision+":"+selection(key);
        }catch(RuntimeException unavailable){return null;}
    }
    static String prefix(Scope scope){return "heyi:finance:snapshot:v17-advertising-commissions:{"+scope.organizationId()+":"+scope.userId()+"}:";}
    private static String revisionKey(Scope scope){return prefix(scope)+"revision";}
    private static String dataRevisionKey(Scope scope){return prefix(scope)+"data-revision";}
    private String dataRevision(Scope scope){return Objects.toString(redis.opsForValue().get(dataRevisionKey(scope)),"0");}
    private static String selection(FinanceSnapshotCache.Key key){
        return digest(FinanceCalendar.VERSION+"|"+key.filter()+"|"+key.cny()+"|"+key.snapshot()+"|"+key.live());
    }
    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private Map<String,Object> read(String address){
        return read(address,RESPONSE_LIMIT,false,RESPONSE_DECODED_LIMIT);
    }
    private Map<String,Object> read(String address,int limit,boolean compressed){
        return read(address,limit,compressed,FACTS_LIMIT);
    }
    private Map<String,Object> read(String address,int limit,boolean compressed,int decodedLimit){
        try{
            String value=redis.opsForValue().get(address);
            if(value==null||value.length()>limit)return null;
            int decodedBytes=value.getBytes(StandardCharsets.UTF_8).length;
            if(compressed&&!value.startsWith("gz:"))return null;
            if(value.startsWith("gz:")){
                try(var gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(value.substring(3))))){
                    byte[] raw=gzip.readNBytes(decodedLimit+1);
                    if(raw.length>decodedLimit)return null;
                    decodedBytes=raw.length;
                    value=new String(raw,StandardCharsets.UTF_8);
                }
            }
            var envelope=map(json.readerFor(Map.class)
                    .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value));
            if(!(envelope.get("data") instanceof Map<?,?>)||!(envelope.get("built") instanceof Number))return null;
            if(compressed)rememberFacts(address,envelope,decodedBytes);
            return envelope;
        }catch(RuntimeException|java.io.IOException unavailable){return null;}
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
            var loaded=new LinkedHashMap<>(load.get());
            Object factsRevision=loaded.remove("_financeDataRevision");
            Object factsBuilt=loaded.remove("_financeFactsBuilt");
            if(factsRevision!=null)revision=text(factsRevision);
            // A projection retains its raw snapshot's finish time. New database
            // reads use their finish time, not when they entered a busy queue.
            // Capture the data revision before querying: a batch committed during the
            // read must trigger another refresh, never be labelled already included.
            long built=factsBuilt instanceof Number?Math.min(clock.getAsLong(),number(factsBuilt)):clock.getAsLong();
            var envelope=Map.<String,Object>of("built",built,"data",loaded,
                    "dataRevision",revision==null?"unavailable":revision);
            try{
                byte[] raw=json.writeValueAsBytes(envelope);
                String value=raw.length>RESPONSE_DECODED_LIMIT?null:raw.length>65_536?compressFacts(raw):new String(raw,StandardCharsets.UTF_8);
                if(revision!=null&&value!=null&&value.getBytes(StandardCharsets.UTF_8).length<=RESPONSE_LIMIT){
                    save(address,key.scope(),value);
                    // The UI resolves an automatic currency / FX snapshot after its first response.
                    // Save that equivalent key too, without collapsing different explicit filters.
                    var data=map(envelope.get("data"));var f=map(data.get("filters"));
                    String currency=text(f.get("currency")),snapshot=key.cny()?text(map(data.get("fx")).get("id")):key.snapshot();
                    var alias=new FinanceSnapshotCache.Key(key.scope(),new Filter(key.filter().start(),key.filter().end(),
                            key.filter().shopId(),key.filter().platform(),currency,key.filter().granularity(),key.filter().groupId()),key.cny(),snapshot,key.live());
                    // Preserve the original revision so an in-flight refresh cannot repopulate an invalidated scope.
                    int separator=address.lastIndexOf(':');
                    save(address.substring(0,separator+1)+selection(alias),key.scope(),value);
                }
            }catch(RuntimeException unavailable){/* A cache failure must not hide a successful database read. */}
            boolean refreshing=true;
            try{refreshing=clock.getAsLong()-built>freshMillis(key)||!Objects.equals(revision,dataRevision(key.scope()));}catch(RuntimeException unavailable){/* Retry on the next refresh. */}
            future.complete(response(envelope,refreshing));
        }catch(Throwable failure){future.completeExceptionally(failure);}
        finally{pending.remove(address,future);}
    }
    private void save(String address,Scope scope,String value){
        redis.execute(SAVE,List.of(address,prefix(scope)+"entries"),value,Long.toString(RETENTION.toSeconds()),Long.toString(clock.getAsLong()),"64");
    }
    @PreDestroy public void stop(){if(ownedExecutor!=null)ownedExecutor.shutdownNow();}
}
