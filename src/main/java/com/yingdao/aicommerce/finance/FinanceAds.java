package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.ShopCredentialCipher;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceAds {
    static final List<String> ADVERTISER_FIELDS=List.of("advertiser_id","name","currency","timezone");
    final FinanceRepository repo;final FinanceService finance;final MarketplaceHttp http;final ShopCredentialCipher cipher;
    private final java.util.concurrent.atomic.AtomicBoolean verified=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-ad-sync");t.setDaemon(true);return t;});
    public FinanceAds(FinanceRepository repo,FinanceService finance,MarketplaceHttp http,ShopCredentialCipher cipher){
        this.repo=repo;this.finance=finance;this.http=http;this.cipher=cipher;
    }
    @Autowired
    public FinanceAds(FinanceRepository repo,FinanceService finance,FinanceAdHttp transport,ShopCredentialCipher cipher){
        this(repo,finance,transport.transport(),cipher);
    }
    public List<Map<String,Object>> list(Scope s){
        return repo.jdbc().queryForList("SELECT advertiser_id,name,shop_id,currency,enabled,status,error_message,next_at,last_success_at,gmv_store_id,gmv_status,gmv_error,timezone"
                +" FROM finance_ad_accounts a WHERE organization_id=? AND "+visibleAccount()+" ORDER BY name",s.organizationId());
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
            gmv_store_id=VALUES(gmv_store_id),gmv_status=VALUES(gmv_status),gmv_error=NULL,timezone=VALUES(timezone),checkpoint_json=NULL,
            enabled=TRUE,status='READY',error_message=NULL,failures=0,lease_token=NULL,lease_until=NULL,next_at=UTC_TIMESTAMP(6)
            """,storage.organizationId(),storage.userId(),id,first(advertiser,"name"),shopId,currency,encrypted,gmvStoreId,gmvStoreId.isBlank()?"DISABLED":"READY",first(advertiser,"timezone"));
    }
    public void pause(Scope s,String id,boolean enabled){
        if(enabled&&list(s).stream().noneMatch(a->id.equals(text(a.get("advertiser_id")))))throw bad("广告账户不存在或绑定的财务店铺已移除");
        if(repo.jdbc().update("UPDATE finance_ad_accounts SET enabled=?,next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND advertiser_id=?",
                enabled,s.organizationId(),id)!=1)throw bad("广告账户不存在");
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
        if(!response.containsKey("code")||number(response.get("code"))!=0)
            throw new FinanceAdFailures.ProviderFailure(response.get("code"));
        return map(response.get("data"));
    }
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->{try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);if(verified.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).info("Finance ad sync ready");}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance ad dispatcher temporarily unavailable",e);}},60,30,java.util.concurrent.TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    public void tick(){
        var accounts=repo.jdbc().queryForList("SELECT * FROM finance_ad_accounts a WHERE enabled=TRUE AND "+visibleAccount()+" AND next_at<=UTC_TIMESTAMP(6)"
                +" AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY next_at LIMIT 1");
        if(accounts.isEmpty())return;var account=accounts.getFirst();
        Scope s=new Scope(text(account.get("organization_id")),text(account.get("user_id")));String id=text(account.get("advertiser_id"));
        String lease=UUID.randomUUID().toString();
        String where="organization_id=? AND user_id=? AND advertiser_id=? AND lease_token=?";
        if(repo.jdbc().update("UPDATE finance_ad_accounts SET status='RUNNING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)"
                +" WHERE organization_id=? AND user_id=? AND advertiser_id=? AND shop_id=? AND enabled=TRUE"
                +" AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",lease,s.organizationId(),s.userId(),id,text(account.get("shop_id")))!=1)return;
        try{
            String token=cipher.decrypt(text(account.get("access_token_encrypted")),s.organizationId(),"finance:"+s.userId()+":"+id);
            Map<String,Object> cp=account.get("checkpoint_json")==null?new LinkedHashMap<>():new LinkedHashMap<>(repo.read(text(account.get("checkpoint_json"))));
            LocalDate today=LocalDate.now(ZoneOffset.UTC).plusDays(1);
            // Covers every advertiser timezone; the report's stat_time_day remains authoritative.
            if(cp.isEmpty()){
                boolean nightly=account.get("last_success_at")!=null&&instant(account.get("last_success_at")).atZone(ZoneOffset.UTC).toLocalDate().isBefore(LocalDate.now(ZoneOffset.UTC));
                cp.put("from",today.minusDays(account.get("last_success_at")==null?90:nightly?31:7).toString());cp.put("until",today.toString());cp.put("page",1);
            }
            cp.putIfAbsent("run",UUID.randomUUID().toString());
            repo.jdbc().update("UPDATE finance_ad_accounts SET checkpoint_json=? WHERE "+where,repo.write(cp),s.organizationId(),s.userId(),id,lease);
            LocalDate from=LocalDate.parse(text(cp.get("from"))),to=from.plusDays(6);LocalDate until=LocalDate.parse(text(cp.get("until")));if(to.isAfter(until))to=until;
            int page=(int)Math.max(1,number(cp.get("page")));
            var q=new LinkedHashMap<String,Object>(Map.of("advertiser_id",id,"report_type","BASIC","data_level","AUCTION_ADVERTISER",
                    "dimensions",repo.write(List.of("stat_time_day")),"metrics",repo.write(List.of("spend","impressions","clicks","conversion")),
                    "start_date",from.toString(),"end_date",to.toString(),"page",page,"page_size",100));
            boolean gmv="GMV".equals(cp.get("channel"));
            if(gmv){q=new LinkedHashMap<>(Map.of("advertiser_id",id,"store_ids",repo.write(List.of(text(account.get("gmv_store_id")))),
                    "dimensions",repo.write(List.of("advertiser_id","stat_time_day")),"metrics",repo.write(List.of("cost","orders","gross_revenue","net_cost")),
                    "start_date",from.toString(),"end_date",to.toString(),"page",page,"page_size",100));}
            Map<String,Object> data;
            try{data=get(gmv?"/gmv_max/report/get/":"/report/integrated/get/",token,q);}
            catch(Exception ex){
                if(!gmv)throw ex;
                repo.jdbc().update("UPDATE finance_ad_accounts SET gmv_status='ERROR',gmv_error='GMV Max 报表未授权或店铺 ID 不匹配，请核对广告权限' WHERE "+where,s.organizationId(),s.userId(),id,lease);
                // Never block ordinary advertising costs because the optional GMV report is unavailable.
                cp.put("gmvFailed",true);data=Map.of("list",List.of(),"page_info",Map.of("total_page",1));
            }
            for(var row:FinanceMarketplace.requiredRows(data,"list")){
                var dim=map(row.get("dimensions"));var metrics=map(row.get("metrics"));
                LocalDate date=FinanceData.date(dim.get("stat_time_day"),"UTC");BigDecimal spend=amount(metrics,gmv?"cost":"spend");
                if(spend==null)throw bad("广告报表未返回消耗字段");
                String shop=text(account.get("shop_id"));if(shop.isBlank())shop="UNASSIGNED";
                repo.save(s,new Entry(shop,"TIKTOK_SHOP",gmv?"AD_GMV":"AD",id+":"+date,"","",gmv?"GMV Max 专项分析":"广告账户总消耗","REPORTED",date,
                        text(account.get("currency")),spend,gmv?amount(metrics,"gross_revenue"):null,gmv?null:metricCount(metrics,"impressions"),gmv?null:metricCount(metrics,"clicks"),amount(metrics,gmv?"orders":"conversion"),"API",
                        Map.of("advertiser_id",id,"dimensions",dim,"metrics",metrics,"_finance_sync",cp.get("run"))));
            }
            if(!data.containsKey("page_info"))throw bad("广告报表未返回分页状态，已保留同步进度");
            boolean more=number(at(data,"page_info.total_page"))>page;
            if(more)cp.put("page",page+1);
            else{
                if(!gmv||!Boolean.TRUE.equals(cp.get("gmvFailed"))){
                    repo.jdbc().update("DELETE FROM finance_records WHERE organization_id=? AND user_id=? AND shop_id=? AND kind=? AND source='API'"
                                    +" AND record_date>=? AND record_date<=? AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.advertiser_id'))=?"
                                    +" AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_sync')),'')<>?",
                            s.organizationId(),s.userId(),text(account.get("shop_id")).isBlank()?"UNASSIGNED":text(account.get("shop_id")),gmv?"AD_GMV":"AD",from.toString(),to.toString(),id,cp.get("run"));
                    if(gmv)repo.jdbc().update("UPDATE finance_ad_accounts SET gmv_status='SUCCESS',gmv_error=NULL WHERE "+where,s.organizationId(),s.userId(),id,lease);
                }
                if(!gmv&&!text(account.get("gmv_store_id")).isBlank()){cp.put("channel","GMV");cp.put("page",1);}
                else if(to.isBefore(until)){cp.put("from",to.plusDays(1).toString());cp.put("page",1);cp.remove("channel");cp.remove("run");cp.remove("gmvFailed");}
                else cp.clear();
            }
            boolean done=cp.isEmpty();
            repo.jdbc().update("UPDATE finance_ad_accounts SET checkpoint_json=?,status=?,error_message=NULL,failures=0,lease_until=NULL,lease_token=NULL,"
                    +"last_success_at=CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE last_success_at END,next_at=? WHERE "+where,
                    done?null:repo.write(cp),done?"SUCCESS":"SYNCING",done,Timestamp.from(Instant.now().plusSeconds(done?3600:30)),s.organizationId(),s.userId(),id,lease);
        }catch(Exception e){
            long seconds=Math.min(21600,120*(1L<<Math.min(7,number(account.get("failures")))));
            repo.jdbc().update("UPDATE finance_ad_accounts SET status='ERROR',error_message=?,failures=failures+1,lease_until=NULL,lease_token=NULL,next_at=? WHERE "+where,
                    "广告同步失败，请检查授权与报表权限；已保存同步进度",Timestamp.from(Instant.now().plusSeconds(seconds)),s.organizationId(),s.userId(),id,lease);
        }
    }
}
