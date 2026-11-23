package com.yingdao.aicommerce.finance;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
class FinanceReportReadiness {
    enum State { READY, PARTIAL, PROCESSING, ERROR }
    record Gap(State state,String source,String shop,String detail) {}
    record Status(State state,Instant checkedAt,Instant dataAsOf,String revision,int gapCount,List<Gap> gaps,Instant extractedAt) {
        Status(State state,Instant checkedAt,Instant dataAsOf,String revision,int gapCount,List<Gap> gaps){
            this(state,checkedAt,dataAsOf,revision,gapCount,gaps,null);
        }
        Status takenAt(Instant time){return new Status(state,checkedAt,dataAsOf,revision,gapCount,gaps,time);}
        boolean ready(){return state==State.READY;}
        List<List<String>> notes(FinanceReportSelection selection){
            var rows=new ArrayList<List<String>>();
            rows.add(List.of("项目","内容"));
            rows.add(List.of("数据状态",ready()?"可导出":"部分数据 · 仅供核对"));
            rows.add(List.of("报表",selection.type().label));
            rows.add(List.of("筛选日期（两端均含）",selection.allDates()?"全部收货日期":selection.filter().start()+" 至 "+selection.filter().end()));
            var f=selection.filter();
            rows.add(List.of("平台筛选",f.platform().isBlank()?"全部平台":f.platform()));
            rows.add(List.of("店铺筛选",f.shopId().isBlank()?"全部店铺":f.shopId()));
            rows.add(List.of("分组筛选",f.groupId().isBlank()?"全部分组":f.groupId()));
            rows.add(List.of("原币筛选",f.currency().isBlank()?"全部原币":f.currency()));
            rows.add(List.of("金额展示",selection.cny()?"人民币历史参考率":"报表原有币种口径"));
            rows.add(List.of("状态核对时间（北京时间）",FinanceCalendar.display(checkedAt)));
            if(extractedAt!=null)rows.add(List.of("本次报表取数时间（北京时间）",FinanceCalendar.display(extractedAt)));
            rows.add(List.of("来源共同核对截至（北京时间）",dataAsOf==null?"尚无法确认":FinanceCalendar.display(dataAsOf)));
            rows.add(List.of("核对版本",revision));
            for(var gap:gaps)rows.add(List.of(gap.source()+" · "+gap.shop(),gap.detail()));
            if(gapCount>gaps.size())rows.add(List.of("其他缺口",Integer.toString(gapCount-gaps.size())));
            return rows;
        }
    }
    private static final Map<String,String> CHANNEL_NAMES=Map.of("ORDER","历史订单","SETTLEMENT","结算","REFUND","平台退款","PENDING","预计待结算");
    private final FinanceRepository repo;
    private final FinanceService finance;
    private final FinanceQueryProjections projections;
    private final FinanceQianyiReturns returns;
    private record StatusRequest(Scope company,FinanceReportSelection selection,int deadlineSeconds){}
    private final FinanceQueryReadBatch<StatusRequest,Status> statusReads=new FinanceQueryReadBatch<>();

    FinanceReportReadiness(FinanceRepository repo,FinanceService finance,FinanceQueryProjections projections,FinanceQianyiReturns returns){
        this.repo=repo;this.finance=finance;this.projections=projections;this.returns=returns;
    }

    Status assessForDisplay(Scope company,FinanceReportSelection selection){
        if(FinanceLedgerIndexWindow.active())
            return displayDeferred(selection,"索引切换期间暂缓核对，稍后会自动重试");
        try{return assess(company,selection,3);}
        catch(org.springframework.dao.QueryTimeoutException|org.springframework.transaction.TransactionTimedOutException waiting){
            return displayDeferred(selection,"当前范围核对耗时较长，稍后将自动重试");
        }
    }

    private Status displayDeferred(FinanceReportSelection selection,String reason){
        var evidence=new Evidence();
        evidence.add(State.PROCESSING,"报表核对","",reason);
        return evidence.finish(selection);
    }

    Status assess(Scope company,FinanceReportSelection selection){
        return assess(company,selection,12);
    }

    private Status assess(Scope company,FinanceReportSelection selection,int seconds){
        return statusReads.read(new StatusRequest(company,selection,seconds),()->{
            Status result=repo.readSnapshot(seconds,()->assessInSnapshot(company,selection));
            requestUpdates(company,selection,result);
            return result;
        });
    }

    void requestUpdates(Scope company,FinanceReportSelection selection,Status status){
        if(status.gaps().stream().anyMatch(gap->gap.source().equals("报表更新")))
            projections.requestMissing(company,selection.filter());
    }

    Status assessInSnapshot(Scope company,FinanceReportSelection selected){
        return assessInSnapshot(company,selected,null);
    }

    Status assessInSnapshot(Scope company,FinanceReportSelection selected,Map<String,Object> exportedOrderEvidence){
        var result=new Evidence();
        var f=selected.filter();
        var shops=finance.shops(company).stream().filter(shop->FinanceGroups.matches(shop,f)).toList();
        if(shops.isEmpty())result.add(State.PARTIAL,"筛选范围","","当前条件下没有可核对的财务店铺");
        var channels=channels(selected);
        if(!channels.isEmpty()&&!shops.isEmpty())checkShopSync(company,selected,shops,channels,result);
        if(selected.type()==FinanceReportSelection.Type.ADVERTISING||("ALL".equals(selected.get("view")))||
                (selected.type()==FinanceReportSelection.Type.RECORDS&&Set.of("AD","AD_GMV").contains(selected.get("view"))))
            checkAds(company,f,shops,result,selected.type()==FinanceReportSelection.Type.RECORDS?selected.get("view"):"ALL");
        if(selected.type()==FinanceReportSelection.Type.ORDER_COST)checkCosts(company,selected,shops,result,exportedOrderEvidence);
        if(selected.type()==FinanceReportSelection.Type.QIANYI)checkQianyi(company,selected,shops,result);
        if(channels.contains("SETTLEMENT")&&selected.get("recordId").isBlank())checkBills(company,f,result);
        if(selected.type()==FinanceReportSelection.Type.RECORDS||selected.type()==FinanceReportSelection.Type.FEE_MATRIX)
            checkLedgerAmounts(company,selected,result);
        if(selected.cny())checkRates(company,selected,result);
        // Order cost and after-sales exports read committed detail rows directly.
        if(!Set.of(FinanceReportSelection.Type.QIANYI,FinanceReportSelection.Type.ORDER_COST).contains(selected.type())&&!shops.isEmpty()){
            var version=projections.version(company,f,2500);
            result.revisions.add(text(version.get("revision")));
            if(!"READY".equals(version.get("status"))&&overlapsReportRefresh(company,f,shops))
                result.add(State.PROCESSING,"报表更新","","当前店铺和日期范围的报表汇总正在更新");
        }
        return result.finish(selected);
    }

    private boolean overlapsReportRefresh(Scope company,Filter f,List<Map<String,Object>> shops){
        var ids=new ArrayList<>(shops.stream().map(shop->text(shop.get("id"))).toList());
        if(f.shopId().isBlank()&&Set.of("","UNGROUPED").contains(f.groupId()))ids.add("UNASSIGNED");
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        var pending=new ArrayList<Object>(List.of(company.organizationId(),f.start(),f.end()));
        pending.addAll(ids);
        boolean event=!repo.jdbc().queryForList("""
                SELECT event_id FROM finance_query_events WHERE organization_id=?
                AND (event_scope='ALL' OR record_date BETWEEN ? AND ?)
                AND (shop_id IS NULL OR shop_id='' OR shop_id IN (
                """+slots+")) LIMIT 1",pending.toArray()).isEmpty();
        if(event)return true;
        var dirty=new ArrayList<Object>(List.of(company.organizationId(),f.platform(),f.start(),f.end()));
        dirty.addAll(ids);
        return !repo.jdbc().queryForList("""
                SELECT 1 FROM finance_query_dirty_shop_days WHERE organization_id=? AND platform_filter=?
                AND record_date BETWEEN ? AND ? AND shop_id IN (
                """+slots+") LIMIT 1",dirty.toArray()).isEmpty();
    }

    private static Set<String> channels(FinanceReportSelection s){
        return switch(s.type()){
            case ADVERTISING,QIANYI->Set.of();
            case FEE_MATRIX->Set.of("SETTLEMENT");
            case ORDER_COST->s.get("recordId").isBlank()?Set.of("ORDER","SETTLEMENT"):Set.of();
            case RECORDS->switch(s.get("view")){
                case "AD","AD_GMV"->Set.of();
                case "ORDER"->"ALL".equals(s.get("orderScope"))?Set.of("ORDER"):Set.of("ORDER","SETTLEMENT");
                case "REFUND"->Set.of("REFUND","SETTLEMENT");
                case "PENDING"->Set.of("PENDING");
                case "ALL"->Set.of("ORDER","REFUND","SETTLEMENT","PENDING");
                default->Set.of("SETTLEMENT");
            };
        };
    }

    private void checkShopSync(Scope company,FinanceReportSelection selection,List<Map<String,Object>> shops,Set<String> channels,Evidence result){
        var states=new HashMap<List<String>,Map<String,Object>>();
        for(var row:repo.jdbc().queryForList("""
            SELECT shop_id,channel,status,last_success_at,coverage_start,checkpoint_json
            FROM finance_sync_states WHERE organization_id=? ORDER BY shop_id,channel
            """,company.organizationId()))states.put(List.of(text(row.get("shop_id")),text(row.get("channel"))),row);
        Filter f=selection.filter();
        boolean lookup=selection.type()==FinanceReportSelection.Type.RECORDS&&
            "ORDER".equals(selection.get("view"))&&"ALL".equals(selection.get("orderScope"))&&!selection.get("numbers").isBlank();
        for(var shop:shops)for(String channel:new TreeSet<>(channels)){
            if("PENDING".equals(channel)&&!"TIKTOK_SHOP".equals(shop.get("platform")))continue;
            String name=first(shop,"display_name","name"),source=CHANNEL_NAMES.get(channel);
            var state=states.getOrDefault(List.of(text(shop.get("id")),channel),Map.of());
            ZoneId zone=zone(shop);
            boolean enabled="ACTIVE".equals(shop.get("authorization_status"))&&flag(shop.get("enabled"));
            if(overlapsSync(state,f,zone,enabled)){
                result.add(State.PROCESSING,source,name,"正在刷新所选期间的数据");
                result.observed(state.get("last_success_at"));
            }else checkWindow(result,source,name,f,zone,state,"coverage_start",lookup,enabled);
        }
    }

    private boolean overlapsSync(Map<String,Object> state,Filter f,ZoneId zone,boolean enabled){
        if(zone==null||!enabled||state.get("checkpoint_json")==null
                ||syncState(text(state.get("status")),true)!=State.PROCESSING)return false;
        var cursor=repo.read(text(state.get("checkpoint_json")));
        long from=number(cursor.get("from")),until=number(cursor.get("until"));
        return from>0&&until>from&&from<f.end().plusDays(1).atStartOfDay(zone).toEpochSecond()
            &&until>f.start().atStartOfDay(zone).toEpochSecond();
    }

    private static void checkWindow(Evidence result,String source,String name,Filter f,ZoneId zone,Map<String,Object> row,
                                    String coverageKey,boolean allDates,boolean enabled){
        Object last=row.get("last_success_at");
        result.observed(last);
        String status=text(row.get("status"));
        if(zone==null){
            result.add(State.PARTIAL,source,name,"来源时区待核实");
            return;
        }
        Instant until=f.end().plusDays(1).atStartOfDay(zone).toInstant();
        String start=text(row.get(coverageKey));
        if(allDates){
            result.add(State.PARTIAL,source,name,"按完整编号跨日期查询，历史覆盖范围无法逐日确认");
            return;
        }
        if(last!=null&&!start.isBlank()&&f.start().toString().compareTo(start)>=0&&!instant(last).isBefore(until))return;
        if(last!=null&&!start.isBlank()&&f.start().toString().compareTo(start)<0)
            result.add(State.PARTIAL,source,name,"所选开始日早于已确认覆盖起点 "+start);
        else if(f.end().isAfter(LocalDate.now(zone)))
            result.add(State.PARTIAL,source,name,"所选结束日尚未到达");
        else
            result.add(syncState(status,enabled),source,name,last==null?"尚无完整同步记录":
                "尚未确认覆盖至 "+f.end()+"，最近成功同步 "+FinanceCalendar.display(instant(last)));
    }

    private void checkBills(Scope company,Filter filter,Evidence result){
        var q=repo.query(company,filter,"r");
        String where=q.where().replace(" AND r.status<>'DATE_PENDING'","");
        long missing=number(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records r WHERE "+where
            +" AND r.kind='BILL' AND r.source='API' AND r.status IN ('PARTIAL','DATE_PENDING')",Long.class,q.args().toArray()));
        if(missing>0)result.add(State.PARTIAL,"结算明细","",missing+" 张账单待补齐明细或确认日期");
    }

    private void checkLedgerAmounts(Scope company,FinanceReportSelection selected,Evidence result){
        var f=selected.filter();
        String kind=selected.type()==FinanceReportSelection.Type.FEE_MATRIX?"FEE":selected.get("view");
        if(Set.of("ORDER","REFUND","PENDING","RECONCILIATION").contains(kind))return;
        boolean advertisingOnly=FinanceController.advertisingOnly(selected.parameters(),kind);
        boolean all="ALL".equals(kind);
        boolean unrestrictedNumbers=FinanceRecordSearch.numbers(selected.get("numbers")).isEmpty();
        if(all&&unrestrictedNumbers){
            var gaps=repo.dashboardLedgerAmountGaps(company,f);
            if(gaps.isPresent()){
                addLedgerAmountGaps(result,gaps.get());
                return;
            }
        }
        if((all||"FEE".equals(kind))&&unrestrictedNumbers){
            var daily=repo.feeDays(company,f,advertisingOnly);
            if(daily.isPresent()){
                var missing=new TreeMap<String,Long>(String.CASE_INSENSITIVE_ORDER);
                for(var row:daily.get()){
                    long count=number(row.get("records"))-number(row.get("known_amounts"));
                    if(count>0)missing.merge(text(row.get("shop_id")),count,Long::sum);
                }
                if(!all||mergeOtherAmountGaps(company,f,missing)){
                    missing.forEach((shop,count)->result.add(State.PARTIAL,"报表金额",shop,count+" 条记录的金额尚未提供"));
                    return;
                }
            }
        }
        var q=repo.query(company,f,"r");
        q=FinanceRecordSearch.apply(q,"r",selected.get("numbers"),false);
        var args=new ArrayList<>(q.args());
        String condition="";
        if(!"ALL".equals(kind)){condition=" AND r.kind=?";args.add(kind);}
        if(advertisingOnly)condition+=" AND "+FinanceAdvertisingReport.ledgerPredicate("r");
        var gaps=repo.jdbc().queryForList("SELECT r.shop_id,COUNT(*) missing FROM finance_records r WHERE "
            +q.where()+condition+" AND r.amount IS NULL GROUP BY r.shop_id ORDER BY r.shop_id",args.toArray());
        addLedgerAmountGaps(result,gaps);
    }

    private static void addLedgerAmountGaps(Evidence result,List<Map<String,Object>> gaps){
        for(var row:gaps)result.add(State.PARTIAL,"报表金额",text(row.get("shop_id")),number(row.get("missing"))+" 条记录的金额尚未提供");
    }

    private boolean mergeOtherAmountGaps(Scope company,Filter filter,Map<String,Long> missing){
        var kinds=repo.nonFeeKinds(company);
        if(kinds.size()>64)return false;
        if(kinds.isEmpty())return true;
        var query=repo.query(company,filter,"r");
        var args=new ArrayList<Object>(query.args());
        args.addAll(kinds);
        String in=String.join(",",Collections.nCopies(kinds.size(),"?"));
        var rows=repo.jdbc().queryForList("""
            SELECT r.shop_id,COUNT(*) missing FROM finance_records r FORCE INDEX(idx_finance_company_kind_date) WHERE
            """+query.where()+" AND r.kind IN ("+in+") AND r.amount IS NULL GROUP BY r.shop_id",args.toArray());
        for(var row:rows)missing.merge(text(row.get("shop_id")),number(row.get("missing")),Long::sum);
        return true;
    }

    private void checkCosts(Scope company,FinanceReportSelection selection,List<Map<String,Object>> shops,Evidence result,
                            Map<String,Object> exportedOrderEvidence){
        if(new FinanceCostPriceScans(repo).pending(company,selection.filter()))
            result.add(State.PROCESSING,"商品成本","","所选店铺正在核对成本表变更");
        String record=selection.get("recordId");
        var costShops=record.isBlank()?shops.stream().map(shop->text(shop.get("id"))).toList():
            repo.jdbc().queryForList("SELECT r.shop_id FROM finance_records r WHERE r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),
                String.class,company.organizationId(),record);
        if(FinanceCostTargetScans.pending(repo,company,costShops))
            result.add(State.PROCESSING,"商品成本","","所选店铺正在核对人工成本变更");
        var data=exportedOrderEvidence==null?costEvidence(repo,company,selection):exportedOrderEvidence;
        long queued=number(data.get("queued")),review=number(data.get("price_review")),missing=number(data.get("missing"))-review;
        if(data.get("last_cost")!=null)result.revisions.add(text(data.get("last_cost")));
        if(!record.isBlank()&&number(data.get("total"))==0)
            result.add(State.PARTIAL,"订单成本","","没有取得该记录关联的已结算订单");
        if(queued>0)result.add(State.PROCESSING,"订单成本","",queued+" 笔订单正在计算或等待重算");
        if(missing>0)result.add(State.PARTIAL,"订单成本","",missing+" 笔订单成本待补齐");
        if(review>0)result.add(State.PARTIAL,"订单成本","",review+" 笔订单的零单价待核对");
        long labelQueued=number(data.get("label_queued")),labelPending=number(data.get("label_pending"));
        if(labelQueued>0)result.add(State.PROCESSING,"贴单成本","",labelQueued+" 笔订单的贴单成本正在更新");
        if(labelPending>0)result.add(State.PARTIAL,"贴单成本","",labelPending+" 项贴单费用待核对");
    }

    static Map<String,Object> costEvidence(FinanceRepository repo,Scope company,FinanceReportSelection selection){
        String kind=selection.get("kind"),record=selection.get("recordId"),numbers=selection.get("numbers");
        var members=Optional.<FinanceRepository.Query>empty();
        if(record.isBlank()&&numbers.isBlank()&&"SETTLED_ORDER".equals(kind))
            members=repo.dashboardSettledMembers(company,selection.filter());
        var q=members.orElseGet(()->{
            var nativeScope=record.isBlank()?repo.query(company,selection.filter(),"r"):
                new FinanceRepository.Query("r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),
                    List.of(company.organizationId(),record));
            var search=FinanceRecordSearch.orders(nativeScope,Set.of("BILL","RECONCILIATION").contains(kind)?"o.order_id":"r.order_id",numbers);
            return new FinanceRepository.Query(FinanceCostReport.related(kind)+search.where(),search.args());
        });
        boolean covered="ALL".equals(FinanceCostReport.costStatus(selection.get("costStatus")));
        String hint=covered?"/*+ SET_VAR(optimizer_switch='use_invisible_indexes=on') */ ":"";
        String review=covered?"COALESCE(c.zero_price_review,0)":FinanceCostReport.zeroPriceReview("c");
        String costIndex=covered?" FORCE INDEX(idx_finance_cost_readiness)":"";
        return repo.jdbc().queryForMap("""
            SELECT %sCOUNT(*) total,SUM(COALESCE(j.priority<2,0)) queued,
              SUM(COALESCE(j.priority>=2,1) AND (c.status IS NULL OR c.status NOT IN ('READY','ESTIMATED','EXCLUDED'))) missing,
              SUM(CASE WHEN COALESCE(j.priority>=2,1) THEN %s ELSE 0 END) price_review,
              SUM(COALESCE(j.priority=2,0)) label_queued,
              SUM(COALESCE(lc.pending_count,0)) label_pending, MAX(c.updated_at) last_cost
            FROM (
            """.formatted(hint,review)+q.where()+") x"
            +" LEFT JOIN finance_order_cost_jobs j ON j.order_record_id=x.order_record_id AND j.organization_id=x.organization_id"
            +" LEFT JOIN finance_order_costs c"+costIndex+" ON c.order_record_id=x.order_record_id AND c.organization_id=x.organization_id"
            +FinanceLabelCosts.join("x.organization_id","x.shop_id","x.reference")
            +FinanceCostReport.costStatusWhere(selection.get("costStatus")),q.args().toArray());
    }

    private void checkQianyi(Scope company,FinanceReportSelection selection,List<Map<String,Object>> shops,Evidence result){
        var states=new HashMap<String,Map<String,Object>>();
        for(var row:repo.jdbc().queryForList("""
                SELECT shop_id,status,enabled,sync_start,last_success_at FROM finance_qianyi_connections WHERE organization_id=?
                """,company.organizationId()))states.put(text(row.get("shop_id")),row);
        for(var shop:shops){
            var row=states.getOrDefault(text(shop.get("id")),Map.of());
            checkWindow(result,"千易售后",first(shop,"display_name","name"),selection.filter(),FinanceCalendar.DISPLAY_ZONE,
                row,"sync_start",selection.allDates(),flag(row.get("enabled")));
        }
        var q=returns.query(company,selection.parameters());
        var costs=new FinanceQianyiReturnCostSummary(repo,company.organizationId()).totals(q);
        long pending=number(costs.get("pendingRecords"));
        if(pending>0)result.add(State.PARTIAL,"退货成本","",pending+" 笔售后单的退货成本待补齐");
    }

    private void checkAds(Scope company,Filter f,List<Map<String,Object>> shops,Evidence result,String kind){
        if(!"SHOPEE".equals(f.platform())&&!"AD_GMV".equals(kind))checkBasicAds(company,f,shops,result);
        if("AD".equals(kind))return;
        var ids=new HashSet<String>();
        shops.forEach(shop->ids.add(text(shop.get("id"))));
        long days=ChronoUnit.DAYS.between(f.start(),f.end())+1;
        var links=repo.jdbc().queryForList("""
            SELECT d.shop_id,d.advertiser_id,d.store_id,a.name,a.enabled,s.status,
              COUNT(c.record_date) covered,MIN(c.observed_at) oldest,MAX(c.observed_at) latest
            FROM finance_ad_stores d JOIN finance_ad_accounts a USING (organization_id,advertiser_id)
            LEFT JOIN finance_ad_streams s ON s.organization_id=d.organization_id AND s.advertiser_id=d.advertiser_id
              AND s.store_id=d.store_id AND s.channel='GMV'
            LEFT JOIN finance_ad_coverage c ON c.organization_id=d.organization_id AND c.advertiser_id=d.advertiser_id
              AND c.store_id=d.store_id AND c.channel='GMV' AND c.record_date BETWEEN ? AND ?
            WHERE d.organization_id=? AND d.active=TRUE AND d.report_eligible=TRUE AND d.match_status='MATCHED'
              AND (?='' OR a.currency=?)
            GROUP BY d.shop_id,d.advertiser_id,d.store_id,a.name,a.enabled,s.status ORDER BY d.shop_id,d.advertiser_id
            """,f.start(),f.end(),company.organizationId(),f.currency(),f.currency());
        var stores=new HashSet<String>();
        if(!"SHOPEE".equals(f.platform()))for(var row:links){
            String shop=text(row.get("shop_id"));
            if(!ids.contains(shop))continue;
            stores.add(shop);
            result.observed(row.get("oldest"));
            if(row.get("latest")!=null)result.revisions.add(text(row.get("latest")));
            long missing=days-number(row.get("covered"));
            if(missing>0)result.add(f.end().isAfter(LocalDate.now(FinanceCalendar.DISPLAY_ZONE))?State.PARTIAL:
                syncState(text(row.get("status")),flag(row.get("enabled"))),"TikTok GMV Max",
                shopName(shops,shop),first(row,"name","advertiser_id")+" 缺少 "+missing+" 天报表覆盖");
        }
        for(var shop:shops){
            String name=first(shop,"display_name","name");
            if("TIKTOK_SHOP".equals(shop.get("platform"))&&!stores.contains(text(shop.get("id"))))
                result.add(State.PARTIAL,"TikTok 广告",name,"当前筛选未取得可核对的店铺广告覆盖");
            if("SHOPEE".equals(shop.get("platform")))checkShopeeAds(company,f,shop,days,result);
        }
    }

    private void checkBasicAds(Scope company,Filter f,List<Map<String,Object>> shops,Evidence result){
        var expected=ChronoUnit.DAYS.between(f.start(),f.end())+1;
        var scoped=new HashSet<String>();
        shops.stream().filter(s->"TIKTOK_SHOP".equals(s.get("platform"))).forEach(s->scoped.add(text(s.get("id"))));
        for(var row:repo.jdbc().queryForList("""
            SELECT a.shop_id,a.name,a.enabled,s.status,COUNT(c.record_date) covered,
              MIN(c.observed_at) oldest,MAX(c.observed_at) latest
            FROM finance_ad_accounts a
            LEFT JOIN finance_ad_streams s ON s.organization_id=a.organization_id AND s.advertiser_id=a.advertiser_id
              AND s.channel='BASIC' AND s.store_id=''
            LEFT JOIN finance_ad_coverage c ON c.organization_id=a.organization_id AND c.advertiser_id=a.advertiser_id
              AND c.channel='BASIC' AND c.store_id='' AND c.record_date BETWEEN ? AND ?
            WHERE a.organization_id=? AND (?='' OR a.currency=?)
            GROUP BY a.advertiser_id,a.shop_id,a.name,a.enabled,s.status ORDER BY a.advertiser_id
            """,f.start(),f.end(),company.organizationId(),f.currency(),f.currency())){
            String shop=text(row.get("shop_id"));
            if(!scoped.contains(shop)&&!(shop.isBlank()&&f.shopId().isBlank()&&Set.of("","UNGROUPED").contains(f.groupId())))continue;
            result.observed(row.get("oldest"));
            if(row.get("latest")!=null)result.revisions.add(text(row.get("latest")));
            long missing=expected-number(row.get("covered"));
            if(missing>0)result.add(syncState(text(row.get("status")),flag(row.get("enabled"))),"TikTok 普通广告",
                shopName(shops,shop),text(row.get("name"))+" 缺少 "+missing+" 天账户日报覆盖");
        }
    }

    private void checkShopeeAds(Scope company,Filter f,Map<String,Object> shop,long days,Evidence result){
        var found=repo.jdbc().queryForList("""
            SELECT a.enabled,a.status,c.status gms_status,COUNT(d.record_date) cpc_days,
              COUNT(g.record_date) gms_days, MIN(d.observed_at) oldest,MAX(d.observed_at) latest,
              MIN(g.observed_at) gms_oldest,MAX(g.observed_at) gms_latest
            FROM finance_shopee_ad_grants a
            LEFT JOIN finance_shopee_gms_sync c USING(organization_id,external_shop_id)
            LEFT JOIN finance_shopee_ad_daily d ON d.organization_id=a.organization_id
              AND d.external_shop_id=a.external_shop_id AND d.record_date BETWEEN ? AND ?
            LEFT JOIN finance_shopee_gms_daily g ON g.organization_id=d.organization_id AND g.external_shop_id=d.external_shop_id
              AND g.record_date=d.record_date AND g.currency=d.currency AND g.campaign_revision=c.campaign_revision
            WHERE a.organization_id=? AND a.external_shop_id=? AND a.status<>'REMOVED' AND (?='' OR a.currency=?)
            GROUP BY a.enabled,a.status,c.status
            """,f.start(),f.end(),company.organizationId(),shop.get("external_shop_id"),f.currency(),f.currency());
        String name=first(shop,"display_name","name");
        if(found.isEmpty()){
            result.add(State.PARTIAL,"Shopee 广告",name,"缺少当前币种下的广告报表覆盖");
            return;
        }
        var row=found.getFirst();result.observed(row.get("oldest"));result.observed(row.get("gms_oldest"));
        for(String key:List.of("latest","gms_latest"))if(row.get(key)!=null)result.revisions.add(text(row.get(key)));
        long cpc=days-number(row.get("cpc_days")),gms=number(row.get("cpc_days"))-number(row.get("gms_days"));
        if(cpc>0)result.add(syncState(text(row.get("status")),flag(row.get("enabled"))),"Shopee CPC",name,"缺少 "+cpc+" 天全店 CPC 日报");
        if(gms>0)result.add(syncState(text(row.get("gms_status")),flag(row.get("enabled"))),"Shopee GMS",name,"缺少 "+gms+" 天同币种 GMS 日报");
    }

    private void checkRates(Scope company,FinanceReportSelection selection,Evidence result){
        // Only inspect stored FX evidence here. Status polling must not start an upstream rate pull.
        String id=selection.get("fxSnapshot");
        List<Map<String,Object>> snapshots=id.isBlank()?List.of():repo.jdbc().queryForList(
            "SELECT payload_json FROM finance_fx_historical_snapshots WHERE id=? AND provider='ECB'",id);
        var amounts=repo.readSnapshot(12,()->rateRequirements(company,selection,selection.filter(),snapshots.isEmpty()));
        if(amounts.isEmpty())return;
        if(id.isBlank()){
            result.add(State.PARTIAL,"历史汇率","","尚未确认本次查询使用的历史汇率快照，刷新报表后可重新核对");
            return;
        }
        if(snapshots.isEmpty()){
            result.add(State.PARTIAL,"历史汇率","","本次查询的历史汇率快照无可用记录");
            return;
        }
        Map<String,Object> payload=repo.read(text(snapshots.getFirst().get("payload_json")));
        result.revisions.add(id);
        int missing=0;
        for(var row:amounts){
            var rates=map(map(map(payload.get("days")).get(text(row.get("record_date")))).get("rates"));
            if(decimal(rates.get(text(row.get("currency"))))==null)missing++;
        }
        if(missing>0)result.add(State.PARTIAL,"历史汇率","",missing+" 个业务日及币种组合未核实可用历史汇率");
    }

    private List<Map<String,Object>> rateRequirements(Scope company,FinanceReportSelection selection,Filter f,
                                                      boolean existenceOnly){
        String kind=selection.get("view");
        if(Set.of("ORDER","REFUND").contains(kind)&&!"ALL".equals(selection.get("orderScope"))){
            var base=repo.query(company,f,"r");
            var search=FinanceRecordSearch.apply(base,"o",selection.get("numbers"),false);
            String select=existenceOnly?"SELECT 1 ":"SELECT o.record_date,o.currency ";
            String end=existenceOnly?" LIMIT 1":" GROUP BY o.record_date,o.currency";
            return repo.jdbc().queryForList(select+repo.settledOrderRelation(company,f,kind,false)
                +search.where().substring(base.where().length())+" AND o.currency<>'CNY' AND (o.amount<>0 OR o.revenue<>0)"
                +end,search.args().toArray());
        }
        boolean allOrderLookup="ORDER".equals(kind)&&"ALL".equals(selection.get("orderScope"))&&!selection.get("numbers").isBlank();
        boolean createdOrders="ORDER".equals(kind)&&"ALL".equals(selection.get("orderScope"));
        var q=repo.exportRateScope(company,f,!createdOrders,!allOrderLookup);
        q=FinanceRecordSearch.apply(q,"r",selection.get("numbers"),false);
        var args=new ArrayList<>(q.args());
        String clause="";
        if(selection.type()==FinanceReportSelection.Type.ADVERTISING||Set.of("AD","AD_GMV").contains(kind))clause=" AND r.kind IN ('AD','AD_GMV')";
        else if(!kind.isBlank()&&!Set.of("ALL","RECONCILIATION").contains(kind)){clause=" AND r.kind=?";args.add(kind);}
        else if("RECONCILIATION".equals(kind))clause=" AND r.kind IN ('BILL','SETTLEMENT')";
        boolean advertisingOnly=FinanceController.advertisingOnly(selection.parameters(),kind);
        if(advertisingOnly)clause+=" AND "+FinanceAdvertisingReport.ledgerPredicate("r");
        // Without a saved rate snapshot, any matching non-zero foreign value is sufficient.
        boolean ordinary=!existenceOnly&&!containsCurrencyDateOverrides(company,f,!allOrderLookup);
        List<Map<String,Object>> feeRates=List.of();
        String ledgerFilter=clause;
        var ledgerArgs=new ArrayList<>(args);
        boolean allKinds=clause.isBlank();
        boolean splitFees=false;
        if(ordinary&&selection.type()==FinanceReportSelection.Type.RECORDS
                &&("FEE".equals(kind)||allKinds)&&selection.get("numbers").isBlank()){
            var days=feeRateRequirements(company,f,advertisingOnly);
            if(days.isPresent()){
                if("FEE".equals(kind))return days.get();
                var nonFeeKinds=repo.nonFeeKinds(company);
                if(nonFeeKinds.isEmpty())return days.get();
                if(nonFeeKinds.size()<=64){
                    feeRates=days.get();
                    ledgerFilter+=" AND r.kind IN ("+String.join(",",Collections.nCopies(nonFeeKinds.size(),"?"))+")";
                    ledgerArgs.addAll(nonFeeKinds);
                    splitFees=true;
                }
            }
        }
        var result=new ArrayList<>(ledgerRateRequirements(selection,q,ledgerFilter,ledgerArgs,ordinary,existenceOnly));
        if(splitFees){
            // Native SQL groups using its collation. Do not merge legacy currency spellings in Java.
            if(result.stream().anyMatch(row->!canonicalCurrency(row.get("currency"))))
                return ledgerRateRequirements(selection,q,clause,args,true,false);
            result.addAll(feeRates);
        }
        if(selection.type()==FinanceReportSelection.Type.ADVERTISING&&(!existenceOnly||result.isEmpty())){
            String shopeeSelect=existenceOnly?"SELECT 1":"SELECT DISTINCT a.record_date,a.currency";
            result.addAll(repo.jdbc().queryForList(shopeeSelect+"""
                 FROM finance_shopee_ad_daily a
                JOIN finance_connections c ON c.organization_id=a.organization_id AND c.external_shop_id=a.external_shop_id
                WHERE a.organization_id=? AND c.platform='SHOPEE' AND c.status<>'REMOVED'
                  AND (?='' OR ?='SHOPEE') AND (?='' OR c.id=?)
                  AND (?='' OR (?='UNGROUPED' AND c.group_id IS NULL) OR c.group_id=?)
                  AND (?='' OR a.currency=?) AND a.record_date BETWEEN ? AND ? AND a.amount<>0
                """+(existenceOnly?" LIMIT 1":""),company.organizationId(),f.platform(),f.platform(),f.shopId(),f.shopId(),
                f.groupId(),f.groupId(),f.groupId(),f.currency(),f.currency(),f.start(),f.end()));
        }
        return result.stream().distinct().toList();
    }

    private List<Map<String,Object>> ledgerRateRequirements(FinanceReportSelection selection,FinanceRepository.Query query,
            String clause,List<Object> args,boolean ordinary,boolean existenceOnly){
        String date="CASE WHEN JSON_EXTRACT(r.raw_json,'$._finance_reporting_month') IS NOT NULL THEN "+
            FinanceFxDates.trustedDate("r")+" ELSE r.record_date END";
        String selectedDate=ordinary?"CAST(r.record_date AS CHAR CHARACTER SET utf8mb4)":date;
        String groupDate=ordinary?"r.record_date":date;
        String select=existenceOnly?"SELECT 1":"SELECT "+selectedDate+" record_date,r.currency";
        String end=existenceOnly?" LIMIT 1":" GROUP BY "+groupDate+",r.currency";
        String source="finance_records r";
        if(ordinary&&selection.filter().shopId().isBlank()&&selection.get("numbers").isBlank())
            source+=clause.isBlank()?" FORCE INDEX(idx_finance_company_dashboard)":" FORCE INDEX(idx_finance_company_kind_date)";
        return repo.jdbc().queryForList(select+" FROM "+source+" WHERE "+
            query.where()+clause+" AND r.currency<>'CNY' AND (r.amount<>0 OR r.revenue<>0)"+end,args.toArray());
    }

    private static boolean canonicalCurrency(Object value){
        return value instanceof String code&&code.matches("[A-Z]{3}");
    }

    private record CurrencyDay(String day,String currency){}

    private Optional<List<Map<String,Object>>> feeRateRequirements(Scope scope,Filter filter,boolean advertisingOnly){
        var fees=repo.feeDays(scope,filter,advertisingOnly);
        if(fees.isEmpty())return Optional.empty();
        var possible=new LinkedHashMap<CurrencyDay,Boolean>();
        for(var fee:fees.get()){
            if(!canonicalCurrency(fee.get("currency")))return Optional.empty();
            String code=text(fee.get("currency"));
            if("CNY".equals(code)||number(fee.get("known_amounts"))==0&&number(fee.get("known_revenues"))==0)continue;
            var amount=decimal(fee.get("amount"));
            var revenue=decimal(fee.get("revenue"));
            boolean proven=amount!=null&&amount.signum()!=0||revenue!=null&&revenue.signum()!=0;
            var key=new CurrencyDay(text(fee.get("record_date")),code);
            possible.merge(key,proven,Boolean::logicalOr);
        }
        // A zero sum may contain offsetting amounts, requiring an exact ledger read.
        if(possible.containsValue(false))return Optional.empty();
        return Optional.of(possible.keySet().stream().map(day->Map.<String,Object>of(
            "record_date",day.day(),"currency",day.currency())).toList());
    }

    private boolean containsCurrencyDateOverrides(Scope scope,Filter filter,boolean restrictDates){
        String bounded=restrictDates?" AND record_date BETWEEN ? AND ?":"";
        Object[] args=restrictDates?new Object[]{scope.organizationId(),filter.start(),filter.end()}
            :new Object[]{scope.organizationId()};
        return !repo.jdbc().queryForList("""
            SELECT /*+ SET_VAR(optimizer_switch='use_invisible_indexes=on') */ 1
            FROM finance_records FORCE INDEX(idx_finance_fx_month_corrected)
            WHERE organization_id=? AND fx_month_corrected=TRUE
            """+bounded+" LIMIT 1",args).isEmpty();
    }

    private static ZoneId zone(Map<String,Object> shop){
        try{return ZoneId.of(text(shop.get("timezone")));}catch(DateTimeException invalid){return null;}
    }
    private static boolean flag(Object value){return Boolean.TRUE.equals(value)||"1".equals(text(value));}
    private static String shopName(List<Map<String,Object>> shops,String id){
        return shops.stream().filter(s->id.equals(s.get("id"))).findFirst().map(s->first(s,"display_name","name")).orElse("");
    }
    private static State syncState(String status,boolean enabled){
        if(Set.of("ERROR","TOKEN_EXPIRED","NEEDS_AUTHORIZATION","NEEDS_PERMISSION","NEEDS_REAUTHORIZATION").contains(status))return State.ERROR;
        return enabled&&Set.of("READY","RUNNING","SYNCING","RATE_LIMITED").contains(status)?State.PROCESSING:State.PARTIAL;
    }
    private static final class Evidence {
        private static final int MAX_GAPS=40;
        private record GapGroup(String source,State state){}
        final List<Gap> gaps=new ArrayList<>();
        final Map<GapGroup,Gap> firstByGroup=new LinkedHashMap<>();
        final List<String> revisions=new ArrayList<>();
        Instant oldest;int count;State state=State.READY;
        void observed(Object value){
            if(value==null)return;
            Instant time=instant(value);
            if(oldest==null||time.isBefore(oldest))oldest=time;
            revisions.add(time.toString());
        }
        void add(State value,String source,String shop,String detail){
            count++;
            var gap=new Gap(value,source,shop,detail);
            firstByGroup.putIfAbsent(new GapGroup(source,value),gap);
            if(gaps.size()<MAX_GAPS)gaps.add(gap);
            if(value.ordinal()>state.ordinal())state=value;
        }
        Status finish(FinanceReportSelection selection){
            Collections.sort(revisions);
            // A long list from one source must not hide a different reason for waiting.
            var displayed=firstByGroup.values().stream().anyMatch(gap->!gaps.contains(gap))
                ?java.util.stream.Stream.concat(firstByGroup.values().stream(),gaps.stream()
                    .filter(gap->firstByGroup.get(new GapGroup(gap.source(),gap.state()))!=gap)).limit(MAX_GAPS).toList()
                :List.copyOf(gaps);
            return new Status(state,Instant.now(),oldest,hash(selection.fingerprint()+revisions+displayed+count),count,displayed);
        }
    }
}
