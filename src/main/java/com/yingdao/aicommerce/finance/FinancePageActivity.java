package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Reads work already scheduled for a page without starting a sync or rebuilding a report. */
@Service
final class FinancePageActivity {
    enum Page { OVERVIEW, LIVE, SETTLEMENTS, RECONCILIATION, ORDERS, QIANYI, FEES, COSTS, LABELS, CATALOG, ADS, SHOPS, BRUSH }
    enum Category { SYNC, PROCESSING }
    record Request(String page, Map<String,String> selection) {}
    record Activity(Category category, String label, List<String> details, Instant retryAt, boolean affectsExport) {}
    private final FinanceController guard;
    private final FinanceService finance;
    private final FinanceRepository repo;

    FinancePageActivity(FinanceController guard,FinanceService finance,FinanceRepository repo){
        this.guard=guard;this.finance=finance;this.repo=repo;
    }

    List<Activity> read(Scope scope,Request request){
        final Page page;
        try{page=Page.valueOf(text(request.page()));}
        catch(IllegalArgumentException invalid){throw bad("任务页面不正确");}
        if(request.selection()==null||request.selection().size()>48)throw bad("任务筛选不完整");
        var selection=new HashMap<>(request.selection());
        selection.values().removeIf(Objects::isNull);
        boolean unbounded=page==Page.QIANYI&&text(selection.get("start")).isBlank()&&text(selection.get("end")).isBlank();
        var dates=new HashMap<>(selection);
        if(unbounded){dates.remove("start");dates.remove("end");}
        var filter=guard.filter(dates);
        return repo.readSnapshot(5,()->readScoped(scope,page,filter,selection,unbounded));
    }

    private List<Activity> readScoped(Scope scope,Page page,Filter filter,Map<String,String> selection,boolean unbounded){
        var shops=new LinkedHashMap<String,Map<String,Object>>();
        for(var shop:finance.shops(scope))if(FinanceGroups.matches(shop,filter))shops.put(text(shop.get("id")),shop);
        var groups=new Groups();
        String kind=text(selection.get("kind")),numbers=text(selection.get("numbers"));
        var channels=switch(page){
            case OVERVIEW->Set.of("ORDER","REFUND","SETTLEMENT","PENDING");
            case SHOPS->Set.of("ORDER","ORDER_LIVE","ORDER_REPAIR","REFUND","SETTLEMENT","PENDING");
            case LIVE->Set.of("ORDER_LIVE");
            case SETTLEMENTS->"PENDING".equals(kind)?Set.of("PENDING"):Set.of("SETTLEMENT");
            case RECONCILIATION,FEES->Set.of("SETTLEMENT");
            case COSTS->text(selection.get("recordId")).isBlank()?Set.of("ORDER","SETTLEMENT"):Set.<String>of();
            case ORDERS->"SETTLED".equals(selection.get("orderScope"))?Set.of("ORDER","SETTLEMENT"):Set.of("ORDER");
            default->Set.<String>of();
        };
        if(!shops.isEmpty()){
            if(!channels.isEmpty()&&numbers.isBlank())shopSync(scope,page,filter,shops,channels,groups);
            if(page==Page.QIANYI)qianyi(scope,filter,shops,selection,unbounded,groups);
            if(page==Page.COSTS||page==Page.OVERVIEW)costs(scope,filter,selection,groups);
            if(Set.of(Page.CATALOG,Page.COSTS,Page.OVERVIEW,Page.SHOPS).contains(page)&&numbers.isBlank()
                    &&text(selection.get("recordId")).isBlank())rebuilds(scope,shops,groups);
            if(Set.of(Page.OVERVIEW,Page.SETTLEMENTS,Page.RECONCILIATION,Page.FEES,Page.ADS).contains(page)
                    &&!"PENDING".equals(kind)&&numbers.isBlank())projections(scope,filter,shops,groups);
        }
        if(page==Page.ADS||page==Page.OVERVIEW||page==Page.SHOPS)ads(scope,page,filter,shops,groups);
        if(page==Page.LABELS)labels(scope,filter,shops,selection,groups);
        if(page==Page.SHOPS)deletions(scope,filter,groups);
        return groups.result();
    }

    private void shopSync(Scope scope,Page page,Filter f,Map<String,Map<String,Object>> shops,Set<String> channels,Groups groups){
        for(var row:repo.jdbc().queryForList("""
            SELECT s.shop_id,s.channel,s.status,s.checkpoint_json,s.priority_requested_at,s.next_at,
              s.lease_until>UTC_TIMESTAMP(6) leased
            FROM finance_sync_states s JOIN finance_connections c ON c.organization_id=s.organization_id AND c.id=s.shop_id AND c.user_id=s.user_id
            WHERE s.organization_id=? AND s.status IN ('READY','RUNNING','SYNCING','ERROR','RATE_LIMITED')
            """,scope.organizationId())){
            var shop=shops.get(text(row.get("shop_id")));String channel=text(row.get("channel"));
            if(shop==null||!channels.contains(channel)||!enabled(shop)||!currency(f,shop))continue;
            var checkpoint=checkpoint(row);boolean exact=epochOverlap(f,checkpoint,text(shop.get("timezone")));
            if(!exact&&(page!=Page.SHOPS||checkpoint.isEmpty()&&row.get("priority_requested_at")==null&&!flag(row.get("leased"))))continue;
            String label=switch(channel){
                case "ORDER_LIVE"->"实时订单采集";case "ORDER_REPAIR"->"历史订单状态修复";
                case "ORDER"->"订单同步";case "REFUND"->"退款同步";
                case "PENDING"->"待结算数据同步";default->"结算账单与明细同步";
            };
            track(groups,label,row,checkpoint,exact,page!=Page.SHOPS&&page!=Page.LIVE);
        }
    }

    private void ads(Scope scope,Page page,Filter f,Map<String,Map<String,Object>> shops,Groups groups){
        boolean management=page==Page.SHOPS;
        if(!"SHOPEE".equals(f.platform()))for(var row:repo.jdbc().queryForList("""
            SELECT t.channel,t.status,t.checkpoint_json,t.next_at,FALSE leased,
              CASE WHEN t.channel='BASIC' THEN a.shop_id ELSE d.shop_id END shop_id,
              a.currency
            FROM finance_ad_streams t JOIN finance_ad_accounts a USING(organization_id,advertiser_id)
            LEFT JOIN finance_ad_stores d ON d.organization_id=t.organization_id AND d.advertiser_id=t.advertiser_id
              AND d.store_id=t.store_id AND d.active=TRUE AND d.report_eligible=TRUE AND d.match_status='MATCHED'
            WHERE t.organization_id=? AND a.enabled=TRUE
              AND (t.channel='BASIC' OR d.shop_id IS NOT NULL)
              AND t.status IN ('READY','RUNNING','SYNCING','ERROR','RATE_LIMITED')
            """,scope.organizationId())){
            String id=text(row.get("shop_id"));
            if(!shops.containsKey(id)&&!(id.isBlank()&&f.shopId().isBlank()&&Set.of("","UNGROUPED").contains(f.groupId())))continue;
            if(!currency(f,row))continue;
            var cp=checkpoint(row);boolean exact=dateOverlap(f,cp);
            if(exact||management&&!cp.isEmpty())track(groups,"BASIC".equals(row.get("channel"))?"TikTok 普通广告日报":"TikTok GMV Max 日报",row,cp,exact,false);
        }
        if(!"TIKTOK_SHOP".equals(f.platform())){
            var external=new HashSet<String>();
            for(var shop:shops.values())if("SHOPEE".equals(shop.get("platform")))external.add(text(shop.get("external_shop_id")));
            if(external.isEmpty())return;
            for(var row:repo.jdbc().queryForList("""
                SELECT external_shop_id,currency,status,checkpoint_json,next_at,
                  lease_until>UTC_TIMESTAMP(6) leased,'Shopee CPC 日报' label
                FROM finance_shopee_ad_grants WHERE organization_id=? AND enabled=TRUE
                UNION ALL
                SELECT s.external_shop_id,g.currency,s.status,s.checkpoint_json,s.next_at,
                  FALSE leased,'Shopee GMS 日报' label
                FROM finance_shopee_gms_sync s JOIN finance_shopee_ad_grants g USING(organization_id,external_shop_id)
                WHERE s.organization_id=? AND g.enabled=TRUE AND g.status<>'REMOVED'
                """,scope.organizationId(),scope.organizationId())){
                var cp=checkpoint(row);boolean exact=dateOverlap(f,cp);
                if(external.contains(text(row.get("external_shop_id")))&&currency(f,row)&&(exact||management&&!cp.isEmpty()))
                    track(groups,text(row.get("label")),row,cp,exact,false);
            }
        }
    }

    private void qianyi(Scope scope,Filter f,Map<String,Map<String,Object>> shops,Map<String,String> selection,boolean unbounded,Groups groups){
        String dateField=FinanceQianyiReturns.dateField(selection);
        boolean specific=!text(selection.get("numbers")).isBlank();
        for(var row:repo.jdbc().queryForList("""
            SELECT shop_id,status,checkpoint_json,next_at,lease_until>UTC_TIMESTAMP(6) leased
            FROM finance_qianyi_connections WHERE organization_id=? AND enabled=TRUE
            """,scope.organizationId())){
            if(specific||!shops.containsKey(text(row.get("shop_id"))))continue;
            var cp=checkpoint(row);
            boolean receipt=flag(cp.get("receiptScan"));
            boolean comparable=(receipt?"RECEIPT":"UPDATED").equals(dateField);
            boolean exact=unbounded||comparable&&dateOverlap(f,cp);
            if(exact||!comparable&&!cp.isEmpty())track(groups,"千易售后同步",row,cp,exact,exact);
        }
        var returns=new FinanceQianyiReturns(repo,finance).query(scope,selection);
        if(!repo.jdbc().queryForList("""
            SELECT r.return_number FROM finance_qianyi_returns r
            JOIN finance_connections c ON c.organization_id=r.organization_id AND c.id=r.shop_id
            JOIN finance_qianyi_connections q ON q.organization_id=r.organization_id AND q.shop_id=r.shop_id
            WHERE q.enabled=TRUE AND q.cost_basis_verified=TRUE AND r.cost_status='PENDING' AND r.next_at<=UTC_TIMESTAMP(6) AND
            """+returns.where()+" LIMIT 1",returns.args().toArray()).isEmpty())
            groups.add("千易退货成本补算","等待计算",null,false,"所选售后范围");
    }

    private void costs(Scope scope,Filter f,Map<String,String> selection,Groups groups){
        if(new FinanceCostPriceScans(repo).pending(scope,f))
            groups.add("成本表变更核对","排队或分批核对",null,false,"所选店铺 · 日期影响待确认");
        if(repo.jdbc().queryForList("""
            SELECT order_record_id FROM finance_order_cost_jobs WHERE organization_id=? LIMIT 1
            """,scope.organizationId()).isEmpty())return;
        String record=text(selection.get("recordId"));
        String kind=FinanceCostExport.kind(FinanceCostReport.scopeKind(selection.get("kind"),!record.isBlank()));
        var q=record.isBlank()?repo.query(scope,f,"r"):new FinanceRepository.Query(
            "r.organization_id=? AND r.id=? AND "+FinanceRepository.visible("r"),List.of(scope.organizationId(),record));
        q=FinanceRecordSearch.orders(q,Set.of("BILL","RECONCILIATION").contains(kind)?"o.order_id":"r.order_id",selection.get("numbers"));
        String source=FinanceCostReport.related(kind)+q.where();
        var args=new ArrayList<>(q.args());
        if(record.isBlank()&&"SETTLED_ORDER".equals(kind)){
            source="""
                SELECT job.organization_id,job.order_record_id FROM finance_order_cost_jobs job
                JOIN finance_records o ON o.id=job.order_record_id AND o.organization_id=job.organization_id
                WHERE job.organization_id=? AND EXISTS (
                    SELECT 1 FROM finance_records r FORCE INDEX(idx_finance_settled_order)
                    WHERE r.organization_id=o.organization_id AND r.shop_id=o.shop_id AND r.order_id=o.order_id
                    AND
                """+FinanceCostReport.firstSettlement("r",false)+" AND "+q.where()+")";
            args.addFirst(scope.organizationId());
        }
        var rows=repo.jdbc().queryForList("SELECT j.priority FROM ("+source+") x"
            +" JOIN finance_order_cost_jobs j ON j.organization_id=x.organization_id AND j.order_record_id=x.order_record_id"
            +FinanceCostReport.costStatusWhere(selection.get("costStatus"))+" GROUP BY j.priority",args.toArray());
        if(rows.stream().anyMatch(row->number(row.get("priority"))<2))groups.add("商品成本重算","等待计算",null,true,"所选订单范围");
        if(rows.stream().anyMatch(row->number(row.get("priority"))==2))groups.add("贴单成本更新","等待计算",null,true,"所选订单范围");
    }

    private void rebuilds(Scope scope,Map<String,Map<String,Object>> shops,Groups groups){
        jobsForManualChanges(scope,shops,groups);
        for(var row:repo.jdbc().queryForList("""
            SELECT shop_id,status FROM finance_cost_rebuilds WHERE organization_id=? AND status IN ('READY','LABELS')
            """,scope.organizationId()))if(shops.containsKey(text(row.get("shop_id"))))
                groups.add("LABELS".equals(row.get("status"))?"历史贴单成本重算":"历史订单成本重算",
                    "排队或分批计算",null,false,"所选店铺 · 日期影响待确认");
    }

    private void jobsForManualChanges(Scope scope,Map<String,Map<String,Object>> shops,Groups groups){
        if(FinanceCostTargetScans.pending(repo,scope,new ArrayList<>(shops.keySet())))
            groups.add("人工成本变更核对","分批核对",null,false,"所选店铺 · 日期影响待确认");
    }

    private void projections(Scope scope,Filter f,Map<String,Map<String,Object>> shops,Groups groups){
        var ids=new ArrayList<>(shops.keySet());
        if(f.shopId().isBlank()&&Set.of("","UNGROUPED").contains(f.groupId()))ids.add("UNASSIGNED");
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        var args=new ArrayList<Object>(List.of(scope.organizationId(),f.start(),f.end()));args.addAll(ids);
        boolean event=!repo.jdbc().queryForList("""
            SELECT event_id FROM finance_query_events WHERE organization_id=?
              AND (event_scope='ALL' OR record_date BETWEEN ? AND ?)
              AND (shop_id IS NULL OR shop_id='' OR shop_id IN (%s)) LIMIT 1
            """.formatted(slots),args.toArray()).isEmpty();
        args.add(1,f.platform());
        if(event||!repo.jdbc().queryForList("""
            SELECT 1 FROM finance_query_dirty_shop_days WHERE organization_id=? AND platform_filter=?
              AND record_date BETWEEN ? AND ? AND shop_id IN (%s) LIMIT 1
            """.formatted(slots),args.toArray()).isEmpty())
            groups.add("报表汇总更新","等待更新",null,true,"所选店铺和日期");
    }

    private void labels(Scope scope,Filter f,Map<String,Map<String,Object>> shops,Map<String,String> selection,Groups groups){
        boolean all=f.groupId().isBlank()&&f.shopId().isBlank()&&f.platform().isBlank();
        var args=new ArrayList<Object>(List.of(scope.organizationId(),f.end(),f.start()));
        String shopScope="";
        if(!all){
            if(shops.isEmpty())return;
            shopScope=" AND EXISTS (SELECT 1 FROM finance_label_invoice_rows r WHERE r.import_id=i.id AND r.organization_id=i.organization_id"
                +" AND r.transaction_date BETWEEN ? AND ? AND r.matched_shop_id IN ("+String.join(",",Collections.nCopies(shops.size(),"?"))+"))";
            args.add(f.start());args.add(f.end());args.addAll(shops.keySet());
        }
        if(!text(selection.get("numbers")).isBlank())return;
        String file=text(selection.get("fileId"));
        String fileScope=file.isBlank()?"":" AND i.id=?";
        if(!file.isBlank())args.add(file);
        for(var row:repo.jdbc().queryForList("""
            SELECT i.status,(i.period_start IS NULL OR i.period_end IS NULL) unknown_period,s.retry_after,COUNT(*) files
            FROM finance_label_imports i
            LEFT JOIN finance_label_state s ON s.organization_id=i.organization_id
            WHERE i.organization_id=? AND i.status IN ('QUEUED','PARSING','PREPARED','REMOVE_QUEUED')
              AND (i.period_start IS NULL OR i.period_start<=?) AND (i.period_end IS NULL OR i.period_end>=?)
            """+shopScope+fileScope+" GROUP BY i.status,unknown_period,s.retry_after",args.toArray())){
            Instant retry=time(row.get("retry_after"));
            groups.add("贴单账单处理",retry!=null&&retry.isAfter(Instant.now())?"重试等待":
                switch(text(row.get("status"))){case "PARSING"->"解析或等待重试";case "PREPARED"->"等待匹配";default->"排队中";},retry,false,
                flag(row.get("unknown_period"))?"账单日期待确认":"所选账单日期",number(row.get("files")),0);
        }
    }

    private void deletions(Scope scope,Filter f,Groups groups){
        if(!f.groupId().isBlank()||!f.platform().isBlank())return;
        for(var row:finance.deletions(scope)){
            if(!f.shopId().isBlank()&&!f.shopId().equals(text(row.get("shop_id"))))continue;
            if(Set.of("QUEUED","RUNNING").contains(text(row.get("status"))))
                groups.add("店铺数据清理","后台清理中",null,false,"店铺管理");
        }
    }

    private Map<String,Object> checkpoint(Map<String,Object> row){
        return row.get("checkpoint_json")==null?Map.of():repo.read(text(row.get("checkpoint_json")));
    }
    private static boolean enabled(Map<String,Object> shop){return "ACTIVE".equals(shop.get("authorization_status"))&&flag(shop.get("enabled"));}
    private static boolean currency(Filter f,Map<String,Object> row){return f.currency().isBlank()||f.currency().equals(text(row.get("currency")));}
    private static boolean flag(Object v){return Boolean.TRUE.equals(v)||"1".equals(text(v));}
    private static Instant time(Object v){return v==null?null:instant(v);}
    static boolean epochOverlap(Filter f,Map<String,Object> cp,String timezone){
        long from=number(cp.get("from")),until=number(cp.get("until"));
        if(from<=0||until<=from||timezone.isBlank())return false;
        try{
            var zone=ZoneId.of(timezone);
            return from<f.end().plusDays(1).atStartOfDay(zone).toEpochSecond()&&until>f.start().atStartOfDay(zone).toEpochSecond();
        }catch(DateTimeException invalid){return false;}
    }
    static boolean dateOverlap(Filter f,Map<String,Object> cp){
        try{
            LocalDate from=LocalDate.parse(text(cp.get("from"))),until=LocalDate.parse(text(cp.get("until")));
            return !until.isBefore(from)&&!from.isAfter(f.end())&&!until.isBefore(f.start());
        }catch(DateTimeException invalid){return false;}
    }
    private static void track(Groups groups,String label,Map<String,Object> row,Map<String,Object> cp,boolean exact,boolean blocked){
        String status=text(row.get("status"));
        boolean retry=Set.of("ERROR","RATE_LIMITED").contains(status)&&!cp.isEmpty();
        if(!retry&&!Set.of("READY","RUNNING","SYNCING").contains(status))return;
        if(cp.isEmpty()&&row.get("priority_requested_at")==null&&!flag(row.get("leased")))return;
        String state=retry?"重试等待":flag(row.get("leased"))?"同步中":"SYNCING".equals(status)?"同步批次未完成":"排队中";
        groups.add(Category.SYNC,label,state,retry?time(row.get("next_at")):null,blocked,exact?"所选期间":"店铺已匹配 · 日期影响待确认",
            1,Math.max(0,number(cp.get("page"))-1));
    }

    private static final class Groups {
        private final Map<String,Group> entries=new LinkedHashMap<>();
        void add(String label,String state,Instant retry,boolean blocks,String scope){
            add(label,state,retry,blocks,scope,1,0);
        }
        void add(String label,String state,Instant retry,boolean blocks,String scope,long count,long pages){
            add(Category.PROCESSING,label,state,retry,blocks,scope,count,pages);
        }
        void add(Category category,String label,String state,Instant retry,boolean blocks,String scope,long count,long pages){
            var group=entries.computeIfAbsent(label,k->new Group(category));
            group.states.merge(state,count,Long::sum);group.scopes.add(scope);
            if(retry!=null&&(group.retry==null||retry.isBefore(group.retry)))group.retry=retry;
            group.blocking|=blocks;group.count+=count;group.pages=pages;
        }
        List<Activity> result(){
            return entries.entrySet().stream().map(entry->{
                var group=entry.getValue();
                var details=new ArrayList<String>();
                group.states.forEach((state,count)->details.add(state+(count>1?" "+count+" 项":"")));
                details.addAll(group.scopes);
                if(group.count==1&&group.pages>0)details.add("当前批次已读取 "+group.pages+" 页");
                return new Activity(group.category,entry.getKey(),List.copyOf(details),group.retry,group.blocking);
            }).sorted(Comparator.comparing(Activity::affectsExport).reversed()).toList();
        }
        private static final class Group {
            final Category category;
            final Map<String,Long> states=new LinkedHashMap<>();
            final Set<String> scopes=new LinkedHashSet<>();
            Instant retry;
            boolean blocking;
            long count,pages;
            Group(Category category){this.category=category;}
        }
    }
}
