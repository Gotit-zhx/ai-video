package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.ShopCredentialCipher;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceAds {
    static final List<String> ADVERTISER_FIELDS=List.of("advertiser_id","name","currency","timezone");
    private enum Work { FIRST_DISCOVERY, RECENT, RECENT_BASIC, FIRST_REPORT, MONTHS, MONTHS_BASIC, REPORT, RECHECK }
    enum Period {
        RECENT("recent"), MONTHS("months"), REPORT("");
        final String key;
        Period(String key){this.key=key;}
        boolean priority(){return this!=REPORT;}
    }
    private static final List<Work> ROTATION=List.of(
            Work.FIRST_DISCOVERY,Work.RECENT,Work.MONTHS,Work.FIRST_REPORT,Work.RECENT_BASIC,Work.MONTHS_BASIC,Work.RECHECK);
    private final java.util.concurrent.atomic.AtomicInteger turn=new java.util.concurrent.atomic.AtomicInteger();
    private static final String LOCAL_DAY=FinanceSyncRange.localDaySql("a.timezone");
    private static final String PRIOR_MONTH=FinanceSyncRange.previousMonthSql(LOCAL_DAY);
    private static final String ALL_PRIORITY_DAYS="(DATEDIFF("+LOCAL_DAY+","+PRIOR_MONTH+")+1)";
    private static final String COVERAGE="""
            (SELECT COUNT(*) FROM finance_ad_coverage c
              WHERE c.organization_id=t.organization_id AND c.advertiser_id=t.advertiser_id
                AND c.channel=t.channel AND c.store_id=t.store_id
                AND c.record_date BETWEEN %s AND %s)
            """;
    private static final String RECENT_COVERAGE=COVERAGE.formatted("DATE_SUB("+LOCAL_DAY+",INTERVAL 6 DAY)",LOCAL_DAY);
    private static final String MONTH_COVERAGE=COVERAGE.formatted(PRIOR_MONTH,LOCAL_DAY);
    private static final String RECENT_DUE="(JSON_EXTRACT(t.checkpoint_json,'$.recent') IS NOT NULL OR "+RECENT_COVERAGE+"<7)";
    private static final String MONTH_DUE="(JSON_EXTRACT(t.checkpoint_json,'$.months') IS NOT NULL OR "+MONTH_COVERAGE+"<"+ALL_PRIORITY_DAYS+")";
    private static final String DUE_ACCOUNT="""
            a.enabled=TRUE AND a.next_at<=UTC_TIMESTAMP(6)
              AND (a.lease_until IS NULL OR a.lease_until<UTC_TIMESTAMP(6))
            """;
    record AdWork(Map<String,Object> account,boolean discovery,Period period,String channel,String store) {}
    final FinanceRepository repo;final FinanceService finance;final MarketplaceHttp http;final ShopCredentialCipher cipher;
    final FinanceAdBcFunding funding;
    private final java.util.concurrent.atomic.AtomicBoolean verified=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean fundingFailureReported=new java.util.concurrent.atomic.AtomicBoolean();
    private final FinanceWorkerCapacity capacity;
    private final int dispatchSeconds;
    private final Semaphore reportSlots;
    private final ScheduledExecutorService worker;
    private final ScheduledExecutorService fundingWorker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-ad-bc-funding");t.setDaemon(true);return t;});
    public FinanceAds(FinanceRepository repo,FinanceService finance,MarketplaceHttp http,ShopCredentialCipher cipher){
        this(repo,finance,http,cipher,FinanceWorkerCapacity.unrestricted(),1,30);
    }
    FinanceAds(FinanceRepository repo,FinanceService finance,MarketplaceHttp http,ShopCredentialCipher cipher,
               FinanceWorkerCapacity capacity,int reportThreads,int dispatchSeconds){
        this.repo=repo;this.finance=finance;this.http=http;this.cipher=cipher;this.funding=new FinanceAdBcFunding(this);
        this.capacity=capacity;
        this.dispatchSeconds=Math.clamp(dispatchSeconds,5,120);
        int reportConcurrency=Math.clamp(reportThreads,1,4);
        reportSlots=new Semaphore(reportConcurrency);
        worker=Executors.newScheduledThreadPool(reportConcurrency+1,r->{var t=new Thread(r,"finance-ad-sync");t.setDaemon(true);return t;});
    }
    @Autowired
    FinanceAds(FinanceRepository repo,FinanceService finance,FinanceAdHttp transport,ShopCredentialCipher cipher,
               FinanceWorkerCapacity capacity,@Value("${APP_FINANCE_TIKTOK_REPORT_THREADS:1}") int reportThreads,
               @Value("${APP_FINANCE_TIKTOK_REPORT_DISPATCH_SECONDS:30}") int dispatchSeconds){
        this(repo,finance,transport.transport(),cipher,capacity,reportThreads,dispatchSeconds);
    }
    public List<Map<String,Object>> list(Scope s){
        return list(s,null,null);
    }
    public List<Map<String,Object>> list(Scope s,LocalDate from,LocalDate to){
        List<Map<String,Object>> accounts=repo.jdbc().queryForList("SELECT advertiser_id,name,shop_id,currency,enabled,status,error_message,next_at,last_success_at,gmv_store_id,gmv_status,gmv_error,timezone,discovery_status,discovery_error"
                +" FROM finance_ad_accounts a WHERE organization_id=? AND "+visibleAccount()+" ORDER BY name",s.organizationId()).stream().map(r->(Map<String,Object>)new LinkedHashMap<>(r)).toList();
        var stores=repo.jdbc().queryForList("SELECT advertiser_id,store_id,store_name,store_code,shop_id,match_status,report_eligible,primary_advertiser_id,gmv_available,active FROM finance_ad_stores WHERE organization_id=? ORDER BY store_name",s.organizationId());
        var streams=repo.jdbc().queryForList("SELECT advertiser_id,channel,store_id,status,error_message,next_at,last_success_at FROM finance_ad_streams WHERE organization_id=?",s.organizationId());
        var progress=from==null&&to==null?syncProgress(s):Map.<String,List<Map<String,Object>>>of();
        for(var account:accounts){
            String id=text(account.get("advertiser_id"));
            account.put("stores",stores.stream().filter(r->id.equals(r.get("advertiser_id"))).toList());
            account.put("streams",streams.stream().filter(r->id.equals(r.get("advertiser_id"))).toList());
            if(from==null&&to==null){
                account.put("syncProgress",progress.getOrDefault(id,List.of()));
                account.put("discoveryAccessIssue",FinanceAdAutomation.accessIssue(text(account.get("discovery_error"))));
            }
        }
        if(from!=null&&to!=null){
            if(from.isAfter(to)||from.plusYears(10).isBefore(to))throw bad("广告覆盖日期范围不正确");
            var coverage=repo.jdbc().queryForList("""
                SELECT advertiser_id,channel,store_id,COUNT(*) covered_days,SUM(amount) amount,SUM(row_count) records,
                  MIN(record_date) first_day,MAX(record_date) last_day,MIN(observed_at) oldest_observation
                FROM finance_ad_coverage WHERE organization_id=? AND record_date BETWEEN ? AND ? GROUP BY advertiser_id,channel,store_id
                """,s.organizationId(),from.toString(),to.toString());
            for(var account:accounts){account.put("coverage",coverage.stream().filter(r->text(account.get("advertiser_id")).equals(r.get("advertiser_id"))).toList());
                account.put("requestedDays",java.time.temporal.ChronoUnit.DAYS.between(from,to)+1);}
        }
        funding.decorate(s,accounts,from,to);return accounts;
    }
    private Map<String,List<Map<String,Object>>> syncProgress(Scope scope){
        var rows=repo.jdbc().queryForList("""
                SELECT t.advertiser_id,t.channel,t.store_id,t.status,t.error_message,t.last_success_at,
                       DATE_SUB(%s,INTERVAL 6 DAY) recent_from,%s recent_until,%s recent_days,
                       %s priority_from,%s priority_days,%s priority_total_days,
                       JSON_UNQUOTE(JSON_EXTRACT(t.checkpoint_json,'$.from')) history_from
                FROM finance_ad_streams t JOIN finance_ad_accounts a
                  ON a.organization_id=t.organization_id AND a.advertiser_id=t.advertiser_id
                WHERE t.organization_id=? AND %s AND %s
                ORDER BY t.advertiser_id,t.channel,t.store_id
                """.formatted(LOCAL_DAY,LOCAL_DAY,RECENT_COVERAGE,PRIOR_MONTH,MONTH_COVERAGE,ALL_PRIORITY_DAYS,
                        visibleAccount(),FinanceAdAutomation.VISIBLE_STREAM),
                scope.organizationId());
        var result=new HashMap<String,List<Map<String,Object>>>();
        for(var row:rows){
            var item=new LinkedHashMap<>(row);
            String recentFrom=text(row.get("recent_from")),historyFrom=text(row.get("history_from"));
            item.put("recent_from",recentFrom);
            item.put("recent_until",text(row.get("recent_until")));
            item.put("access_issue",FinanceAdAutomation.accessIssue(text(row.get("error_message"))));
            item.put("history_pending",row.get("last_success_at")==null||!historyFrom.isBlank()
                    &&LocalDate.parse(historyFrom).isBefore(LocalDate.parse(recentFrom)));
            result.computeIfAbsent(text(row.get("advertiser_id")),ignored->new ArrayList<>()).add(item);
        }
        return result;
    }
    static String visibleAccount(){return "(COALESCE(a.shop_id,'')='' OR EXISTS (SELECT 1 FROM finance_connections c WHERE c.id=a.shop_id AND c.organization_id=a.organization_id AND c.status<>'REMOVED'))";}
    public void connect(Scope s,String id,String shopId,String token,String gmvStoreId){
        if(!text(id).matches("\\d{5,30}")||text(token).length()<10||text(token).length()>4096)throw bad("请填写有效广告账户 ID 和授权 Access Token");
        validateDestination(s,text(shopId),text(gmvStoreId).trim());
        var data=get("/advertiser/info/",token,Map.of("advertiser_ids",repo.write(List.of(id)),"fields",repo.write(ADVERTISER_FIELDS)));
        var advertiser=rows(data.get("list")).stream().filter(r->id.equals(text(r.get("advertiser_id")))).findFirst().orElseThrow(()->bad("该授权未返回指定广告账户"));
        connectVerified(s,id,shopId,token,gmvStoreId,advertiser);
    }
    public void validateDestination(Scope s,String shopId,String gmvStoreId){
        if(!gmvStoreId.isBlank()&&!gmvStoreId.matches("\\d{5,30}"))throw bad("GMV Max 店铺 ID 格式不正确");
        if(!gmvStoreId.isBlank()&&shopId.isBlank())throw bad("GMV Max 店铺报表需先选择对应的专属店铺");
        if(!shopId.isBlank()){
            var shop=finance.ownedShop(s,shopId);if(!"TIKTOK_SHOP".equals(shop.get("platform")))throw bad("TikTok 广告只能绑定 TikTok 店铺");
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='AD' AND source='IMPORT'",
                    Integer.class,s.organizationId(),shopId)>0)throw bad("该店铺已有导入广告费用，请先使用未分配账户接入，避免重复计费");
        }
    }
    public void connectVerified(Scope s,String id,String shopId,String token,String gmvStoreId,Map<String,Object> advertiser){
        connectVerified(s,id,shopId,token,gmvStoreId,advertiser,Instant.now());
    }
    void connectVerified(Scope s,String id,String shopId,String token,String gmvStoreId,Map<String,Object> advertiser,Instant started){
        repo.transaction(()->{saveVerified(s,id,shopId,token,gmvStoreId,advertiser,started);return null;});
    }
    private void saveVerified(Scope s,String id,String shopId,String token,String gmvStoreId,Map<String,Object> advertiser,Instant started){
        shopId=text(shopId);gmvStoreId=text(gmvStoreId).trim();
        if(!shopId.isBlank())repo.requireShopWrite(s,shopId);
        new FinanceShopDeletion(repo).checkAdAuthorization(s,id,started);
        validateDestination(s,shopId,gmvStoreId);
        if(!id.equals(text(advertiser.get("advertiser_id"))))throw bad("广告账户授权身份不匹配");
        String currency=first(advertiser,"currency");if(!currency.matches("[A-Z]{3}"))throw bad("广告接口没有返回有效币种");
        var previous=repo.jdbc().queryForList("SELECT user_id,shop_id FROM finance_ad_accounts WHERE organization_id=? AND advertiser_id=?",s.organizationId(),id);
        Scope storage=previous.isEmpty()?companyScope(s.organizationId()):new Scope(s.organizationId(),text(previous.getFirst().get("user_id")));
        String encrypted=cipher.encrypt(token,storage.organizationId(),"finance:"+storage.userId()+":"+id);
        if(!previous.isEmpty()&&!shopId.equals(text(previous.getFirst().get("shop_id")))){
            String original=text(previous.getFirst().get("shop_id"));
            if(!repo.jdbc().queryForList("SELECT shop_id FROM finance_shop_deletions WHERE organization_id=? AND shop_id=? AND status<>'COMPLETE'",
                    s.organizationId(),original).isEmpty())throw bad("该广告账户所属店铺正在删除，请稍后重新授权");
            boolean retired=!original.isBlank()&&finance.shops(s).stream().noneMatch(shop->original.equals(text(shop.get("id"))));
            if(!retired)throw bad("该广告账户已有归属，请使用原归属更新授权，以免历史费用重复");
        }
        repo.jdbc().update("""
            INSERT INTO finance_ad_accounts(organization_id,user_id,advertiser_id,name,shop_id,currency,access_token_encrypted,gmv_store_id,gmv_status,timezone)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),access_token_encrypted=VALUES(access_token_encrypted),
            last_success_at=IF(shop_id<>VALUES(shop_id),NULL,last_success_at),shop_id=VALUES(shop_id),
            gmv_store_id=VALUES(gmv_store_id),gmv_status=VALUES(gmv_status),gmv_error=NULL,timezone=VALUES(timezone),
            discovery_next_at=UTC_TIMESTAMP(6),
            status=IF(enabled,'READY','PAUSED'),error_message=NULL,failures=0,lease_token=NULL,lease_until=NULL,
            next_at=IF(enabled,UTC_TIMESTAMP(6),next_at)
            """,storage.organizationId(),storage.userId(),id,first(advertiser,"name"),shopId,currency,encrypted,gmvStoreId,gmvStoreId.isBlank()?"DISABLED":"READY",first(advertiser,"timezone"));
        repo.jdbc().update("UPDATE finance_ad_streams SET next_at=UTC_TIMESTAMP(6),failures=0 WHERE organization_id=? AND advertiser_id=?",s.organizationId(),id);
        funding.reauthorized(s,id,token);
        FinanceAdMetadataSync.reauthorized(repo,s,id);
    }
    public void pause(Scope s,String id,boolean enabled){
        if(enabled){
            var account=list(s).stream().filter(a->id.equals(text(a.get("advertiser_id")))).findFirst()
                    .orElseThrow(()->bad("广告账户不存在或绑定的财务店铺已移除"));
            validateDestination(s,text(account.get("shop_id")),text(account.get("gmv_store_id")));
        }
        repo.transaction(()->{if(repo.jdbc().update("""
            UPDATE finance_ad_accounts SET enabled=?,status=?,lease_token=NULL,lease_until=NULL,
              next_at=GREATEST(next_at,UTC_TIMESTAMP(6)) WHERE organization_id=? AND advertiser_id=?
            """,enabled,enabled?"READY":"PAUSED",s.organizationId(),id)!=1)throw bad("广告账户不存在");
            funding.revoke(s,id);FinanceAdMetadataSync.paused(repo,s,id,enabled);return null;});
    }
    static Long metricCount(Map<String,Object> metrics,String key){
        BigDecimal value=decimal(metrics.get(key));
        if(value==null)return null;
        try{long count=value.longValueExact();if(count>=0)return count;}
        catch(ArithmeticException invalid){throw bad("广告报表计数指标格式不正确："+key);}
        throw bad("广告报表计数指标不能为负数："+key);
    }
    Map<String,Object> get(String path,String token,Map<String,?> query){
        var response=http.get(MarketplaceHttp.uri("https://business-api.tiktok.com","/open_api/v1.3"+path,query),Map.of("Access-Token",token));
        return providerData(response);
    }
    Map<String,Object> getFunding(String path,String token,Map<String,?> query){
        var response=http.getExact(MarketplaceHttp.uri("https://business-api.tiktok.com","/open_api/v1.3"+path,query),Map.of("Access-Token",token));
        return providerData(response);
    }
    private Map<String,Object> providerData(Map<String,Object> response){
        if(!response.containsKey("code")||number(response.get("code"))!=0)
            throw new FinanceAdFailures.ProviderFailure(response.get("code"));
        return map(response.get("data"));
    }
    @PostConstruct public void start(){
        worker.scheduleWithFixedDelay(this::dispatch,60,dispatchSeconds,TimeUnit.SECONDS);
        fundingWorker.scheduleWithFixedDelay(()->{try{
            com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(funding::tick);fundingFailureReported.set(false);
        }catch(Exception failure){if(fundingFailureReported.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance BC funding dispatcher temporarily unavailable");}},75,30,java.util.concurrent.TimeUnit.SECONDS);
    }
    @PreDestroy public void stop(){worker.shutdownNow();fundingWorker.shutdownNow();}
    void dispatch(){
        // Dispatch pace and concurrent requests are independent. A slow response
        // cannot accumulate a queue of delayed requests or consume every report slot.
        if(!reportSlots.tryAcquire())return;
        try{
            worker.execute(()->{
                try{
                    com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);
                    if(verified.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).info("Finance ad sync ready");
                }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance ad dispatcher temporarily unavailable",e);}
                finally{reportSlots.release();}
            });
        }catch(java.util.concurrent.RejectedExecutionException stopped){
            reportSlots.release();
            if(!worker.isShutdown())throw stopped;
        }
    }
    public void tick(){
        try(var permit=capacity.acquire()){
            if(permit!=null)tick(permit.historyAllowed());
        }
    }
    private void tick(boolean historyAllowed){
        Work slot=ROTATION.get(turn.getAndUpdate(value->(value+1)%ROTATION.size()));
        AdWork work=dueWork(slot);
        if(work==null){
            for(Work fallback:List.of(Work.FIRST_DISCOVERY,Work.RECENT,Work.MONTHS,Work.REPORT,Work.RECHECK)){
                if(fallback!=slot&&(historyAllowed||fallback!=Work.REPORT)&&(work=dueWork(fallback))!=null)break;
            }
        }
        if(work!=null)execute(work);
    }
    private AdWork dueWork(Work slot){
        boolean discovery=slot==Work.FIRST_DISCOVERY||slot==Work.RECHECK;
        if(discovery){
            String first=slot==Work.FIRST_DISCOVERY?" AND a.discovery_status='PENDING'":"";
            var rows=repo.jdbc().queryForList("SELECT a.* FROM finance_ad_accounts a WHERE "+DUE_ACCOUNT
                    +" AND "+visibleAccount()+" AND a.discovery_next_at<=UTC_TIMESTAMP(6)"+first
                    +" ORDER BY a.discovery_next_at,a.organization_id,a.advertiser_id LIMIT 1");
            return rows.isEmpty()?null:new AdWork(rows.getFirst(),true,Period.REPORT,"","");
        }
        Period period=switch(slot){
            case MONTHS, MONTHS_BASIC -> Period.MONTHS;
            case REPORT -> Period.REPORT;
            default -> Period.RECENT;
        };
        String condition=switch(period){
            case RECENT -> RECENT_DUE;
            case MONTHS -> "NOT "+RECENT_DUE+" AND "+MONTH_DUE;
            case REPORT -> "NOT "+RECENT_DUE+" AND NOT "+MONTH_DUE;
        };
        // Older report pages yield while another due, visible source can fill a priority date.
        String deferHistory=period==Period.REPORT?"""
                AND NOT EXISTS(SELECT 1 FROM finance_ad_streams t JOIN finance_ad_accounts a
                  ON a.organization_id=t.organization_id AND a.advertiser_id=t.advertiser_id
                  WHERE %s AND %s AND t.next_at<=UTC_TIMESTAMP(6) AND %s AND (%s OR %s))
                """.formatted(DUE_ACCOUNT,visibleAccount(),FinanceAdAutomation.VISIBLE_STREAM,RECENT_DUE,MONTH_DUE):"";
        String sourceFilter=switch(slot){
            case FIRST_REPORT -> " AND t.last_success_at IS NULL AND JSON_EXTRACT(t.checkpoint_json,'$.from') IS NULL";
            case RECENT_BASIC, MONTHS_BASIC -> " AND t.channel='BASIC'";
            default -> "";
        };
        String order=period.priority()?"(JSON_EXTRACT(t.checkpoint_json,'$."+period.key+"') IS NOT NULL) DESC,"
                +" (t.channel='GMV' AND EXISTS(SELECT 1 FROM finance_ad_stores d WHERE d.organization_id=t.organization_id"
                +" AND d.advertiser_id=t.advertiser_id AND d.store_id=t.store_id AND d.match_status='MATCHED')) DESC, "
                :"(JSON_EXTRACT(t.checkpoint_json,'$.from') IS NOT NULL) DESC, ";
        order+=slot==Work.FIRST_REPORT?"t.next_at DESC":"t.next_at";
        var rows=repo.jdbc().queryForList("""
                SELECT a.*,t.channel dispatch_channel,t.store_id dispatch_store
                FROM finance_ad_accounts a JOIN finance_ad_streams t
                  ON t.organization_id=a.organization_id AND t.advertiser_id=a.advertiser_id
                WHERE %s AND %s AND t.next_at<=UTC_TIMESTAMP(6) AND %s AND %s%s%s
                ORDER BY %s,a.organization_id,a.advertiser_id,t.channel,t.store_id LIMIT 1
                """.formatted(DUE_ACCOUNT,visibleAccount(),FinanceAdAutomation.VISIBLE_STREAM,condition,sourceFilter,deferHistory,order));
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        return new AdWork(row,false,period,text(row.get("dispatch_channel")),text(row.get("dispatch_store")));
    }
    private void execute(AdWork work){
        var account=work.account();
        Scope s=new Scope(text(account.get("organization_id")),text(account.get("user_id")));String id=text(account.get("advertiser_id"));
        String lease=UUID.randomUUID().toString();
        String where="organization_id=? AND user_id=? AND advertiser_id=? AND lease_token=?";
        if(repo.jdbc().update("UPDATE finance_ad_accounts SET status='RUNNING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)"
                +" WHERE organization_id=? AND user_id=? AND advertiser_id=? AND shop_id=? AND enabled=TRUE"
                +" AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",lease,s.organizationId(),s.userId(),id,text(account.get("shop_id")))!=1)return;
        try{
            new FinanceAdAutomation(this).tick(s,lease,work);
        }catch(Exception e){
            long seconds=Math.min(21600,120*(1L<<Math.min(7,number(account.get("failures")))));
            repo.jdbc().update("UPDATE finance_ad_accounts SET status='ERROR',error_message=?,failures=failures+1,lease_until=NULL,lease_token=NULL,next_at=? WHERE "+where,
                    "广告同步失败，请检查授权与报表权限；已保存同步进度",Timestamp.from(Instant.now().plusSeconds(seconds)),s.organizationId(),s.userId(),id,lease);
        }
    }
}
