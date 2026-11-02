package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.ShopCredentialCipher;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceQianyi {
    final FinanceRepository repo;final FinanceService finance;final FinanceQianyiClient client;final ShopCredentialCipher cipher;
    public FinanceQianyi(FinanceRepository repo,FinanceService finance,FinanceQianyiClient client,ShopCredentialCipher cipher){
        this.repo=repo;this.finance=finance;this.client=client;this.cipher=cipher;
    }
    Map<String,Object> connection(Scope s,String shop){
        finance.shopScope(s,shop);
        var rows=repo.jdbc().queryForList("SELECT * FROM finance_qianyi_connections WHERE organization_id=? AND shop_id=?",s.organizationId(),shop);
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    Map<String,Object> account(Scope s){
        var rows=repo.jdbc().queryForList("SELECT * FROM finance_qianyi_accounts WHERE organization_id=?",s.organizationId());
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    public Map<String,Object> configuration(Scope s,String shop){
        var row=connection(s,shop);var result=new LinkedHashMap<String,Object>();
        result.put("configured",!row.isEmpty());result.put("hasSecret",!text(row.get("secret_encrypted")).isBlank());
        for(String field:List.of("environment","app_id","remote_shop_id","remote_shop_name","remote_page","cost_currency","cost_basis_verified","enabled","sync_start",
                "status","last_success_at","next_at","error_message","checkpoint_json"))if(row.get(field)!=null)result.put(field,row.get(field));
        if(row.isEmpty()){
            var account=account(s);
            result.put("environment",account.getOrDefault("environment","CN"));result.put("app_id",account.getOrDefault("app_id",""));
            result.put("hasSecret",!account.isEmpty());result.put("credentialsSaved",!account.isEmpty());
            result.put("cost_currency","CNY");result.put("sync_start",LocalDate.now().minusMonths(3).withDayOfMonth(1));
        }
        result.put("counts",repo.jdbc().queryForList("SELECT cost_status,COUNT(*) records FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=? GROUP BY cost_status",s.organizationId(),shop));
        return result;
    }
    FinanceQianyiClient.Credentials credentials(Map<String,Object> row){
        return new FinanceQianyiClient.Credentials(text(row.get("environment")),text(row.get("app_id")),
            cipher.decrypt(text(row.get("secret_encrypted")),text(row.get("organization_id")),"finance-qianyi:"+row.get("shop_id")));
    }
    FinanceQianyiClient.Credentials credentials(Scope s,String shop,Map<String,Object> body,Map<String,Object> old){
        String environment=text(body.get("environment")),app=text(body.get("appId")).trim(),secret=text(body.get("appSecret")).trim();
        if(!FinanceQianyiClient.HOSTS.containsKey(environment)||app.isBlank()||app.length()>128||secret.length()>4096)throw bad("请填写有效的千易环境与 App ID");
        if(secret.isBlank()){
            if(environment.equals(old.get("environment"))&&app.equals(old.get("app_id")))secret=credentials(old).secret();
            else {
                var account=account(s);
                if(!environment.equals(account.get("environment"))||!app.equals(account.get("app_id")))throw bad("更换千易环境或 App ID 后请重新填写 App Secret");
                secret=cipher.decrypt(text(account.get("secret_encrypted")),s.organizationId(),"finance-qianyi-default");
            }
        }
        if(secret.isBlank())throw bad("请填写千易 App Secret");
        return new FinanceQianyiClient.Credentials(environment,app,secret);
    }
    public void saveCredentials(Scope s,String shop,Map<String,Object> body){
        var credential=credentials(s,shop,body,connection(s,shop));
        String encrypted=cipher.encrypt(credential.secret(),s.organizationId(),"finance-qianyi-default");
        repo.transaction(()->{
            repo.requireShopWrite(s,shop);
            repo.jdbc().update("""
                INSERT INTO finance_qianyi_accounts(organization_id,environment,app_id,secret_encrypted)
                VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE environment=VALUES(environment),app_id=VALUES(app_id),
                  secret_encrypted=VALUES(secret_encrypted),updated_at=UTC_TIMESTAMP(6)
                """,s.organizationId(),credential.environment(),credential.appId(),encrypted);
            return null;
        });
    }
    public Map<String,Object> discover(Scope s,String shop,Map<String,Object> body){
        var old=connection(s,shop);var credentials=credentials(s,shop,body,old);
        int page=(int)Math.max(1,Math.min(10000,number(body.get("page"))));
        var response=client.query(credentials,"QUERY_SHOP_LIST",Map.of("page",page,"pageSize",200));
        var items=new ArrayList<Map<String,Object>>();
        for(var item:response.items()){
            var safe=new LinkedHashMap<String,Object>();
            for(String key:List.of("shopId","name","platform","siteCode","onlineShopId"))safe.put(key,text(item.get(key)));
            items.add(safe);
        }
        return Map.of("items",items,"total",response.total(),"page",page);
    }
    public void save(Scope s,String shop,Map<String,Object> body){
        var old=connection(s,shop);var credential=credentials(s,shop,body,old);
        String remote=text(body.get("remoteShopId")),currency=text(body.get("costCurrency"));
        if(!remote.matches("[0-9]{1,20}"))throw bad("请先读取并选择对应的千易店铺");
        if(!"CNY".equals(currency))throw bad("目前只接入千易人民币库存成本，请确认千易账户的成本币种");
        LocalDate start;
        try{start=LocalDate.parse(text(body.get("syncStart")));}catch(Exception e){throw bad("千易同步开始日期不正确");}
        if(start.isAfter(LocalDate.now())||start.isBefore(LocalDate.now().minusYears(5)))throw bad("同步开始日期应在最近五年内");
        String account=hash(credential.environment()+"\0"+credential.appId());
        boolean identityChanged=!old.isEmpty()&&(!account.equals(old.get("account_key"))||!remote.equals(old.get("remote_shop_id")));
        if(identityChanged&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=?",Long.class,s.organizationId(),shop)>0)
            throw bad("该店铺已有千易售后记录，不能直接改绑其他账户或店铺");
        int page=(int)Math.max(1,Math.min(10000,number(body.get("remotePage"))));
        var available=client.query(credential,"QUERY_SHOP_LIST",Map.of("page",page,"pageSize",200));
        var selected=available.items().stream().filter(v->remote.equals(text(v.get("shopId")))).findFirst().orElseThrow(()->bad("千易授权未返回所选店铺，请重新读取店铺列表"));
        boolean enabled=Boolean.TRUE.equals(body.get("enabled"));String secret=cipher.encrypt(credential.secret(),s.organizationId(),"finance-qianyi:"+shop);
        repo.transaction(()->{
            repo.requireShopWrite(s,shop);
            var current=repo.jdbc().queryForList("SELECT revision FROM finance_qianyi_connections WHERE organization_id=? AND shop_id=? FOR UPDATE",s.organizationId(),shop);
            if(current.isEmpty()!=old.isEmpty()||!current.isEmpty()&&!current.getFirst().get("revision").equals(old.get("revision")))
                throw bad("千易配置已被修改，请重新打开配置后保存");
            if(identityChanged&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=?",Long.class,s.organizationId(),shop)>0)
                throw bad("该店铺已有千易售后记录，不能直接改绑其他账户或店铺");
            try{
                if(current.isEmpty()){
                    // Do not use ON DUPLICATE KEY here: a duplicate remote binding must not update another local shop.
                    repo.jdbc().update("""
                        INSERT INTO finance_qianyi_connections(organization_id,shop_id,environment,app_id,secret_encrypted,account_key,
                          remote_shop_id,remote_shop_name,remote_page,cost_currency,enabled,sync_start,status)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,s.organizationId(),shop,credential.environment(),credential.appId(),secret,account,remote,text(selected.get("name")),page,currency,enabled,start,enabled?"READY":"PAUSED");
                }else{
                    boolean retain=!identityChanged&&start.toString().equals(text(old.get("sync_start")));
                    repo.jdbc().update("""
                        UPDATE finance_qianyi_connections SET checkpoint_json=IF(?,checkpoint_json,NULL),
                          last_success_at=IF(?,last_success_at,NULL),cost_basis_verified=IF(?,FALSE,cost_basis_verified),environment=?,app_id=?,secret_encrypted=?,
                          account_key=?,remote_shop_id=?,remote_shop_name=?,remote_page=?,cost_currency=?,enabled=?,sync_start=?,revision=revision+1,
                          status=IF(?,IF(checkpoint_json IS NULL,'READY','SYNCING'),'PAUSED'),
                          lease_token=NULL,lease_until=NULL,failures=0,error_message=NULL,next_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
                        WHERE organization_id=? AND shop_id=?
                        """,retain,retain,identityChanged,credential.environment(),credential.appId(),secret,account,remote,text(selected.get("name")),page,currency,enabled,start,
                        enabled,s.organizationId(),shop);
                }
            }catch(org.springframework.dao.DuplicateKeyException duplicate){throw bad("这个千易店铺已绑定本公司的其他财务店铺，不能重复统计");}
            finance.invalidateAfterCommit(s);return null;
        });
    }
    public void sync(Scope s,String shop){
        connection(s,shop);
        if(repo.jdbc().update("UPDATE finance_qianyi_connections SET next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND shop_id=? AND enabled=TRUE",
            s.organizationId(),shop)!=1)throw bad("请先配置并开启千易售后同步");
        repo.jdbc().update("UPDATE finance_qianyi_returns SET next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND shop_id=? AND cost_status='PENDING'",s.organizationId(),shop);
    }
    public List<Map<String,Object>> details(Scope s,String shop,int page){
        connection(s,shop);
        return repo.jdbc().queryForList("""
            SELECT return_number,order_id,receipt_date,cost_status,cost_note,updated_at
            FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=?
            ORDER BY source_updated_at DESC,return_number LIMIT 50 OFFSET ?
            """,s.organizationId(),shop,50L*(Math.max(1,Math.min(100000,page))-1));
    }
    /** Runs only after the HTTP response; no database lock is held while contacting Qianyi. */
    void acceptPage(Map<String,Object> config,String lease,FinanceQianyiClient.Page page,Map<String,Object> next,boolean done){
        String org=text(config.get("organization_id")),shop=text(config.get("shop_id"));
        var scope=companyScope(org);String zone=repo.shopTimezone(scope,shop);
        repo.transaction(()->{
            if(!repo.writableShop(org,shop))return null;
            if(!owns(config,lease))return null;
            if(page.items().size()>200)throw bad("千易单页超过处理上限，保留进度重试");
            var previous=new HashMap<String,Map<String,Object>>();
            var ids=page.items().stream().map(v->text(v.get("returnNumber"))).distinct().toList();
            if(!ids.isEmpty()){
                var args=new ArrayList<Object>();Collections.addAll(args,org,shop);args.addAll(ids);
                for(var row:repo.jdbc().queryForList("""
                    SELECT return_number,source_updated_at,source_hash FROM finance_qianyi_returns
                    WHERE organization_id=? AND shop_id=? AND return_number IN (
                    """+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray()))
                    previous.put(text(row.get("return_number")),row);
            }
            boolean changed=false;
            for(var source:page.items()){
                var value=FinanceQianyiEvidence.sanitize(source);
                String number=text(value.get("returnNumber")),order=text(value.get("onlineOrderNumber"));
                if(number.isBlank()||number.length()>100||order.length()>255||FinanceData.number(value.get("updateTime"))<=0)
                    throw bad("千易售后缺少有效编号或更新时间，未跳过该页");
                var prior=previous.getOrDefault(number,Map.of());
                if(!prior.isEmpty()&&FinanceData.number(prior.get("source_updated_at"))>FinanceData.number(value.get("updateTime")))continue;
                String status=FinanceQianyiEvidence.status(value),hash=hash(repo.write(value));
                if(hash.equals(prior.get("source_hash")))continue;
                var date=FinanceQianyiEvidence.receiptDate(value,zone);
                repo.jdbc().update("""
                    INSERT INTO finance_qianyi_returns(organization_id,shop_id,return_number,account_key,order_id,receipt_date,source_updated_at,source_hash,data_json,cost_status)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE cost_status=IF(source_hash=VALUES(source_hash),cost_status,VALUES(cost_status)),
                      cost_note=IF(source_hash=VALUES(source_hash),cost_note,''),next_at=IF(source_hash=VALUES(source_hash),next_at,UTC_TIMESTAMP(6)),
                      order_id=VALUES(order_id),receipt_date=VALUES(receipt_date),source_updated_at=VALUES(source_updated_at),
                      data_json=VALUES(data_json),source_hash=VALUES(source_hash),updated_at=UTC_TIMESTAMP(6)
                    """,org,shop,number,config.get("account_key"),order,date,FinanceData.number(value.get("updateTime")),hash,repo.write(value),status);
                if(!prior.isEmpty()||Set.of("CLOSED","NO_RETURN","WAIT_RECEIPT").contains(status))
                    repo.jdbc().update("DELETE FROM finance_qianyi_return_costs WHERE organization_id=? AND shop_id=? AND return_number=?",org,shop,number);
                previous.put(number,Map.of("source_updated_at",value.get("updateTime"),"source_hash",hash));
                changed=true;
            }
            var progress=new LinkedHashMap<>(next);
            if(changed)progress.put("sourcesChanged",true);
            repo.jdbc().update("""
                UPDATE finance_qianyi_connections SET checkpoint_json=?,status=?,last_success_at=IF(?,UTC_TIMESTAMP(6),last_success_at),
                  source_version=IF(?,2,source_version),
                  failures=0,error_message=NULL,lease_token=NULL,lease_until=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND)
                WHERE organization_id=? AND shop_id=? AND lease_token=?
                """,done?null:repo.write(progress),done?"SUCCESS":"SYNCING",done,done,done?300:1,org,shop,lease);
            // Initial source collection and identical overlap pages do not alter
            // financial amounts. Do not evict the whole BI cache on every page.
            if(changed&&Boolean.TRUE.equals(config.get("cost_basis_verified"))||done&&Boolean.TRUE.equals(progress.get("sourcesChanged")))
                finance.invalidateAfterCommit(scope);
            return null;
        });
    }
    boolean owns(Map<String,Object> config,String lease){
        return !repo.jdbc().queryForList("SELECT shop_id FROM finance_qianyi_connections WHERE organization_id=? AND shop_id=? AND revision=? AND lease_token=? AND enabled=TRUE FOR UPDATE",
            config.get("organization_id"),config.get("shop_id"),config.get("revision"),lease).isEmpty();
    }
    void acceptCosts(Map<String,Object> config,String lease,Map<String,Object> document,FinanceQianyiEvidence.Result result,Map<String,Object> order){
        String org=text(config.get("organization_id")),shop=text(config.get("shop_id")),number=text(document.get("return_number"));
        repo.transaction(()->{
            if(!repo.writableShop(org,shop)||!owns(config,lease))return null;
            var current=repo.jdbc().queryForList("SELECT source_hash FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=? AND return_number=? FOR UPDATE",org,shop,number);
            if(current.isEmpty()||!document.get("source_hash").equals(current.getFirst().get("source_hash")))return null;
            if(result.status().equals("READY")){
                if(!Boolean.TRUE.equals(config.get("cost_basis_verified")))throw bad("千易库存成本币种和计价口径尚未核验，未计入财务汇总");
                for(var item:result.movements()){
                    var existing=repo.jdbc().queryForList("SELECT shop_id,return_number FROM finance_qianyi_return_costs WHERE organization_id=? AND account_key=? AND movement_id=?",
                        org,config.get("account_key"),item.get("movementId"));
                    if(!existing.isEmpty()&&(!shop.equals(existing.getFirst().get("shop_id"))||!number.equals(existing.getFirst().get("return_number"))))
                        throw bad("千易库存流水已归属其他退货单，未重复入账");
                }
                repo.jdbc().update("DELETE FROM finance_qianyi_return_costs WHERE organization_id=? AND shop_id=? AND return_number=?",org,shop,number);
                for(var item:result.movements()){
                    String identity=hash("QIANYI\0"+org+"\0"+config.get("account_key")+"\0"+item.get("movementId"));
                    repo.jdbc().update("""
                        INSERT INTO finance_qianyi_return_costs(id,organization_id,shop_id,account_key,movement_id,return_number,
                          order_record_id,order_id,receipt_date,native_currency,amount_cny,data_json)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                        """,identity,org,shop,config.get("account_key"),item.get("movementId"),number,order.get("id"),document.get("order_id"),
                        document.get("receipt_date"),order.get("currency"),item.get("amountCny"),repo.write(item));
                }
            }
            repo.jdbc().update("""
                UPDATE finance_qianyi_returns SET cost_status=?,cost_note=?,order_record_id=?,attempts=attempts+1,
                  next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),updated_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND shop_id=? AND return_number=?
                """,result.status(),result.note(),order.get("id"),org,shop,number);
            if(Boolean.TRUE.equals(config.get("cost_basis_verified")))finance.invalidateAfterCommit(companyScope(org));
            return null;
        });
    }
}
