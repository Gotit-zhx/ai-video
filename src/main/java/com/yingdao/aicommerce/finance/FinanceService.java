package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceService {
    private final FinanceRepository repo; private final OrganizationAccessPolicy access; private final FinanceShopAuthorization shops;private final FinanceFx fx;
    private final FinanceSnapshotCache snapshots=new FinanceSnapshotCache();
    private final FinanceSnapshotCache dashboardFacts=new FinanceSnapshotCache(System::nanoTime,java.util.concurrent.TimeUnit.SECONDS.toNanos(15),32);
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FinanceReadCache sharedSnapshots;
    public FinanceService(FinanceRepository repo,OrganizationAccessPolicy access,FinanceShopAuthorization shops,FinanceFx fx){
        this.repo=repo;this.access=access;this.shops=shops;this.fx=fx;
    }
    public FinanceFx exchangeRates(){return fx;}
    public FinanceShopAuthorization authorization(){return shops;}
    public Scope authorize(PlatformPrincipal principal){return scope(principal,access.isPlatformAdmin(principal));}
    public void requireManagement(PlatformPrincipal p){
        authorize(p);if(!canManage(p)&&!access.isPlatformAdmin(p))throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"没有财务配置管理权限");
    }
    public List<Map<String,Object>> groups(Scope s){return new FinanceGroups(repo).list(s);}
    @Transactional public String saveGroup(Scope s,String id,String name){String result=new FinanceGroups(repo).save(s,id,name);invalidateAfterCommit(s);return result;}
    @Transactional public void deleteGroup(Scope s,String id){new FinanceGroups(repo).delete(s,id);invalidateAfterCommit(s);}
    public Scope shopScope(Scope s,String id){
        var owners=repo.jdbc().queryForList("SELECT user_id FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED'",s.organizationId(),id);
        if(owners.isEmpty())throw bad("没有找到本公司的财务店铺");return new Scope(s.organizationId(),text(owners.getFirst().get("user_id")));
    }
    public List<Map<String,Object>> shops(Scope s){
        return repo.jdbc().queryForList("""
            SELECT s.id,s.name,s.display_name,s.platform,s.region,s.external_shop_id,s.status authorization_status,
            s.enabled,s.currency,s.timezone,s.group_id,
            (SELECT g.name FROM finance_groups g WHERE g.organization_id=s.organization_id AND g.id=s.group_id) group_name,
            (SELECT MAX(last_success_at) FROM finance_sync_states st WHERE st.organization_id=s.organization_id
              AND st.user_id=s.user_id AND st.shop_id=s.id) last_success_at
            FROM finance_connections s WHERE s.organization_id=? AND s.status<>'REMOVED' ORDER BY s.platform,s.name
            """,s.organizationId()).stream().map(row->{
                if(row.get("currency")==null)row.put("currency",currencyFor(text(row.get("region"))));
                if(row.get("timezone")==null)row.put("timezone",timezoneFor(text(row.get("region"))));return row;
            }).toList();
    }
    public Map<String,Object> ownedShop(Scope s,String id){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())repo.requireShopWrite(s,id);
        return shops(s).stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow(()->bad("没有找到本公司的财务店铺"));
    }
    @Transactional
    public void configure(Scope s,String id,boolean enabled,String currency,String timezone){
        Map<String,Object> shop=ownedShop(s,id);
        s=shopScope(s,id);
        if(!Set.of("SHOPEE","TIKTOK_SHOP").contains(text(shop.get("platform"))))throw bad("仅支持 Shopee 与 TikTok Shop");
        if(currency==null||!currency.matches("[A-Z]{3}"))throw bad("请选择正确币种");
        try{ZoneId.of(timezone);}catch(Exception e){throw bad("请选择正确时区");}
        repo.jdbc().update("""
            UPDATE finance_connections SET enabled=?,currency=?,timezone=?,updated_at=UTC_TIMESTAMP(6)
            WHERE organization_id=? AND user_id=? AND id=? AND status<>'REMOVED'
            """,enabled,currency,timezone,s.organizationId(),s.userId(),id);
        if(enabled)FinanceSyncChannels.enable(repo,s,id,text(shop.get("platform")));
        if(!timezone.equals(text(shop.get("timezone"))))new FinanceOrderCosts(repo).requestShop(s.organizationId(),id);
        invalidateAfterCommit(s);
    }
    @Transactional
    public void configure(Scope s,String id,boolean enabled,String currency,String timezone,String groupId){
        if(!text(groupId).isBlank())new FinanceGroups(repo).require(s,groupId,true);
        String previousGroup=text(ownedShop(s,id).get("group_id"));
        configure(s,id,enabled,currency,timezone);
        repo.jdbc().update("UPDATE finance_connections SET group_id=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",
                text(groupId).isBlank()?null:groupId,s.organizationId(),id);
        if(!previousGroup.equals(text(groupId)))new FinanceOrderCosts(repo).requestShop(s.organizationId(),id);
    }
    @Transactional
    public void configure(Scope s,String id,boolean enabled,String currency,String timezone,String groupId,String alias){
        String value=alias==null?null:alias.strip();
        if(value!=null&&(value.codePointCount(0,value.length())>100||value.codePoints().anyMatch(Character::isISOControl)))
            throw bad("店铺别名最多 100 个字，不能包含换行或控制字符");
        configure(s,id,enabled,currency,timezone,groupId);
        if(value!=null){
            repo.jdbc().update("UPDATE finance_connections SET display_name=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=? AND status<>'REMOVED'",
                value.isBlank()?null:value,s.organizationId(),id);
            invalidateAfterCommit(s);
        }
    }
    public List<Map<String,Object>> syncStates(Scope s,Filter f){
        var ids=shops(s).stream().filter(shop->FinanceGroups.matches(shop,f)).map(shop->text(shop.get("id"))).collect(java.util.stream.Collectors.toSet());
        return syncStates(s).stream().filter(row->ids.contains(text(row.get("shop_id")))).toList();
    }
    public List<Map<String,Object>> syncStates(Scope s){
        var rows=repo.jdbc().queryForList("""
            SELECT st.shop_id,st.channel,st.status,st.error_message,st.next_at,st.last_success_at,st.coverage_start,
              st.lease_until,st.failures,st.priority_requested_at,st.last_progress_at,st.completed_month,st.month_completed_at,
              c.platform,c.enabled,c.timezone,c.status connection_status,
              CASE WHEN st.channel='ORDER_REPAIR' THEN 'ORDER_ID' ELSE 'TIME_RANGE' END progress_mode,
              CASE WHEN st.channel='ORDER_REPAIR' THEN NULL ELSE NULLIF(JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.from')),'0') END window_start,
              CASE WHEN st.channel='ORDER_REPAIR' THEN NULL ELSE NULLIF(JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.to')),'0') END window_end,
              CASE WHEN st.channel='ORDER_REPAIR' THEN NULL ELSE NULLIF(JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.until')),'0') END target_until,
              JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.completedMonthFrom')) completed_month_from,
              JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.completedMonthUntil')) completed_month_until,
              CASE WHEN st.channel='SETTLEMENT' THEN COALESCE(pending.bills,0) ELSE 0 END pending_date_bills
            FROM finance_sync_states st JOIN finance_connections c ON c.id=st.shop_id AND c.organization_id=st.organization_id AND c.user_id=st.user_id
            LEFT JOIN (
              SELECT shop_id,COUNT(*) bills FROM finance_records
              WHERE organization_id=? AND kind='BILL' AND source='API' AND status='DATE_PENDING'
              GROUP BY shop_id
            ) pending ON pending.shop_id=st.shop_id
            WHERE st.organization_id=? AND c.status<>'REMOVED' ORDER BY st.shop_id,st.channel
            """,s.organizationId(),s.organizationId());
        Instant now=Instant.now();
        // A bounded indexed check over open bills, not a scan of transaction details.
        // Two preceding UTC months cover the prior local month at timezone boundaries.
        LocalDate month=now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        var openBills=repo.jdbc().queryForList("""
            SELECT shop_id,record_date,COUNT(*) bills FROM finance_records
            WHERE organization_id=? AND kind='BILL' AND source='API'
              AND status IN ('PARTIAL','DATE_PENDING') AND record_date>=? AND record_date<?
            GROUP BY shop_id,record_date
            """,s.organizationId(),month.minusMonths(2),month.plusMonths(1));
        return FinanceSyncMonth.describe(FinanceSyncProgress.describe(rows,repo.jdbc().queryForList(
            "SELECT platform,channel,next_allowed_at FROM finance_api_pacing"),now),openBills,now);
    }
    @Transactional
    public void sync(Scope s,String shopId){
        ownedShop(s,shopId);s=shopScope(s,shopId);
        var enabled=repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND user_id=? AND id=? AND enabled=TRUE AND status='ACTIVE' FOR UPDATE",
                s.organizationId(),s.userId(),shopId);
        if(enabled.isEmpty())throw bad("请先在财务店铺管理中开启同步");
        Long pending=repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE organization_id=? AND user_id=? AND shop_id=? AND priority_requested_at IS NOT NULL",
                Long.class,s.organizationId(),s.userId(),shopId);
        if(pending!=null&&pending>0)return; // Repeated clicks join the unfinished request.
        repo.jdbc().update("""
            UPDATE finance_sync_states SET priority_requested_at=COALESCE(priority_requested_at,UTC_TIMESTAMP(6)),
            next_at=CASE WHEN failures>0 OR status IN ('ERROR','RATE_LIMITED') THEN next_at ELSE LEAST(next_at,UTC_TIMESTAMP(6)) END,
            status=CASE WHEN lease_until>UTC_TIMESTAMP(6) OR failures>0 OR status IN ('ERROR','RATE_LIMITED','SYNCING') THEN status ELSE 'READY' END
            WHERE organization_id=? AND user_id=? AND shop_id=?
            """,s.organizationId(),s.userId(),shopId);
        invalidateSnapshots(s);
    }
    @Transactional
    public void removeShop(Scope s,String id){
        new FinanceShopDeletion(repo).request(s,id);
        invalidateAfterCommit(s);
    }
    @Transactional public void removeShop(Scope s,String id,String actor){
        new FinanceShopDeletion(repo).request(s,id,actor);invalidateAfterCommit(s);
    }
    public List<Map<String,Object>> deletions(Scope s){return new FinanceShopDeletion(repo).progress(s);}
    void invalidateAfterCommit(Scope s){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){invalidateSnapshots(s);}
            });
        else invalidateSnapshots(s);
    }
    private void invalidateSnapshots(Scope s){
        Scope shared=companyScope(s.organizationId());snapshots.invalidateCompany(s.organizationId());dashboardFacts.invalidateCompany(s.organizationId());if(sharedSnapshots!=null)sharedSnapshots.invalidate(shared);
    }
    /** Called only after committed worker batches; edits/removals retain hard invalidation. */
    void refreshCostSnapshots(Scope s){
        snapshots.invalidateCompany(s.organizationId());dashboardFacts.invalidateCompany(s.organizationId());
        if(sharedSnapshots!=null)sharedSnapshots.refresh(companyScope(s.organizationId()));
    }
    public Map<String,Object> dashboard(Scope s,Filter requested,boolean cny,String snapshotId){
        var key=new FinanceSnapshotCache.Key(s,requested,cny,text(snapshotId),false);
        java.util.function.Supplier<Map<String,Object>> load=()->buildDashboard(s,requested,cny,snapshotId,false);
        return withCalculations(s,requested,sharedSnapshots==null?snapshots.get(key,load):sharedSnapshots.get(key,load));
    }
    private Map<String,Object> withCalculations(Scope s,Filter f,Map<String,Object> result){
        if(sharedSnapshots==null)return result;
        var selected=rows(result.get("shops")).stream().filter(shop->FinanceGroups.matches(shop,f)).map(shop->text(shop.get("id"))).toList();
        var copy=new LinkedHashMap<>(result);copy.put("settlementCalculation",sharedSnapshots.settlementCalculation(s,selected));return copy;
    }
    public void prewarm(Scope s){
        if(sharedSnapshots==null)return;
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var month=FinanceCalendar.previousMonth(Instant.now());
        for(boolean live:List.of(false,true))for(boolean cny:List.of(false,true)){
            var filter=new Filter(live?today:month.atDay(1),live?today:month.atEndOfMonth(),"","","","day");
            sharedSnapshots.prime(new FinanceSnapshotCache.Key(s,filter,cny,"",live),
                    ()->live?buildScreen(s,filter,cny,""):buildDashboard(s,filter,cny,"",false));
        }
    }
    public Map<String,Object> reconciliationContext(Scope s,Filter requested,boolean cny,String snapshotId){
        var all=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"",requested.granularity(),requested.groupId());
        var currencies=repo.billCurrencies(s,all);
        String currency=requested.currency().isBlank()?(cny||currencies.isEmpty()?"":currencies.getFirst()):requested.currency();
        var result=new LinkedHashMap<String,Object>();
        result.put("filters",Map.of("start",requested.start(),"end",requested.end(),"shopId",requested.shopId(),
                "platform",requested.platform(),"currency",currency,"granularity",requested.granularity(),
                "displayCurrency",cny?"CNY":currency,"groupId",requested.groupId()));
        result.put("currencies",currencies);result.put("shops",shops(s));result.put("groups",groups(s));result.put("sync",syncStates(s,requested));
        result.put("dateBasis",FinanceCalendar.contract());
        result.put("dateWindows",FinanceCalendar.windows(requested,rows(result.get("shops"))));
        if(cny)result.put("fx",fx.quote(snapshotId).metadata());
        result.put("productCosts",new FinanceCostReport(repo).summary(s,new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity(),requested.groupId()),"RECONCILIATION"));
        return result;
    }
    Map<String,Object> buildDashboard(Scope s,Filter requested,boolean cny,String snapshotId,boolean live){
        // SQL facts are daily and currency-neutral. Display granularity is applied below.
        Filter all=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"","day",requested.groupId());
        var costReport=new FinanceCostReport(repo);
        var facts=loadDashboardFacts(s,all,live);
        var reportingMonths=rows(facts.get("reportingMonths"));
        String trendGranularity=reportingMonths.stream().anyMatch(r->Boolean.TRUE.equals(r.get("included")))?"month":requested.granularity();
        var buckets=rows(facts.get("buckets"));
        var allRanking=aggregate(buckets,"shop_id","kind");
        List<String> codes=allRanking.stream().map(r->text(r.get("currency"))).distinct().sorted().toList();
        String currency=requested.currency().isBlank()?(cny||codes.isEmpty()?"":codes.getFirst()):requested.currency();
        Filter f=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity(),requested.groupId());
        var ranking=allRanking.stream().filter(r->currency.isBlank()||currency.equals(text(r.get("currency")))).toList();
        List<Map<String,Object>> totals=nativeTotals(ranking);
        FinanceFx.Quote quote=cny?fx.quote(snapshotId):null;
        if(cny)totals=FinanceFx.groups(totals,quote,"kind");
        LinkedHashMap<String,Map<String,Object>> points=new LinkedHashMap<>();
        for(LocalDate d=f.start();!live&&!d.isAfter(f.end());d=d.plusDays(1)){
            String key=bucket(d,trendGranularity);points.computeIfAbsent(key,k->{var m=new LinkedHashMap<String,Object>();m.put("period",k);return m;});
        }
        var selected=buckets.stream().filter(r->currency.isBlank()||currency.equals(text(r.get("currency")))).toList();
        var daily=live?List.<Map<String,Object>>of():aggregate(selected,"record_date","kind");
        if(cny)daily=FinanceFx.groups(daily,quote,"record_date","kind");
        daily=FinanceProfitReport.withGross(daily,"record_date");
        var incompletePoints=new HashSet<String>();
        for(var row:daily){
            String key=bucket(LocalDate.parse(text(row.get("record_date"))),trendGranularity);
            var point=points.get(key);String kind=text(row.get("kind"));if(point==null)continue;
            BigDecimal value=Set.of("SETTLEMENT","BILL").contains(kind)?FinanceProfitReport.goods(row):decimal(row.get("amount"));if(value!=null)point.merge(kind,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            if(Set.of("SETTLEMENT","BILL").contains(kind)&&value==null)incompletePoints.add(key+"\0"+kind);
            BigDecimal gross=decimal(row.get("settled_gross_amount"));if(gross!=null)point.merge("SETTLEMENT_GROSS",gross,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            point.merge(kind+"_count",number(row.get("records")),(a,b)->(Long)a+(Long)b);
        }
        for(String missing:incompletePoints){var parts=missing.split("\0");var point=points.get(parts[0]);point.put(parts[1],null);if(parts[1].equals("SETTLEMENT"))point.put("SETTLEMENT_GROSS",null);}
        var out=new LinkedHashMap<String,Object>();
        out.put("reportingMonths",reportingMonths);out.put("trendGranularity",trendGranularity);
        if(!reportingMonths.isEmpty())out.put("reportingMonthNotice",FinanceReportingMonths.NOTE);
        out.put("filters",Map.of("start",f.start(),"end",f.end(),"shopId",f.shopId(),"platform",f.platform(),"currency",currency,"granularity",f.granularity(),"displayCurrency",cny?"CNY":currency,"groupId",requested.groupId()));
        if(cny)out.put("fx",quote.metadata());
        out.put("currencies",codes);out.put("totals",totals);out.put("trend",new ArrayList<>(points.values()));
        out.put("shops",shops(s));out.put("groups",groups(s));
        out.put("dateBasis",FinanceCalendar.contract());
        out.put("dateWindows",FinanceCalendar.windows(f,rows(out.get("shops"))));
        var fees=live?List.<Map<String,Object>>of():aggregate(selected.stream().filter(r->"FEE".equals(r.get("kind"))).toList(),"category","kind");
        var statuses=aggregate(selected.stream().filter(r->"ORDER".equals(r.get("kind"))).toList(),"status","kind");
        out.put("ranking",FinanceProfitReport.withGross(cny?FinanceFx.groups(ranking,quote,"shop_id","kind"):ranking,"shop_id"));
        out.put("fees",(cny?FinanceFx.groups(fees,quote,"category","kind"):fees).stream().filter(r->"FEE".equals(r.get("kind"))).toList());
        out.put("statuses",cny?FinanceFx.groups(statuses,quote,"status","kind"):statuses);out.put("sync",syncStates(s,requested));
        out.put("updatedAt",Instant.now().toString());
        var productCosts=costReport.dashboardSummary(s,f,rows(facts.get("orders")));
        productCosts.put("labelConfiguration",new FinanceOrderCharges(repo).labelConfiguration(s,rows(out.get("shops")),rows(productCosts.get("shops"))));
        out.put("productCosts",productCosts);
        FinanceFx.Quote profitQuote=quote;
        if(!cny&&!"CNY".equals(currency)&&(decimal(map(productCosts.get("total")).get("known_amount_cny")).signum()!=0||decimal(map(productCosts.get("total")).get("label_cost_cny")).signum()!=0||decimal(map(productCosts.get("total")).get("return_cost_cny")).signum()!=0)&&fx!=null){
            try{profitQuote=fx.quote(snapshotId);}catch(RuntimeException unavailable){/* Native-currency ledger stays available when a reference quote is unavailable. */}
        }
        out.put("profit",FinanceProfitReport.calculate(totals,productCosts,cny?"CNY":currency,profitQuote));
        out.put("basis","销售收入已扣除商家优惠和退款；平台佣金、物流、税费及调整另列，不重复扣减。"
                +(live?"实时订单按下单日显示；销售收入与成本按结算账单日期。":"日期筛选使用结算账单日期：TikTok 与 Excel 的 Statement date 口径一致，Shopee 使用 actual_payout_time。订单、关联退款和商品成本按首次结算账单日期归属，不限制下单月份。")
                +"已核对的 TikTok 账单日期优先于历史手工月份校正，原始平台时间保留。"
                +"经营汇总排除刷单；原始账单净额用于对账。广告及独立费用按发生日，待结算尚无结算日期。已结算不代表银行到账。");
        for(String key:List.of("_financeDataRevision","_financeFactsBuilt"))
            if(facts.containsKey(key))out.put(key,facts.get(key));
        return out;
    }
    private Map<String,Object> loadDashboardFacts(Scope s,Filter all,boolean live){
        var costReport=new FinanceCostReport(repo);
        var factsKey=new FinanceSnapshotCache.Key(s,all,false,"",live);
        var companyKey=new FinanceSnapshotCache.Key(s,new Filter(all.start(),all.end(),"","","","day"),false,"",live);
        // A shop-first visit stays a shop query. A previously loaded company snapshot can
        // serve its shops/groups without repeating the large ledger and cost scans.
        var wider=factsKey.equals(companyKey)?null:sharedSnapshots==null?dashboardFacts.find(companyKey):sharedSnapshots.findFacts(companyKey);
        // Unassigned expenses may span platforms, which the existing daily buckets
        // combine. Preserve the SQL platform filter rather than infer their platform.
        if(wider!=null&&!all.platform().isBlank()&&rows(wider.get("buckets")).stream()
                .anyMatch(row->"UNASSIGNED".equals(text(row.get("shop_id")))))wider=null;
        // The bounded cold read contains multiple queries; a per-query-sized total
        // transaction deadline previously discarded complete month computations.
        java.util.function.Supplier<Map<String,Object>> read=()->repo.readSnapshot(180,()->{
            var buckets=new ArrayList<>(repo.dashboardBuckets(s,all,live));
            var orders=costReport.dashboardFacts(s,all);
            if(!live){
                buckets.removeIf(row->Set.of("ORDER","REFUND").contains(text(row.get("kind"))));
                buckets.addAll(orders.stream().filter(row->number(row.get("records"))>0).toList());
                buckets.addAll(repo.settledOrderBuckets(s,all,"REFUND"));
            }
            return Map.of("buckets",dailyFactRows(buckets),"orders",dailyFactRows(orders),
                    "reportingMonths",live?List.of():new FinanceReportingMonths(repo).coverage(s,all));
        });
        var facts=wider==null?(sharedSnapshots==null?dashboardFacts.get(factsKey,read):sharedSnapshots.facts(factsKey,read)):
                narrowDashboardFacts(wider,all,shops(s),live?List.of():new FinanceReportingMonths(repo).coverage(s,all));
        return facts;
    }
    Map<String,Object> feeExportFacts(Scope s,Filter requested){
        Filter all=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"","day",requested.groupId());
        var facts=loadDashboardFacts(s,all,false);
        var fees=rows(facts.get("buckets")).stream().filter(row->"FEE".equals(row.get("kind")))
                .filter(row->requested.currency().isBlank()||requested.currency().equals(text(row.get("currency")))).toList();
        return Map.of("shops",shops(s).stream().filter(shop->FinanceGroups.matches(shop,requested)).toList(),
                "groups",groups(s),"fees",aggregate(fees,"shop_id","category"));
    }
    static List<Map<String,Object>> dailyFactRows(List<Map<String,Object>> rows){
        return rows.stream().map(row->{
            var copy=new LinkedHashMap<String,Object>(row);
            // JDBC calendar dates must survive Redis as ISO dates, not epoch milliseconds.
            if(row.get("record_date")!=null)copy.put("record_date",text(row.get("record_date")));
            return (Map<String,Object>)copy;
        }).toList();
    }
    static Map<String,Object> narrowDashboardFacts(Map<String,Object> facts,Filter filter,List<Map<String,Object>> shops,
                                                   List<Map<String,Object>> reportingMonths){
        var selected=shops.stream().filter(shop->FinanceGroups.matches(shop,filter)).map(shop->text(shop.get("id")))
                .collect(java.util.stream.Collectors.toSet());
        // Company queries allow unassigned independent expenses; named groups do not.
        if((filter.shopId().isBlank()||filter.shopId().equals("UNASSIGNED"))
                &&(filter.groupId().isBlank()||filter.groupId().equals("UNGROUPED"))&&filter.platform().isBlank())
            selected.add("UNASSIGNED");
        var narrowed=new LinkedHashMap<String,Object>(Map.of("buckets",rows(facts.get("buckets")).stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "orders",rows(facts.get("orders")).stream().filter(row->selected.contains(text(row.get("shop_id")))).toList(),
                "reportingMonths",reportingMonths));
        for(String key:List.of("_financeDataRevision","_financeFactsBuilt"))
            if(facts.containsKey(key))narrowed.put(key,facts.get(key));
        return narrowed;
    }
    static List<Map<String,Object>> nativeTotals(List<Map<String,Object>> ranking){
        return aggregate(ranking,"kind");
    }
    static List<Map<String,Object>> aggregate(List<Map<String,Object>> ranking,String... dimensions){
        var totals=new LinkedHashMap<List<Object>,Map<String,Object>>();
        for(var row:ranking){
            var key=new ArrayList<Object>();for(String d:dimensions)key.add(row.get(d));key.add(row.get("currency"));
            var total=totals.computeIfAbsent(key,k->{
                var m=new LinkedHashMap<String,Object>();for(String d:dimensions)m.put(d,row.get(d));m.put("currency",row.get("currency"));return m;
            });
            for(String field:List.of("amount","revenue","records","known_amounts","known_revenues","impressions","clicks","conversions")){
                BigDecimal value=decimal(row.get(field));
                if(value==null)total.putIfAbsent(field,null);
                else total.merge(field,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            }
        }
        return new ArrayList<>(totals.values());
    }
    public Map<String,Object> screen(Scope s,Filter requested,boolean cny,String snapshotId){
        if(!requested.start().equals(requested.end()))throw bad("实时大屏按单个业务日展示");
        var key=new FinanceSnapshotCache.Key(s,requested,cny,text(snapshotId),true);
        java.util.function.Supplier<Map<String,Object>> load=()->buildScreen(s,requested,cny,snapshotId);
        return withCalculations(s,requested,sharedSnapshots==null?snapshots.get(key,load):sharedSnapshots.get(key,load));
    }
    private Map<String,Object> buildScreen(Scope s,Filter requested,boolean cny,String snapshotId){
        // An open screen requests a fresher order snapshot. Never reset an active page,
        // historical checkpoint or failed request's backoff; polling stops when closed.
        repo.jdbc().update("""
            UPDATE finance_sync_states st JOIN finance_connections c ON c.id=st.shop_id
              AND c.organization_id=st.organization_id AND c.user_id=st.user_id
            SET st.next_at=LEAST(st.next_at,UTC_TIMESTAMP(6)),st.status='READY'
            WHERE st.organization_id=? AND c.enabled=TRUE AND c.status='ACTIVE'
              AND st.channel='ORDER_LIVE' AND st.status='SUCCESS' AND st.checkpoint_json IS NULL
              AND st.last_success_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 60 SECOND)
              AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6))
              AND (?='' OR c.id=?) AND (?='' OR c.platform=?)
              AND (?='' OR (?='UNGROUPED' AND c.group_id IS NULL) OR c.group_id=?)
            """,s.organizationId(),requested.shopId(),requested.shopId(),requested.platform(),requested.platform(),requested.groupId(),requested.groupId(),requested.groupId());
        var out=buildDashboard(s,requested,cny,snapshotId,true);
        var f=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),text(map(out.get("filters")).get("currency")),"day",requested.groupId());
        FinanceFx.Quote quote=cny?fx.quote(text(map(out.get("fx")).get("id"))):null;
        var shops=rows(out.get("shops"));var hourly=new LinkedHashMap<Integer,Map<String,Object>>();
        for(int h=0;h<24;h++)hourly.put(h,new LinkedHashMap<>(Map.of("hour",String.format("%02d:00",h))));
        for(var row:repo.hourlyOrders(s,f)){
            String zone=shops.stream().filter(shop->Objects.equals(shop.get("id"),row.get("shop_id"))).map(shop->text(shop.get("timezone"))).findFirst().orElse("UTC");
            int hour=Instant.ofEpochSecond(number(row.get("utc_hour"))*3600).atZone(ZoneId.of(zone)).getHour();
            var point=hourly.get(hour);BigDecimal amount=cny?quote.convert(row.get("amount"),text(row.get("currency"))):decimal(row.get("amount"));
            if(amount!=null)point.merge("amount",amount,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            point.merge("count",number(row.get("paid_count")),(a,b)->(Long)a+(Long)b);
        }
        out.put("hourly",new ArrayList<>(hourly.values()));
        var recent=repo.recentOrders(s,f);if(cny)fx.enrich(recent,quote);out.put("recent",recent);
        out.put("refreshSeconds",15);out.put("orderSyncTargetSeconds",60);
        return out;
    }
}
