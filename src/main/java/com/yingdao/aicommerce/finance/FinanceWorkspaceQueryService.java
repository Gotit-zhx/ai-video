package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A single, scope-consistent response for each financial workspace. */
@Service
public class FinanceWorkspaceQueryService {
    private static final long PENDING_WAIT_NANOS=java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    private static final long INTERACTIVE_PENDING_WAIT_NANOS=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
    private static final long PENDING_POLL_NANOS=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
    @FunctionalInterface interface PendingWait {void await(long nanos)throws InterruptedException;}
    public record Selection(String view,String kind,boolean allOrders,boolean cny,String fxSnapshot,
                            int page,int spendPage,String numbers,String search,String assignment,boolean interactivePage){
        public Selection(String view,String kind,boolean allOrders,boolean cny,String fxSnapshot,
                         int page,int spendPage,String numbers,String search,String assignment){
            this(view,kind,allOrders,cny,fxSnapshot,page,spendPage,numbers,search,assignment,false);
        }
    }
    final FinanceRepository repo;final FinanceService finance;final FinanceAdSpendService spend;
    final FinanceQueryProjections projections;final FinanceAds ads;final FinanceReadCache cache;
    final java.util.function.LongSupplier nanoTime;final PendingWait pendingWait;
    private record OverviewRead(Scope scope,Filter filter,boolean cny,String fxSnapshot){}
    private record WorkspaceRead(Scope scope,Filter filter,Selection selection){}
    private final FinanceQueryReadBatch<OverviewRead,Map<String,Object>> nativeOverviews=new FinanceQueryReadBatch<>();
    private final FinanceQueryReadBatch<WorkspaceRead,Map<String,Object>> nativeWorkspace=new FinanceQueryReadBatch<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private FinanceShopeeAds shopeeAds;
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceWorkspaceQueryService(FinanceRepository repo,FinanceService finance,FinanceAdSpendService spend,
            FinanceQueryProjections projections,FinanceAds ads,FinanceReadCache cache){
        this(repo,finance,spend,projections,ads,cache,System::nanoTime,java.util.concurrent.TimeUnit.NANOSECONDS::sleep);
    }
    FinanceWorkspaceQueryService(FinanceRepository repo,FinanceService finance,FinanceAdSpendService spend,
            FinanceQueryProjections projections,FinanceAds ads,FinanceReadCache cache,
            java.util.function.LongSupplier nanoTime,PendingWait pendingWait){
        this.repo=repo;this.finance=finance;this.spend=spend;this.projections=projections;this.ads=ads;this.cache=cache;
        this.nanoTime=nanoTime;this.pendingWait=pendingWait;
    }
    Map<String,Object> overview(Scope scope,Filter filter,boolean cny,String fxSnapshot){
        Filter nativeFilter=nativeFilter(filter);
        var version=projections.version(scope,nativeFilter);
        if(!"READY".equals(version.get("status")))return nativeOverview(scope,filter,cny,fxSnapshot);

        String revision=text(version.get("revision"));
        java.util.function.Supplier<Map<String,Object>> load=()->{
            var snapshot=projections.findForDashboard(scope,nativeFilter)
                .filter(found->found.revision().equals(revision)).orElseThrow(ProjectionChanged::new);
            var result=new LinkedHashMap<>(finance.dashboardFromFacts(scope,filter,cny,fxSnapshot,false,snapshot.facts()));
            // A verified projection has its own revision. The response cache tracks
            // the existing Redis invalidation version independently.
            result.remove("_financeDataRevision");
            result.remove("_financeFactsBuilt");
            result.put("readModel",Map.of("status","READY","revision",revision,"generatedAt",snapshot.generatedAt().toString()));
            return result;
        };
        try{
            var result=cache==null?load.get():cache.getPage(
                new FinanceSnapshotCache.Key(scope,filter,cny,text(fxSnapshot),false),"overview",revision,"",load);
            return finance.withCalculations(scope,filter,result);
        }catch(ProjectionChanged changed){
            return nativeOverview(scope,filter,cny,fxSnapshot);
        }
    }
    private Map<String,Object> nativeOverview(Scope scope,Filter filter,boolean cny,String fxSnapshot){
        var result=nativeOverviews.read(new OverviewRead(scope,filter,cny,fxSnapshot),()->{
            var all=nativeFilter(filter);
            projections.requestMissing(scope,all);
            var repaired=projections.repairedLedger(scope,all,false);
            var facts=repaired.map(FinanceQueryProjections.Snapshot::facts)
                .orElseGet(()->finance.buildNativeDashboardFacts(scope,all,false));
            return finance.dashboardFromFacts(scope,filter,cny,fxSnapshot,false,facts);
        });
        return finance.withCalculations(scope,filter,result);
    }
    public Map<String,Object> query(Scope scope,Scope personal,Filter filter,Selection selection){
        Selection selected=validated(selection);
        Filter nativeFilter=nativeFilter(filter);
        var version=projections.version(scope,nativeFilter);
        if(selected.view().equals("settlements")&&selected.kind().equals("SETTLEMENT")&&"READY".equals(version.get("status")))
            projections.requestSettlementCosts(scope,nativeFilter);
        if(!selected.view().equals("ads")&&!selected.allOrders()){
            // Leave most of the page budget for the exact native query.
            boolean quickFallback=selected.view().equals("settlements")||selected.interactivePage();
            long waitBudget=quickFallback?INTERACTIVE_PENDING_WAIT_NANOS:PENDING_WAIT_NANOS;
            version=awaitProjection(scope,nativeFilter,version,waitBudget);
        }
        Map<String,Object> result;
        if("READY".equals(version.get("status"))){
            String revision=text(version.get("revision"));
            java.util.function.Supplier<Map<String,Object>> load=()->build(scope,filter,selected,revision);
            try{
                result=cache==null?load.get():cache.getPage(new FinanceSnapshotCache.Key(companyScope(scope.organizationId()),filter,
                    selected.cny(),text(selected.fxSnapshot()),false),selected.view(),revision,selectionKey(selected),load);
            }catch(ProjectionChanged changed){result=fallback(scope,filter,selected);}
        }else result=fallback(scope,filter,selected);
        // Personal field preferences are read after the shared financial cache.
        var copy=new LinkedHashMap<>(result);
        if(cache!=null)copy.put("settlementCalculation",cache.settlementCalculation(scope,selectedShops(rows(copy.get("shops")),filter)));
        if(result.containsKey("records")){
            var records=new LinkedHashMap<>(map(result.get("records")));
            records.put("fieldSettings",new FinanceFields(repo).settings(personal,selected.view().equals("reconciliation")?"RECONCILIATION":selected.kind()));
            copy.put("records",records);
        }
        return copy;
    }
    private Map<String,Object> awaitProjection(Scope scope,Filter filter,Map<String,Object> version,long waitBudget){
        if(!"PENDING".equals(version.get("status")))return version;
        requireActiveRequest();
        long started=nanoTime.getAsLong();
        while("PENDING".equals(version.get("status"))){
            long remaining=waitBudget-(nanoTime.getAsLong()-started);
            if(remaining<=0){requireActiveRequest();return version;}
            try{pendingWait.await(Math.min(PENDING_POLL_NANOS,remaining));}
            catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                requireActiveRequest();
            }
            requireActiveRequest();
            long elapsed=nanoTime.getAsLong()-started;
            if(elapsed>=waitBudget){requireActiveRequest();return version;}
            long pollRemaining=waitBudget-elapsed;
            Map<String,Object> polled;
            try{polled=projections.version(scope,filter,Math.max(1,(pollRemaining+999_999L)/1_000_000L));}
            catch(FinanceQueryProjections.PollDeadlineElapsed expired){requireActiveRequest();return version;}
            catch(org.springframework.dao.QueryTimeoutException timeout){
                requireActiveRequest();
                if(nanoTime.getAsLong()-started>=waitBudget)return version;
                var unavailable=new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"财务投影状态查询超时，请稍后重试");
                unavailable.initCause(timeout);throw unavailable;
            }
            requireActiveRequest();
            if(nanoTime.getAsLong()-started>=waitBudget){requireActiveRequest();return version;}
            version=polled;
        }
        requireActiveRequest();
        return version;
    }
    private static void requireActiveRequest(){
        if(Thread.currentThread().isInterrupted())
            throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"财务查询等待已中断");
    }
    public Map<String,Object> status(Scope scope,Filter filter){return status(scope,filter,"");}
    public Map<String,Object> status(Scope scope,Filter filter,String view){
        var result=new LinkedHashMap<>(projections.version(scope,nativeFilter(filter)));
        var ids=selectedShops(finance.shops(scope),filter);var operations=new LinkedHashMap<String,Object>();
        operations.put("sync",finance.syncStates(scope,filter));
        operations.put("settlementCalculation",cache==null?Map.of():cache.settlementCalculation(scope,ids));
        boolean rebuilding=false,jobs=false;
        if(!ids.isEmpty()){
            String slots=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>();args.add(scope.organizationId());args.addAll(ids);
            rebuilding=!repo.jdbc().queryForList("SELECT shop_id FROM finance_cost_rebuilds WHERE organization_id=? AND shop_id IN ("+slots+") AND status='READY' LIMIT 1",args.toArray()).isEmpty();
            jobs=!repo.jdbc().queryForList("SELECT order_record_id FROM finance_order_cost_jobs FORCE INDEX(idx_cost_job_shop) WHERE organization_id=? AND shop_id IN ("+slots+") AND priority<2 LIMIT 1",args.toArray()).isEmpty();
        }
        operations.put("costState",Map.of("rebuilding",rebuilding,"hasProductJobs",jobs));
        if(Set.of("ads","ads-support").contains(text(view))){
            try{operations.put("adAccounts",ads.list(scope,filter.start(),filter.end()));operations.put("adAccountError",false);}
            catch(RuntimeException unavailable){operations.put("adAccountError",true);}
            if(shopeeAds!=null)operations.put("shopeeAds",shopeeAds.status(scope));
        }
        operations.put("revision",hash(repo.write(operations)));operations.put("generatedAt",java.time.Instant.now().toString());
        result.put("operations",operations);return result;
    }
    private static List<String> selectedShops(List<Map<String,Object>> shops,Filter filter){
        return shops.stream().filter(shop->FinanceGroups.matches(shop,filter)).map(shop->text(shop.get("id"))).toList();
    }
    private Map<String,Object> fallback(Scope scope,Filter f,Selection selected){
        return nativeWorkspace.read(new WorkspaceRead(scope,f,selected),()->{
            // Enqueue outside the read-only snapshot; failures never publish fake empty facts.
            projections.requestMissing(scope,nativeFilter(f));
            return build(scope,f,selected,null);
        });
    }
    private record Prepared(FinanceQueryProjections.Snapshot snapshot,Map<String,Object> facts,
                            Map<String,Object> nativeSpend,Filter filter,Map<String,Object> records,List<String> billCurrencies,
                            boolean reconciliationPageOnly){}
    private Map<String,Object> build(Scope scope,Filter requested,Selection selected,String expectedRevision){
        boolean primary=selected.view().equals("ads");
        // All mutable financial reads/counts share one MVCC snapshot. Public FX I/O
        // and its append-only public-rate snapshot writes happen after it closes.
        Prepared prepared=repo.readSnapshot(180,()->{
            var all=nativeFilter(requested);
            boolean browserReconciliation=expectedRevision==null&&selected.view().equals("reconciliation")&&selected.interactivePage();
            // With no currency to select, preserve the existing full response
            // for native-currency callers. Bill currencies and rows share the snapshot.
            List<String> nativeBillCurrencies=browserReconciliation&&!selected.cny()&&requested.currency().isBlank()
                ?repo.billCurrencies(scope,all):null;
            boolean pageOnly=browserReconciliation&&(selected.cny()||!requested.currency().isBlank()
                ||nativeBillCurrencies!=null&&!nativeBillCurrencies.isEmpty()&&!nativeBillCurrencies.getFirst().isBlank());
            // Advertising support needs category-specific raw facets, so it
            // retains the independent count query.
            String countKind=selected.view().equals("ads-support")?"":selected.kind();
            var snapshot=expectedRevision!=null?(selected.allOrders()?projections.findForOrderCounts(scope,all):projections.findForQuery(scope,all,primary)).orElseThrow(ProjectionChanged::new)
                :primary||selected.allOrders()?null:projections.repairedLedgerForPage(scope,all,countKind,pageOnly).orElse(null);
            if(expectedRevision!=null&&!snapshot.revision().equals(expectedRevision))throw new ProjectionChanged();
            boolean unsearched=FinanceRecordSearch.numbers(selected.numbers()).isEmpty();
            boolean reuseNativeCount=snapshot==null&&unsearched&&(
                selected.view().equals("settlements")&&!selected.kind().equals("BILL")||selected.view().equals("fees"));
            String currency=requested.currency();
            List<String> billCurrencies=List.of();
            if(selected.view().equals("reconciliation")){
                billCurrencies=nativeBillCurrencies!=null?nativeBillCurrencies:snapshot==null?repo.billCurrencies(scope,all):snapshot.rawCounts().stream()
                    .filter(row->"RECORD".equals(row.get("basis"))&&"BILL".equals(row.get("kind")))
                    .map(row->text(row.get("currency"))).distinct().sorted().toList();
                if(currency.isBlank()&&!selected.cny()&&!billCurrencies.isEmpty())currency=billCurrencies.getFirst();
            }
            var nativeCounts=new ArrayList<Map<String,Object>>();
            Map<String,Object> facts;
            if(primary||selected.allOrders())facts=Map.of();
            else if(pageOnly){
                facts=snapshot!=null?Map.of("orders",snapshot.facts().get("orders"),"reportingMonths",snapshot.facts().get("reportingMonths"))
                    :Map.of("orders",FinanceService.dailyFactRows(new FinanceCostReport(repo).dashboardFacts(scope,all)),
                            "reportingMonths",new FinanceReportingMonths(repo).coverage(scope,all));
            }
            else if(snapshot!=null)facts=snapshot.facts();
            else if(reuseNativeCount)facts=finance.buildNativeDashboardFacts(scope,nativeFilter(requested),false,nativeCounts);
            else facts=finance.buildNativeDashboardFacts(scope,nativeFilter(requested),false);
            if(!selected.view().equals("reconciliation")&&!primary&&!selected.allOrders()&&currency.isBlank()&&!selected.cny())currency=java.util.stream.Stream.concat(
                rows(facts.get("buckets")).stream(),rows(facts.get("advertising")).stream()).map(r->text(r.get("currency"))).distinct().sorted().findFirst().orElse("");
            var filter=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity(),requested.groupId());
            if(primary)return new Prepared(snapshot,facts,snapshot==null?spend.buildNativeSpendFacts(scope,nativeFilter(requested)):snapshot.spendFacts(),filter,Map.of(),billCurrencies,false);
            java.util.function.Supplier<Map<String,Object>> read=()->selected.view().equals("reconciliation")?
                repo.reconciliationPage(scope,filter,selected.page(),50,selected.numbers()):
                selected.view().equals("ads-support")&&selected.kind().equals("FEE")?
                repo.advertisingFeeDetails(scope,filter,selected.page(),50,selected.numbers()):
                repo.details(scope,filter,selected.kind(),selected.page(),50,selected.numbers(),selected.allOrders());
            boolean countReady=unsearched&&(snapshot!=null||reuseNativeCount);
            var records=new LinkedHashMap<>(countReady?repo.withAuthoritativePageTotal(
                exactTotal(snapshot==null?nativeCounts:snapshot.rawCounts(),filter,selected),read):read.get());
            records.put("dateBasis",FinanceCalendar.contract());
            if(selected.view().equals("ads-support")&&selected.kind().equals("FEE"))records.put("advertisingOnly",true);
            if(!selected.allOrders()&&!selected.view().equals("ads-support")
                    &&!"SETTLED_ORDER".equals(FinanceCostReport.scopeKind(selected.view().equals("reconciliation")?"RECONCILIATION":selected.kind(),false))){
                var projected=selected.view().equals("settlements")&&selected.kind().equals("SETTLEMENT")
                    ?projections.settlementCosts(scope,filter):Optional.<Map<String,Object>>empty();
                records.put("productCosts",projected.orElseGet(()->new FinanceCostReport(repo).summary(scope,filter,selected.kind())));
            }
            if(selected.cny()&&selected.view().equals("reconciliation"))repo.reconciliationFxDays(scope,rows(records.get("items")));
            return new Prepared(snapshot,facts,Map.of(),filter,records,billCurrencies,pageOnly);
        });
        var snapshot=prepared.snapshot();
        var quote=!primary&&selected.cny()?finance.exchangeRates().historical(selected.fxSnapshot(),requested.start(),requested.end()):null;
        var out=new LinkedHashMap<>(primary||selected.allOrders()?context(scope,requested,selected,snapshot):
            finance.dashboardFromFacts(scope,prepared.filter(),selected.cny(),selected.fxSnapshot(),false,prepared.facts(),quote));
        out.remove("_financeDataRevision");out.remove("_financeFactsBuilt");
        if(prepared.reconciliationPageOnly()){
            // This browser contract renders reconciliation rows and their common
            // filtering/cost context. The general API still returns all summaries.
            out.keySet().retainAll(Set.of("filters","fx","shops","groups","dateBasis","dateWindows",
                "reportingMonths","reportingMonthNotice","sync","updatedAt","productCosts"));
        }
        if(selected.view().equals("reconciliation"))out.put("currencies",prepared.billCurrencies());
        if(primary){
            java.util.function.Supplier<Map<String,Object>> calculate=()->spend.reportFromNative(scope,prepared.nativeSpend(),requested,selected.cny(),selected.fxSnapshot(),selected.search(),selected.assignment());
            // Summary, allocation and FX work belongs to the period, not to each page.
            // Reuse the existing bounded/compressed fact cache; page changes only slice
            // its immutable result. A published revision or selection picks a new key.
            var periodKey=new FinanceSnapshotCache.Key(companyScope(scope.organizationId()),requested,selected.cny(),
                "workspace-spend:"+hash(repo.write(List.of(snapshot==null?"":snapshot.revision(),selected.fxSnapshot(),selected.search(),selected.assignment()))),false);
            var report=new LinkedHashMap<>(cache!=null&&snapshot!=null?cache.facts(periodKey,calculate):calculate.get());
            for(String stamp:List.of("_financeDataRevision","_financeFactsBuilt"))if(report.containsKey(stamp))out.put(stamp,report.remove(stamp));
            var items=rows(report.get("items"));int offset=(int)Math.min(items.size(),(selected.spendPage()-1L)*50);
            report.put("items",new ArrayList<>(items.subList(offset,Math.min(items.size(),offset+50))));
            report.put("total",items.size());report.put("page",selected.spendPage());report.put("pageSize",50);
            out.put("adSpend",report);out.put("fx",report.get("fx"));out.put("currencies",report.get("currencies"));
            out.put("adAccounts",ads.list(scope,requested.start(),requested.end()));
        }else{
            var records=prepared.records();
            if(selected.view().equals("ads-support"))out.put("adAccounts",ads.list(scope,requested.start(),requested.end()));
            if(selected.cny()){
                finance.exchangeRates().enrich(rows(records.get("items")),quote);out.put("fx",quote.metadata());
            }
            out.put("records",records);if(selected.allOrders())out.put("orderScope","ALL");
        }
        boolean ready=snapshot!=null&&!snapshot.refreshing();
        var model=new LinkedHashMap<String,Object>();model.put("status",ready?"READY":"PENDING");
        model.put("revision",ready?snapshot.revision():"");model.put("generatedAt",ready?snapshot.generatedAt().toString():null);
        model.put("coverage","已入库数据；未同步、待补齐及未知金额不视为零");
        out.put("readModel",model);out.put("updatedAt",java.time.Instant.now().toString());return out;
    }
    private Map<String,Object> context(Scope scope,Filter f,Selection selected,FinanceQueryProjections.Snapshot snapshot){
        var shops=finance.shops(scope);var result=new LinkedHashMap<String,Object>();
        result.put("shops",shops);result.put("groups",finance.groups(scope));result.put("sync",List.of());
        var currencies=shops.stream().map(shop->text(shop.get("currency"))).filter(c->!c.isBlank()).distinct().sorted().toList();
        result.put("currencies",currencies);result.put("dateBasis",FinanceCalendar.contract());result.put("dateWindows",FinanceCalendar.windows(f,shops));
        result.put("filters",Map.of("start",f.start(),"end",f.end(),"shopId",f.shopId(),"platform",f.platform(),
            "groupId",f.groupId(),"currency",f.currency(),"displayCurrency",selected.cny()?"CNY":f.currency(),"granularity",f.granularity()));
        return result;
    }
    long exactTotal(List<Map<String,Object>> counts,Filter f,Selection selected){
        String kind=selected.view().equals("reconciliation")?"BILL":selected.kind();
        String basis=!selected.allOrders()&&Set.of("ORDER","REFUND").contains(kind)?"SETTLED":"RECORD";
        var eligible=counts.stream().filter(row->basis.equals(row.get("basis"))&&kind.equals(row.get("kind")))
            .filter(row->!selected.allOrders()||"API".equals(row.get("source")))
            .filter(row->!selected.view().equals("ads-support")||!kind.equals("FEE")||number(row.get("advertising_fee"))==1)
            .map(row->Map.<String,Object>of("currency",text(row.get("currency")),"records",number(row.get("records")))).toList();
        if(f.currency().isBlank())return eligible.stream().mapToLong(row->number(row.get("records"))).sum();
        if(asciiCurrency(f.currency())&&eligible.stream().allMatch(row->asciiCurrency(text(row.get("currency")))))
            return eligible.stream().filter(row->f.currency().equals(row.get("currency"))).mapToLong(row->number(row.get("records"))).sum();
        return number(repo.jdbc().queryForObject("""
            SELECT COALESCE(SUM(bucket.records),0)
            FROM JSON_TABLE(CAST(? AS JSON),'$[*]' COLUMNS(
              currency VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$.currency',
              records BIGINT PATH '$.records'
            )) bucket
            WHERE bucket.currency=CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci
            """,Long.class,repo.write(eligible),f.currency()));
    }
    private static boolean asciiCurrency(String currency){return currency.length()==3&&currency.charAt(0)>='A'&&currency.charAt(0)<='Z'
        &&currency.charAt(1)>='A'&&currency.charAt(1)<='Z'&&currency.charAt(2)>='A'&&currency.charAt(2)<='Z';}
    private static Filter nativeFilter(Filter f){return new Filter(f.start(),f.end(),f.shopId(),f.platform(),"",f.granularity(),f.groupId());}
    private String selectionKey(Selection s){
        return s.view().equals("ads")?repo.write(List.of(s.spendPage(),s.search(),s.assignment())):
            repo.write(List.of(s.kind(),s.allOrders(),s.page(),s.spendPage(),s.numbers(),s.search(),s.assignment()));
    }
    private static Selection validated(Selection s){
        String view=text(s.view());if(view.equals("fee-records"))view="fees";
        var kinds=switch(view){case "settlements"->List.of("SETTLEMENT","BILL","PENDING");case "fees"->List.of("FEE","COST");
            case "orders"->List.of("ORDER","REFUND");case "reconciliation"->List.of("BILL");case "ads","ads-support"->List.of("AD","FEE","AD_GMV");default->throw bad("财务查询页面不正确");};
        String kind=text(s.kind());if(kind.isBlank()||view.equals("reconciliation"))kind=kinds.getFirst();
        if(!kinds.contains(kind))throw bad("此页面不支持所选记录类型");
        return new Selection(view,kind,view.equals("orders")&&kind.equals("ORDER")&&s.allOrders(),s.cny(),text(s.fxSnapshot()),
            Math.max(1,s.page()),Math.max(1,s.spendPage()),text(s.numbers()),text(s.search()),text(s.assignment()),
            Set.of("reconciliation","fees").contains(view)&&s.interactivePage());
    }
    private static final class ProjectionChanged extends RuntimeException {}
}
