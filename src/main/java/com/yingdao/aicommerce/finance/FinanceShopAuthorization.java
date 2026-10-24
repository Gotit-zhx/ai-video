package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Finance-owned grants and credentials. Never reads or writes commerce shop credentials. */
@Service
public class FinanceShopAuthorization {
    private final FinanceRepository repo;
    private final MarketplaceClientRegistry clients;
    private final ShopCredentialCipher cipher;
    private final ShopIntegrationSettings settings;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FinanceReadCache sharedSnapshots;

    public FinanceShopAuthorization(FinanceRepository repo,MarketplaceClientRegistry clients,
            ShopCredentialCipher cipher,ShopIntegrationSettings settings,PlatformTransactionManager manager){
        this.repo=repo;this.clients=clients;this.cipher=cipher;this.settings=settings;
        this.transactions=new TransactionTemplate(manager);
    }
    public static boolean financeState(String state){return state!=null&&state.startsWith("finance_");}
    static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public ShopAuthorizationService.AuthorizationStart start(String value,String market,Scope scope,String actorUserId,String businessGroup,String targetShop){
        new FinanceGroups(repo).require(scope,businessGroup,false);
        if(!text(targetShop).isBlank()&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections WHERE organization_id=? AND id=? AND platform=? AND status<>'REMOVED'",Integer.class,scope.organizationId(),targetShop,MarketplacePlatform.parse(value).name())!=1)throw bad("没有找到要重新授权的店铺");
        MarketplacePlatform platform=MarketplacePlatform.parse(value);
        String normalized=text(market).trim().toUpperCase(Locale.ROOT);
        if(!normalized.isBlank()&&!normalized.equals("ROW")&&!normalized.matches("[A-Z]{2}"))throw bad("授权市场格式不正确");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String state="finance_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String callback=settings.callbackBaseUrl()+"/api/v1/shop-authorizations/callback/"+platform.name().toLowerCase(Locale.ROOT);
        var client=clients.require(platform);
        String url=normalized.isBlank()?client.authorizationUrl(callback,state):client.authorizationUrl(callback,state,normalized);
        Instant expires=Instant.now().plus(settings.stateTtl());
        repo.transaction(()->{
            if(!text(targetShop).isBlank())repo.requireShopWrite(scope,targetShop);
            repo.jdbc().update("""
            INSERT INTO finance_authorization_sessions(id,organization_id,user_id,platform,state_hash,expires_at,context_json)
            VALUES(?,?,?,?,?,?,?)
            """,UUID.randomUUID().toString(),scope.organizationId(),scope.userId(),platform.name(),hash(state),Timestamp.from(expires),repo.write(Map.of("groupId",businessGroup,"actorUserId",text(actorUserId),"shopId",text(targetShop))));
            return null;
        });
        return new ShopAuthorizationService.AuthorizationStart(url,expires,platform.name());
    }
    public String complete(String value,String state,String code,Map<String,String> parameters,String providerError){
        var platform=MarketplacePlatform.parse(value);
        if(!financeState(state)||state.length()>200)throw bad("财务授权请求无效");
        var sessions=repo.jdbc().queryForList("SELECT * FROM finance_authorization_sessions WHERE state_hash=? AND platform=?",hash(state),platform.name());
        if(sessions.size()!=1)throw bad("财务授权请求不存在或已过期");
        var session=sessions.getFirst();String id=text(session.get("id"));
        if(repo.jdbc().update("UPDATE finance_authorization_sessions SET status='PROCESSING' WHERE id=? AND status='PENDING' AND expires_at>UTC_TIMESTAMP(6)",id)!=1)
            throw bad("财务授权请求已过期或已使用，请重新发起");
        try{
            if(!text(providerError).isBlank())throw bad("平台未完成财务店铺授权，请重新发起");
            if(text(code).isBlank())throw bad("平台未返回授权码");
            Scope scope=new Scope(text(session.get("organization_id")),text(session.get("user_id")));
            var context=session.get("context_json")==null?Map.<String,Object>of():repo.read(text(session.get("context_json")));
            String businessGroup=text(context.get("groupId"));
            if(businessGroup.isBlank())return failSession(id,"请重新生成授权链接并选择店铺分组");
            new FinanceGroups(repo).require(scope,businessGroup,false);
            var grant=clients.require(platform).exchangeAuthorizationCode(code,parameters);
            var result=persistGrant(scope,platform,grant,businessGroup,text(context.get("shopId")),instant(session.get("created_at")));
            repo.jdbc().update("UPDATE finance_authorization_sessions SET status='SUCCESS',error_message=NULL WHERE id=?",id);
            if(sharedSnapshots!=null)sharedSnapshots.invalidate(companyScope(scope.organizationId()));
            String message=result.created()>0?"已连接 "+result.created()+" 家财务店铺，请配置并开启同步":"";
            if(result.existing()>0)message+=(message.isEmpty()?"":"；")+"店铺已存在（"+result.existing()+" 家），已更新原店铺授权，未重复导入";
            return redirect(true,message);
        }catch(RuntimeException e){
            String message=safeMessage(e);
            repo.jdbc().update("UPDATE finance_authorization_sessions SET status='FAILED',error_message=? WHERE id=?",message,id);
            return redirect(false,message);
        }
    }
    private String failSession(String id,String message){repo.jdbc().update("UPDATE finance_authorization_sessions SET status='FAILED',error_message=? WHERE id=?",message,id);return redirect(false,message);}
    public String redirect(boolean success,String message){
        return settings.frontendBaseUrl()+"/?financeAuth="+(success?"success":"error")+"&financeMessage="
                +java.net.URLEncoder.encode(message,StandardCharsets.UTF_8)+"#finance-shops";
    }
    static String safeMessage(Throwable error){
        // Provider exceptions can include URLs, request bodies or credentials. Never echo them.
        return "财务店铺授权失败，请检查平台授权、回调配置和可用店铺后重新连接";
    }
    int persist(Scope s,MarketplacePlatform platform,MarketplaceAccess.AuthorizationGrant grant){
        var result=persistGrant(s,platform,grant,null,"");return result.created()+result.existing();
    }
    record PersistResult(int created,int existing){}
    PersistResult persistGrant(Scope s,MarketplacePlatform platform,MarketplaceAccess.AuthorizationGrant grant,String businessGroup,String targetShop){
        return persistGrant(s,platform,grant,businessGroup,targetShop,Instant.now());
    }
    PersistResult persistGrant(Scope s,MarketplacePlatform platform,MarketplaceAccess.AuthorizationGrant grant,String businessGroup,String targetShop,Instant started){
        if(grant==null||grant.tokens()==null||grant.shops().isEmpty()||text(grant.tokens().accessToken()).isBlank())throw bad("平台未返回可用店铺授权");
        Set<String> ids=new HashSet<>();
        for(var shop:grant.shops()){
            if(text(shop.externalShopId()).isBlank()||shop.externalShopId().length()>128||!ids.add(shop.externalShopId()))throw bad("平台店铺列表无效，未保存授权");
            if(platform==MarketplacePlatform.TIKTOK_SHOP&&text(shop.shopCipher()).isBlank())throw bad("平台未返回店铺标识，未保存授权");
            if(platform==MarketplacePlatform.SHOPEE&&!text(shop.authorizationSubjectType()).isBlank()
                    &&(!Set.of("SHOP","MERCHANT").contains(shop.authorizationSubjectType())||text(shop.authorizationSubjectId()).isBlank()))
                throw bad("平台未返回有效的授权主体，未保存授权");
        }
        PersistResult saved=transactions.execute(status->{
            if(businessGroup!=null)new FinanceGroups(repo).require(s,businessGroup,true);
            // Deterministic lock order keeps reauthorization and rotating token groups isolated.
            var existing=repo.jdbc().queryForList("SELECT id,user_id,external_shop_id,group_id,status FROM finance_connections WHERE organization_id=? AND platform=? ORDER BY id FOR UPDATE",
                    s.organizationId(),platform.name());
            if(!text(targetShop).isBlank()&&existing.stream().noneMatch(r->targetShop.equals(text(r.get("id")))&&ids.contains(text(r.get("external_shop_id")))))throw bad("本次授权未包含所选店铺");
            String group=UUID.randomUUID().toString();int created=0,duplicates=0;
            for(var remote:grant.shops()){
                new FinanceShopDeletion(repo).checkAuthorization(s,platform.name(),remote.externalShopId(),started);
                var previous=existing.stream().filter(r->remote.externalShopId().equals(text(r.get("external_shop_id")))).findFirst().orElse(null);
                String id=previous==null?UUID.randomUUID().toString():text(previous.get("id"));
                Scope storage=previous==null?s:new Scope(s.organizationId(),text(previous.get("user_id")));
                boolean added=previous==null||"REMOVED".equals(previous.get("status"));
                if(added)created++;else duplicates++;
                String assigned=added||id.equals(targetShop)?businessGroup:text(previous.get("group_id"));
                var tokens=grant.tokens();
                repo.jdbc().update("""
                    INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,region,
                    merchant_id,shop_cipher,seller_type,authorization_subject_type,authorization_subject_id,currency,timezone,
                    token_group_id,access_token_encrypted,refresh_token_encrypted,access_token_expires_at,refresh_token_expires_at,scopes_json,group_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE name=VALUES(name),region=VALUES(region),merchant_id=VALUES(merchant_id),
                    shop_cipher=VALUES(shop_cipher),seller_type=VALUES(seller_type),authorization_subject_type=VALUES(authorization_subject_type),
                    authorization_subject_id=VALUES(authorization_subject_id),token_group_id=VALUES(token_group_id),
                    access_token_encrypted=VALUES(access_token_encrypted),refresh_token_encrypted=VALUES(refresh_token_encrypted),
                    access_token_expires_at=VALUES(access_token_expires_at),refresh_token_expires_at=VALUES(refresh_token_expires_at),
                    scopes_json=VALUES(scopes_json),group_id=VALUES(group_id),enabled=IF(status='REMOVED',FALSE,enabled),status='ACTIVE',updated_at=UTC_TIMESTAMP(6)
                    """,id,storage.organizationId(),storage.userId(),platform.name(),remote.externalShopId(),text(remote.name()),remote.region(),
                        remote.merchantId(),remote.shopCipher(),remote.sellerType(),remote.authorizationSubjectType(),remote.authorizationSubjectId(),
                        currencyFor(remote.region()),timezoneFor(remote.region()),group,
                        encrypt(storage,id,tokens.accessToken()),encrypt(storage,id,tokens.refreshToken()),timestamp(tokens.accessTokenExpiresAt()),
                        timestamp(tokens.refreshTokenExpiresAt()),repo.write(tokens.scopes()),text(assigned).isBlank()?null:assigned);
            }
            return new PersistResult(created,duplicates);
        });
        return Objects.requireNonNull(saved);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public MarketplaceAccess.Connection connection(Scope s,String id){
        var found=repo.jdbc().queryForList("SELECT * FROM finance_connections WHERE organization_id=? AND user_id=? AND id=?",s.organizationId(),s.userId(),id);
        if(found.isEmpty()||!"ACTIVE".equals(found.getFirst().get("status")))throw bad("财务店铺授权不可用，请在财务店铺管理重新连接");
        String group=text(found.getFirst().get("token_group_id"));
        var locked=repo.jdbc().queryForList("SELECT * FROM finance_connections WHERE organization_id=? AND token_group_id=? AND status<>'REMOVED' ORDER BY id FOR UPDATE",s.organizationId(),group);
        var row=locked.stream().filter(r->id.equals(text(r.get("id")))).findFirst().orElseThrow(()->bad("财务授权已变更，请重试"));
        if(!"ACTIVE".equals(row.get("status")))throw bad("财务店铺已移除");
        Shop shop=new FinanceShop(row);
        var current=tokens(s,row);
        if(current.accessTokenExpiresAt()!=null&&!current.accessTokenExpiresAt().isAfter(Instant.now().plus(settings.tokenRefreshSkew()))){
            if(text(current.refreshToken()).isBlank()||current.refreshTokenExpiresAt()!=null&&!current.refreshTokenExpiresAt().isAfter(Instant.now()))
                throw bad("财务店铺授权已过期，请重新连接");
            current=clients.require(MarketplacePlatform.parse(shop.getPlatform())).refresh(current,shop);
            if(current==null||text(current.accessToken()).isBlank()||text(current.refreshToken()).isBlank())
                throw bad("平台刷新授权未返回完整凭据，请重新连接财务店铺");
            for(var item:locked){
                String sibling=text(item.get("id"));
                Scope storage=new Scope(s.organizationId(),text(item.get("user_id")));
                repo.jdbc().update("UPDATE finance_connections SET access_token_encrypted=?,refresh_token_encrypted=?,access_token_expires_at=?,refresh_token_expires_at=?,scopes_json=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND user_id=? AND id=?",
                        encrypt(storage,sibling,current.accessToken()),encrypt(storage,sibling,current.refreshToken()),timestamp(current.accessTokenExpiresAt()),
                        timestamp(current.refreshTokenExpiresAt()),repo.write(current.scopes()),storage.organizationId(),storage.userId(),sibling);
            }
        }
        return new MarketplaceAccess.Connection(shop,current);
    }
    private MarketplaceAccess.TokenSet tokens(Scope s,Map<String,Object> row){
        String id=text(row.get("id"));
        Object scopes=repo.read("{\"items\":"+text(row.get("scopes_json"))+"}").get("items");
        return new MarketplaceAccess.TokenSet(decrypt(s,id,row.get("access_token_encrypted")),decrypt(s,id,row.get("refresh_token_encrypted")),
                row.get("access_token_expires_at")==null?null:instant(row.get("access_token_expires_at")),
                row.get("refresh_token_expires_at")==null?null:instant(row.get("refresh_token_expires_at")),
                scopes instanceof List<?> values?values.stream().map(String::valueOf).toList():List.of());
    }
    private String encrypt(Scope s,String id,String token){return cipher.encrypt(token,s.organizationId(),"finance-shop:"+s.userId()+":"+id);}
    private String decrypt(Scope s,String id,Object value){return value==null?null:cipher.decrypt(text(value),s.organizationId(),"finance-shop:"+s.userId()+":"+id);}
    static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    static final class FinanceShop extends Shop {
        private final String financeId;
        FinanceShop(Map<String,Object> row){
            super(text(row.get("organization_id")),MarketplacePlatform.parse(text(row.get("platform"))),text(row.get("external_shop_id")),
                    text(row.get("merchant_id")),text(row.get("shop_cipher")),text(row.get("seller_type")),text(row.get("name")),
                    text(row.get("region")),text(row.get("scopes_json")),text(row.get("user_id")),
                    nullable(row.get("authorization_subject_type")),nullable(row.get("authorization_subject_id")));
            financeId=text(row.get("id"));
        }
        @Override public String getId(){return financeId;}
    }
    static String nullable(Object value){return text(value).isBlank()?null:text(value);}
}
