package com.yingdao.aicommerce.finance;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.*;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Public reference data only. Snapshot additions cannot replace an already frozen business day. */
final class FinanceFxHistory implements AutoCloseable {
    private static final Set<String> CURRENCIES=Set.of("CNY","USD","PHP","THB","MYR");
    private static final String ENDPOINT="https://api.frankfurter.dev/v2/rates?base=EUR&quotes=CNY,USD,PHP,THB,MYR&providers=ecb";
    private static final String SNAPSHOT_POLICY="ECB-business-date-previous-publication-v1";
    private final FinanceRepository repo;private final ObjectMapper json;private final Function<URI,String> fetch;
    private record Market(FinanceFx.Day day,Instant fetched){}
    private record StoredDay(FinanceFx.Day day,LocalDate through){}
    private volatile Market latest;
    private final FinanceFxRefreshes refreshes;private final Clock clock;
    FinanceFxHistory(FinanceRepository repo,ObjectMapper json,Function<URI,String> fetch){this(repo,json,fetch,Clock.systemUTC(),new FinanceFxRefreshes());}
    FinanceFxHistory(FinanceRepository repo,ObjectMapper json,Function<URI,String> fetch,Clock clock,FinanceFxRefreshes refreshes){
        this.repo=repo;this.json=json;this.fetch=fetch;this.clock=clock.withZone(ZoneOffset.UTC);this.refreshes=refreshes;
    }

    FinanceFx.Quote quote(String id,Collection<LocalDate> dates){
        if(id!=null&&!id.isBlank()&&!id.matches("[a-f0-9]{32}"))throw bad("旧参考汇率快照不能用于历史金额，请刷新看板");
        Map<String,Object> row;
        if(id==null||id.isBlank()){
            Instant created=clock.instant();LocalDate cutoff;
            Market current=latest;refreshMarket();
            if(current!=null&&current.fetched().plusSeconds(900).isAfter(clock.instant()))cutoff=current.day().rateDate();
            else{
                // Today's unavailable market must not disable already verified older history.
                // Cache hits still require an independently verified valid-through boundary.
                cutoff=LocalDate.now(clock);
            }
            var initialDates=new TreeSet<String>();dates.stream().filter(Objects::nonNull).forEach(date->initialDates.add(date.toString()));
            id=hash(SNAPSHOT_POLICY+"|"+cutoff+"|"+String.join(",",initialDates)).substring(0,32);
            String payload=repo.write(Map.of("days",Map.of(),"requestedDates",List.of(),"initialDates",initialDates,"policy",SNAPSHOT_POLICY));
            repo.jdbc().update("INSERT IGNORE INTO finance_fx_historical_snapshots(id,provider,cutoff_date,created_at,payload_json) VALUES(?,'ECB',?,?,?)",
                id,cutoff,Timestamp.from(created),payload);
            // Another initializer may already have extended this shared public-rate snapshot.
            var found=repo.jdbc().queryForList("SELECT * FROM finance_fx_historical_snapshots WHERE id=? AND provider='ECB'",id);
            if(found.isEmpty()||!cutoff.equals(day(found.getFirst().get("cutoff_date"))))throw bad("历史汇率快照初始化未完成");
            row=found.getFirst();
        }else{
            var found=repo.jdbc().queryForList("SELECT * FROM finance_fx_historical_snapshots WHERE id=? AND provider='ECB'",id);
            if(found.isEmpty())throw bad("历史汇率快照不存在，请刷新看板");row=found.getFirst();
        }
        var payload=repo.read(text(row.get("payload_json")));
        var result=new FinanceFx.Quote(id,instant(row.get("created_at")),Map.of(),decodeDays(map(payload.get("days"))));
        String snapshot=id;result.loader(wanted->extend(snapshot,wanted,result,false));
        var allDates=new TreeSet<LocalDate>();dates.stream().filter(Objects::nonNull).forEach(allDates::add);
        if(payload.get("requestedDates") instanceof List<?> previous)previous.forEach(value->allDates.add(LocalDate.parse(text(value))));
        result.preload(allDates);return result;
    }
    private void extend(String id,Collection<LocalDate> wanted,FinanceFx.Quote quote,boolean remote){
        var found=repo.jdbc().queryForList("SELECT * FROM finance_fx_historical_snapshots WHERE id=? AND provider='ECB'",id);
        if(found.isEmpty())throw bad("历史汇率快照不存在");
        var row=found.getFirst();String old=text(row.get("payload_json"));var payload=repo.read(old);
        var days=decodeDays(map(payload.get("days")));quote.addDays(selected(days,wanted));
        var requested=new TreeSet<String>();
        if(payload.get("requestedDates") instanceof List<?> prior)prior.forEach(day->requested.add(text(day)));
        wanted.forEach(day->requested.add(day.toString()));
        LocalDate cutoff=day(row.get("cutoff_date"));
        var pending=new TreeSet<LocalDate>();wanted.stream().filter(date->!days.containsKey(date)).forEach(pending::add);
        if(cutoff!=null&&!pending.isEmpty()){
            LocalDate low=pending.first().minusDays(14),high=pending.last().isAfter(cutoff)?cutoff:pending.last();
            low=low.isBefore(LocalDate.of(1999,1,4))?LocalDate.of(1999,1,4):low;
            if(!low.isAfter(high)){
                applyStored(stored(low,high),pending,cutoff,days);
                pending.removeAll(days.keySet());
                // One bounded provider request per year, never one network request per record.
                for(LocalDate from=low;remote&&!pending.isEmpty()&&!from.isAfter(high);){
                    LocalDate until=from.plusYears(1).minusDays(1);if(until.isAfter(high))until=high;
                    try{
                        String raw=fetch.apply(URI.create(ENDPOINT+"&from="+from+"&to="+until));
                        var loaded=parse(raw,until);persist(loaded,raw,until);
                        var canonical=stored(from,until);
                        applyStored(canonical,pending,cutoff,days);pending.removeAll(days.keySet());
                        // A response fetched today may legitimately return yesterday's publication.
                        // Freeze that observed mapping in this snapshot, but do not make an open
                        // current-day hole permanent in the shared provider cache.
                        for(LocalDate business:pending){
                            LocalDate upper=business.isAfter(cutoff)?cutoff:business;
                            if(upper.isBefore(from)||upper.isAfter(until))continue;
                            var observed=loaded.floorEntry(upper);
                            if(observed!=null&&!observed.getKey().isBefore(business.minusDays(14))&&canonical.containsKey(observed.getKey()))
                                days.putIfAbsent(business,canonical.get(observed.getKey()).day());
                        }
                        pending.removeAll(days.keySet());
                    }catch(RuntimeException unavailable){/* Do not replace a missing historical rate with a current price. */}
                    from=until.plusDays(1);
                }
            }
        }
        // CAS protects append-only snapshot coverage across independent application processes.
        for(int attempt=0;attempt<4;attempt++){
            var authoritative=decodeDays(map(payload.get("days")));
            days.forEach(authoritative::putIfAbsent);
            if(payload.get("requestedDates") instanceof List<?> prior)prior.forEach(date->requested.add(text(date)));
            var updated=new LinkedHashMap<>(payload);updated.put("days",encodeDays(authoritative));updated.put("requestedDates",requested);
            String next=repo.write(updated);
            if(repo.jdbc().update("UPDATE finance_fx_historical_snapshots SET payload_json=? WHERE id=? AND payload_json=CAST(? AS JSON)",next,id,old)==1){
                quote.addDays(selected(authoritative,wanted));
                if(!remote&&!pending.isEmpty())refreshHistory(id,pending);
                return;
            }
            var current=repo.jdbc().queryForList("SELECT payload_json FROM finance_fx_historical_snapshots WHERE id=?",id);
            if(current.isEmpty())throw bad("历史汇率快照已变化");
            old=text(current.getFirst().get("payload_json"));payload=repo.read(old);
        }
        // High contention may leave new dates unknown, but cannot replace committed rates or claim partial writes succeeded.
        quote.addDays(selected(decodeDays(map(payload.get("days"))),wanted));
        if(!remote&&!pending.isEmpty())refreshHistory(id,pending);
    }
    private static Map<LocalDate,FinanceFx.Day> selected(Map<LocalDate,FinanceFx.Day> days,Collection<LocalDate> wanted){
        // Additional preloads cannot turn an earlier attempted/missing date into a known amount mid-response.
        var selected=new TreeMap<LocalDate,FinanceFx.Day>();
        wanted.forEach(date->{if(days.containsKey(date))selected.put(date,days.get(date));});return selected;
    }
    private void refreshHistory(String id,Collection<LocalDate> pending){
        var wanted=List.copyOf(new TreeSet<>(pending));
        String key="history:"+id+":"+hash(wanted.toString());
        afterCommit(()->refreshes.submit(key,()->{
            // A later date range has a different key and is not held behind an older range's cooldown.
            // The worker owns its Quote; a returned response never changes during calculation.
            extend(id,wanted,new FinanceFx.Quote(id,clock.instant(),Map.of(),Map.of()),true);
        }));
    }
    private static void applyStored(NavigableMap<LocalDate,StoredDay> stored,Collection<LocalDate> wanted,LocalDate cutoff,Map<LocalDate,FinanceFx.Day> target){
        for(LocalDate business:wanted){
            LocalDate upper=business.isAfter(cutoff)?cutoff:business;var candidate=stored.floorEntry(upper);
            if(candidate!=null&&!candidate.getKey().isBefore(business.minusDays(14))&&!candidate.getValue().through().isBefore(upper))
                target.putIfAbsent(business,candidate.getValue().day());
        }
    }
    static NavigableMap<LocalDate,FinanceFx.Day> parse(String raw,LocalDate maximum){
        var json=new ObjectMapper();List<?> rows;
        try{rows=json.readValue(raw,List.class);}catch(RuntimeException invalid){throw bad("历史汇率响应不正确");}
        var eur=new TreeMap<LocalDate,Map<String,BigDecimal>>();
        for(Object value:rows){
            var row=map(value);LocalDate date=day(row.get("date"));String currency=text(row.get("quote"));BigDecimal rate=decimal(row.get("rate"));
            if(date==null||date.isAfter(maximum)||!"EUR".equals(row.get("base"))||!CURRENCIES.contains(currency)||rate==null||rate.signum()<=0)throw bad("历史汇率包含无效日期、币种或数值");
            BigDecimal previous=eur.computeIfAbsent(date,k->new TreeMap<>()).putIfAbsent(currency,rate);
            if(previous!=null&&previous.compareTo(rate)!=0)throw bad("历史汇率存在冲突");
        }
        var result=new TreeMap<LocalDate,FinanceFx.Day>();
        eur.forEach((date,rates)->{
            if(!rates.keySet().containsAll(CURRENCIES))throw bad("历史汇率币种覆盖不完整");
            var converted=new TreeMap<String,BigDecimal>();BigDecimal cny=rates.get("CNY");
            rates.forEach((currency,rate)->converted.put(currency,cny.divide(rate,16,RoundingMode.HALF_UP)));
            converted.put("CNY",BigDecimal.ONE);result.put(date,new FinanceFx.Day(date,converted));
        });
        return result;
    }
    private void persist(Map<LocalDate,FinanceFx.Day> days,String raw,LocalDate verifiedThrough){
        if(days.isEmpty())return;
        // Fetch and parse finish before this short transaction. Every currency and coverage update
        // from the provider result commits together, including drivers that execute batches row by row.
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
        transaction.executeWithoutResult(status->{
            Instant fetched=clock.instant();var batch=new ArrayList<Object[]>();String digest=hash(raw);
            var ordered=new TreeMap<>(days);
            ordered.forEach((date,day)->{
                var existing=repo.jdbc().queryForList("SELECT currency,source_sha256 FROM finance_fx_historical_rates WHERE provider='ECB' AND rate_date=? ORDER BY currency FOR UPDATE",date);
                var currencies=new HashSet<String>();var sources=new HashSet<String>();
                existing.forEach(row->{currencies.add(text(row.get("currency")));sources.add(text(row.get("source_sha256")));});
                // Legacy partial/mixed publications cannot become falsely complete using another payload.
                // Complete canonical publications may still extend their boundary without changing rates.
                if(!existing.isEmpty()&&(sources.size()!=1||!currencies.containsAll(CURRENCIES)&&!sources.contains(digest)))return;
                LocalDate next=ordered.higherKey(date),through=next==null?verifiedThrough:next.minusDays(1);
                LocalDate closedDay=LocalDate.now(clock).minusDays(1);
                LocalDate immutableThrough=through.isAfter(closedDay)?date.isAfter(closedDay)?date:closedDay:through;
                new TreeMap<>(day.rates()).forEach((currency,rate)->batch.add(new Object[]{date,currency,rate,immutableThrough,Timestamp.from(fetched),digest}));
            });
            if(!batch.isEmpty())repo.jdbc().batchUpdate("INSERT INTO finance_fx_historical_rates(provider,rate_date,currency,rate_cny,valid_through_date,fetched_at,source_sha256) VALUES('ECB',?,?,?,?,?,?)"
                +" ON DUPLICATE KEY UPDATE valid_through_date=GREATEST(valid_through_date,VALUES(valid_through_date))",batch);
        });
    }
    private NavigableMap<LocalDate,StoredDay> stored(LocalDate from,LocalDate until){
        var rates=new TreeMap<LocalDate,Map<String,BigDecimal>>();
        var through=new TreeMap<LocalDate,LocalDate>();var sources=new TreeMap<LocalDate,Set<String>>();
        for(var row:repo.jdbc().queryForList("SELECT rate_date,currency,rate_cny,valid_through_date,source_sha256 FROM finance_fx_historical_rates WHERE provider='ECB' AND rate_date BETWEEN ? AND ? ORDER BY rate_date",from,until)){
            var date=day(row.get("rate_date"));BigDecimal rate=decimal(row.get("rate_cny"));
            var end=day(row.get("valid_through_date"));
            if(date!=null&&end!=null&&rate!=null&&rate.signum()>0){
                rates.computeIfAbsent(date,k->new TreeMap<>()).put(text(row.get("currency")),rate);
                through.merge(date,end,(a,b)->a.isBefore(b)?a:b);
                sources.computeIfAbsent(date,key->new HashSet<>()).add(text(row.get("source_sha256")));
            }
        }
        var result=new TreeMap<LocalDate,StoredDay>();
        rates.forEach((date,value)->{if(value.keySet().containsAll(CURRENCIES)&&sources.get(date).size()==1)result.put(date,new StoredDay(new FinanceFx.Day(date,value),through.get(date)));});
        return result;
    }
    private void refreshMarket(){
        Market current=latest;
        if(current!=null&&current.fetched().plusSeconds(900).isAfter(clock.instant()))return;
        afterCommit(()->refreshes.submit("market",()->{
            String raw=fetch.apply(URI.create(ENDPOINT));var days=parse(raw,LocalDate.now(clock));
            if(days.isEmpty())throw bad("当前参考行情暂时不可用");
            persist(days,raw,days.lastKey());latest=new Market(days.lastEntry().getValue(),clock.instant());
        }));
    }
    private static void afterCommit(Runnable work){
        if(TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isSynchronizationActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){work.run();}});
        }else work.run();
    }
    Map<String,Object> market(){
        Market value=latest;refreshMarket();
        String status=value==null?"UNAVAILABLE":value.fetched().plusSeconds(900).isAfter(clock.instant())?"READY":"STALE";
        String error="READY".equals(status)?null:"当前参考行情暂时不可用；历史金额不会使用当前行情兜底";
        var result=new LinkedHashMap<String,Object>();result.put("provider",FinanceFx.PROVIDER);result.put("attributionUrl",FinanceFx.ATTRIBUTION);
        result.put("rateDate",value==null?null:value.day().rateDate().toString());result.put("updatedAt",value==null?null:value.day().rateDate().toString());
        result.put("fetchedAt",value==null?null:value.fetched().toString());result.put("rates",value==null?Map.of():value.day().rates());
        result.put("status",status);result.put("error",error);result.put("basis","1 单位外币对应人民币；仅作当前参考行情，不参与历史金额换算");return result;
    }
    @Override public void close(){refreshes.close();}
    private static LocalDate day(Object value){
        try{String date=text(value);return date.isBlank()?null:LocalDate.parse(date.substring(0,10));}catch(RuntimeException e){return null;}
    }
    private static Map<LocalDate,FinanceFx.Day> decodeDays(Map<String,Object> values){
        var result=new TreeMap<LocalDate,FinanceFx.Day>();
        values.forEach((key,value)->{
            var item=map(value);var rates=new TreeMap<String,BigDecimal>();map(item.get("rates")).forEach((currency,rate)->rates.put(currency,decimal(rate)));
            result.put(LocalDate.parse(key),new FinanceFx.Day(LocalDate.parse(text(item.get("rateDate"))),rates));
        });
        return result;
    }
    private static Map<String,Object> encodeDays(Map<LocalDate,FinanceFx.Day> days){
        var result=new TreeMap<String,Object>();
        days.forEach((business,day)->{
            var rates=new TreeMap<String,String>();day.rates().forEach((currency,rate)->rates.put(currency,rate.toPlainString()));
            result.put(business.toString(),Map.of("rateDate",day.rateDate().toString(),"rates",rates));
        });
        return result;
    }
}
