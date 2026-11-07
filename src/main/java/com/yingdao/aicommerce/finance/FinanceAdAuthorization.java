package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.ShopCredentialCipher;
import com.yingdao.aicommerce.shop.application.ShopIntegrationSettings;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Independent TikTok for Business OAuth; shop application credentials are never reused. */
@Service
public class FinanceAdAuthorization {
    static final String PLATFORM="TIKTOK_ADS",PREFIX="finance_ads_";
    private final FinanceRepository repo;
    private final FinanceAds ads;
    private final MarketplaceHttp http;
    private final ShopCredentialCipher cipher;
    private final ShopIntegrationSettings settings;
    private final FinanceShopAuthorization shopAuthorization;
    private final TransactionTemplate transactions;
    private final FinanceAdApplicationSettings applicationSettings;

    public FinanceAdAuthorization(FinanceRepository repo,FinanceAds ads,MarketplaceHttp http,
            ShopCredentialCipher cipher,ShopIntegrationSettings settings,
            FinanceShopAuthorization shopAuthorization,PlatformTransactionManager manager,
            FinanceAdApplicationSettings applicationSettings){
        this.repo=repo;this.ads=ads;this.http=http;this.cipher=cipher;this.settings=settings;
        this.shopAuthorization=shopAuthorization;this.transactions=new TransactionTemplate(manager);
        this.applicationSettings=applicationSettings;
    }
    String callbackUrl(){return settings.callbackBaseUrl()+"/api/v1/shop-authorizations/callback/tiktok_ads";}
    String aad(Scope s){return "finance-ads-app:"+s.userId();}
    Map<String,Object> application(Scope s){
        var configured=applicationSettings.current();
        if(configured!=null)return Map.of("app_id",configured.appId(),"revision",configured.revision(),
                "user_id",companyScope(s.organizationId()).userId(),"source","SERVER");
        return legacyApplication(s);
    }
    Map<String,Object> legacyApplication(Scope s){
        var rows=repo.jdbc().queryForList("SELECT * FROM finance_ad_applications WHERE organization_id=?",s.organizationId());
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    Map<String,Object> sessionApplication(Scope s,Map<String,Object> context){
        return "SERVER".equals(context.get("applicationSource"))?application(s):legacyApplication(s);
    }
    String applicationSecret(Scope s,Map<String,Object> app){
        if("SERVER".equals(app.get("source"))){
            var configured=applicationSettings.current();
            if(configured==null||!configured.revision().equals(app.get("revision")))throw bad("广告应用配置已变更");
            return configured.secret();
        }
        return cipher.decrypt(text(app.get("secret_encrypted")),s.organizationId(),"finance-ads-app:"+text(app.get("user_id")));
    }
    public Map<String,Object> configuration(Scope s){
        var app=application(s);
        return Map.of("configured",!app.isEmpty(),"managed",true,"callbackUrl",callbackUrl());
    }
    public void configure(Scope s,String appId,String secret){
        if(applicationSettings.current()!=null)throw bad("广告应用由服务端统一配置，无需在页面填写");
        appId=text(appId).trim();secret=text(secret).trim();
        if(!appId.matches("\\d{5,30}"))throw bad("请填写 TikTok for Business 广告应用 App ID");
        var old=application(s);
        final Scope storage=old.isEmpty()?companyScope(s.organizationId()):new Scope(s.organizationId(),text(old.get("user_id")));
        if(secret.isBlank()&&!old.isEmpty()&&appId.equals(text(old.get("app_id"))))
            secret=cipher.decrypt(text(old.get("secret_encrypted")),storage.organizationId(),aad(storage));
        if(secret.length()<10||secret.length()>4096)throw bad("请填写该广告应用的 App Secret");
        final String id=appId,encrypted=cipher.encrypt(secret,storage.organizationId(),aad(storage));
        transactions.executeWithoutResult(status->{
            repo.jdbc().update("""
                INSERT INTO finance_ad_applications(organization_id,user_id,app_id,secret_encrypted,revision)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE app_id=VALUES(app_id),secret_encrypted=VALUES(secret_encrypted),
                revision=VALUES(revision),updated_at=UTC_TIMESTAMP(6)
                """,storage.organizationId(),storage.userId(),id,encrypted,UUID.randomUUID().toString());
            repo.jdbc().update("UPDATE finance_authorization_sessions SET status='CANCELLED' WHERE organization_id=? AND user_id=? AND platform=? AND status='PENDING'",
                    storage.organizationId(),storage.userId(),PLATFORM);
        });
    }
    public Map<String,Object> start(Scope s,String advertiserId,String shopId,String gmvStoreId){
        var app=application(s);if(app.isEmpty())throw bad("广告授权暂未准备好，请联系管理员完成服务端配置");
        Scope storage=new Scope(s.organizationId(),text(app.get("user_id")));
        advertiserId=text(advertiserId).trim();shopId=text(shopId).trim();gmvStoreId=text(gmvStoreId).trim();
        if(!advertiserId.isBlank()&&!advertiserId.matches("\\d{5,30}"))throw bad("广告账户 ID 格式不正确");
        if(advertiserId.isBlank()&&(!shopId.isBlank()||!gmvStoreId.isBlank()))
            throw bad("绑定专属店铺时请指定广告账户 ID；跨店批量授权请保留未分配");
        ads.validateDestination(s,shopId,gmvStoreId);
        var context=Map.of("appId",text(app.get("app_id")),"revision",text(app.get("revision")),
                "advertiserId",advertiserId,"shopId",shopId,"gmvStoreId",gmvStoreId,
                "applicationSource","SERVER".equals(app.get("source"))?"SERVER":"LEGACY");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String state=PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expires=Instant.now().plus(settings.stateTtl());
        repo.transaction(()->{
            if(!text(context.get("shopId")).isBlank())repo.requireShopWrite(s,text(context.get("shopId")));
            repo.jdbc().update("""
            INSERT INTO finance_authorization_sessions(id,organization_id,user_id,platform,state_hash,expires_at,context_json)
            VALUES(?,?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),storage.organizationId(),storage.userId(),PLATFORM,FinanceShopAuthorization.hash(state),Timestamp.from(expires),repo.write(context));
            return null;
        });
        String url=MarketplaceHttp.uri("https://business-api.tiktok.com","/portal/auth",
                Map.of("app_id",app.get("app_id"),"state",state,"redirect_uri",callbackUrl())).toString();
        return Map.of("authorizationUrl",url,"expiresAt",expires,"platform",PLATFORM);
    }
    public String redirect(boolean success,String message){return shopAuthorization.redirect(success,message);}
    public String complete(String state,String code,String providerError){
        if(state==null||!state.startsWith(PREFIX)||state.length()>200)throw bad("广告授权请求无效");
        var sessions=repo.jdbc().queryForList("SELECT * FROM finance_authorization_sessions WHERE state_hash=? AND platform=?",
                FinanceShopAuthorization.hash(state),PLATFORM);
        if(sessions.size()!=1)throw bad("广告授权请求不存在");
        var session=sessions.getFirst();String sessionId=text(session.get("id"));
        if(repo.jdbc().update("UPDATE finance_authorization_sessions SET status='PROCESSING' WHERE id=? AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)",sessionId)!=1)
            throw bad("广告授权链接已过期或使用，请重新生成");
        Scope s=new Scope(text(session.get("organization_id")),text(session.get("user_id")));
        var stage=FinanceAdFailures.Stage.CALLBACK;
        try{
            if(!text(providerError).isBlank()||text(code).isBlank()||code.length()>4096)throw bad("广告授权未完成");
            stage=FinanceAdFailures.Stage.CONFIGURATION;
            var context=repo.read(text(session.get("context_json")));var app=sessionApplication(s,context);
            if(!Objects.equals(app.get("revision"),context.get("revision")))throw bad("广告应用配置已变更");
            String secret=applicationSecret(s,app);
            stage=FinanceAdFailures.Stage.TOKEN_EXCHANGE;
            var result=http.postJson(URI.create("https://business-api.tiktok.com/open_api/v1.3/oauth2/access_token/"),Map.of(),
                    repo.write(Map.of("app_id",app.get("app_id"),"secret",secret,"auth_code",code)));
            var grant=successful(result);String token=text(grant.get("access_token"));
            if(token.length()<10||token.length()>4096)throw bad("平台没有返回广告授权令牌");
            stage=FinanceAdFailures.Stage.ACCOUNT_DISCOVERY;
            var ids=new LinkedHashSet<String>();
            Object rawIds=grant.get("advertiser_ids");
            if(rawIds instanceof List<?> list)list.forEach(id->ids.add(text(id)));
            else {
                var available=successful(http.get(MarketplaceHttp.uri("https://business-api.tiktok.com","/open_api/v1.3/oauth2/advertiser/get/",
                        Map.of("app_id",app.get("app_id"),"secret",secret,"access_token",token)),Map.of()));
                rows(available.get("list")).forEach(row->ids.add(text(row.get("advertiser_id"))));
            }
            if(ids.isEmpty()||ids.stream().anyMatch(id->!id.matches("\\d{5,30}")))throw bad("本次授权没有可用广告账户");
            String target=text(context.get("advertiserId"));
            if(!target.isBlank()){if(!ids.contains(target))throw bad("本次授权没有包含指定广告账户");ids.retainAll(Set.of(target));}
            var info=new LinkedHashMap<String,Map<String,Object>>();
            stage=FinanceAdFailures.Stage.ACCOUNT_INFO;
            var idList=new ArrayList<>(ids);
            for(int i=0;i<idList.size();i+=100){
                var batch=idList.subList(i,Math.min(i+100,idList.size()));
                rows(ads.get("/advertiser/info/",token,Map.of("advertiser_ids",repo.write(batch),
                        "fields",repo.write(FinanceAds.ADVERTISER_FIELDS))).get("list"))
                    .forEach(row->{String id=text(row.get("advertiser_id"));if(ids.contains(id))info.put(id,row);});
            }
            if(!info.keySet().equals(ids))throw bad("未能确认全部授权账户，未保存");
            // Validate first, then persist all accounts atomically; never partly attach a grant.
            stage=FinanceAdFailures.Stage.ACCOUNT_SAVE;
            transactions.executeWithoutResult(status->{
                if("SERVER".equals(context.get("applicationSource"))){
                    if(!Objects.equals(application(s).get("revision"),context.get("revision")))throw bad("广告应用配置已变更");
                }else{
                    var current=repo.jdbc().queryForList("SELECT revision FROM finance_ad_applications WHERE organization_id=? AND user_id=? FOR UPDATE",s.organizationId(),s.userId());
                    if(current.size()!=1||!Objects.equals(current.getFirst().get("revision"),context.get("revision")))throw bad("广告应用配置已变更");
                }
                for(String id:ids){
                    String shop=text(context.get("shopId")),gmv=text(context.get("gmvStoreId"));
                    if(target.isBlank()){
                        var previous=repo.jdbc().queryForList("SELECT shop_id,gmv_store_id FROM finance_ad_accounts WHERE organization_id=? AND advertiser_id=?",s.organizationId(),id);
                        if(!previous.isEmpty()){shop=text(previous.getFirst().get("shop_id"));gmv=text(previous.getFirst().get("gmv_store_id"));}
                    }
                    ads.connectVerified(s,id,shop,token,gmv,info.get(id),instant(session.get("created_at")));
                }
                repo.jdbc().update("UPDATE finance_authorization_sessions SET status='SUCCESS',error_message=NULL WHERE id=?",sessionId);
            });
            return redirect(true,"已连接 "+ids.size()+" 个广告账户，广告数据开始同步");
        }catch(RuntimeException e){
            String message=FinanceAdFailures.message(stage,e);
            repo.jdbc().update("UPDATE finance_authorization_sessions SET status='FAILED',error_message=? WHERE id=?",message,sessionId);
            return redirect(false,message);
        }
    }
    static Map<String,Object> successful(Map<String,Object> response){
        if(!response.containsKey("code")||number(response.get("code"))!=0)throw new FinanceAdFailures.ProviderFailure(response.get("code"));
        return map(response.get("data"));
    }
}
