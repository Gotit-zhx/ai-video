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
        var channels=new ArrayList<>(List.of("ORDER","ORDER_LIVE","REFUND","SETTLEMENT"));
        if("TIKTOK_SHOP".equals(shop.get("platform")))channels.add("PENDING");
        if(enabled)for(String channel:channels)repo.jdbc().update("""
            INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel) VALUES(?,?,?,?)
            """,s.organizationId(),s.userId(),id,channel);
        invalidateAfterCommit(s);
    }
    @Transactional
    public void configure(Scope s,String id,boolean enabled,String currency,String timezone,String groupId){
        if(!text(groupId).isBlank())new FinanceGroups(repo).require(s,groupId,true);
        configure(s,id,enabled,currency,timezone);
        repo.jdbc().update("UPDATE finance_connections SET group_id=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",
                text(groupId).isBlank()?null:groupId,s.organizationId(),id);
    }
    public List<Map<String,Object>> syncStates(Scope s,Filter f){
        var ids=shops(s).stream().filter(shop->FinanceGroups.matches(shop,f)).map(shop->text(shop.get("id"))).collect(java.util.stream.Collectors.toSet());
        return syncStates(s).stream().filter(row->ids.contains(text(row.get("shop_id")))).toList();
    }
    public List<Map<String,Object>> syncStates(Scope s){
        return repo.jdbc().queryForList("""
            SELECT st.shop_id,st.channel,st.status,st.error_message,st.next_at,st.last_success_at,st.coverage_start,
              JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.from')) window_start,
              JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.to')) window_end,
              JSON_UNQUOTE(JSON_EXTRACT(st.checkpoint_json,'$.until')) target_until
            FROM finance_sync_states st JOIN finance_connections c ON c.id=st.shop_id AND c.organization_id=st.organization_id AND c.user_id=st.user_id
            WHERE st.organization_id=? AND c.status<>'REMOVED' ORDER BY st.shop_id,st.channel
            """,s.organizationId());
    }
    public void sync(Scope s,String shopId){
        ownedShop(s,shopId);s=shopScope(s,shopId);
        Integer enabled=repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections WHERE organization_id=? AND user_id=? AND id=? AND enabled=TRUE AND status='ACTIVE'",
                Integer.class,s.organizationId(),s.userId(),shopId);
        if(enabled==null||enabled==0)throw bad("请先在财务店铺管理中开启同步");
        repo.jdbc().update("""
            UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6),error_message=NULL,failures=0,
            status=CASE WHEN lease_until>UTC_TIMESTAMP(6) THEN status ELSE 'READY' END
            WHERE organization_id=? AND user_id=? AND shop_id=?
            """,s.organizationId(),s.userId(),shopId);
        invalidateSnapshots(s);
    }
    @Transactional
    public void removeShop(Scope s,String id){
        ownedShop(s,id);s=shopScope(s,id);
        repo.jdbc().update("UPDATE finance_connections SET enabled=FALSE,status='REMOVED',updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND user_id=? AND id=?",s.organizationId(),s.userId(),id);
        repo.jdbc().update("UPDATE finance_ad_accounts SET enabled=FALSE,status='DISABLED' WHERE organization_id=? AND shop_id=?",s.organizationId(),id);
        invalidateAfterCommit(s);
    }
    private void invalidateAfterCommit(Scope s){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){invalidateSnapshots(s);}
            });
        else invalidateSnapshots(s);
    }
    private void invalidateSnapshots(Scope s){
        Scope shared=companyScope(s.organizationId());snapshots.invalidateCompany(s.organizationId());if(sharedSnapshots!=null)sharedSnapshots.invalidate(shared);
    }
    public Map<String,Object> dashboard(Scope s,Filter requested,boolean cny,String snapshotId){
        var key=new FinanceSnapshotCache.Key(s,requested,cny,text(snapshotId),false);
        java.util.function.Supplier<Map<String,Object>> load=()->buildDashboard(s,requested,cny,snapshotId,false);
        return sharedSnapshots==null?snapshots.get(key,load):sharedSnapshots.get(key,load);
    }
    public void prewarm(Scope s){
        if(sharedSnapshots==null)return;
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for(boolean live:List.of(false,true))for(boolean cny:List.of(false,true)){
            var filter=new Filter(live?today:today.minusDays(29),today,"","","","day");
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
        if(cny)result.put("fx",fx.quote(snapshotId).metadata());
        return result;
    }
    Map<String,Object> buildDashboard(Scope s,Filter requested,boolean cny,String snapshotId,boolean live){
        Filter all=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),"",requested.granularity(),requested.groupId());
        // One shop aggregate supplies currencies, totals and ranking. Previously
        // each request scanned the same record range three times for these.
        var allRanking=repo.grouped(s,all,"shop_id,kind");
        List<String> codes=allRanking.stream().map(r->text(r.get("currency"))).distinct().sorted().toList();
        String currency=requested.currency().isBlank()?(cny||codes.isEmpty()?"":codes.getFirst()):requested.currency();
        Filter f=new Filter(requested.start(),requested.end(),requested.shopId(),requested.platform(),currency,requested.granularity(),requested.groupId());
        var ranking=allRanking.stream().filter(r->currency.isBlank()||currency.equals(text(r.get("currency")))).toList();
        List<Map<String,Object>> totals=nativeTotals(ranking);
        FinanceFx.Quote quote=cny?fx.quote(snapshotId):null;
        if(cny)totals=FinanceFx.groups(totals,quote,"kind");
        LinkedHashMap<String,Map<String,Object>> points=new LinkedHashMap<>();
        for(LocalDate d=f.start();!live&&!d.isAfter(f.end());d=d.plusDays(1)){
            String key=bucket(d,f.granularity());points.computeIfAbsent(key,k->{var m=new LinkedHashMap<String,Object>();m.put("period",k);return m;});
        }
        var daily=live?List.<Map<String,Object>>of():repo.grouped(s,f,"record_date,kind");
        if(cny)daily=FinanceFx.groups(daily,quote,"record_date","kind");
        for(var row:daily){
            String key=bucket(LocalDate.parse(text(row.get("record_date"))),f.granularity());
            var point=points.get(key);String kind=text(row.get("kind"));if(point==null)continue;
            BigDecimal value=decimal(row.get("amount"));if(value!=null)point.merge(kind,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));
            point.merge(kind+"_count",number(row.get("records")),(a,b)->(Long)a+(Long)b);
        }
        var out=new LinkedHashMap<String,Object>();
        out.put("filters",Map.of("start",f.start(),"end",f.end(),"shopId",f.shopId(),"platform",f.platform(),"currency",currency,"granularity",f.granularity(),"displayCurrency",cny?"CNY":currency,"groupId",requested.groupId()));
        if(cny)out.put("fx",quote.metadata());
        out.put("currencies",codes);out.put("totals",totals);out.put("trend",new ArrayList<>(points.values()));
        out.put("shops",shops(s));out.put("groups",groups(s));
        var fees=live?List.<Map<String,Object>>of():repo.grouped(s,f,"category,kind");var statuses=repo.grouped(s,f,"status,kind");
        out.put("ranking",cny?FinanceFx.groups(ranking,quote,"shop_id","kind"):ranking);
        out.put("fees",(cny?FinanceFx.groups(fees,quote,"category","kind"):fees).stream().filter(r->"FEE".equals(r.get("kind"))).toList());
        out.put("statuses",cny?FinanceFx.groups(statuses,quote,"status","kind"):statuses);out.put("sync",syncStates(s,requested));
        out.put("updatedAt",Instant.now().toString());
        out.put("basis","订单按创建日期，结算与费用按平台结算日期；广告按广告账户日期。原币分别统计，已结算不代表银行到账。");
        return out;
    }
    static List<Map<String,Object>> nativeTotals(List<Map<String,Object>> ranking){
        var totals=new LinkedHashMap<List<String>,Map<String,Object>>();
        for(var row:ranking){
            String kind=text(row.get("kind")),currency=text(row.get("currency"));
            var total=totals.computeIfAbsent(List.of(kind,currency),k->new LinkedHashMap<>(Map.of("kind",kind,"currency",currency)));
            for(String field:List.of("amount","revenue","records","known_amounts","impressions","clicks","conversions")){
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
        return sharedSnapshots==null?snapshots.get(key,load):sharedSnapshots.get(key,load);
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
