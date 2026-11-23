package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import com.yingdao.aicommerce.finance.FinanceSettlementCostDays.Window;

/** Durable, original-currency read facts. A missing or dirty partition never masquerades as zero. */
@Service
public class FinanceQueryProjections {
    static final int SCHEMA_VERSION=1, EVENT_BATCH=2000;
    private static final Map<String,Object> SPEND_READ_FROM_SOURCE=Map.of("readFromSource",true);
    private static final int MAX_SPEND_PAYLOAD_BYTES=56*1024*1024;
    private static final int MAX_REPAIR_EVENTS=512;
    private static final int MAX_REPAIR_SHOP_DAYS=1024;
    private static final int SHOP_SCAN_LIMIT=24;
    private static final int ALL_SHOPS_DAY_WEIGHT=32;
    private static final Set<String> SPEND_ONLY_GLOBAL=Set.of("finance_ad_accounts","finance_ad_stores",
        "finance_ad_spend_allocation_sets","finance_ad_spend_allocations");
    private static final Set<String> COST_INDEPENDENT=java.util.stream.Stream.concat(
        SPEND_ONLY_GLOBAL.stream(),java.util.stream.Stream.of("finance_expenses")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private boolean scopedAdRefresh;
    @org.springframework.beans.factory.annotation.Value("${app.finance.scoped-ad-projection-refresh:false}")
    void configureScopedAdRefresh(boolean enabled){scopedAdRefresh=enabled;}
    private boolean usesScopedAdRefresh(String company){
        return scopedAdRefresh&&scopedSettlementCompany.equals(company);
    }
    private boolean scopedSettlementRevisions;
    private String scopedSettlementCompany="";
    @org.springframework.beans.factory.annotation.Value("${app.finance.scoped-settlement-company:}")
    void configureScopedSettlementCompany(String company){scopedSettlementCompany=company.strip();}
    private boolean usesScopedSettlementRevisions(String company){
        return scopedSettlementRevisions&&scopedSettlementCompany.equals(company);
    }
    @org.springframework.beans.factory.annotation.Value("${app.finance.scoped-settlement-revisions:false}")
    void configureScopedSettlementRevisions(boolean enabled){scopedSettlementRevisions=enabled;}
    private final FinanceRepository repo;
    private final FinanceService finance;
    private final FinanceAdSpendService spend;
    private final FinanceSettlementCostDays settlementDays;
    private final FinanceQueryPriority priority;
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final tools.jackson.databind.ObjectReader ledgerField=json.readerFor(Object.class)
        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Snapshot(Map<String,Object> facts,Map<String,Object> spendFacts,List<Map<String,Object>> rawCounts,
                           Instant generatedAt,String revision,boolean refreshing) {}
    private record Key(String organization,String platform,LocalDate month) {}
    private record Built(SnapshotBodies bodies,FinanceSettlementCostDays.Patch costs,Instant snapshotStarted) {}
    private record SnapshotBodies(Map<String,Object> ledger,Map<String,Object> firstDay,Map<String,Object> spend) {}
    private record OrderKey(String shop,String order) {}
    private record BillPrefix(String user,String shop,String source) {}
    private record FeeFacet(Object day,Object shop,Object currency) {
        static FeeFacet of(Map<String,Object> row){return new FeeFacet(row.get("record_date"),row.get("shop_id"),row.get("currency"));}
        Map<String,Object> count(long records,int advertisingFee){
            // Fee pages use all source types. Only the all-orders page filters source.
            return Map.of("shop_id",shop,"record_date",day,"currency",currency,"kind","FEE","source","",
                "basis","RECORD","advertising_fee",advertisingFee,"records",records);
        }
    }
    private record ShopDay(LocalDate day,String shop) {}
    private record RepairWindows(List<Window> ledger,List<Window> orders) {}
    private enum LedgerFields {
        ALL(List.of("buckets","orders","advertising","rawCounts")),
        OVERVIEW(List.of("buckets","orders","advertising")),
        RECONCILIATION(List.of("orders","rawCounts")),
        ORDER_COUNTS(List.of("rawCounts")),
        NONE(List.of());
        final List<String> names;
        LedgerFields(List<String> names){this.names=names;}
    }
    private static final class Change {
        boolean all,allCosts,spendAll;
        void requireFull(boolean costs){all=true;allCosts|=costs;}
        final Map<ShopDay,Boolean> shopDays=new LinkedHashMap<>();
        void add(Map<String,Object> row,boolean costs){
            if(row.get("record_date")!=null)shopDays.merge(
                new ShopDay(date(row.get("record_date")),text(row.get("shop_id"))),costs,Boolean::logicalOr);
        }
    }
    static final class PollDeadlineElapsed extends RuntimeException {}
    @FunctionalInterface private interface MetadataQuery {List<Map<String,Object>> read(String sql,Object...args);}
    public FinanceQueryProjections(FinanceRepository repo,FinanceService finance,FinanceAdSpendService spend){
        this.repo=repo;this.finance=finance;this.spend=spend;this.settlementDays=new FinanceSettlementCostDays(repo);this.priority=new FinanceQueryPriority(repo);
    }

    /** Only complete committed facts are returned. The caller keeps its original fallback. */
    public Optional<Snapshot> find(Scope scope,Filter filter){
        return find(scope,filter,LedgerFields.ALL,true,false,"");
    }
    Optional<Snapshot> findForDashboard(Scope scope,Filter filter){
        return find(scope,filter,LedgerFields.OVERVIEW,false,false,"");
    }
    /** A page needs either dashboard facts/counts or the independent spend report, never both. */
    Optional<Snapshot> findForQuery(Scope scope,Filter filter,boolean primaryAdvertising){
        return find(scope,filter,primaryAdvertising?LedgerFields.NONE:LedgerFields.ALL,primaryAdvertising,false,"");
    }
    Optional<Snapshot> findForOrderCounts(Scope scope,Filter filter){
        return find(scope,filter,LedgerFields.ORDER_COUNTS,false,false,"ORDER");
    }
    Optional<Snapshot> repairedLedger(Scope scope,Filter filter,boolean countsNeeded){
        return find(scope,filter,countsNeeded?LedgerFields.ALL:LedgerFields.OVERVIEW,false,true,"");
    }
    Optional<Snapshot> repairedLedgerForPage(Scope scope,Filter filter,String kind,boolean reconciliation){
        return find(scope,filter,reconciliation?LedgerFields.RECONCILIATION:LedgerFields.ALL,false,true,kind);
    }
    private Optional<Snapshot> find(Scope scope,Filter filter,LedgerFields fields,boolean independentSpend,boolean repair,String countKind){
        return repo.readSnapshot(repair?180:15,()->{
            boolean dashboard=fields!=LedgerFields.NONE;
            var months=months(filter);
            if(!repair&&hasRelevantEvents(scope,filter))return Optional.empty();
            LocalDate lastMonth=months.getLast();
            // Only the last month can include day one while using a partial-month basis.
            boolean firstDayNeeded=dashboard&&!filter.start().isAfter(lastMonth)
                &&filter.end().isBefore(YearMonth.from(lastMonth).atEndOfMonth());
            // Decide whether this revision can be used before transferring the
            // monthly payload, which may be tens of megabytes.
            var stored=repo.jdbc().queryForList("""
                SELECT partition_month,schema_version,built_revision,requested_revision,generated_at,full_rebuild,
                       CASE WHEN full_month_json IS NOT NULL AND partial_first_day_json IS NOT NULL AND spend_json IS NOT NULL
                            THEN 1 ELSE 0 END payload_present
                """+(repair&&usesScopedAdRefresh(scope.organizationId())?",spend_full_revision ":"")+"""
                FROM finance_query_partitions
                WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ?
                ORDER BY partition_month
                """,scope.organizationId(),filter.platform(),months.getFirst(),lastMonth);
            if(stored.size()!=months.size()||stored.stream().anyMatch(row->number(row.get("schema_version"))!=SCHEMA_VERSION
                    ||number(row.get("built_revision"))==0||!repair&&number(row.get("built_revision"))!=number(row.get("requested_revision"))
                    ||repair&&flag(row.get("full_rebuild"))||number(row.get("payload_present"))!=1))return Optional.empty();
            var repairs=repair?pendingWindows(scope,filter,stored,false):Optional.of(new RepairWindows(List.of(),List.of()));
            if(repairs.isEmpty())return Optional.empty();
            var buckets=new ArrayList<Map<String,Object>>();var orders=new ArrayList<Map<String,Object>>();
            var advertising=new ArrayList<Map<String,Object>>();var counts=new ArrayList<Map<String,Object>>();
            var sources=new ArrayList<Map<String,Object>>();var assignments=new ArrayList<Map<String,Object>>();
            var revisions=new ArrayList<String>();Instant generated=null;
            for(var row:stored){
                LocalDate month=date(row.get("partition_month"));
                boolean full=!filter.start().isAfter(month)&&!filter.end().isBefore(YearMonth.from(month).atEndOfMonth());
                var body=readPayload(scope,filter.platform(),month,number(row.get("built_revision")),
                    dashboard,firstDayNeeded&&month.equals(lastMonth),independentSpend);
                if(dashboard){
                    var data=readLedger(body.get("full_month_json"),fields);
                    var first=body.get("partial_first_day_json")==null?Map.<String,Object>of():readLedger(body.get("partial_first_day_json"),fields);
                    appendRange(buckets,rows(data.get("buckets")),rows(first.get("buckets")),filter,month,full);
                    appendRange(orders,rows(data.get("orders")),rows(first.get("orders")),filter,month,full);
                    appendRange(advertising,rows(data.get("advertising")),rows(first.get("advertising")),filter,month,full);
                    appendRange(counts,rows(data.get("rawCounts")),rows(first.get("rawCounts")),filter,month,full);
                }
                if(independentSpend){
                    var ads=read(body.get("spend_json"));
                    if(flag(ads.get("readFromSource")))return Optional.empty();
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
            Map<String,Object> facts=dashboard&&fields!=LedgerFields.ORDER_COUNTS?new LinkedHashMap<>(Map.of("buckets",buckets.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "orders",orders.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "advertising",advertising.stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "reportingMonths",new FinanceReportingMonths(repo).coverage(scope,nativeFilter))):new LinkedHashMap<>();
            counts.removeIf(row->!selected.contains(text(row.get("shop_id")))
                ||!countKind.isBlank()&&!countKind.equals(row.get("kind"))
                ||!filter.currency().isBlank()&&!filter.currency().equals(text(row.get("currency"))));
            var keys=new HashSet<String>();sources.forEach(row->keys.add(text(row.get("source_key"))));
            assignments.removeIf(row->!keys.contains(text(row.get("source_key"))));
            String revision=hash(scope.organizationId()+"|"+filter.platform()+"|"+String.join(",",revisions));
            if(repair&&!repairs.get().ledger().isEmpty()){
                facts.put("rawCounts",counts);
                facts=new LinkedHashMap<>(repairLedger(scope,nativeFilter,facts,repairs.get(),fields,countKind));
                counts.clear();counts.addAll(rows(facts.remove("rawCounts")));
            }
            if(!repair){
                facts.put("_financeDataRevision",revision);
                facts.put("_financeFactsBuilt",generated.toEpochMilli());
            }
            return Optional.of(new Snapshot(facts,Map.of("shops",shops,"sources",sources,"assignments",assignments),counts,generated,revision,repair));
        });
    }

    private Map<String,Object> readPayload(Scope scope,String platform,LocalDate month,long revision,
                                           boolean ledger,boolean firstDay,boolean spend){
        var columns=new StringJoiner(",");
        if(ledger)columns.add("full_month_json");
        if(firstDay)columns.add("partial_first_day_json");
        if(spend)columns.add("spend_json");
        return repo.jdbc().queryForMap("SELECT "+columns+" "+"""
             FROM finance_query_partitions WHERE organization_id=? AND platform_filter=? AND partition_month=?
               AND built_revision=?
            """,scope.organizationId(),platform,month,revision);
    }

    /** Revision polling reads only partition metadata, never financial JSON payloads. */
    public Map<String,Object> version(Scope scope,Filter filter){
        return repo.readSnapshot(15,()->version(scope,filter,repo.jdbc()::queryForList));
    }
    /** A pending page poll uses the caller's remaining budget without changing the public status timeout. */
    Map<String,Object> version(Scope scope,Filter filter,long timeoutMillis){
        if(timeoutMillis<=0)throw new PollDeadlineElapsed();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int seconds=(int)Math.max(1,Math.min(Integer.MAX_VALUE,(timeoutMillis+999L)/1000L));
        return repo.readSnapshot(seconds,()->version(scope,filter,(sql,args)->boundedMetadata(sql,args,deadline)));
    }
    private Map<String,Object> version(Scope scope,Filter filter,MetadataQuery metadata){
        var months=months(filter);
        var partitions=metadata.read("SELECT partition_month,schema_version,built_revision,requested_revision,CASE WHEN full_month_json IS NOT NULL AND partial_first_day_json IS NOT NULL AND spend_json IS NOT NULL THEN 1 ELSE 0 END payload_present FROM finance_query_partitions WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ? ORDER BY partition_month",
            scope.organizationId(),filter.platform(),months.getFirst(),months.getLast());
        boolean ready=partitions.size()==months.size()&&partitions.stream().allMatch(row->number(row.get("schema_version"))==SCHEMA_VERSION
            &&number(row.get("built_revision"))>0&&number(row.get("built_revision"))==number(row.get("requested_revision"))&&number(row.get("payload_present"))==1)&&!hasRelevantEvents(scope,filter,metadata);
        var revisions=partitions.stream().map(row->date(row.get("partition_month"))+":"+row.get("built_revision")).toList();
        return Map.of("status",ready?"READY":"PENDING","revision",hash(scope.organizationId()+"|"+filter.platform()+"|"+String.join(",",revisions)));
    }
    private List<Map<String,Object>> boundedMetadata(String sql,Object[] args,long deadline){
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new PollDeadlineElapsed();
        long timeoutMillis=Math.max(1,(remaining+999_999L)/1_000_000L);
        var setter=new org.springframework.jdbc.core.ArgumentPreparedStatementSetter(args);
        try{return repo.jdbc().query(sql,statement->{
            setter.setValues(statement);
            setMetadataTimeout(statement,timeoutMillis);
        },new org.springframework.jdbc.core.ColumnMapRowMapper());}
        finally{setter.cleanupParameters();}
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void setMetadataTimeout(java.sql.PreparedStatement statement,long timeoutMillis)throws java.sql.SQLException{
        statement.setQueryTimeout((int)Math.max(1,Math.min(Integer.MAX_VALUE,(timeoutMillis+999L)/1000L)));
        try{
            Class type=Class.forName("com.mysql.cj.jdbc.JdbcStatement");
            if(type.isInstance(statement)||statement.isWrapperFor(type)){
                Object mysql=type.isInstance(statement)?statement:statement.unwrap(type);
                Object query=type.getMethod("getQuery").invoke(mysql);
                query.getClass().getMethod("setTimeoutInMillis",long.class).invoke(query,timeoutMillis);
            }
        }catch(ClassNotFoundException ignored){
            // Portable JDBC retains the rounded-up seconds timeout when Connector/J is absent.
        }catch(ReflectiveOperationException failure){
            throw new java.sql.SQLException("Cannot apply Connector/J millisecond query timeout",failure);
        }
    }

    /** Enqueue only absent partitions; normal reads never rewrite a clean revision. */
    public void request(Scope scope,Filter filter){
        for(LocalDate month:months(filter))createPartition(scope,filter.platform(),month);
    }
    void requestMissing(Scope scope,Filter filter){
        var requested=months(filter);
        // A metadata read does not wait on the lease publisher's row lock.
        var existing=new HashSet<>(repo.jdbc().queryForList("""
            SELECT partition_month FROM finance_query_partitions
            WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ?
            """,java.sql.Date.class,scope.organizationId(),filter.platform(),requested.getFirst(),requested.getLast()));
        for(LocalDate month:requested)if(!existing.contains(java.sql.Date.valueOf(month)))createPartition(scope,filter.platform(),month);
    }
    private void createPartition(Scope scope,String platform,LocalDate month){
        repo.jdbc().update("INSERT IGNORE INTO finance_query_partitions(organization_id,platform_filter,partition_month) VALUES(?,?,?)",
            scope.organizationId(),platform,month);
    }

    /** Enqueue the two default business months, with every company's previous month first. */
    void warmDefaults(Instant now){
        var organizations=repo.jdbc().queryForList("SELECT DISTINCT organization_id FROM finance_connections WHERE status<>'REMOVED' ORDER BY organization_id",String.class);
        var previous=FinanceCalendar.previousMonth(now);
        for(var month:List.of(previous,previous.plusMonths(1)))for(String organization:organizations)
            for(String platform:List.of("","SHOPEE","TIKTOK_SHOP")){
                var filter=new Filter(month.atDay(1),month.atEndOfMonth(),"",platform,"","day");
                var scope=companyScope(organization);
                request(scope,filter);
                requestSettlementCosts(scope,filter);
            }
    }

    public Map<String,Object> status(Scope scope){
        var result=new LinkedHashMap<>(repo.jdbc().queryForMap("SELECT COUNT(*) partitions,COALESCE(SUM(built_revision>0 AND built_revision=requested_revision),0) ready,COALESCE(SUM(built_revision<>requested_revision),0) pending,COALESCE(SUM(lease_until>UTC_TIMESTAMP(6)),0) running,COALESCE(SUM(failures>0),0) failed FROM finance_query_partitions WHERE organization_id=?",scope.organizationId()));
        long events=number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=?",Long.class,scope.organizationId()));
        result.put("events",events);result.put("builtPartitions",result.get("ready"));
        // Without resolving each pending event, a lightweight status cannot assert that its partitions are readable.
        if(events>0)result.put("ready",0L);
        result.put("coverage","Only committed, complete partitions without relevant pending events are readable");return result;
    }

    /** Claim concrete visible IDs. Open transactions must never be skipped by a guessed watermark. */
    void advanceDashboardLedgerDays(Instant now){repo.advanceDashboardLedgerDays(now);}
    void advanceDashboardOrderFacts(Instant now){repo.advanceDashboardOrderFacts(now);}
    int drainEvents(){return drainEvents(false);}
    int drainRecentEvents(){return drainEvents(true);}
    private int drainEvents(boolean recent){
        long legacyThrough=priority.cutover();
        if(recent&&(legacyThrough<0||repo.jdbc().queryForList(
                "SELECT organization_id FROM finance_query_priority_orgs LIMIT 1",String.class).isEmpty()))return 0;
        return repo.transaction(()->{
            // Discover the batch without a range lock. A locking scan to the end
            // of this append-only index can deadlock with multi-row ledger writes.
            var candidates=recent?repo.jdbc().queryForList("""
                SELECT e.event_id FROM finance_query_events e FORCE INDEX(PRIMARY)
                WHERE e.event_id>? AND EXISTS(
                  SELECT 1 FROM finance_query_priority_orgs o WHERE o.organization_id=e.organization_id)
                ORDER BY e.event_id LIMIT
                """+EVENT_BATCH,Long.class,legacyThrough):
                repo.jdbc().queryForList("SELECT event_id FROM finance_query_events ORDER BY event_id LIMIT "+EVENT_BATCH,Long.class);
            if(candidates.isEmpty())return 0;
            var events=repo.jdbc().queryForList("SELECT * FROM finance_query_events FORCE INDEX(PRIMARY) WHERE event_id IN ("
                +placeholders(candidates.size())+") ORDER BY event_id FOR UPDATE SKIP LOCKED",candidates.toArray());
            if(events.isEmpty())return 0;
            if(legacyThrough>0){
                var split=events.stream().collect(java.util.stream.Collectors.partitioningBy(
                    event->number(event.get("event_id"))<=legacyThrough));
                invalidate(resolveChanges(split.get(true),true),legacyThrough);
                invalidate(resolveChanges(split.get(false),true),0);
            }else{
                invalidate(resolveChanges(events,true),0);
            }
            var ids=events.stream().map(row->row.get("event_id")).toList();
            repo.jdbc().update("DELETE FROM finance_query_events WHERE event_id IN ("+placeholders(ids.size())+")",ids.toArray());
            return events.size();
        });
    }

    private void invalidate(Map<String,Change> changed,long legacyThrough){
        // Once a full snapshot covers the legacy prefix, draining that prefix
        // only changes the remaining months. New events can touch any month.
        String uncovered=legacyThrough<=0?"":("""
             AND NOT EXISTS(
                 SELECT 1 FROM finance_query_priority_baselines b
                 WHERE b.organization_id=p.organization_id AND b.platform_filter=p.platform_filter
                 AND b.partition_month=p.partition_month AND b.legacy_through>=%d)
            """.formatted(legacyThrough));
        for(var item:changed.entrySet()){
            String org=item.getKey();var change=item.getValue();
            boolean scopedCosts=usesScopedSettlementRevisions(org);
            if(change.all){
                String costReset=scopedCosts&&change.allCosts?"settlement_cost_full_revision=requested_revision+1,":"";
                repo.jdbc().update("UPDATE finance_query_partitions p SET "+costReset
                    +"full_rebuild_revision=requested_revision+1,requested_revision=requested_revision+1,full_rebuild=TRUE,"
                    +"requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6) WHERE p.organization_id=?"+uncovered,org);
                if(!scopedCosts||change.allCosts)continue;
            }else if(change.spendAll){
                repo.jdbc().update("UPDATE finance_query_partitions p SET spend_full_revision=requested_revision+1,"
                    +"requested_revision=requested_revision+1,requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6) "
                    +"WHERE p.organization_id=?"+uncovered,org);
            }
            var days=new TreeSet<LocalDate>();change.shopDays.keySet().forEach(itemDay->days.add(itemDay.day().withDayOfMonth(1)));
            if(!change.all&&!change.spendAll)for(LocalDate month:days)repo.jdbc().update("UPDATE finance_query_partitions p SET requested_revision=requested_revision+1,requested_at=UTC_TIMESTAMP(6),next_attempt_at=UTC_TIMESTAMP(6) WHERE p.organization_id=? AND p.partition_month=?"+uncovered,org,month);
            var daily=change.shopDays.keySet().stream().map(ShopDay::day).distinct().sorted().toList();
            var args=daily.stream().map(day->new Object[]{day,org,day.withDayOfMonth(1)}).toList();
            if(!args.isEmpty())repo.jdbc().batchUpdate("""
                INSERT INTO finance_query_dirty_days(organization_id,platform_filter,partition_month,record_date,requested_revision)
                SELECT organization_id,platform_filter,partition_month,?,requested_revision
                FROM finance_query_partitions p WHERE organization_id=? AND partition_month=?
                """+uncovered+"""
                 ON DUPLICATE KEY UPDATE requested_revision=GREATEST(finance_query_dirty_days.requested_revision,VALUES(requested_revision))
                """,args);
            var shopArgs=change.shopDays.entrySet().stream().sorted(Comparator
                .comparing((Map.Entry<ShopDay,Boolean> value)->value.getKey().day())
                .thenComparing(value->value.getKey().shop())).map(value->new Object[]{
                    value.getKey().day(),value.getKey().shop(),value.getValue(),org,value.getKey().day().withDayOfMonth(1)
                }).toList();
            if(!shopArgs.isEmpty())repo.jdbc().batchUpdate("""
                INSERT INTO finance_query_dirty_shop_days(organization_id,platform_filter,partition_month,record_date,shop_id,requested_revision,cost_revision)
                SELECT organization_id,platform_filter,partition_month,?,?,requested_revision,IF(?,requested_revision,0)
                FROM finance_query_partitions p WHERE organization_id=? AND partition_month=?
                """+uncovered+"""
                 ON DUPLICATE KEY UPDATE
                  requested_revision=GREATEST(finance_query_dirty_shop_days.requested_revision,VALUES(requested_revision)),
                  cost_revision=GREATEST(finance_query_dirty_shop_days.cost_revision,VALUES(cost_revision))
                """,shopArgs);
        }
    }

    private Map<String,Change> resolveChanges(List<Map<String,Object>> events,boolean includeBills){
        var changed=new LinkedHashMap<String,Change>();
        var orders=new LinkedHashMap<String,Set<OrderKey>>();
        var records=new LinkedHashMap<String,Set<String>>();
        var statements=new LinkedHashMap<String,Map<String,Set<String>>>();
        for(var event:events){
            String org=text(event.get("organization_id"));
            // The locked batch already bounds event identities. A subsequent batch may
            // cover different months, so resolve and acknowledge only this batch.
            var change=changed.computeIfAbsent(org,key->new Change());
            if(change.allCosts)continue;
            if("ALL".equals(event.get("event_scope"))){
                String source=text(event.get("source_table"));
                if(usesScopedAdRefresh(org)&&SPEND_ONLY_GLOBAL.contains(source)){
                    change.spendAll=true;
                }else{
                    boolean affectsCosts=!usesScopedSettlementRevisions(org)||!COST_INDEPENDENT.contains(source);
                    if(includeBills||affectsCosts)change.requireFull(affectsCosts);
                }
                continue;
            }
            change.add(event,!"DAY".equals(event.get("event_scope")));
            addStatement(statements,org,text(event.get("shop_id")),text(event.get("statement_id")));
            if(!"ORDER".equals(event.get("event_scope")))continue;
            String shop=text(event.get("shop_id")),order=text(event.get("order_id"));
            if(order.isBlank()&&!text(event.get("record_id")).isBlank()){
                records.computeIfAbsent(org,key->new LinkedHashSet<>()).add(text(event.get("record_id")));
                continue;
            }
            if(order.isBlank())continue;
            orders.computeIfAbsent(org,key->new LinkedHashSet<>()).add(new OrderKey(shop,order));
        }
        for(var item:records.entrySet()){
            String org=item.getKey();if(changed.get(org).allCosts)continue;
            var args=new ArrayList<Object>();args.add(org);args.addAll(item.getValue());
            var found=repo.jdbc().queryForList("SELECT id,shop_id,order_id FROM finance_records WHERE organization_id=? AND id IN ("+placeholders(item.getValue().size())+")",args.toArray());
            if(found.size()!=item.getValue().size()){changed.get(org).requireFull(true);continue;}
            for(var row:found)if(!text(row.get("order_id")).isBlank())orders.computeIfAbsent(org,key->new LinkedHashSet<>()).add(new OrderKey(text(row.get("shop_id")),text(row.get("order_id"))));
        }
        for(var item:orders.entrySet()){
            String org=item.getKey();var change=changed.get(org);if(change.allCosts)continue;
            var keys=item.getValue().stream().map(key->List.of(key.shop,key.order)).toList();
            // Probe the complete indexed key for each order. A large OR of shop/order lists
            // can use only the shop prefix and rescan the same history in every event batch.
            var rows=repo.jdbc().queryForList("""
                SELECT DISTINCT r.shop_id,r.record_date,
                  CASE WHEN r.kind='SETTLEMENT' AND r.source='API' THEN r.statement_id ELSE '' END statement_id
                FROM JSON_TABLE(CAST(? AS JSON),'$[*]' COLUMNS(
                  shop_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[0]',
                  order_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$[1]'
                )) requested STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)
                  ON r.organization_id=? AND r.shop_id=requested.shop_key AND r.order_id=requested.order_key
                LIMIT 10001
                """,repo.write(keys),org);
            if(rows.size()>10000){change.requireFull(true);continue;}
            for(var row:rows){change.add(row,true);addStatement(statements,org,text(row.get("shop_id")),text(row.get("statement_id")));}
        }
        if(includeBills)for(var item:statements.entrySet())if(!changed.get(item.getKey()).all)addBillDates(changed.get(item.getKey()),item.getKey(),item.getValue());
        return changed;
    }

    /** Upgrade existing approved monthly baselines without rebuilding their ledger body. */
    void requestSettlementCosts(Scope scope,Filter filter){
        var period=months(filter);
        String missing="""
            WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ?
              AND settlement_cost_revision=0 AND settlement_cost_requested=FALSE AND built_revision=requested_revision
              AND built_revision>0
            """;
        Object[] args={scope.organizationId(),filter.platform(),period.getFirst(),period.getLast()};
        if(!repo.jdbc().queryForList("SELECT 1 FROM finance_query_partitions "+missing+" LIMIT 1",args).isEmpty())
            repo.jdbc().update("UPDATE finance_query_partitions SET settlement_cost_requested=TRUE,next_attempt_at=UTC_TIMESTAMP(6) "+missing,args);
    }

    /** The page already owns the read-only snapshot shared with its records and currency. */
    Optional<Map<String,Object>> settlementCosts(Scope scope,Filter filter){
        boolean scopedCosts=usesScopedSettlementRevisions(scope.organizationId());
        if(scopedCosts&&filter.platform().isBlank()&&filter.currency().isBlank()){
            // The all-platform, all-original-currency scope counts each order once on
            // its first settlement day, even when the provider sent several transactions.
            var costFacts=repo.readDashboardOrderFacts(scope,filter);
            if(costFacts.isPresent())
                return Optional.of(new FinanceCostReport(repo).settlementSummaryFromDays(scope,costFacts.get()));
        }
        var period=months(filter);
        var partitions=repo.jdbc().queryForList("""
            SELECT partition_month,settlement_cost_revision,built_revision,requested_revision,full_rebuild
            """+(scopedCosts?",settlement_cost_full_revision ":"")+"""
            FROM finance_query_partitions WHERE organization_id=? AND platform_filter=? AND partition_month BETWEEN ? AND ?
            """,scope.organizationId(),filter.platform(),period.getFirst(),period.getLast());
        if(partitions.size()!=period.size()||partitions.stream().anyMatch(row->number(row.get("settlement_cost_revision"))==0
                ||requiresFullSettlement(row,scopedCosts)))return Optional.empty();
        return pendingWindows(scope,filter,partitions,true).map(repairs->settlementDays.summarize(scope,filter,repairs.orders()));
    }

    private static boolean requiresFullSettlement(Map<String,Object> row,boolean scopedCosts){
        return scopedCosts
            ?number(row.get("settlement_cost_full_revision"))>number(row.get("settlement_cost_revision"))
            :flag(row.get("full_rebuild"));
    }

    private Optional<RepairWindows> pendingWindows(Scope scope,Filter filter,List<Map<String,Object>> partitions,boolean costsOnly){
        long covered=priority.coveredThrough(repo.jdbc()::queryForList,scope.organizationId(),filter.platform(),months(filter));
        var pending=pendingRepairEvents(scope,covered,costsOnly,MAX_REPAIR_EVENTS);
        if(pending.size()>MAX_REPAIR_EVENTS){
            if(costsOnly||pending.stream().allMatch(event->affectsRepairWindow(event,filter)))return Optional.empty();
            // Use at most one existing event batch to establish that the selected range fits the repair budget.
            var examined=pendingRepairEvents(scope,covered,false,EVENT_BATCH);
            if(examined.size()>EVENT_BATCH)return Optional.empty();
            pending=examined.stream().filter(event->affectsRepairWindow(event,filter)).toList();
            if(pending.size()>MAX_REPAIR_EVENTS)return Optional.empty();
        }
        var active=resolveChanges(pending,!costsOnly).get(scope.organizationId());
        if(active!=null&&active.all)return Optional.empty();

        var ledger=new TreeMap<LocalDate,Set<String>>();
        var orders=new TreeMap<LocalDate,Set<String>>();
        for(var partition:partitions){
            var key=new Key(scope.organizationId(),filter.platform(),date(partition.get("partition_month")));
            var dirty=dirty(key,number(partition.get(costsOnly?"settlement_cost_revision":"built_revision")));
            if((!costsOnly||!usesScopedSettlementRevisions(scope.organizationId()))
                    &&number(partition.get("built_revision"))!=number(partition.get("requested_revision"))
                    &&dirty.ledger().isEmpty()&&!needsSpendRefresh(partition))
                return Optional.empty();
            if(!costsOnly)dirty.ledger().forEach((day,shops)->shops.forEach(shop->addRepair(ledger,filter,day,shop)));
            dirty.costs().forEach((day,shops)->shops.forEach(shop->addRepair(orders,filter,day,shop)));
        }
        if(active!=null)active.shopDays.forEach((day,affected)->{
            if(!costsOnly)addRepair(ledger,filter,day.day(),day.shop());
            if(affected)addRepair(orders,filter,day.day(),day.shop());
        });
        var repair=new RepairWindows(windows(ledger,false),windows(orders));
        return withinRepairBudget(costsOnly?repair.orders():repair.ledger())?Optional.of(repair):Optional.empty();
    }
    private List<Map<String,Object>> pendingRepairEvents(Scope scope,long covered,boolean costsOnly,int budget){
        Set<String> independentSources=costsOnly&&usesScopedSettlementRevisions(scope.organizationId())?COST_INDEPENDENT:
            usesScopedAdRefresh(scope.organizationId())?SPEND_ONLY_GLOBAL:Set.of();
        String independent=independentSources.isEmpty()?""
            :" AND NOT (event_scope='ALL' AND source_table IN ('"+String.join("','",independentSources)+"'))";
        return repo.jdbc().queryForList("""
            SELECT event_scope,record_date,shop_id,order_id,record_id,statement_id,organization_id,source_table
            FROM finance_query_events FORCE INDEX(idx_finance_query_events_scope)
            WHERE organization_id=? AND event_id>?
            """+(costsOnly?" AND event_scope<>'DAY'":"")+independent+" ORDER BY event_id LIMIT ?",
            scope.organizationId(),covered,budget+1);
    }

    private static boolean affectsRepairWindow(Map<String,Object> event,Filter filter){
        // An order or statement can affect a different period than the source row.
        if(!"DAY".equals(event.get("event_scope"))||!text(event.get("statement_id")).isBlank())return true;
        Object recordDate=event.get("record_date");
        if(recordDate==null)return false;
        String shop=text(event.get("shop_id"));
        return inRange(event,filter)&&(filter.shopId().isBlank()||shop.isBlank()||filter.shopId().equals(shop));
    }

    private static boolean withinRepairBudget(List<Window> windows){
        long shopDays=0;
        for(var window:windows){
            long days=java.time.temporal.ChronoUnit.DAYS.between(window.start(),window.end())+1;
            shopDays+=days*(window.shops().isEmpty()?ALL_SHOPS_DAY_WEIGHT:window.shops().size());
        }
        return shopDays<=MAX_REPAIR_SHOP_DAYS;
    }
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||number(value)!=0;}

    private static void addRepair(Map<LocalDate,Set<String>> costs,Filter filter,LocalDate day,String shop){
        if(day.isBefore(filter.start())||day.isAfter(filter.end()))return;
        if(!filter.shopId().isBlank()){
            if(!shop.isBlank()&&!shop.equals(filter.shopId()))return;
            shop=filter.shopId();
        }
        costs.computeIfAbsent(day,ignored->new TreeSet<>()).add(shop);
    }

    /** One leased month per call; no source/provider requests are made by this worker. */
    void advanceFeeDays(){repo.advanceFeeDays();}

    boolean buildNext(){return buildNext(Instant.now(),false,false);}
    boolean buildRecent(Instant now){return buildNext(now,true,false);}
    boolean buildHistorical(Instant now){return buildNext(now,false,false);}
    boolean publishedPreviousMonthEnabled(){return usesScopedAdRefresh(scopedSettlementCompany);}
    boolean refreshPublishedPreviousMonth(Instant now){
        return publishedPreviousMonthEnabled()&&buildNext(now,true,true);
    }

    private boolean buildNext(Instant now,boolean recent,boolean publishedPreviousMonth){
        long legacyThrough=priority.cutover();
        if(recent&&legacyThrough<0)return false;
        var claimed=repo.transaction(()->{
            // Outside the rollout, keep draining before competing with source
            // scans. Enrolled companies use separate recent and historical build slots.
            String pending="(p.built_revision<>p.requested_revision OR p.settlement_cost_requested)";
            String available=" AND p.next_attempt_at<=UTC_TIMESTAMP(6) AND (p.lease_until IS NULL OR p.lease_until<UTC_TIMESTAMP(6))";
            String belowBacklog="NOT EXISTS(SELECT 1 FROM finance_query_events e WHERE e.organization_id=p.organization_id ORDER BY e.event_id LIMIT 1 OFFSET "+EVENT_BATCH+")";
            String pick=" ORDER BY (p.built_revision>0),p.generated_at,p.requested_at,p.organization_id,p.partition_month,p.platform_filter LIMIT 1 FOR UPDATE SKIP LOCKED";
            String columns="SELECT p.organization_id,p.platform_filter,p.partition_month,p.requested_revision,p.built_revision";
            String enabled="EXISTS(SELECT 1 FROM finance_query_priority_orgs o WHERE o.organization_id=p.organization_id)";
            var lastMonth=FinanceCalendar.previousMonth(now).atDay(1);
            List<Map<String,Object>> ready;
            if(publishedPreviousMonth){
                // Keep one bounded lane for the shared, published prior-month report. Its
                // existing lease also prevents the general worker from claiming it twice.
                String readyCosts=usesScopedSettlementRevisions(scopedSettlementCompany)
                    ?" AND p.settlement_cost_full_revision<=p.settlement_cost_revision":"";
                ready=repo.jdbc().queryForList(columns+"""
                    ,FALSE needs_baseline FROM finance_query_partitions p
                    JOIN finance_query_priority_baselines b ON b.organization_id=p.organization_id
                      AND b.partition_month=p.partition_month AND b.platform_filter=p.platform_filter
                    WHERE p.organization_id=? AND p.partition_month=? AND p.platform_filter=''
                      AND b.legacy_through>=? AND p.built_revision>0 AND NOT p.full_rebuild
                      AND p.settlement_cost_revision>0 AND p.built_revision<>p.requested_revision
                    """+readyCosts+" AND "+enabled+available+pick,scopedSettlementCompany,lastMonth,legacyThrough);
            }else if(recent){
                ready=repo.jdbc().queryForList(columns+"""
                    , b.legacy_through IS NULL needs_baseline FROM finance_query_partitions p
                    LEFT JOIN finance_query_priority_baselines b ON b.organization_id=p.organization_id
                    AND b.partition_month=p.partition_month AND b.platform_filter=p.platform_filter
                    WHERE p.partition_month BETWEEN ? AND ? AND
                    """+enabled+" AND ("+pending+" OR b.legacy_through IS NULL)"+available+pick,lastMonth,lastMonth.plusMonths(1));
            }else if(legacyThrough<0){
                ready=repo.jdbc().queryForList(columns+" FROM finance_query_partitions p WHERE "+pending+available+" AND "+belowBacklog+pick);
            }else{
                ready=repo.jdbc().queryForList(columns+" FROM finance_query_partitions p WHERE "+pending+available
                    +" AND ((NOT "+enabled+" AND "+belowBacklog+") OR ("+enabled+" AND p.partition_month NOT BETWEEN ? AND ?))"+pick,
                    lastMonth,lastMonth.plusMonths(1));
            }
            if(ready.isEmpty())return Map.<String,Object>of();
            var row=new LinkedHashMap<>(ready.getFirst());String token=UUID.randomUUID().toString();
            // A full baseline must also have a fresh cache revision, even if
            // the old published revision had no acknowledged dirty days yet.
            long revision=number(row.get("requested_revision"))+(flag(row.get("needs_baseline"))?1:0);
            repo.jdbc().update("""
                UPDATE finance_query_partitions SET requested_revision=?,lease_token=?,
                  lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
                WHERE organization_id=? AND platform_filter=? AND partition_month=?
                """,revision,token,row.get("organization_id"),row.get("platform_filter"),row.get("partition_month"));
            row.put("requested_revision",revision);
            row.put("lease_token",token);return row;
        });
        if(claimed.isEmpty())return false;
        var key=new Key(text(claimed.get("organization_id")),text(claimed.get("platform_filter")),date(claimed.get("partition_month")));
        try{
            boolean newBaseline=flag(claimed.get("needs_baseline"));
            boolean costsOnly=!newBaseline&&number(claimed.get("built_revision"))==number(claimed.get("requested_revision"));
            var snapshot=repo.readSnapshot(180,()->{
                Instant started=Instant.now();
                var previous=repo.jdbc().queryForMap("""
                    SELECT full_rebuild,built_revision,settlement_cost_revision,
                      CASE WHEN full_month_json IS NOT NULL AND partial_first_day_json IS NOT NULL AND spend_json IS NOT NULL
                           THEN 1 ELSE 0 END payload_present
                    """+(usesScopedSettlementRevisions(key.organization)?",settlement_cost_full_revision ":"")
                      +(usesScopedAdRefresh(key.organization)?",spend_full_revision ":"")+"""
                     FROM finance_query_partitions
                    WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=?
                    """,key.organization,key.platform,key.month,claimed.get("lease_token"));
                if(newBaseline){
                    previous.put("full_rebuild",true);
                    previous.put("settlement_cost_full_revision",Long.MAX_VALUE);
                }
                return build(key,previous,costsOnly,started);
            });
            var built=snapshot.bodies();long revision=number(claimed.get("requested_revision"));
            String full=built==null||built.ledger()==null?null:json.writeValueAsString(normalize(built.ledger()));
            String first=built==null||built.firstDay()==null?null:json.writeValueAsString(normalize(built.firstDay()));
            String ads=built==null?null:json.writeValueAsString(normalize(built.spend()));
            if(ads!=null&&ads.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_SPEND_PAYLOAD_BYTES)
                ads=json.writeValueAsString(SPEND_READ_FROM_SOURCE);
            String spendPayload=ads;
            repo.transaction(()->{
                // The lease/revision fences publication of both the month body and
                // the changed cost days. Newer dirty rows remain queued.
                int published=repo.jdbc().update(built==null?"""
                    UPDATE finance_query_partitions SET settlement_cost_revision=?,settlement_cost_requested=FALSE,
                      lease_token=NULL,lease_until=NULL,failures=0,error_code=NULL
                    WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=?
                      AND lease_until>UTC_TIMESTAMP(6) AND requested_revision>=? AND built_revision<=?
                    """:"""
                    UPDATE finance_query_partitions SET full_month_json=COALESCE(?,full_month_json),built_revision=?,schema_version=?,generated_at=?,
                      full_rebuild=(full_rebuild_revision>?),settlement_cost_revision=?,settlement_cost_requested=FALSE,
                      lease_token=NULL,lease_until=NULL,failures=0,error_code=NULL
                    WHERE organization_id=? AND platform_filter=? AND partition_month=? AND lease_token=?
                      AND lease_until>UTC_TIMESTAMP(6) AND requested_revision>=? AND built_revision<=?
                    """,publishArgs(key,claimed,built,snapshot.snapshotStarted(),full,revision));
                if(published==1){
                    settlementDays.publish(companyScope(key.organization),monthFilter(key),snapshot.costs());
                    if(newBaseline)priority.publishBaseline(key.organization,key.platform,key.month,legacyThrough);
                    if(built!=null){
                        if(first!=null)
                            repo.jdbc().update("UPDATE finance_query_partitions SET partial_first_day_json=? WHERE organization_id=? AND platform_filter=? AND partition_month=?",first,key.organization,key.platform,key.month);
                        repo.jdbc().update("UPDATE finance_query_partitions SET spend_json=? WHERE organization_id=? AND platform_filter=? AND partition_month=?",spendPayload,key.organization,key.platform,key.month);
                        repo.jdbc().update("DELETE FROM finance_query_dirty_days WHERE organization_id=? AND platform_filter=? AND partition_month=? AND requested_revision<=?",key.organization,key.platform,key.month,revision);
                        repo.jdbc().update("DELETE FROM finance_query_dirty_shop_days WHERE organization_id=? AND platform_filter=? AND partition_month=? AND requested_revision<=?",key.organization,key.platform,key.month,revision);
                    }
                }
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

    private static Object[] publishArgs(Key key,Map<String,Object> claimed,SnapshotBodies bodies,Instant started,String full,long revision){
        var args=new ArrayList<Object>();
        if(bodies!=null){
            args.add(full);
            args.addAll(List.of(revision,SCHEMA_VERSION,java.sql.Timestamp.from(started),revision));
        }
        args.addAll(List.of(revision,key.organization,key.platform,key.month,claimed.get("lease_token"),revision,revision));
        return args.toArray();
    }

    private record Dirty(TreeMap<LocalDate,Set<String>> ledger,TreeMap<LocalDate,Set<String>> costs) {}

    private Dirty dirty(Key key,long costRevision){
        var ledger=new TreeMap<LocalDate,Set<String>>();var costs=new TreeMap<LocalDate,Set<String>>();
        var days=repo.jdbc().queryForList("""
            SELECT d.record_date,d.requested_revision,r.shop_id,r.requested_revision shop_revision,r.cost_revision
            FROM finance_query_dirty_days d LEFT JOIN finance_query_dirty_shop_days r
              ON r.organization_id=d.organization_id AND r.platform_filter=d.platform_filter
              AND r.partition_month=d.partition_month AND r.record_date=d.record_date
            WHERE d.organization_id=? AND d.platform_filter=? AND d.partition_month=?
            """,key.organization,key.platform,key.month);
        var latest=new HashMap<LocalDate,Long>();var required=new HashMap<LocalDate,Long>();
        for(var row:days){
            LocalDate day=date(row.get("record_date"));String shop=text(row.get("shop_id"));
            required.put(day,number(row.get("requested_revision")));
            latest.merge(day,number(row.get("shop_revision")),Math::max);
            ledger.computeIfAbsent(day,ignored->new TreeSet<>()).add(shop);
            if(row.get("shop_id")==null||number(row.get("cost_revision"))>costRevision)
                costs.computeIfAbsent(day,ignored->new TreeSet<>()).add(shop);
        }
        for(var day:required.keySet())if(latest.get(day)<required.get(day)){
            ledger.put(day,Set.of(""));costs.put(day,Set.of(""));
        }
        return new Dirty(ledger,costs);
    }

    private static List<Window> windows(SortedMap<LocalDate,Set<String>> days){
        return windows(days,true);
    }
    private static List<Window> windows(SortedMap<LocalDate,Set<String>> days,boolean denseScan){
        var entries=new ArrayList<>(days.entrySet());var windows=new ArrayList<Window>();
        for(int index=0;index<entries.size();){
            var entry=entries.get(index++);
            LocalDate from=entry.getKey(),to=from;
            var shops=physicalShops(entry.getValue(),denseScan);
            while(index<entries.size()&&entries.get(index).getKey().equals(to.plusDays(1))
                    &&shops.equals(physicalShops(entries.get(index).getValue(),denseScan)))to=entries.get(index++).getKey();
            windows.add(new Window(from,to,shops));
        }
        return windows;
    }

    private static List<String> physicalShops(Set<String> shops,boolean denseScan){
        // Beyond the indexed shop-day budget, keep the covering date scan.
        return shops.contains("")||denseScan&&shops.size()>SHOP_SCAN_LIMIT?List.of():List.copyOf(shops);
    }

    private static List<Window> buildWindows(SortedMap<LocalDate,Set<String>> days){
        var exact=windows(days,false);
        return withinRepairBudget(exact)?exact:windows(days);
    }

    private Built build(Key key,Map<String,Object> previous,boolean costsOnly,Instant started){
        Scope scope=companyScope(key.organization);var month=monthFilter(key);
        var firstDay=new Filter(key.month,key.month,"",key.platform,"","day");
        // The compact partial-month strategy is valid only when corrections are anchored on month day one.
        var invalid=repo.jdbc().queryForList("SELECT r.id FROM finance_reporting_months m JOIN finance_records r FORCE INDEX(idx_finance_marketing_order) ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id AND r.statement_id=m.statement_id WHERE m.organization_id=? AND m.reporting_month=? AND r.kind IN ('SETTLEMENT','FEE') AND r.source='API' AND r.platform='TIKTOK_SHOP' AND JSON_EXTRACT(r.raw_json,'$._finance_reporting_month') IS NOT NULL AND r.record_date<>m.reporting_month LIMIT 1",key.organization,key.month);
        if(!invalid.isEmpty())throw new IllegalStateException("Reporting month anchor requires source repair");
        boolean rebuild=flag(previous.get("full_rebuild"))||number(previous.get("payload_present"))!=1;
        boolean spendRefresh=needsSpendRefresh(previous);
        long costRevision=number(previous.get("settlement_cost_revision"));
        var fullWindow=new Window(month.start(),month.end(),List.of());
        boolean scopedCosts=usesScopedSettlementRevisions(key.organization);
        boolean rebuildCosts=costsOnly||costRevision==0||requiresFullSettlement(previous,scopedCosts)||(!scopedCosts&&rebuild);
        var dirty=rebuild&&rebuildCosts||costsOnly?new Dirty(new TreeMap<>(),new TreeMap<>()):dirty(key,costRevision);
        var orderWindows=buildWindows(dirty.costs());
        var costWindows=rebuildCosts?List.of(fullWindow):orderWindows;
        var costs=settlementDays.calculate(scope,month,costWindows);
        if(costsOnly)return new Built(null,costs,started);

        var ledgerWindows=rebuild?List.of(fullWindow):buildWindows(dirty.ledger());
        if(ledgerWindows.isEmpty()){
            if(!spendRefresh)throw new IllegalStateException("Dirty partition has no affected days");
            // Carry the already published ledger forward without transferring its JSON.
            return new Built(new SnapshotBodies(null,null,fullSpend(scope,month)),costs,started);
        }
        if(ledgerWindows.equals(List.of(fullWindow))){
            var ads=fullSpend(scope,month);
            return new Built(new SnapshotBodies(nativeFacts(scope,month),nativeFacts(scope,firstDay),ads),costs,started);
        }

        var payload=readPayload(scope,key.platform,key.month,number(previous.get("built_revision")),true,false,!spendRefresh);
        var ledger=repairLedger(scope,month,read(payload.get("full_month_json")),
            new RepairWindows(ledgerWindows,orderWindows),LedgerFields.ALL,"");
        var first=ledgerWindows.getFirst().start().equals(month.start())
            ?patchFirstDay(scope,key,firstDay,ledgerWindows.getFirst().shops()):null;
        var ads=spendRefresh?fullSpend(scope,month):patchSpend(scope,month,read(payload.get("spend_json")),dirty.ledger().keySet());
        return new Built(new SnapshotBodies(ledger,first,ads),costs,started);
    }

    private static boolean needsSpendRefresh(Map<String,Object> revision){
        return number(revision.get("spend_full_revision"))>number(revision.get("built_revision"));
    }

    private Map<String,Object> fullSpend(Scope scope,Filter filter){
        try{return spend.buildProjectedSpendFacts(scope,filter);}
        catch(FinanceAdSpendService.RangeTooLarge limit){return SPEND_READ_FROM_SOURCE;}
    }

    private Map<String,Object> patchFirstDay(Scope scope,Key key,Filter firstDay,List<String> shops){
        if(shops.isEmpty())return nativeFacts(scope,firstDay);
        String saved=repo.jdbc().queryForObject("""
            SELECT partial_first_day_json FROM finance_query_partitions
            WHERE organization_id=? AND platform_filter=? AND partition_month=?
            """,String.class,key.organization,key.platform,key.month);
        return patchLedger(scope,firstDay,read(saved),List.of(new Window(firstDay.start(),firstDay.end(),shops)),LedgerFields.ALL,"");
    }

    private Map<String,Object> patchLedger(Scope scope,Filter semantic,Map<String,Object> old,List<Window> windows,LedgerFields fields,String countKind){
        var ledger=new LinkedHashMap<>(old);
        var shopsByDay=shopsByDay(windows);
        var updatedRows=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String name:fields.names)updatedRows.put(name,new ArrayList<>(rows(old.get(name)).stream()
            .filter(row->!covered(shopsByDay,row)).toList()));
        var latest=repo.inProjectionWindows(semantic,windows,()->nativeFacts(scope,semantic,fields,countKind));
        for(String name:fields.names)updatedRows.get(name).addAll(rows(latest.get(name)));
        ledger.put("reportingMonths",latest.get("reportingMonths"));
        ledger.putAll(updatedRows);
        return ledger;
    }

    private static Map<LocalDate,Set<String>> shopsByDay(List<Window> windows){
        var byDay=new HashMap<LocalDate,Set<String>>();
        for(var window:windows){
            var shops=Set.copyOf(window.shops());
            for(var day=window.start();!day.isAfter(window.end());day=day.plusDays(1))byDay.put(day,shops);
        }
        return byDay;
    }

    private static boolean covered(Map<LocalDate,Set<String>> shopsByDay,Map<String,Object> row){
        var shops=shopsByDay.get(date(row.get("record_date")));
        return shops!=null&&(shops.isEmpty()||shops.contains(text(row.get("shop_id"))));
    }

    /** Amount-only events affect the ledger and bills without moving first settlements. */
    private Map<String,Object> repairLedger(Scope scope,Filter filter,Map<String,Object> saved,
                                           RepairWindows repair,LedgerFields fields,String countKind){
        if(repair.ledger().equals(repair.orders()))
            return patchLedger(scope,filter,saved,repair.ledger(),fields,countKind);

        var counts=new ArrayList<Map<String,Object>>();
        var ordinary=repo.inProjectionWindows(filter,repair.ledger(),()->{
            if(fields==LedgerFields.RECONCILIATION)
                return Map.<String,Object>of("rawCounts",recordCounts(scope,filter,countKind));
            boolean reuseCounts=FinanceRepository.RAW_PAGE_COUNT_KINDS.contains(countKind);
            var advertising=new ArrayList<Map<String,Object>>();
            var buckets=repo.dashboardBuckets(scope,filter,false,advertising,reuseCounts?counts:null);
            if(reuseCounts)counts.removeIf(row->!countKind.equals(row.get("kind")));
            if(fields==LedgerFields.ALL&&(countKind.isBlank()||countKind.equals("BILL")))
                counts.addAll(recordCounts(scope,filter,countKind));
            return Map.<String,Object>of("buckets",FinanceService.dailyFactRows(buckets),
                "advertising",FinanceService.dailyFactRows(advertising),"rawCounts",FinanceService.dailyFactRows(counts));
        });
        Map<String,Object> related=repair.orders().isEmpty()?Map.of():repo.inProjectionWindows(filter,repair.orders(),()->{
            var orders=new FinanceCostReport(repo).dashboardFacts(scope,filter);
            if(fields==LedgerFields.RECONCILIATION)return Map.<String,Object>of("orders",FinanceService.dailyFactRows(orders));
            var buckets=new ArrayList<>(orders.stream().filter(row->number(row.get("records"))>0).toList());
            buckets.addAll(repo.settledOrderBuckets(scope,filter,"REFUND"));
            var orderCounts=new ArrayList<Map<String,Object>>();
            if(fields==LedgerFields.ALL)for(String kind:List.of("ORDER","REFUND"))
                if(countKind.isBlank()||countKind.equals(kind))orderCounts.addAll(settledCounts(scope,filter,kind));
            return Map.<String,Object>of("orders",FinanceService.dailyFactRows(orders),
                "buckets",FinanceService.dailyFactRows(buckets),"rawCounts",FinanceService.dailyFactRows(orderCounts));
        });
        var merged=new LinkedHashMap<>(saved);
        var shopsByLedgerDay=shopsByDay(repair.ledger());
        var shopsByOrderDay=shopsByDay(repair.orders());
        for(String field:fields.names){
            var untouched=new ArrayList<>(rows(saved.get(field)).stream().filter(row->!covered(
                requiresOrderRepair(field,row)?shopsByOrderDay:shopsByLedgerDay,row)).toList());
            untouched.addAll(rows(ordinary.get(field)));
            untouched.addAll(rows(related.get(field)));
            merged.put(field,untouched);
        }
        merged.put("reportingMonths",new FinanceReportingMonths(repo).coverage(scope,filter));
        return merged;
    }

    private static boolean requiresOrderRepair(String field,Map<String,Object> row){
        return switch(field){
            case "orders"->true;
            case "buckets"->Set.of("ORDER","REFUND").contains(text(row.get("kind")));
            case "rawCounts"->"SETTLED".equals(row.get("basis"));
            default->false;
        };
    }

    private Map<String,Object> patchSpend(Scope scope,Filter month,Map<String,Object> ads,Set<LocalDate> days){
        if(flag(ads.get("readFromSource")))return SPEND_READ_FROM_SOURCE;
        var affectedKeys=new HashSet<String>();
        var unchangedSources=new ArrayList<Map<String,Object>>();
        for(var row:rows(ads.get("sources"))){
            if(days.contains(date(row.get("record_date"))))affectedKeys.add(text(row.get("source_key")));
            else unchangedSources.add(row);
        }
        var assignments=new ArrayList<>(rows(ads.get("assignments")).stream()
            .filter(row->!affectedKeys.contains(text(row.get("source_key")))).toList());
        List<Map<String,Object>> shops=List.of();
        var scanDays=new TreeMap<LocalDate,Set<String>>();days.forEach(day->scanDays.put(day,Set.of("")));
        for(var scan:windows(scanDays)){
            var slice=new Filter(scan.start(),scan.end(),"",month.platform(),"","day");
            var refreshedSpend=fullSpend(scope,slice);
            if(flag(refreshedSpend.get("readFromSource")))return SPEND_READ_FROM_SOURCE;
            unchangedSources.addAll(rows(refreshedSpend.get("sources")));
            if(unchangedSources.size()>FinanceAdSpendService.MAX_PROJECTED_RECORDS)return SPEND_READ_FROM_SOURCE;
            assignments.addAll(rows(refreshedSpend.get("assignments")));
            shops=rows(refreshedSpend.get("shops"));
        }
        ads.put("sources",unchangedSources);ads.put("assignments",assignments);ads.put("shops",shops);
        return ads;
    }

    private Map<String,Object> nativeFacts(Scope scope,Filter filter){
        return nativeFacts(scope,filter,LedgerFields.ALL,"");
    }
    private Map<String,Object> nativeFacts(Scope scope,Filter filter,LedgerFields fields,String countKind){
        if(fields==LedgerFields.OVERVIEW)return finance.buildNativeDashboardFacts(scope,filter,false);
        if(FinanceRepository.RAW_PAGE_COUNT_KINDS.contains(countKind)){
            // The operating range scan already has these exact list counts
            // before shop order exclusions. Dirty rows can reuse that scan.
            var counts=new ArrayList<Map<String,Object>>();
            var result=new LinkedHashMap<>(finance.buildNativeDashboardFacts(scope,filter,false,counts));
            counts.removeIf(row->!countKind.equals(row.get("kind")));
            result.put("rawCounts",FinanceService.dailyFactRows(counts));
            return result;
        }
        var result=new LinkedHashMap<>(fields==LedgerFields.RECONCILIATION?Map.of(
            "orders",FinanceService.dailyFactRows(new FinanceCostReport(repo).dashboardFacts(scope,filter)),
            "reportingMonths",new FinanceReportingMonths(repo).coverage(scope,filter)):
            finance.buildNativeDashboardFacts(scope,filter,false));
        var counts=new ArrayList<Map<String,Object>>();
        // These are raw-page facets: source restrictions and advertising fees are independent of operating exclusions.
        if(!Set.of("ORDER","REFUND").contains(countKind))counts.addAll(recordCounts(scope,filter,countKind));
        for(String kind:List.of("ORDER","REFUND")){
            if(!countKind.isBlank()&&!countKind.equals(kind))continue;
            counts.addAll(settledCounts(scope,filter,kind));
        }
        result.put("rawCounts",FinanceService.dailyFactRows(counts));return result;
    }
    private List<Map<String,Object>> recordCounts(Scope scope,Filter filter,String kind){
        var fees=kind.isBlank()||kind.equals("FEE")?feePageCounts(scope,filter):Optional.<List<Map<String,Object>>>empty();
        if(kind.equals("FEE")&&fees.isPresent())return fees.get();
        var nonFee=fees.isPresent()?repo.nonFeeKinds(scope):List.<String>of();
        if(fees.isPresent()&&nonFee.isEmpty())return fees.get();
        boolean reuseFees=fees.isPresent()&&nonFee.size()<=64;
        String index=kind.equals("BILL")||reuseFees?"idx_finance_company_kind_date":repo.dashboardIndex(filter);
        String advertisingFee="CASE WHEN kind='FEE' AND "+FinanceAdvertisingReport.ledgerPredicate("finance_records")+" THEN 1 ELSE 0 END";
        var counts=repo.aggregateRecords(scope,filter,"",index,0,(source,query)->{
            var args=new ArrayList<>(query.args());
            if(!kind.isBlank())args.add(kind);
            if(reuseFees)args.addAll(nonFee);
            return repo.jdbc().queryForList("SELECT shop_id,record_date,kind,currency,source,'RECORD' basis,"+(reuseFees?"0":advertisingFee)
                +" advertising_fee,COUNT(*) records FROM "+source+" WHERE "+query.where()
                +(kind.isBlank()?"":" AND kind=?")+(reuseFees?" AND kind IN ("+placeholders(nonFee.size())+")":"")
                +" GROUP BY shop_id,record_date,kind,currency,source,advertising_fee",args.toArray());
        });
        if(!reuseFees)return counts;
        var merged=new ArrayList<>(counts);
        merged.addAll(fees.orElseThrow());
        return merged;
    }

    private Optional<List<Map<String,Object>>> feePageCounts(Scope scope,Filter filter){
        var fees=repo.feeDays(scope,filter,false);
        if(fees.isEmpty())return Optional.empty();
        var ads=repo.feeDays(scope,filter,true);
        if(ads.isEmpty())return Optional.empty();
        var allCounts=new LinkedHashMap<FeeFacet,Long>();
        var adCounts=new HashMap<FeeFacet,Long>();
        fees.get().forEach(row->allCounts.merge(FeeFacet.of(row),number(row.get("records")),Long::sum));
        ads.get().forEach(row->adCounts.merge(FeeFacet.of(row),number(row.get("records")),Long::sum));
        var rows=new ArrayList<Map<String,Object>>();
        for(var total:allCounts.entrySet()){
            long ad=adCounts.getOrDefault(total.getKey(),0L);
            long other=total.getValue()-ad;
            if(other<0)return Optional.empty();
            if(ad>0)rows.add(total.getKey().count(ad,1));
            if(other>0)rows.add(total.getKey().count(other,0));
        }
        if(!allCounts.keySet().containsAll(adCounts.keySet()))return Optional.empty();
        return Optional.of(rows);
    }
    private List<Map<String,Object>> settledCounts(Scope scope,Filter filter,String kind){
        var q=repo.query(scope,filter,"r");
        return repo.jdbc().queryForList("SELECT r.shop_id,r.record_date,o.kind,r.currency,'API' source,0 advertising_fee,'SETTLED' basis,COUNT(*) records"
            +repo.settledOrderRelation(scope,filter,kind,false)+" GROUP BY r.shop_id,r.record_date,o.kind,r.currency",q.args().toArray());
    }

    private boolean hasRelevantEvents(Scope scope,Filter filter){
        return hasRelevantEvents(scope,filter,repo.jdbc()::queryForList);
    }
    private boolean hasRelevantEvents(Scope scope,Filter filter,MetadataQuery metadata){
        long covered=priority.coveredThrough(metadata::read,scope.organizationId(),filter.platform(),months(filter));
        if(metadata.read("SELECT event_id FROM finance_query_events WHERE organization_id=? AND event_id>? LIMIT 1",scope.organizationId(),covered).isEmpty())return false;
        LocalDate start=filter.start().withDayOfMonth(1),end=YearMonth.from(filter.end()).atEndOfMonth();
        if(!metadata.read("SELECT event_id FROM finance_query_events WHERE organization_id=? AND event_id>? AND (event_scope='ALL' OR record_date BETWEEN ? AND ?) LIMIT 1",scope.organizationId(),covered,start,end).isEmpty())return true;
        var prefixes=billPrefixes(scope.organizationId(),metadata);
        var pairs=prefixes.stream().map(p->List.of(p.user,p.source)).distinct().toList();
        // A pathological owner/source history is conservatively pending, never silently omitted.
        if(pairs.size()>128)return true;
        // ORDER events can alter a historical first-settlement month even when the source date is elsewhere.
        var args=new ArrayList<Object>();args.add(scope.organizationId());args.add(covered);
        String eventBill=billExists("e",pairs,start,end,args);
        String related="(r.record_date BETWEEN ? AND ? OR (r.kind='SETTLEMENT' AND ";
        args.add(start);args.add(end);String firstRelated=related+billExists("r",pairs,start,end,args)+"))";
        args.add(start);args.add(end);String secondRelated=related+billExists("r",pairs,start,end,args)+"))";
        return !metadata.read("SELECT e.event_id FROM finance_query_events e WHERE e.organization_id=? AND e.event_id>? AND ("
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
    private List<BillPrefix> billPrefixes(String org){return billPrefixes(org,repo.jdbc()::queryForList);}
    private List<BillPrefix> billPrefixes(String org,MetadataQuery metadata){
        return metadata.read("SELECT organization_id,user_id,shop_id,kind,source FROM finance_records FORCE INDEX(idx_finance_reconcile_bill) WHERE organization_id=? GROUP BY organization_id,user_id,shop_id,kind,source",org).stream()
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
        var dates=repo.jdbc().queryForList("SELECT DISTINCT shop_id,record_date FROM finance_records FORCE INDEX(idx_finance_reconcile_bill) WHERE organization_id=? AND kind='BILL' AND ("+String.join(" OR ",clauses)+") LIMIT 10001",args.toArray());
        if(dates.size()>10000)change.all=true;else dates.forEach(row->change.add(row,false));
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
    private static LocalDate date(Object value){return LocalDate.parse(text(value).substring(0,10));}
    private Map<String,Object> readLedger(Object value,LedgerFields fields){
        if(fields==LedgerFields.ALL)return read(value);
        // Keep decimal text intact. MySQL JSON functions convert it to binary JSON.
        var result=new LinkedHashMap<String,Object>();
        try(var parser=json.createParser(text(value))){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IllegalStateException("Invalid finance projection payload");
            for(JsonToken field=parser.nextToken();field!=JsonToken.END_OBJECT;field=parser.nextToken()){
                if(field!=JsonToken.PROPERTY_NAME)throw new IllegalStateException("Invalid finance projection field");
                String name=parser.currentName();
                parser.nextToken();
                if(fields.names.contains(name))result.put(name,ledgerField.readValue(parser));
                else parser.skipChildren();
            }
        }
        return result;
    }
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
