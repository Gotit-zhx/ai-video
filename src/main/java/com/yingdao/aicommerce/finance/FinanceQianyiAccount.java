package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One authorization per company; remote discovery never holds database locks. */
@Service
public class FinanceQianyiAccount {
    final FinanceQianyi integration;final FinanceRepository repo;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-qianyi-binding");t.setDaemon(true);return t;});
    public FinanceQianyiAccount(FinanceQianyi integration){this.integration=integration;this.repo=integration.repo;}
    @PostConstruct public void start(){worker.scheduleWithFixedDelay(()->{try{tick();}catch(RuntimeException ignored){}},45,5,TimeUnit.SECONDS);}
    @PreDestroy public void stop(){worker.shutdownNow();}
    public Map<String,Object> configuration(Scope scope){
        var row=integration.account(scope);var result=new LinkedHashMap<String,Object>();
        result.put("configured",!row.isEmpty());result.put("hasSecret",!text(row.get("secret_encrypted")).isBlank());
        for(String key:List.of("environment","app_id","enabled","sync_start","status","last_success_at","error_message"))
            if(row.get(key)!=null)result.put(key,row.get(key));
        result.putIfAbsent("environment","CN");result.putIfAbsent("enabled",true);
        result.putIfAbsent("sync_start",LocalDate.now().minusMonths(3).withDayOfMonth(1).toString());
        result.putAll(repo.jdbc().queryForMap("""
            SELECT COUNT(*) shop_count,COUNT(q.shop_id) bound_count,
              COALESCE(SUM(q.cost_basis_verified=TRUE),0) verified_count
            FROM finance_connections c LEFT JOIN finance_qianyi_connections q
              ON q.organization_id=c.organization_id AND q.shop_id=c.id
            WHERE c.organization_id=? AND c.status<>'REMOVED'
            """,scope.organizationId()));
        return result;
    }
    public List<Map<String,Object>> shops(Scope scope,int page){
        return repo.jdbc().queryForList("""
            SELECT c.id,COALESCE(NULLIF(c.display_name,''),c.name) name,c.platform,c.region,
              q.remote_shop_name,q.status,q.last_success_at,q.error_message,q.cost_basis_verified
            FROM finance_connections c LEFT JOIN finance_qianyi_connections q
              ON q.organization_id=c.organization_id AND q.shop_id=c.id
            WHERE c.organization_id=? AND c.status<>'REMOVED'
            ORDER BY c.name,c.id LIMIT 50 OFFSET ?
            """,scope.organizationId(),50L*(Math.max(1,Math.min(page,100000))-1));
    }
    FinanceQianyiClient.Credentials credentials(Map<String,Object> account){
        return new FinanceQianyiClient.Credentials(text(account.get("environment")),text(account.get("app_id")),
            integration.cipher.decrypt(text(account.get("secret_encrypted")),text(account.get("organization_id")),"finance-qianyi-default"));
    }
    public void save(Scope scope,Map<String,Object> body){
        var old=integration.account(scope);
        var credential=integration.credentials(scope,"",body,Map.of());
        LocalDate start;
        try{start=LocalDate.parse(text(body.get("syncStart")));}catch(RuntimeException invalid){throw bad("历史同步开始日期不正确");}
        if(start.isAfter(LocalDate.now())||start.isBefore(LocalDate.now().minusYears(5)))throw bad("同步开始日期应在最近五年内");
        boolean enabled=Boolean.TRUE.equals(body.get("enabled"));
        String key=hash(credential.environment()+"\0"+credential.appId());
        String secret=integration.cipher.encrypt(credential.secret(),scope.organizationId(),"finance-qianyi-default");
        repo.transaction(()->{
            var current=repo.jdbc().queryForList("SELECT revision FROM finance_qianyi_accounts WHERE organization_id=? FOR UPDATE",scope.organizationId());
            if(current.isEmpty()!=old.isEmpty()||!current.isEmpty()&&!current.getFirst().get("revision").equals(old.get("revision")))
                throw bad("公司千易配置已更新，请重新打开后保存");
            if(Boolean.TRUE.equals(repo.jdbc().queryForObject("""
                SELECT EXISTS(SELECT 1 FROM finance_qianyi_connections q JOIN finance_connections c
                  ON c.organization_id=q.organization_id AND c.id=q.shop_id
                  WHERE q.organization_id=? AND q.account_key<>? AND c.status<>'REMOVED')
                """,Boolean.class,scope.organizationId(),key)))
                throw bad("已有店铺关联其他千易账户，请保留当前公司账户，避免改变售后数据归属");
            repo.jdbc().update("""
                INSERT INTO finance_qianyi_accounts(organization_id,environment,app_id,secret_encrypted,enabled,sync_start,status)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE environment=VALUES(environment),app_id=VALUES(app_id),
                  secret_encrypted=VALUES(secret_encrypted),enabled=VALUES(enabled),sync_start=VALUES(sync_start),
                  revision=revision+1,status=VALUES(status),next_at=UTC_TIMESTAMP(6),
                  lease_token=NULL,lease_until=NULL,error_message=NULL,failures=0,updated_at=UTC_TIMESTAMP(6)
                """,scope.organizationId(),credential.environment(),credential.appId(),secret,enabled,start,enabled?"READY":"PAUSED");
            for(var shop:repo.jdbc().queryForList("""
                SELECT q.shop_id,q.sync_start FROM finance_qianyi_connections q JOIN finance_connections c
                  ON c.organization_id=q.organization_id AND c.id=q.shop_id
                WHERE q.organization_id=? AND c.status<>'REMOVED' ORDER BY q.shop_id
                """,scope.organizationId())){
                if(!repo.writableShop(scope.organizationId(),text(shop.get("shop_id"))))continue;
                boolean retain=start.toString().equals(text(shop.get("sync_start")));
                String encrypted=integration.cipher.encrypt(credential.secret(),scope.organizationId(),"finance-qianyi:"+shop.get("shop_id"));
                repo.jdbc().update("""
                    UPDATE finance_qianyi_connections SET secret_encrypted=?,enabled=?,sync_start=?,
                      checkpoint_json=IF(?,checkpoint_json,NULL),last_success_at=IF(?,last_success_at,NULL),
                      revision=revision+1,status=IF(?,IF(checkpoint_json IS NULL,'READY','SYNCING'),'PAUSED'),
                      lease_token=NULL,lease_until=NULL,next_at=UTC_TIMESTAMP(6),error_message=NULL,failures=0,updated_at=UTC_TIMESTAMP(6)
                    WHERE organization_id=? AND shop_id=?
                    """,encrypted,enabled,start,retain,retain,enabled,scope.organizationId(),shop.get("shop_id"));
            }
            integration.finance.invalidateAfterCommit(scope);return null;
        });
    }
    public void sync(Scope scope){
        if(repo.jdbc().update("UPDATE finance_qianyi_accounts SET next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND enabled=TRUE",scope.organizationId())!=1)
            throw bad("请先保存并开启公司千易同步");
        repo.jdbc().update("""
            UPDATE finance_qianyi_connections q JOIN finance_connections c
              ON c.organization_id=q.organization_id AND c.id=q.shop_id
            SET q.next_at=UTC_TIMESTAMP(6) WHERE q.organization_id=? AND q.enabled=TRUE AND c.status<>'REMOVED'
            """,scope.organizationId());
    }
    static String identity(Object platform,Object id){
        String p=text(platform).toUpperCase(Locale.ROOT).replace("_","");
        if(Set.of("TIKTOK","TIKTOKSHOP").contains(p))p="TIKTOK";
        return p+"\0"+text(id);
    }
    public void tick(){
        var found=repo.jdbc().queryForList("""
            SELECT * FROM finance_qianyi_accounts WHERE enabled=TRUE AND next_at<=UTC_TIMESTAMP(6)
              AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY next_at LIMIT 1
            """);
        if(found.isEmpty())return;
        var account=found.getFirst();String org=text(account.get("organization_id")),lease=UUID.randomUUID().toString();
        if(repo.jdbc().update("""
            UPDATE finance_qianyi_accounts SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 MINUTE),status='SYNCING'
            WHERE organization_id=? AND revision=? AND enabled=TRUE AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
            """,lease,org,account.get("revision"))!=1)return;
        try{discover(account,lease);}
        catch(RuntimeException failure){
            if(Thread.currentThread().isInterrupted())return;
            String message=failure instanceof com.yingdao.aicommerce.common.exception.BusinessException?text(failure.getMessage()):"千易店铺关联暂未完成，后台自动重试";
            int wait=(int)Math.min(3600,60L*(1L<<Math.min(6,number(account.get("failures")))));
            repo.jdbc().update("""
                UPDATE finance_qianyi_accounts SET status='ERROR',error_message=?,failures=failures+1,
                  next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE organization_id=? AND lease_token=?
                """,message.substring(0,Math.min(450,message.length())),wait,org,lease);
        }finally{repo.jdbc().update("UPDATE finance_qianyi_accounts SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND lease_token=?",org,lease);}
    }
    void discover(Map<String,Object> account,String lease){
        String org=text(account.get("organization_id"));
        var locals=repo.jdbc().queryForList("SELECT id,platform,external_shop_id FROM finance_connections WHERE organization_id=? AND status<>'REMOVED' LIMIT 5001",org);
        if(locals.size()>5000)throw bad("公司店铺数超过自动关联单批上限");
        var wanted=new HashMap<String,List<Map<String,Object>>>();
        for(var local:locals)if(!text(local.get("external_shop_id")).isBlank())
            wanted.computeIfAbsent(identity(local.get("platform"),local.get("external_shop_id")),k->new ArrayList<>()).add(local);
        var matches=new HashMap<String,Map<String,Object>>();var ambiguous=new HashSet<String>();
        for(int page=1;page<=50;page++){
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Binding interrupted");
            if(repo.jdbc().update("UPDATE finance_qianyi_accounts SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 MINUTE) WHERE organization_id=? AND revision=? AND lease_token=? AND enabled=TRUE",org,account.get("revision"),lease)!=1)return;
            var result=integration.client.query(credentials(account),"QUERY_SHOP_LIST",Map.of("page",page,"pageSize",200));
            if(result.items().isEmpty()&&(page-1L)*200<result.total())throw bad("千易店铺分页不完整，未猜测店铺绑定");
            for(var remote:result.items()){
                String key=identity(remote.get("platform"),remote.get("onlineShopId"));
                if(!wanted.containsKey(key)||wanted.get(key).size()!=1)continue;
                String id=text(remote.get("shopId"));
                if(!id.matches("[0-9]{1,20}"))throw bad("千易店铺编号不完整");
                var safe=Map.<String,Object>of("id",id,"name",text(remote.get("name")),"page",page);
                var previous=matches.putIfAbsent(key,safe);
                if(previous!=null&&!id.equals(previous.get("id")))ambiguous.add(key);
            }
            if(page*200L>=result.total())break;
            if(page==50)throw bad("千易店铺列表超过自动关联范围，未按不完整结果关联");
        }
        for(var entry:matches.entrySet()){
            if(ambiguous.contains(entry.getKey()))continue;
            String shop=text(wanted.get(entry.getKey()).getFirst().get("id"));
            var remote=entry.getValue();
            repo.transaction(()->{
                if(!owned(account,lease)||!repo.writableShop(org,shop))return null;
                if(Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM finance_qianyi_connections WHERE organization_id=? AND shop_id=?)",Boolean.class,org,shop)))return null;
                String key=hash(text(account.get("environment"))+"\0"+account.get("app_id"));
                if(Boolean.TRUE.equals(repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM finance_qianyi_connections WHERE organization_id=? AND account_key=? AND remote_shop_id=?)",Boolean.class,org,key,remote.get("id"))))return null;
                var credential=credentials(account);
                repo.jdbc().update("""
                    INSERT INTO finance_qianyi_connections(organization_id,shop_id,environment,app_id,secret_encrypted,account_key,
                      remote_shop_id,remote_shop_name,remote_page,cost_currency,enabled,sync_start,status)
                    VALUES(?,?,?,?,?,?,?,?,?,'CNY',TRUE,?,'READY')
                    """,org,shop,account.get("environment"),account.get("app_id"),
                    integration.cipher.encrypt(credential.secret(),org,"finance-qianyi:"+shop),key,remote.get("id"),
                    remote.get("name"),remote.get("page"),account.get("sync_start"));
                return null;
            });
        }
        repo.jdbc().update("""
            UPDATE finance_qianyi_accounts SET status='SUCCESS',last_success_at=UTC_TIMESTAMP(6),error_message=NULL,failures=0,
              next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
            WHERE organization_id=? AND revision=? AND lease_token=? AND enabled=TRUE
            """,org,account.get("revision"),lease);
    }
    boolean owned(Map<String,Object> account,String lease){
        return !repo.jdbc().queryForList("SELECT organization_id FROM finance_qianyi_accounts WHERE organization_id=? AND revision=? AND lease_token=? AND enabled=TRUE FOR UPDATE",
            account.get("organization_id"),account.get("revision"),lease).isEmpty();
    }
}
