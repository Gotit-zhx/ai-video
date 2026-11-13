package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A single, scope-consistent response for each financial workspace. */
@Service
public class FinanceWorkspaceQueryService {
    public record Selection(String view,String kind,boolean allOrders,boolean cny,String fxSnapshot,
                            int page,int spendPage,String numbers,String search,String assignment){}
    final FinanceRepository repo;final FinanceService finance;final FinanceAdSpendService spend;
    final FinanceQueryProjections projections;final FinanceAds ads;final FinanceReadCache cache;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private FinanceShopeeAds shopeeAds;
    public FinanceWorkspaceQueryService(FinanceRepository repo,FinanceService finance,FinanceAdSpendService spend,
            FinanceQueryProjections projections,FinanceAds ads,FinanceReadCache cache){
        this.repo=repo;this.finance=finance;this.spend=spend;this.projections=projections;this.ads=ads;this.cache=cache;
    }
    public Map<String,Object> query(Scope scope,Scope personal,Filter filter,Selection selection){
        Selection selected=validated(selection);
        Filter nativeFilter=nativeFilter(filter);
        var version=projections.version(scope,nativeFilter);
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
        // Enqueue outside the read-only snapshot; failures never publish fake empty facts.
        projections.request(scope,nativeFilter(f));
        return build(scope,f,selected,null);
    }
    private record Prepared(FinanceQueryProjections.Snapshot snapshot,Map<String,Object> facts,
                            Map<String,Object> nativeSpend,Filter filter,Map<String,Object> records){}
    private Map<String,Object> build(Scope scope,Filter requested,Selection selected,String expectedRevision){
        boolean primary=selected.view().equals("ads");
        // All mutable financial reads/counts share one MVCC snapshot. Public FX I/O
        // and its append-only public-rate snapshot writes happen after it closes.
        Prepared prepared=repo.readSnapshot(180,()->{
            var snapshot=expectedRevision==null?null:projections.findForQuery(scope,nativeFilter(requested),primary).orElseThrow(ProjectionChanged::new);
            if(expectedRevision!=null&&!snapshot.revision().equals(expectedRevision))throw new ProjectionChanged();
            var facts=primary||selected.allOrders()?Map.<String,Object>of():snapshot==null?
                finance.buildNativeDashboardFacts(scope,nativeFilter(requested),false):snapshot.facts();
            String currency=requested.currency();
            if(selected.view().equals("reconciliation")&&currency.isBlank()&&!selected.cny()){
                var billCurrencies=snapshot==null?repo.billCurrencies(scope,nativeFilter(requested)):snapshot.rawCounts().stream()
                    .filter(row->"RECORD".equals(row.get("basis"))&&"BILL".equals(row.get("kind")))
                    .map(row->text(row.get("currency"))).distinct().sorted().toList();
                currency=billCurrencies.isEmpty()?"":billCurrencies.getFirst();
            }
            if(!selected.view().equals("reconciliation")&&!primary&&!selected.allOrders()&&currency.isBlank()&&!selected.cny())currency=java.util.stream.Stream.concat(
                rows(facts.get("buckets")).stream(),rows(facts.get("advertising")).stream()).map(r->text(r.get("currency"))).distinct().sorted().findFirst().orElse("");
            var filter=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity(),requested.groupId());
            if(primary)return new Prepared(snapshot,facts,snapshot==null?spend.buildNativeSpendFacts(scope,nativeFilter(requested)):snapshot.spendFacts(),filter,Map.of());
            java.util.function.Supplier<Map<String,Object>> read=()->selected.view().equals("reconciliation")?
                repo.reconciliationPage(scope,filter,selected.page(),50,selected.numbers()):
                selected.view().equals("ads-support")&&selected.kind().equals("FEE")?
                repo.advertisingFeeDetails(scope,filter,selected.page(),50,selected.numbers()):
                repo.details(scope,filter,selected.kind(),selected.page(),50,selected.numbers(),selected.allOrders());
            boolean countReady=snapshot!=null&&FinanceRecordSearch.numbers(selected.numbers()).isEmpty();
            var records=new LinkedHashMap<>(countReady?repo.withAuthoritativePageTotal(exactTotal(snapshot.rawCounts(),filter,selected),read):read.get());
            records.put("dateBasis",FinanceCalendar.contract());
            if(selected.view().equals("ads-support")&&selected.kind().equals("FEE"))records.put("advertisingOnly",true);
            if(!selected.allOrders()&&!selected.view().equals("ads-support")
                    &&!"SETTLED_ORDER".equals(FinanceCostReport.scopeKind(selected.view().equals("reconciliation")?"RECONCILIATION":selected.kind(),false)))
                records.put("productCosts",new FinanceCostReport(repo).summary(scope,filter,selected.kind()));
            if(selected.cny()&&selected.view().equals("reconciliation"))repo.reconciliationFxDays(scope,rows(records.get("items")));
            return new Prepared(snapshot,facts,Map.of(),filter,records);
        });
        var snapshot=prepared.snapshot();
        var quote=!primary&&selected.cny()?finance.exchangeRates().historical(selected.fxSnapshot(),requested.start(),requested.end()):null;
        var out=new LinkedHashMap<>(primary||selected.allOrders()?context(scope,requested,selected,snapshot):
            finance.dashboardFromFacts(scope,prepared.filter(),selected.cny(),selected.fxSnapshot(),false,prepared.facts(),quote));
        out.remove("_financeDataRevision");out.remove("_financeFactsBuilt");
        if(selected.view().equals("reconciliation"))out.put("currencies",snapshot==null?repo.billCurrencies(scope,nativeFilter(requested)):
            snapshot.rawCounts().stream().filter(row->"RECORD".equals(row.get("basis"))&&"BILL".equals(row.get("kind")))
                .map(row->text(row.get("currency"))).distinct().sorted().toList());
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
        var model=new LinkedHashMap<String,Object>();model.put("status",snapshot==null?"PENDING":"READY");
        model.put("revision",snapshot==null?"":snapshot.revision());model.put("generatedAt",snapshot==null?null:snapshot.generatedAt().toString());
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
    static long exactTotal(List<Map<String,Object>> counts,Filter f,Selection selected){
        String kind=selected.view().equals("reconciliation")?"BILL":selected.kind();
        String basis=!selected.allOrders()&&Set.of("ORDER","REFUND").contains(kind)?"SETTLED":"RECORD";
        return counts.stream().filter(row->basis.equals(row.get("basis"))&&kind.equals(row.get("kind")))
            .filter(row->f.currency().isBlank()||f.currency().equals(text(row.get("currency"))))
            .filter(row->!selected.allOrders()||"API".equals(row.get("source")))
            .filter(row->!selected.view().equals("ads-support")||!kind.equals("FEE")||number(row.get("advertising_fee"))==1)
            .mapToLong(row->number(row.get("records"))).sum();
    }
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
            Math.max(1,s.page()),Math.max(1,s.spendPage()),text(s.numbers()),text(s.search()),text(s.assignment()));
    }
    private static final class ProjectionChanged extends RuntimeException {}
}
