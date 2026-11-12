package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Independent read-only Ads Service OAuth and shop-day CPC collection. Never posts profit entries. */
@Service
public class FinanceShopeeAds {
    static final String PLATFORM="SHOPEE_ADS",PREFIX="finance_shopee_ads_";
    private final FinanceRepository repo;private final ShopCredentialCipher cipher;
    private final FinanceShopeeAdsSettings settings;private final ShopIntegrationSettings urls;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-shopee-ads");t.setDaemon(true);return t;});
    public FinanceShopeeAds(FinanceRepository repo,ShopCredentialCipher cipher,FinanceShopeeAdsSettings settings,ShopIntegrationSettings urls){this.repo=repo;this.cipher=cipher;this.settings=settings;this.urls=urls;}
    @PostConstruct void startWorker(){worker.scheduleWithFixedDelay(()->{try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shopee Ads dispatcher deferred: {}",failure.getClass().getSimpleName());}},60,5,java.util.concurrent.TimeUnit.SECONDS);}
    @PreDestroy void stopWorker(){worker.shutdownNow();}
    String callback(){return urls.callbackBaseUrl()+"/api/v1/shop-authorizations/callback/shopee_ads";}
    String redirect(boolean success){return urls.frontendBaseUrl()+"/?financeAuth="+(success?"success":"error")+"&financeMessage="+java.net.URLEncoder.encode(success?"Shopee 广告授权已保存，按官方店铺编号自动关联并开始采集 CPC 日报":"Shopee 广告授权未完成，请核对正式 Ads 应用、店铺权限并重新生成链接",java.nio.charset.StandardCharsets.UTF_8)+"#finance-shops";}
    Map<String,Object> start(Scope scope){
        var app=settings.current();if(app==null)throw bad("Shopee Ads 需先通过正式审核并配置独立应用，不能使用 ERP 或测试应用令牌");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);String state=PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(random);Instant expiry=Instant.now().plus(urls.stateTtl());
        String link=app.client().authorizationUrl(callback(),state);Scope storage=companyScope(scope.organizationId());
        repo.jdbc().update("INSERT INTO finance_authorization_sessions(id,organization_id,user_id,platform,state_hash,expires_at,context_json) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),storage.organizationId(),storage.userId(),PLATFORM,hash(state),Timestamp.from(expiry),repo.write(Map.of("partnerId",app.partnerId(),"revision",app.revision())));
        return Map.of("authorizationUrl",link,"expiresAt",expiry,"platform",PLATFORM);
    }
    String complete(String state,String code,Map<String,String> query,String error){
        if(state==null||!state.startsWith(PREFIX)||state.length()>200)throw bad("Shopee 广告授权链接无效");
        var sessions=repo.jdbc().queryForList("SELECT * FROM finance_authorization_sessions WHERE platform=? AND state_hash=?",PLATFORM,hash(state));
        if(sessions.size()!=1)throw bad("Shopee 广告授权链接不存在");var session=sessions.getFirst();String id=text(session.get("id"));
        if(repo.jdbc().update("UPDATE finance_authorization_sessions SET status='PROCESSING' WHERE id=? AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)",id)!=1)throw bad("Shopee 广告授权链接已过期或已使用");
        try{
            if(!text(error).isBlank()||text(code).isBlank()||code.length()>4096)throw bad("授权未完成");
            var app=settings.current();var context=repo.read(text(session.get("context_json")));
            if(app==null||!app.partnerId().equals(context.get("partnerId"))||!app.revision().equals(context.get("revision")))throw bad("应用配置已变化");
            var grant=app.client().exchangeAuthorizationCode(code,query);if(grant==null||grant.tokens()==null||grant.shops().isEmpty()||text(grant.tokens().accessToken()).isBlank()||text(grant.tokens().refreshToken()).isBlank()||grant.shops().size()>500)throw bad("授权店铺或令牌不完整");
            Scope storage=new Scope(text(session.get("organization_id")),text(session.get("user_id")));var seen=new HashSet<String>();String group=UUID.randomUUID().toString();
            repo.transaction(()->{
                repo.jdbc().update("INSERT INTO finance_shopee_ad_groups(organization_id,token_group_id) VALUES(?,?)",storage.organizationId(),group);
                for(var remote:grant.shops()){
                    String external=remote.externalShopId();if(!text(external).matches("\\d{1,30}")||!seen.add(external)||!Set.of("SHOP","MERCHANT").contains(text(remote.authorizationSubjectType()))||!text(remote.authorizationSubjectId()).matches("\\d{1,30}"))throw bad("官方店铺身份无效");
                    new FinanceShopDeletion(repo).checkAuthorization(storage,"SHOPEE",external,instant(session.get("created_at")));
                    String currency=currencyFor(remote.region()),timezone=timezoneFor(remote.region());if(currency.isBlank()||timezone.isBlank())throw bad("官方店铺币种或时区未知");
                    repo.jdbc().queryForList("SELECT enabled FROM finance_shopee_ad_grants WHERE organization_id=? AND external_shop_id=? FOR UPDATE",storage.organizationId(),external);
                    repo.jdbc().update("""
                        INSERT INTO finance_shopee_ad_grants(organization_id,external_shop_id,user_id,partner_id,token_group_id,name,currency,timezone,
                          authorization_subject_type,authorization_subject_id,access_token_encrypted,refresh_token_encrypted,access_token_expires_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),partner_id=VALUES(partner_id),token_group_id=VALUES(token_group_id),name=VALUES(name),currency=VALUES(currency),timezone=VALUES(timezone),
                          authorization_subject_type=VALUES(authorization_subject_type),authorization_subject_id=VALUES(authorization_subject_id),access_token_encrypted=VALUES(access_token_encrypted),refresh_token_encrypted=VALUES(refresh_token_encrypted),access_token_expires_at=VALUES(access_token_expires_at),
                          status=IF(enabled,'READY','PAUSED'),error_message=NULL,failures=0,next_at=UTC_TIMESTAMP(6),lease_token=NULL,lease_until=NULL
                        """,storage.organizationId(),external,storage.userId(),app.partnerId(),group,bounded(remote.name()),currency,timezone,remote.authorizationSubjectType(),remote.authorizationSubjectId(),encrypt(storage,external,grant.tokens().accessToken()),encrypt(storage,external,grant.tokens().refreshToken()),timestamp(grant.tokens().accessTokenExpiresAt()));
                }
                repo.jdbc().update("UPDATE finance_authorization_sessions SET status='SUCCESS',error_message=NULL WHERE id=? AND status='PROCESSING'",id);return null;
            });return redirect(true);
        }catch(RuntimeException failure){repo.jdbc().update("UPDATE finance_authorization_sessions SET status='FAILED',error_message='Shopee Ads 授权未完成，请检查正式应用及店铺授权' WHERE id=?",id);return redirect(false);}
    }
    Map<String,Object> status(Scope scope){
        boolean configured;String configurationError="",partner="";
        try{var app=settings.current();configured=app!=null;if(app!=null)partner=app.partnerId();}catch(RuntimeException invalid){configured=false;configurationError="服务端独立应用配置暂不可用";}
        var rows=repo.jdbc().queryForList("""
            SELECT g.external_shop_id,g.partner_id,g.name,g.currency,g.enabled,g.status,g.error_message,g.last_success_at,g.next_at,
              c.id shop_id,c.group_id,c.display_name shop_alias,c.name shop_name,
              (SELECT COUNT(*) FROM finance_shopee_ad_daily d WHERE d.organization_id=g.organization_id AND d.external_shop_id=g.external_shop_id) records
            FROM finance_shopee_ad_grants g LEFT JOIN finance_connections c ON c.organization_id=g.organization_id AND c.platform='SHOPEE' AND c.external_shop_id=g.external_shop_id AND c.status<>'REMOVED'
              AND NOT EXISTS(SELECT 1 FROM finance_connections other WHERE other.organization_id=c.organization_id AND other.platform=c.platform AND other.external_shop_id=c.external_shop_id AND other.id<>c.id AND other.status<>'REMOVED')
            WHERE g.organization_id=? AND g.status<>'REMOVED' ORDER BY g.external_shop_id LIMIT 501
            """,scope.organizationId());
        if(rows.size()>500)throw bad("Shopee 广告授权店铺超过展示范围");
        for(var row:rows){if(configured&&!partner.equals(row.remove("partner_id"))){row.put("status","NEEDS_AUTHORIZATION");row.put("error_message","独立广告应用已变化，请重新授权；历史费用继续保留");}else row.remove("partner_id");}
        return Map.of("configured",configured,"configurationError",configurationError,"callbackUrl",callback(),"coverageBasis","官方店铺 CPC 日报 expense；尚未核实 GMS 与 CPC 的覆盖关系，不叠加 GMS。现金、授信、赠款和净额接口未拆分。","items",rows);
    }
    void pause(Scope scope,String external,boolean enabled){
        if(repo.jdbc().update("UPDATE finance_shopee_ad_grants SET enabled=?,status=?,lease_token=NULL,lease_until=NULL WHERE organization_id=? AND external_shop_id=?",enabled,enabled?"READY":"PAUSED",scope.organizationId(),external)!=1)throw bad("未找到本公司的 Shopee 广告授权");
    }
    void tick(){
        var app=settings.current();if(app==null)return;
        var due=repo.jdbc().queryForList("""
            SELECT g.organization_id,g.external_shop_id,g.token_group_id FROM finance_shopee_ad_grants g
            WHERE g.enabled=TRUE AND g.partner_id=? AND g.next_at<=UTC_TIMESTAMP(6) AND (g.lease_until IS NULL OR g.lease_until<UTC_TIMESTAMP(6))
              AND EXISTS(SELECT 1 FROM finance_connections c WHERE c.organization_id=g.organization_id AND c.external_shop_id=g.external_shop_id AND c.platform='SHOPEE' AND c.enabled=TRUE AND c.status='ACTIVE')
            ORDER BY g.next_at,g.organization_id,g.external_shop_id LIMIT 30
            """,app.partnerId());
        for(var candidate:due){
            String org=text(candidate.get("organization_id")),external=text(candidate.get("external_shop_id")),lease=UUID.randomUUID().toString();
            var claimed=repo.transaction(()->{
                if(repo.jdbc().queryForList("SELECT token_group_id FROM finance_shopee_ad_groups WHERE organization_id=? AND token_group_id=? AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) FOR UPDATE SKIP LOCKED",org,candidate.get("token_group_id")).isEmpty())return Map.<String,Object>of();
                var group=repo.jdbc().queryForList("SELECT * FROM finance_shopee_ad_grants WHERE organization_id=? AND external_shop_id=? AND token_group_id=? FOR UPDATE",org,external,candidate.get("token_group_id"));
                var selected=group.stream().filter(r->Boolean.TRUE.equals(r.get("enabled"))&&app.partnerId().equals(r.get("partner_id"))&&!instant(r.get("next_at")).isAfter(Instant.now())&&(r.get("lease_until")==null||!instant(r.get("lease_until")).isAfter(Instant.now()))).findFirst();
                if(selected.isEmpty())return Map.<String,Object>of();
                if(repo.jdbc().queryForList("SELECT platform FROM finance_api_pacing WHERE platform='SHOPEE_ADS' AND channel='CPC' AND next_allowed_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED").isEmpty())return Map.<String,Object>of();
                repo.jdbc().update("UPDATE finance_api_pacing SET next_allowed_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL interval_seconds SECOND) WHERE platform='SHOPEE_ADS' AND channel='CPC'");
                repo.jdbc().update("UPDATE finance_shopee_ad_groups SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND token_group_id=?",lease,org,candidate.get("token_group_id"));
                repo.jdbc().update("UPDATE finance_shopee_ad_grants SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),status='SYNCING' WHERE organization_id=? AND external_shop_id=?",lease,org,external);return selected.get();
            });
            if(claimed.isEmpty())continue;
            try{collect(app,claimed,lease);}catch(RuntimeException failure){failed(claimed,lease,failure);}finally{repo.jdbc().update("UPDATE finance_shopee_ad_groups SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND token_group_id=? AND lease_token=?",org,claimed.get("token_group_id"),lease);}return;
        }
    }
    private void collect(FinanceShopeeAdsSettings.Application app,Map<String,Object> grant,String lease){
        String org=text(grant.get("organization_id")),external=text(grant.get("external_shop_id"));Scope storage=new Scope(org,text(grant.get("user_id")));
        var tokens=new MarketplaceAccess.TokenSet(decrypt(storage,external,grant.get("access_token_encrypted")),decrypt(storage,external,grant.get("refresh_token_encrypted")),grant.get("access_token_expires_at")==null?null:instant(grant.get("access_token_expires_at")),null,List.of());
        var shop=new FinanceShopAuthorization.FinanceShop(Map.of("organization_id",org,"user_id",storage.userId(),"id",external,"platform","SHOPEE","external_shop_id",external,"name",text(grant.get("name")),"authorization_subject_type",grant.get("authorization_subject_type"),"authorization_subject_id",grant.get("authorization_subject_id")));
        if(tokens.accessTokenExpiresAt()==null||!tokens.accessTokenExpiresAt().isAfter(Instant.now().plusSeconds(300))){
            var refreshed=app.client().refresh(tokens,shop);if(refreshed==null||text(refreshed.accessToken()).isBlank()||text(refreshed.refreshToken()).isBlank())throw bad("广告授权刷新不完整");
            if(!saveRefreshed(app,grant,lease,refreshed))return;
            tokens=refreshed;
        }
        ZoneId zone=ZoneId.of(text(grant.get("timezone")));LocalDate today=LocalDate.now(zone);
        var cp=grant.get("checkpoint_json")==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(grant.get("checkpoint_json"))));
        if(!cp.containsKey("from"))cp.putAll(FinanceShopeeAdsApi.plan(today,grant.get("last_success_at")==null?null:instant(grant.get("last_success_at")).atZone(zone).toLocalDate()));
        if(LocalDate.parse(text(cp.get("from"))).isBefore(today.minusMonths(6))){
            // Expired provider windows cannot be retried. Keep their coverage unknown while resuming readable history.
            cp.clear();cp.put("from",today.minusMonths(6).toString());cp.put("until",today.toString());cp.put("unavailableBefore",today.minusMonths(6).toString());
        }
        LocalDate from=LocalDate.parse(text(cp.get("from"))),until=LocalDate.parse(text(cp.get("until"))),to=FinanceShopeeAdsApi.windowEnd(from,until);
        if(from.isAfter(to)||until.isAfter(today)||from.isBefore(today.minusMonths(6)))throw bad("广告历史窗口超出官方可读取范围");
        boolean forward=from.equals(to)&&from.equals(today.minusMonths(6));
        LocalDate requestFrom=from.equals(to)&&!forward?from.minusDays(1):from,requestTo=forward?to.plusDays(1):to;final LocalDate end=to;
        int saved=repo.jdbc().update("UPDATE finance_shopee_ad_grants SET checkpoint_json=? WHERE organization_id=? AND external_shop_id=? AND lease_token=?",repo.write(cp),org,external,lease);if(saved!=1)return;
        var response=app.client().shopGetExact(FinanceShopeeAdsApi.PATH,new MarketplaceAccess.Connection(shop,tokens),FinanceShopeeAdsApi.query(requestFrom,requestTo));var facts=FinanceShopeeAdsApi.daily(response,requestFrom,requestTo);
        repo.transaction(()->{
            // Shop deletion also locks the connection before the grant.
            var connected=repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND external_shop_id=? AND platform='SHOPEE' AND status='ACTIVE' AND enabled=TRUE FOR SHARE",org,external);
            if(!owns(org,external,lease))return null;
            if(connected.size()!=1){release(org,external,lease,"WAITING_SHOP",cp,false);return null;}
            var dates=new HashSet<String>();facts.forEach(r->dates.add(text(r.get("record_date"))));
            var previous=repo.jdbc().queryForList("SELECT record_date,currency FROM finance_shopee_ad_daily WHERE organization_id=? AND external_shop_id=? AND record_date BETWEEN ? AND ? FOR UPDATE",org,external,requestFrom,requestTo);
            if(previous.stream().anyMatch(r->!dates.contains(text(r.get("record_date")))||!text(grant.get("currency")).equals(r.get("currency"))))throw bad("广告刷新没有完整返回已有日期或币种发生变化，保留原消耗");
            for(var row:facts){String day=text(row.get("record_date")),currency=text(grant.get("currency"));String identity=hash("shopee-cpc\0"+org+"\0"+external+"\0"+day);
                repo.jdbc().update("""
                    INSERT INTO finance_shopee_ad_daily(organization_id,external_shop_id,source_key,record_date,currency,amount,source_json)
                    VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount=VALUES(amount),source_json=VALUES(source_json),observed_at=UTC_TIMESTAMP(6)
                    """,org,external,identity,day,currency,row.get("amount"),repo.write(row.get("raw")));
            }
            boolean done=!end.isBefore(until);
            if(done){var remaining=new ArrayList<>(rows(cp.get("remaining")));String unavailable=text(cp.get("unavailableBefore"));cp.clear();if(!unavailable.isBlank())cp.put("unavailableBefore",unavailable);if(!remaining.isEmpty()){cp.putAll(remaining.removeFirst());if(!remaining.isEmpty())cp.put("remaining",remaining);done=false;}}
            else cp.put("from",end.plusDays(1).toString());
            release(org,external,lease,done?"SUCCESS":"SYNCING",cp,done);return null;
        });
    }
    private boolean saveRefreshed(FinanceShopeeAdsSettings.Application app,Map<String,Object> grant,String lease,MarketplaceAccess.TokenSet refreshed){
        String org=text(grant.get("organization_id"));
        for(int attempt=0;;attempt++){
            try{return repo.transaction(()->{
                // Lock order matches claims: group first, then its remaining grants.
                if(repo.jdbc().queryForList("SELECT token_group_id FROM finance_shopee_ad_groups WHERE organization_id=? AND token_group_id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",org,grant.get("token_group_id"),lease).isEmpty())return false;
                var siblings=repo.jdbc().queryForList("SELECT external_shop_id,user_id FROM finance_shopee_ad_grants WHERE organization_id=? AND token_group_id=? AND partner_id=? AND status<>'REMOVED' AND access_token_encrypted<>'' ORDER BY external_shop_id FOR UPDATE",org,grant.get("token_group_id"),app.partnerId());
                for(var row:siblings){String id=text(row.get("external_shop_id"));Scope owner=new Scope(org,text(row.get("user_id")));repo.jdbc().update("UPDATE finance_shopee_ad_grants SET access_token_encrypted=?,refresh_token_encrypted=?,access_token_expires_at=? WHERE organization_id=? AND external_shop_id=? AND token_group_id=? AND status<>'REMOVED'",encrypt(owner,id,refreshed.accessToken()),encrypt(owner,id,refreshed.refreshToken()),timestamp(refreshed.accessTokenExpiresAt()),org,id,grant.get("token_group_id"));}
                return true;
            });}catch(org.springframework.dao.TransientDataAccessException transientFailure){if(attempt>=2)throw transientFailure;}
        }
    }
    private boolean owns(String org,String external,String lease){return !repo.jdbc().queryForList("SELECT external_shop_id FROM finance_shopee_ad_grants WHERE organization_id=? AND external_shop_id=? AND enabled=TRUE AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",org,external,lease).isEmpty();}
    private void release(String org,String external,String lease,String status,Map<String,Object> cp,boolean done){
        boolean partial=!text(cp.get("unavailableBefore")).isBlank();
        String warning=partial?"部分历史回补窗口已超出官方可读取范围；未入库日期仍未知，已有消耗保留":null;
        repo.jdbc().update("UPDATE finance_shopee_ad_grants SET checkpoint_json=?,status=?,error_message=?,failures=0,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),last_success_at=IF(?,UTC_TIMESTAMP(6),last_success_at),lease_token=NULL,lease_until=NULL WHERE organization_id=? AND external_shop_id=? AND lease_token=?",cp.isEmpty()?null:repo.write(cp),done&&partial?"PARTIAL":status,warning,done?3600:5,done,org,external,lease);
    }
    private void failed(Map<String,Object> grant,String lease,RuntimeException failure){
        String message=text(failure.getMessage()).toLowerCase(Locale.ROOT);boolean permission=message.contains("403")||message.contains("error_api_permission");boolean auth=message.contains("token")||message.contains("auth")||message.contains("401");boolean limited=message.contains("429")||message.contains("rate_limit");
        String status=permission?"NEEDS_PERMISSION":auth?"NEEDS_AUTHORIZATION":limited?"RATE_LIMITED":"ERROR";
        String safe=permission?"Shopee Ads 报表权限未开通；检查独立正式应用及重新授权":auth?"Shopee Ads 独立授权需更新":limited?"Shopee Ads 请求限流，保留原窗口等待重试":"Shopee Ads 报表未完成或刷新不完整，保留旧数据与窗口";
        long delay=permission||auth?86400:Math.min(21600,60L*(1L<<Math.min(8,number(grant.get("failures"))+1)));
        if(limited)repo.jdbc().update("UPDATE finance_api_pacing SET next_allowed_at=GREATEST(next_allowed_at,?),throttle_count=throttle_count+1 WHERE platform='SHOPEE_ADS' AND channel='CPC'",Timestamp.from(Instant.now().plusSeconds(delay)));
        repo.jdbc().update("UPDATE finance_shopee_ad_grants SET status=?,error_message=?,failures=failures+1,next_at=?,lease_token=NULL,lease_until=NULL WHERE organization_id=? AND external_shop_id=? AND lease_token=?",status,safe,Timestamp.from(Instant.now().plusSeconds(delay)),grant.get("organization_id"),grant.get("external_shop_id"),lease);
    }
    static List<Map<String,Object>> sources(FinanceRepository repo,Scope scope,Filter filter){
        if(!filter.platform().isBlank()&&!"SHOPEE".equals(filter.platform()))return List.of();
        var args=new ArrayList<Object>(List.of(scope.organizationId(),filter.start(),filter.end()));String currency="";
        if(!filter.currency().isBlank()){currency=" AND d.currency=?";args.add(filter.currency());}
        var result=repo.jdbc().queryForList("""
            SELECT d.source_key,d.external_shop_id account_id,d.record_date,d.currency,d.amount,c.id shop_id,c.name account_name
            FROM finance_shopee_ad_daily d JOIN finance_connections c ON c.organization_id=d.organization_id AND c.platform='SHOPEE' AND c.external_shop_id=d.external_shop_id AND c.status<>'REMOVED'
              AND NOT EXISTS(SELECT 1 FROM finance_connections other WHERE other.organization_id=c.organization_id AND other.platform=c.platform AND other.external_shop_id=c.external_shop_id AND other.id<>c.id AND other.status<>'REMOVED')
            WHERE d.organization_id=? AND d.record_date BETWEEN ? AND ?
            """+currency+" LIMIT 50001",args.toArray());
        for(var row:result){row.put("record_date",text(row.get("record_date")));row.put("platform","SHOPEE");row.put("source","API_SHOPEE_CPC");row.put("campaign_id","");row.put("campaign_name","店铺 CPC 日报（未提供系列粒度）");row.put("report_type","CPC 店铺日报");row.put("funding_basis","官方 CPC 店铺日总消耗 expense；不叠加 GMS，未拆现金、授信、赠款或净额");FinanceAdFunding.completeNet(row);}return result;
    }
    private String encrypt(Scope owner,String external,String token){return cipher.encrypt(token,owner.organizationId(),"finance-shopee-ads:"+owner.userId()+":"+external);}
    private String decrypt(Scope owner,String external,Object token){return cipher.decrypt(text(token),owner.organizationId(),"finance-shopee-ads:"+owner.userId()+":"+external);}
    private static String bounded(String value){return text(value).substring(0,Math.min(250,text(value).length()));}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
}
