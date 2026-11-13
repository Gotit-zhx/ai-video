package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Durable, original-currency read facts. A missing or dirty partition never masquerades as zero. */
@Service
public class FinanceQueryProjections {
    static final int SCHEMA_VERSION=1, EVENT_BATCH=2000;
    private final FinanceRepository repo;
    private final FinanceService finance;
    private final FinanceAdSpendService spend;
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    public record Snapshot(Map<String,Object> facts,Map<String,Object> spendFacts,List<Map<String,Object>> rawCounts,
                           Instant generatedAt,String revision,boolean refreshing) {}
    private record Key(String organization,String platform,LocalDate month) {}
    private record OrderKey(String shop,String order) {}
    private record BillPrefix(String user,String shop,String source) {}
    private static final class Change {boolean all;final Set<LocalDate> days=new TreeSet<>();}
    public FinanceQueryProjections(FinanceRepository repo,FinanceService finance,FinanceAdSpendService spend){
        this.repo=repo;this.finance=finance;this.spend=spend;
    }

    /** Only complete committed facts are returned. The caller keeps its original fallback. */
    public Optional<Snapshot> find(Scope scope,Filter filter){
        return find(scope,filter,true,true);
    }
    /** A page needs either dashboard facts/counts or the independent spend report, never both. */
    Optional<Snapshot> findForQuery(Scope scope,Filter filter,boolean primaryAdvertising){
        return find(scope,filter,!primaryAdvertising,primaryAdvertising);
    }
    private Optional<Snapshot> find(Scope scope,Filter filter,boolean dashboard,boolean independentSpend){
        return repo.readSnapshot(15,()->{
            var months=months(filter);
            if(hasRelevantEvents(scope,filter))return Optional.empty();
            String columns="partition_month,schema_version,built_revision,requested_revision,generated_at,CASE WHEN full_month_json IS NOT NULL AND partial_first_day_json IS NOT NULL AND spend_json IS NOT NULL THEN 1 ELSE 0 END payload_present"
                +(dashboard?",full_month_json,partial_first_day_json":"")+(independentSpend?",spend_json":"");
            var stored=repo.jdbc().queryForList("SELECT "+columns+" FROM finance_query_partitions WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ? ORDER BY partition_month",
                scope.organizationId(),filter.platform(),months.getFirst(),months.getLast());
            if(stored.size()!=months.size()||stored.stream().anyMatch(row->number(row.get("schema_version"))!=SCHEMA_VERSION
                    ||number(row.get("built_revision"))==0||number(row.get("built_revision"))!=number(row.get("requested_revision"))
                    ||number(row.get("payload_present"))!=1))return Optional.empty();
            var buckets=new ArrayList<Map<String,Object>>();var orders=new ArrayList<Map<String,Object>>();
            var advertising=new ArrayList<Map<String,Object>>();var counts=new ArrayList<Map<String,Object>>();
            var sources=new ArrayList<Map<String,Object>>();var assignments=new ArrayList<Map<String,Object>>();
            var revisions=new ArrayList<String>();Instant generated=null;
            for(var row:stored){
                LocalDate month=date(row.get("partition_month"));
                boolean full=!filter.start().isAfter(month)&&!filter.end().isBefore(YearMonth.from(month).atEndOfMonth());
                if(dashboard){
                    var data=read(row.get("full_month_json"));var first=read(row.get("partial_first_day_json"));
                    appendRange(buckets,rows(data.get("buckets")),rows(first.get("buckets")),filter,month,full);
                    appendRange(orders,rows(data.get("orders")),rows(first.get("orders")),filter,month,full);
                    appendRange(advertising,rows(data.get("advertising")),rows(first.get("advertising")),filter,month,full);
                    appendRange(counts,rows(data.get("rawCounts")),rows(first.get("rawCounts")),filter,month,full);
                }
                if(independentSpend){
                    var ads=read(row.get("spend_json"));
                    sources.addAll(rows(ads.get("sources")).stream().filter(r->inRange(r,filter))
                        .filter(r->filter.currency().isBlank()||filter.currency().equals(text(r.get("currency")))).toList());
                    assignments.addAll(rows(ads.get("assignments")));
                }
                revisions.add(month+":"+row.get("built_revision"));
                Instant built=instant(row.get("generated_at"));if(generated==null||built.isBefore(generated))generated=built;
            }
            if(sources.size()>FinanceAdSpendService.MAX_RECORDS)return Optional.empty();
            var shops=finance.shops(scope);
            var selected=selectedShops(shops,filter);
            var nativeFilter=new Filter(filter.start(),filter.end(),filter.shopId(),filter.platform(),"","day",filter.groupId());
            // Platform is already part of the persistent key, so UNASSIGNED remains valid for a named platform.
            Map<String,Object> facts=dashboard?new LinkedHashMap<>(Map.of("buckets",buckets.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "orders",orders.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "advertising",advertising.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "reportingMonths",new FinanceReportingMonths(repo).coverage(scope,nativeFilter))):new LinkedHashMap<>();
            counts.removeIf(row->!selected.contains(text(row.get("shop_id")))||!filter.currency().isBlank()&&!filter.currency().equals(text(row.get("currency"))));
            var keys=new HashSet<String>();sources.forEach(row->keys.add(text(row.get("source_key"))));
            assignments.removeIf(row->!keys.contains(text(row.get("source_key"))));
            String revision=hash(scope.organizationId()+"|"+filter.platform()+"|"+String.join(",",revisions));
            facts=new LinkedHashMap<>(facts);facts.put("_financeDataRevision",revision);facts.put("_financeFactsBuilt",generated.toEpochMilli());
            return Optional.of(new Snapshot(facts,Map.of("shops",shops,"sources",sources,"assignments",assignments),counts,generated,revision,false));
        });
    }

    /** Revision polling reads only partition metadata, never financial JSON payloads. */
    public Map<String,Object> version(Scope scope,Filter filter){
        return repo.readSnapshot(15,()->{
            var months=months(filter);
            var partitions=repo.jdbc().queryForList("SELECT partition_month,schema_version,built_revision,requested_revision,CASE WHEN full_month_json IS NOT NULL AND partial_first_day_json IS NOT NULL AND spend_json IS NOT NULL THEN 1 ELSE 0 END payload_present FROM finance_query_partitions WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ? ORDER BY partition_month",
                scope.organizationId(),filter.platform(),months.getFirst(),months.getLast());
            boolean ready=partitions.size()==months.size()&&partitions.stream().allMatch(row->number(row.get("schema_version"))==SCHEMA_VERSION
                &&number(row.get("built_revision"))>0&&number(row.get("built_revision"))==number(row.get("requested_revision"))&&number(row.get("payload_present"))==1)&&!hasRelevantEvents(scope,filter);
            var revisions=partitions.stream().map(row->date(row.get("partition_month"))+":"+row.get("built_revision")).toList();
            return Map.of("status",ready?"READY":"PENDING","revision",hash(scope.organizationId()+"|"+filter.platform()+"|"+String.join(",",revisions)));
        });
    }

    /** Enqueue only absent partitions; normal reads never rewrite a clean revision. */
    public void request(Scope scope,Filter filter){
        for(LocalDate month:months(filter))repo.jdbc().update("INSERT IGNORE INTO finance_query_partitions(organization_id,platform_filter,partition_month) VALUES(?,?,?)",
            scope.organizationId(),filter.platform(),month);
    }

    /** Enqueue the two default business months, with every company's previous month first. */
    void warmDefaults(Instant now){
        var organizations=repo.jdbc().queryForList("SELECT DISTINCT organization_id FROM finance_connections WHERE status<>'REMOVED' ORDER BY organization_id",String.class);
        var previous=FinanceCalendar.previousMonth(now);
        for(var month:List.of(previous,previous.plusMonths(1)))for(String organization:organizations)
            for(String platform:List.of("","SHOPEE","TIKTOK_SHOP"))
                request(companyScope(organization),new Filter(month.atDay(1),month.atEndOfMonth(),"",platform,"","day"));
    }

    public Map<String,Object> status(Scope scope){
        var result=new LinkedHashMap<>(repo.jdbc().queryForMap("SELECT COUNT(*) partitions,COALESCE(SUM(built_revision>0 AND built_revision=requested_revision),0) ready,COALESCE(SUM(built_revision<>requested_revision),0) pending,COALESCE(SUM(lease_until>UTC_TIMESTAMP(6)),0) running,COALESCE(SUM(failures>0),0) failed FROM finance_query_partitions WHERE organization_id=?",scope.organizationId()));
        long events=number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=?",Long.class,scope.organizationId()));
        result.put("events",events);result.put("builtPartitions",result.get("ready"));
        // Without resolving each pending event, a lightweight status cannot assert that its partitions are readable.
        if(events>0)result.put("ready",0L);
        result.put("coverage","Only committed, complete partitions without relevant pending events are readable");return result;
    }

    /** Claim concrete visible IDs. A MAX(event_id) watermark would lose late commits. */
    int drainEvents(){
        return repo.transaction(()->{
            var events=repo.jdbc().queryForList("SELECT * FROM finance_query_events ORDER BY event_id LIMIT "+EVENT_BATCH+" FOR UPDATE SKIP LOCKED");
            if(events.isEmpty())return 0;
            var changed=new LinkedHashMap<String,Change>();
            var orders=new LinkedHashMap<String,Set<OrderKey>>();
            var records=new LinkedHashMap<String,Set<String>>();
            var statements=new LinkedHashMap<String,Map<String,Set<String>>>();
            for(var event:events){
                String org=text(event.get("organization_id"));var change=changed.computeIfAbsent(org,key->{
                    var pending=new Change();
                    // Full invalidation safely covers every historical date, including a
                    // moved first settlement. Resolve individual dates only for normal bursts.
                    pending.all=!repo.jdbc().queryForList("SELECT event_id FROM finance_query_events FORCE INDEX(idx_finance_query_events_scope) WHERE organization_id=? ORDER BY event_id LIMIT 1 OFFSET "+EVENT_BATCH,org).isEmpty();
                    return pending;
                });
                if(change.all)continue;
                if("ALL".equals(event.get("event_scope"))){change.all=true;continue;}
                if(event.get("record_date")!=null)change.days.add(date(event.get("record_date")));
                addStatement(statements,org,text(event.get("shop_id")),text(event.get("statement_id")));
                if(!"ORDER".equals(event.get("event_scope"))||change.all)continue;
                String shop=text(event.get("shop_id")),order=text(event.get("order_id"));
                if(order.isBlank()&&!text(event.get("record_id")).isBlank()){
                    records.computeIfAbsent(org,key->new LinkedHashSet<>()).add(text(event.get("record_id")));
                    continue;
                }
                if(order.isBlank())continue;
                orders.computeIfAbsent(org,key->new LinkedHashSet<>()).add(new OrderKey(shop,order));
            }
            for(var item:records.entrySet()){
                String org=item.getKey();if(changed.get(org).all)continue;
                var args=new ArrayList<Object>();args.add(org);args.addAll(item.getValue());
                var found=repo.jdbc().queryForList("SELECT id,shop_id,order_id FROM finance_records WHERE organization_id=? AND id IN ("+placeholders(item.getValue().size())+")",args.toArray());
                if(found.size()!=item.getValue().size()){changed.get(org).all=true;continue;}
                for(var row:found)if(!text(row.get("order_id")).isBlank())orders.computeIfAbsent(org,key->new LinkedHashSet<>()).add(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
            }
            for(var item:orders.entrySet()){
                String org=item.getKey();var change=changed.get(org);if(change.all)continue;
                var byShop=new LinkedHashMap<String,Set<String>>();
                item.getValue().forEach(key->byShop.computeIfAbsent(key.shop,k->new LinkedHashSet<>()).add(key.order));
                var clauses=new ArrayList<String>();var args=new ArrayList<Object>();args.add(org);
                byShop.forEach((shop,ids)->{clauses.add("(shop_id=? AND order_id IN ("+placeholders(ids.size())+"))");args.add(shop);args.addAll(ids);});
                // Include every date and API settlement link, once per batch rather than once per event.
                var rows=repo.jdbc().queryForList("SELECT DISTINCT shop_id,record_date,CASE WHEN kind='SETTLEMENT' AND source='API' THEN statement_id ELSE '' END statement_id FROM finance_records FORCE INDEX(idx_finance_marketing_order) WHERE organization_id=? AND ("+String.join(" OR ",clauses)+") LIMIT 10001",args.toArray());
                if(rows.size()>10000){change.all=true;continue;}
                for(var row:rows){change.days.add(date(row.get("record_date")));addStatement(statements,org,text(row.get("shop_id")),text(row.get("statement_id")));}
            }
            for(var item:statements.entrySet())if(!changed.get(item.getKey()).all)addBillDates(changed.get(item.getKey()),item.getKey(),item.getValue());
            for(var item:changed.entrySet()){
                String org=item.getKey();var change=item.getValue();
                if(change.all){repo.jdbc().update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1,full_rebuild=TRUE,requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6) WHERE organization_id=?",org);continue;}
                var months=new TreeSet<LocalDate>();change.days.forEach(day->months.add(day.withDayOfMonth(1)));
                for(LocalDate month:months)repo.jdbc().update("UPDATE finance_query_partitions SET requested_revision=requested_revision+1,requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND partition_month=?",org,month);
                for(LocalDate day:change.days)repo.jdbc().update("INSERT IGNORE INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date) SELECT organization_id,platform_filter,partition_month,? FROM finance_query_partitions WHERE organization_id=? AND partition_month=?",day,org,day.withDayOfMonth(1));
            }
            var ids=events.stream().map(row->row.get("event_id")).toList();
            repo.jdbc().update("DELETE FROM finance_query_events WHERE event_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
            return events.size();
        });
    }

    /** One leased month per call; no source/provider requests are made by this worker. */
    boolean buildNext(){
        var claimed=repo.transaction(()->{
            // During catch-up, source scans compete with the drainer and are repeatedly
            // discarded by its next revision. A bounded index probe prioritizes that
            // company's invalidations; other companies and small normal bursts still build.
            var ready=repo.jdbc().queryForList("SELECT p.* FROM finance_query_partitions p WHERE p.built_revision<>p.requested_revision AND p.next_attempt_at<=UTC_TIMESTAMP(6) AND (p.lease_until IS NULL OR p.lease_until<UTC_TIMESTAMP(6)) AND NOT EXISTS(SELECT 1 FROM finance_query_events e WHERE e.organization_id=p.organization_id ORDER BY e.event_id LIMIT 1 OFFSET "+EVENT_BATCH+") ORDER BY p.requested_at LIMIT 1 FOR UPDATE SKIP LOCKED");
            if(ready.isEmpty())return Map.<String,Object>of();
            var row=new LinkedHashMap<>(ready.getFirst());String token=UUID.randomUUID().toString();
            repo.jdbc().update("UPDATE finance_query_partitions SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND platform_filter=? AND partition_month=?",token,row.get("organization_id"),row.get("platform_filter"),row.get("partition_month"));
            row.put("lease_token",token);return row;
        });
        if(claimed.isEmpty())return false;
        var key=new Key(text(claimed.get("organization_id")),text(claimed.get("platform_filter")),date(claimed.get("partition_month")));
        try{
            var built=repo.readSnapshot(180,()->build(key,claimed));
            String full=json.writeValueAsString(normalize(built.get("full"))),first=json.writeValueAsString(normalize(built.get("first"))),ads=json.writeValueAsString(normalize(built.get("spend")));
            repo.transaction(()->{
                int published=repo.jdbc().update("UPDATE finance_query_partitions SET full_month_json=?,partial_first_day_json=?,spend_json=?,built_revision=requested_revision,schema_version=?,generated_at=UTC_TIMESTAMP(6),full_rebuild=FALSE,lease_token=NULL,lease_until=NULL,failures=0,error_code=NULL WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=? AND requested_revision=? AND lease_until>UTC_TIMESTAMP(6)",
                    full,first,ads,SCHEMA_VERSION,key.organization,key.platform,key.month,claimed.get("lease_token"),claimed.get("requested_revision"));
                if(published==1)repo.jdbc().update("DELETE FROM finance_query_dirty_days WHERE organization_id=? AND platform_filter=? AND partition_month=?",key.organization,key.platform,key.month);
                else repo.jdbc().update("UPDATE finance_query_partitions SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=?",key.organization,key.platform,key.month,claimed.get("lease_token"));
                return null;
            });
        }catch(RuntimeException failure){
            repo.jdbc().update("UPDATE finance_query_partitions SET lease_token=NULL,lease_until=NULL,failures=failures+1,error_code=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=?",
                failure.getClass().getSimpleName(),key.organization,key.platform,key.month,claimed.get("lease_token"));
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance projection deferred: {}",failure.getClass().getSimpleName());
        }
        return true;
    }

    private Map<String,Object> build(Key key,Map<String,Object> previous){
        Scope scope=companyScope(key.organization);var month=monthFilter(key);var firstDay=new Filter(key.month,key.month,"",key.platform,"","day");
        // The compact partial-month strategy is valid only when corrections are anchored on month day one.
        var invalid=repo.jdbc().queryForList("SELECT r.id FROM finance_reporting_months m JOIN finance_records r FORCE INDEX(idx_finance_marketing_order) ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id AND r.statement_id=m.statement_id WHERE m.organization_id=? AND m.reporting_month=? AND r.kind IN ('SETTLEMENT','FEE') AND r.source='API' AND r.platform='TIKTOK_SHOP' AND JSON_EXTRACT(r.raw_json,'$._finance_reporting_month') IS NOT NULL AND r.record_date<>m.reporting_month LIMIT 1",key.organization,key.month);
        if(!invalid.isEmpty())throw new IllegalStateException("Reporting month anchor requires source repair");
        Map<String,Object> full,first,ads;
        if(Boolean.TRUE.equals(previous.get("full_rebuild"))||number(previous.get("full_rebuild"))!=0||previous.get("full_month_json")==null){
            full=nativeFacts(scope,month);first=nativeFacts(scope,firstDay);ads=spend.buildNativeSpendFacts(scope,month);
        }else{
            full=new LinkedHashMap<>(read(previous.get("full_month_json")));first=new LinkedHashMap<>(read(previous.get("partial_first_day_json")));ads=new LinkedHashMap<>(read(previous.get("spend_json")));
            var days=repo.jdbc().queryForList("SELECT record_date FROM finance_query_dirty_days WHERE organization_id=? AND platform_filter=? AND partition_month=? ORDER BY record_date",java.sql.Date.class,key.organization,key.platform,key.month);
            if(days.isEmpty())throw new IllegalStateException("Dirty partition has no affected days");
            for(var sqlDay:days){
                LocalDate day=sqlDay.toLocalDate();
                var changed=repo.inProjectionWindow(month,day,()->nativeFacts(scope,month));
                for(String name:List.of("buckets","orders","advertising","rawCounts"))full.put(name,replaceDay(rows(full.get(name)),rows(changed.get(name)),day));
                full.put("reportingMonths",changed.get("reportingMonths"));
                if(day.equals(key.month))first=nativeFacts(scope,firstDay);
                var dayFilter=new Filter(day,day,"",key.platform,"","day");var newSpend=spend.buildNativeSpendFacts(scope,dayFilter);
                var removed=new HashSet<String>();rows(ads.get("sources")).stream().filter(r->day.equals(date(r.get("record_date")))).forEach(r->removed.add(text(r.get("source_key"))));
                var assignments=new ArrayList<>(rows(ads.get("assignments")).stream().filter(r->!removed.contains(text(r.get("source_key")))).toList());assignments.addAll(rows(newSpend.get("assignments")));
                ads.put("sources",replaceDay(rows(ads.get("sources")),rows(newSpend.get("sources")),day));ads.put("assignments",assignments);ads.put("shops",newSpend.get("shops"));
            }
        }
        if(rows(ads.get("sources")).size()>FinanceAdSpendService.MAX_RECORDS)throw new IllegalStateException("Projection source limit exceeded");
        return Map.of("full",full,"first",first,"spend",ads);
    }

    private Map<String,Object> nativeFacts(Scope scope,Filter filter){
        var result=new LinkedHashMap<>(finance.buildNativeDashboardFacts(scope,filter,false));
        var counts=new ArrayList<Map<String,Object>>();var query=repo.query(scope,filter);
        // These are raw-page facets: source restrictions and advertising fees are independent of operating exclusions.
        String advertisingFee="CASE WHEN kind='FEE' AND "+FinanceAdvertisingReport.ledgerPredicate("finance_records")+" THEN 1 ELSE 0 END";
        counts.addAll(repo.jdbc().queryForList("SELECT shop_id,record_date,kind,currency,source,'RECORD' basis,"+advertisingFee
            +" advertising_fee,COUNT(*) records FROM finance_records WHERE "+query.where()+" GROUP BY shop_id,record_date,kind,currency,source,advertising_fee",query.args().toArray()));
        for(String kind:List.of("ORDER","REFUND")){
            var q=repo.query(scope,filter,"r");
            counts.addAll(repo.jdbc().queryForList("SELECT r.shop_id,r.record_date,o.kind,r.currency,'API' source,0 advertising_fee,'SETTLED' basis,COUNT(*) records"
                +repo.settledOrderRelation(scope,filter,kind,false)+" GROUP BY r.shop_id,r.record_date,o.kind,r.currency",q.args().toArray()));
        }
        result.put("rawCounts",FinanceService.dailyFactRows(counts));return result;
    }

    private boolean hasRelevantEvents(Scope scope,Filter filter){
        if(repo.jdbc().queryForList("SELECT event_id FROM finance_query_events WHERE organization_id=? LIMIT 1",scope.organizationId()).isEmpty())return false;
        LocalDate start=filter.start().withDayOfMonth(1),end=YearMonth.from(filter.end()).atEndOfMonth();
        if(!repo.jdbc().queryForList("SELECT event_id FROM finance_query_events WHERE organization_id=? AND (event_scope='ALL' OR record_date BETWEEN ? AND ?) LIMIT 1",scope.organizationId(),start,end).isEmpty())return true;
        var prefixes=billPrefixes(scope.organizationId());
        var pairs=prefixes.stream().map(p->List.of(p.user,p.source)).distinct().toList();
        // A pathological owner/source history is conservatively pending, never silently omitted.
        if(pairs.size()>128)return true;
        // ORDER events can alter a historical first-settlement month even when the source date is elsewhere.
        var args=new ArrayList<Object>();args.add(scope.organizationId());
        String eventBill=billExists("e",pairs,start,end,args);
        String related="(r.record_date BETWEEN ? AND ? OR (r.kind='SETTLEMENT' AND ";
        args.add(start);args.add(end);String firstRelated=related+billExists("r",pairs,start,end,args)+"))";
        args.add(start);args.add(end);String secondRelated=related+billExists("r",pairs,start,end,args)+"))";
        return !repo.jdbc().queryForList("SELECT e.event_id FROM finance_query_events e WHERE e.organization_id=? AND ("
            +" (e.statement_id IS NOT NULL AND e.statement_id<>'' AND "+eventBill+") OR (e.event_scope='ORDER' AND ("
            +" EXISTS(SELECT 1 FROM finance_records r FORCE INDEX(idx_finance_marketing_order) WHERE r.organization_id=e.organization_id AND r.shop_id=e.shop_id AND r.order_id=e.order_id AND "+firstRelated+")"
            +" OR (COALESCE(e.order_id,'')='' AND e.record_id IS NOT NULL AND (NOT EXISTS(SELECT 1 FROM finance_records o WHERE o.id=e.record_id AND o.organization_id=e.organization_id)"
            +" OR EXISTS(SELECT 1 FROM finance_records o JOIN finance_records r FORCE INDEX(idx_finance_marketing_order) ON r.organization_id=o.organization_id AND r.shop_id=o.shop_id AND r.order_id=o.order_id WHERE o.id=e.record_id AND o.organization_id=e.organization_id AND "+secondRelated+")))"
            +"))) LIMIT 1",args.toArray()).isEmpty();
    }
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static void addStatement(Map<String,Map<String,Set<String>>> statements,String org,String shop,String statement){
        if(!shop.isBlank()&&!statement.isBlank())statements.computeIfAbsent(org,k->new LinkedHashMap<>()).computeIfAbsent(shop,k->new LinkedHashSet<>()).add(statement);
    }
    /** Enumerate real historical owners/sources using a covering loose-index scan, including removed owners. */
    private List<BillPrefix> billPrefixes(String org){
        return repo.jdbc().queryForList("SELECT organization_id,user_id,shop_id,kind,source FROM finance_records FORCE INDEX(idx_finance_reconcile_bill) WHERE organization_id=? GROUP BY organization_id,user_id,shop_id,kind,source",org).stream()
            .filter(row->"BILL".equals(row.get("kind"))).map(row->new BillPrefix(text(row.get("user_id")),text(row.get("shop_id")),text(row.get("source")))).toList();
    }
    private static String billExists(String owner,List<List<String>> pairs,LocalDate start,LocalDate end,List<Object> args){
        var clauses=new ArrayList<String>();
        for(var pair:pairs){
            // Separate equalities are essential: IN over user_id causes correlated probes to use only organization_id.
            clauses.add("EXISTS(SELECT 1 FROM finance_records b FORCE INDEX(idx_finance_reconcile_bill) WHERE b.organization_id="+owner+".organization_id AND b.user_id=? AND b.shop_id="+owner+".shop_id AND b.kind='BILL' AND b.source=? AND b.external_id="+owner+".statement_id AND b.record_date BETWEEN ? AND ?)");
            args.add(pair.get(0));args.add(pair.get(1));args.add(start);args.add(end);
        }
        return clauses.isEmpty()?"FALSE":"("+String.join(" OR ",clauses)+")";
    }
    private void addBillDates(Change change,String org,Map<String,Set<String>> statements){
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();args.add(org);
        for(var prefix:billPrefixes(org)){
            var ids=statements.get(prefix.shop);if(ids==null)continue;
            clauses.add("(user_id=? AND shop_id=? AND source=? AND external_id IN ("+placeholders(ids.size())+"))");
            args.add(prefix.user);args.add(prefix.shop);args.add(prefix.source);args.addAll(ids);
        }
        if(clauses.isEmpty())return;
        var dates=repo.jdbc().queryForList("SELECT DISTINCT record_date FROM finance_records FORCE INDEX(idx_finance_reconcile_bill) WHERE organization_id=? AND kind='BILL' AND ("+String.join(" OR ",clauses)+") LIMIT 10001",java.sql.Date.class,args.toArray());
        if(dates.size()>10000)change.all=true;else dates.forEach(day->change.days.add(day.toLocalDate()));
    }
    private static List<LocalDate> months(Filter filter){
        var result=new ArrayList<LocalDate>();for(LocalDate month=filter.start().withDayOfMonth(1);!month.isAfter(filter.end());month=month.plusMonths(1))result.add(month);return result;
    }
    private static Filter monthFilter(Key key){return new Filter(key.month,YearMonth.from(key.month).atEndOfMonth(),"",key.platform,"","day");}
    private static Set<String> selectedShops(List<Map<String,Object>> shops,Filter filter){
        var selected=new HashSet<String>();shops.stream().filter(row->FinanceGroups.matches(row,filter)).forEach(row->selected.add(text(row.get("id"))));
        if((filter.shopId().isBlank()||filter.shopId().equals("UNASSIGNED"))&&(filter.groupId().isBlank()||filter.groupId().equals("UNGROUPED")))selected.add("UNASSIGNED");return selected;
    }
    private static void appendRange(List<Map<String,Object>> target,List<Map<String,Object>> full,List<Map<String,Object>> first,Filter filter,LocalDate month,boolean whole){
        target.addAll(full.stream().filter(row->inRange(row,filter)).filter(row->whole||!month.equals(date(row.get("record_date")))).toList());
        if(!whole)target.addAll(first.stream().filter(row->inRange(row,filter)).toList());
    }
    private static boolean inRange(Map<String,Object> row,Filter filter){LocalDate day=date(row.get("record_date"));return !day.isBefore(filter.start())&&!day.isAfter(filter.end());}
    private static List<Map<String,Object>> replaceDay(List<Map<String,Object>> before,List<Map<String,Object>> after,LocalDate day){
        var result=new ArrayList<>(before.stream().filter(row->!day.equals(date(row.get("record_date")))).toList());result.addAll(after);return result;
    }
    private static LocalDate date(Object value){return LocalDate.parse(text(value).substring(0,10));}
    private Map<String,Object> read(Object value){return map(json.readValue(text(value),Map.class));}
    private static Object normalize(Object value){
        if(value instanceof java.sql.Date day)return day.toLocalDate().toString();
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant().toString();
        if(value instanceof java.time.temporal.TemporalAccessor)return value.toString();
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<String,Object>();map.forEach((key,item)->copy.put(String.valueOf(key),normalize(item)));return copy;}
        if(value instanceof Collection<?> list)return list.stream().map(FinanceQueryProjections::normalize).toList();
        return value;
    }
}
