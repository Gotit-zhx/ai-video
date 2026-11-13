package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One metadata request per lease, outside financial queries and report/profit streams. */
@Component
final class FinanceAdMetadataSync {
    private static final int MAX_PENDING_REFERENCES=50000;
    private final FinanceRepository repo;private final FinanceAds ads;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-ad-metadata");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean unavailable=new java.util.concurrent.atomic.AtomicBoolean();
    private final LinkedHashMap<String,Reference> pending=new LinkedHashMap<>();
    private record Reference(String organization,String advertiser,String campaign,boolean gmv) {}
    private static final class MetadataChanged extends RuntimeException {}
    FinanceAdMetadataSync(FinanceRepository repo,FinanceAds ads){this.repo=repo;this.ads=ads;}
    @PostConstruct void start(){worker.scheduleWithFixedDelay(()->{
        try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);unavailable.set(false);}
        catch(RuntimeException failure){if(unavailable.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(getClass()).warn("Finance ad metadata temporarily unavailable");}
    },90,30,java.util.concurrent.TimeUnit.SECONDS);}
    @PreDestroy void stop(){worker.shutdownNow();}

    void request(Scope s,List<Map<String,Object>> references){
        var safe=new LinkedHashMap<String,Reference>();
        for(var row:references){
            String advertiser=text(row.get("account_id")),campaign=text(row.get("campaign_id"));
            if(!FinanceAdCampaignMetadata.id(advertiser)||!FinanceAdCampaignMetadata.id(campaign))continue;
            var reference=new Reference(s.organizationId(),advertiser,campaign,Boolean.TRUE.equals(row.get("is_gmv")));
            safe.merge(key(reference),reference,(old,next)->old.gmv()?old:next);
        }
        if(safe.isEmpty())return;var work=List.copyOf(safe.values());
        Runnable dispatch=()->addPending(work);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                &&org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){dispatch.run();}
            });
        else dispatch.run();
    }
    private void addPending(Collection<Reference> references){
        synchronized(pending){for(var reference:references){
            String key=key(reference);var old=pending.get(key);
            if(old!=null){if(!old.gmv()&&reference.gmv())pending.put(key,reference);continue;}
            if(pending.size()<MAX_PENDING_REFERENCES)pending.put(key,reference);
        }}
    }
    private void flushRequests(){
        List<Reference> batch;
        synchronized(pending){if(pending.isEmpty())return;batch=new ArrayList<>(pending.values());pending.clear();}
        try{
            var organizations=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(var reference:batch)organizations.computeIfAbsent(reference.organization(),ignored->new ArrayList<>()).add(Map.of(
                "platform","TIKTOK_SHOP","account_id",reference.advertiser(),"campaign_id",reference.campaign(),"source",reference.gmv()?"API_GMV":"API_CAMPAIGN"));
            organizations.forEach((organization,rows)->enqueue(repo,new Scope(organization,""),rows));
        }catch(RuntimeException failure){
            synchronized(pending){var newer=new ArrayList<>(pending.values());pending.clear();addPending(batch);addPending(newer);}
            throw failure;
        }
    }

    static void enqueue(FinanceRepository repo,Scope s,List<Map<String,Object>> sources){
        var connected=new HashMap<String,Boolean>();repo.jdbc().queryForList("SELECT advertiser_id,enabled FROM finance_ad_accounts a WHERE organization_id=? AND "+FinanceAds.visibleAccount(),s.organizationId()).forEach(a->connected.put(text(a.get("advertiser_id")),Boolean.TRUE.equals(a.get("enabled"))||number(a.get("enabled"))==1));
        var keys=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:sources)if("TIKTOK_SHOP".equals(row.get("platform"))&&FinanceAdCampaignMetadata.id(row.get("campaign_id"))&&connected.containsKey(text(row.get("account_id")))){
            String key=key(row.get("account_id"),row.get("campaign_id"));var previous=keys.putIfAbsent(key,row);
            if(previous!=null&&"API_GMV".equals(row.get("source")))keys.put(key,row);
        }
        var values=new ArrayList<>(keys.values());
        for(int offset=0;offset<values.size();offset+=250){var batch=values.subList(offset,Math.min(values.size(),offset+250));var args=new ArrayList<Object>();
            for(var row:batch)Collections.addAll(args,s.organizationId(),row.get("account_id"),row.get("campaign_id"),"API_GMV".equals(row.get("source")),Boolean.TRUE.equals(connected.get(text(row.get("account_id"))))?"READY":"PAUSED");
            repo.jdbc().update("INSERT INTO finance_ad_campaign_jobs(organization_id,advertiser_id,campaign_id,is_gmv,status) VALUES "+String.join(",",Collections.nCopies(batch.size(),"(?,?,?,?,?)"))+" ON DUPLICATE KEY UPDATE is_gmv=is_gmv OR VALUES(is_gmv)",args.toArray());
        }
    }
    static List<Map<String,Object>> enrich(FinanceRepository repo,Scope s,List<Map<String,Object>> sources){
        var keys=new LinkedHashSet<String>();for(var row:sources)if("TIKTOK_SHOP".equals(row.get("platform"))&&FinanceAdCampaignMetadata.id(row.get("account_id"))&&FinanceAdCampaignMetadata.id(row.get("campaign_id")))keys.add(key(row.get("account_id"),row.get("campaign_id")));
        if(keys.isEmpty())return sources;
        var metadata=new HashMap<String,Map<String,Object>>();var pairs=new ArrayList<>(keys);
        for(int offset=0;offset<pairs.size();offset+=250){var batch=pairs.subList(offset,Math.min(pairs.size(),offset+250));var args=new ArrayList<Object>();args.add(s.organizationId());
            for(String pair:batch)Collections.addAll(args,(Object[])pair.split("\0",-1));
            for(var row:repo.jdbc().queryForList("SELECT j.advertiser_id,j.campaign_id,j.status,j.error_message,m.data_json,m.observed_at FROM finance_ad_campaign_jobs j LEFT JOIN finance_ad_campaign_metadata m ON m.organization_id=j.organization_id AND m.advertiser_id=j.advertiser_id AND m.campaign_id=j.campaign_id WHERE j.organization_id=? AND (j.advertiser_id,j.campaign_id) IN ("+String.join(",",Collections.nCopies(batch.size(),"(?,?)"))+")",args.toArray())){
                var data=row.get("data_json")==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(row.get("data_json"))));
                data.put("metadata_status",row.get("status"));data.put("metadata_error",text(row.get("error_message")));data.put("metadata_observed_at",text(row.get("observed_at")));metadata.put(key(row.get("advertiser_id"),row.get("campaign_id")),data);
            }
        }
        return sources.stream().map(row->"TIKTOK_SHOP".equals(row.get("platform"))?FinanceAdCampaignMetadata.decorate(row,metadata.getOrDefault(key(row.get("account_id"),row.get("campaign_id")),Map.of())):row).toList();
    }
    private static String key(Object advertiser,Object campaign){return text(advertiser)+"\0"+text(campaign);}
    static void reauthorized(FinanceRepository repo,Scope s,String advertiser){
        repo.jdbc().update("UPDATE finance_ad_campaign_jobs SET checkpoint_json=NULL,lease_token=NULL,lease_until=NULL,failures=0,status='READY',error_message=NULL,next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND advertiser_id=?",s.organizationId(),advertiser);
    }
    static void paused(FinanceRepository repo,Scope s,String advertiser,boolean enabled){
        repo.jdbc().update("UPDATE finance_ad_campaign_jobs SET lease_token=NULL,lease_until=NULL,status=?,next_at=GREATEST(next_at,UTC_TIMESTAMP(6)) WHERE organization_id=? AND advertiser_id=?",enabled?"READY":"PAUSED",s.organizationId(),advertiser);
    }
    void tick(){
        flushRequests();
        var due=repo.jdbc().queryForList("""
            SELECT j.*,a.user_id,a.access_token_encrypted FROM finance_ad_campaign_jobs j
            JOIN finance_ad_accounts a ON a.organization_id=j.organization_id AND a.advertiser_id=j.advertiser_id
            WHERE a.enabled=TRUE AND (a.shop_id='' OR EXISTS (SELECT 1 FROM finance_connections c WHERE c.organization_id=a.organization_id AND c.id=a.shop_id AND c.status<>'REMOVED'))
              AND j.next_at<=UTC_TIMESTAMP(6) AND (j.lease_until IS NULL OR j.lease_until<UTC_TIMESTAMP(6))
            ORDER BY j.next_at,j.advertiser_id,j.campaign_id LIMIT 1
            """);
        if(due.isEmpty())return;var job=due.getFirst();String lease=UUID.randomUUID().toString();Object[] keys=keys(job);
        if(repo.jdbc().update("UPDATE finance_ad_campaign_jobs j SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND advertiser_id=? AND campaign_id=? AND next_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) AND EXISTS(SELECT 1 FROM finance_ad_accounts a WHERE a.organization_id=j.organization_id AND a.advertiser_id=j.advertiser_id AND a.enabled=TRUE)",lease,keys[0],keys[1],keys[2])!=1)return;
        try{
            var claimed=repo.jdbc().queryForList("SELECT j.checkpoint_json,j.is_gmv,a.user_id,a.access_token_encrypted FROM finance_ad_campaign_jobs j JOIN finance_ad_accounts a ON a.organization_id=j.organization_id AND a.advertiser_id=j.advertiser_id WHERE j.organization_id=? AND j.advertiser_id=? AND j.campaign_id=? AND j.lease_token=? AND a.enabled=TRUE",keys[0],keys[1],keys[2],lease);
            if(claimed.isEmpty())return;
            job.put("user_id",claimed.getFirst().get("user_id"));job.put("access_token_encrypted",claimed.getFirst().get("access_token_encrypted"));
            if(!repo.transaction(()->owns(job,lease)))return;
            Object checkpoint=claimed.getFirst().get("checkpoint_json");
            var cp=checkpoint==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(checkpoint)));
            Object gmv=claimed.getFirst().get("is_gmv");String phase=text(cp.getOrDefault("phase",Boolean.TRUE.equals(gmv)||number(gmv)==1?"GMV":"CAMPAIGN"));int page=cp.get("page")==null?1:(int)number(cp.get("page"));
            String org=text(keys[0]),advertiser=text(keys[1]),campaign=text(keys[2]);
            String token=ads.cipher.decrypt(text(job.get("access_token_encrypted")),org,"finance:"+text(job.get("user_id"))+":"+advertiser);
            var data=ads.get(FinanceAdCampaignMetadata.endpoint(phase),token,FinanceAdCampaignMetadata.query(phase,advertiser,campaign,page));
            repo.transaction(()->{
                if(!owns(job,lease))return null;
                var metadata=new LinkedHashMap<>(map(cp.get("metadata")));boolean done=false;
                if(phase.equals("GMV")){
                    metadata.putAll(FinanceAdCampaignMetadata.gmv(data,campaign));
                    repo.jdbc().update("UPDATE finance_ad_campaign_jobs SET is_gmv=TRUE WHERE organization_id=? AND advertiser_id=? AND campaign_id=?",keys);done=true;
                }
                else if(phase.startsWith("CAMPAIGN")){
                    var parsed=FinanceAdCampaignMetadata.campaign(data,campaign);if(FinanceAdAutomation.pages(data,page,rows(data.get("list")).size())!=1)throw bad("指定系列查询分页不一致");
                    metadata.putAll(parsed);cp.put("phase",parsed.isEmpty()?(phase.endsWith("_DELETED")?"GMV":"CAMPAIGN_DELETED"):"ADGROUP");cp.put("page",1);
                }else{
                    Map<String,Object> targets;int pages;
                    try{targets=FinanceAdCampaignMetadata.adgroups(data,advertiser,campaign);pages=FinanceAdAutomation.pages(data,page,rows(data.get("list")).size());}
                    catch(com.yingdao.aicommerce.common.exception.BusinessException invalid){throw new MetadataChanged();}
                    var ids=new LinkedHashSet<String>();Object previous=cp.get("adgroup_ids");if(previous instanceof List<?> list)list.forEach(id->ids.add(text(id)));
                    for(Object id:(List<?>)targets.get("adgroup_ids"))if(!ids.add(text(id)))throw new MetadataChanged();
                    if(ids.size()>10000)throw bad("系列广告组资料超过安全范围，未发布截断关系");
                    Object total=at(data,"page_info.total_number");
                    if(total==null)throw bad("广告组资料缺少完整记录总数");
                    if(cp.containsKey("expected_total")&&(number(cp.get("expected_total"))!=number(total)||number(cp.get("expected_pages"))!=pages))throw new MetadataChanged();
                    if(page==pages&&ids.size()!=number(total))throw new MetadataChanged();
                    var stores=new TreeSet<String>();Object original=metadata.get("official_store_ids");if(original instanceof List<?> list)list.forEach(id->stores.add(text(id)));for(Object store:(List<?>)targets.get("official_store_ids"))stores.add(text(store));
                    metadata.put("official_store_ids",List.copyOf(stores));metadata.put("has_unknown_targets",Boolean.TRUE.equals(metadata.get("has_unknown_targets"))||Boolean.TRUE.equals(targets.get("has_unknown_targets")));
                    metadata.put("relationship_basis","ADGROUP_TARGETS");metadata.put("metadata_endpoint","/campaign/get/; /adgroup/get/");
                    if(page<pages){cp.put("page",page+1);cp.put("adgroup_ids",List.copyOf(ids));cp.put("expected_total",total);cp.put("expected_pages",pages);}
                    else if(!phase.endsWith("_DELETED")){cp.put("phase","ADGROUP_DELETED");cp.put("page",1);cp.remove("adgroup_ids");cp.remove("expected_total");cp.remove("expected_pages");}
                    else done=true;
                }
                if(done){
                    boolean empty=text(metadata.get("campaign_name")).isBlank()&&(!(metadata.get("official_store_ids") instanceof List<?> targets)||targets.isEmpty());
                    if(!empty)repo.jdbc().update("INSERT INTO finance_ad_campaign_metadata(organization_id,advertiser_id,campaign_id,data_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json),observed_at=UTC_TIMESTAMP(6)",keys[0],keys[1],keys[2],repo.write(metadata));
                    release(job,lease,empty?"EMPTY":"SUCCESS",null,null,86400,true);
                }else{cp.put("metadata",metadata);release(job,lease,"SYNCING",null,cp,30,false);}
                return null;
            });
        }catch(RuntimeException failure){
            repo.transaction(()->{if(!owns(job,lease))return null;
                String code=failure instanceof FinanceAdFailures.ProviderFailure provider?provider.code():"";
                boolean permission=Set.of("40001","40100","40101","40102").contains(code);
                String message=permission?"系列资料读取被拒绝（错误码 "+code+"），请使用新链接更新广告授权；已保留原有金额及资料。":"系列资料同步暂未完成，已保留原有金额及资料；系统将重试。";
                var saved=repo.jdbc().queryForObject("SELECT checkpoint_json FROM finance_ad_campaign_jobs WHERE organization_id=? AND advertiser_id=? AND campaign_id=?",String.class,keys);
                Map<String,Object> checkpoint=failure instanceof MetadataChanged||saved==null?null:repo.read(saved);
                release(job,lease,permission?"NEEDS_AUTHORIZATION":"ERROR",message,checkpoint,permission?86400:Math.min(21600,120*(1L<<Math.min(7,number(job.get("failures"))))),false);
                repo.jdbc().update("UPDATE finance_ad_campaign_jobs SET failures=failures+1 WHERE organization_id=? AND advertiser_id=? AND campaign_id=?",keys);return null;});
        }
    }
    private boolean owns(Map<String,Object> job,String lease){
        Object[] keys=keys(job);var accounts=repo.jdbc().queryForList("SELECT access_token_encrypted FROM finance_ad_accounts a WHERE organization_id=? AND advertiser_id=? AND enabled=TRUE AND "+FinanceAds.visibleAccount()+" FOR UPDATE",keys[0],keys[1]);
        if(accounts.isEmpty()||!text(job.get("access_token_encrypted")).equals(text(accounts.getFirst().get("access_token_encrypted"))))return false;
        return !repo.jdbc().queryForList("SELECT campaign_id FROM finance_ad_campaign_jobs WHERE organization_id=? AND advertiser_id=? AND campaign_id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",keys[0],keys[1],keys[2],lease).isEmpty();
    }
    private void release(Map<String,Object> job,String lease,String status,String message,Map<String,Object> cp,long delay,boolean success){
        Object[] keys=keys(job);repo.jdbc().update("UPDATE finance_ad_campaign_jobs SET status=?,error_message=?,checkpoint_json=?,next_at=?,lease_token=NULL,lease_until=NULL,last_success_at=CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE last_success_at END,failures=CASE WHEN ? THEN 0 ELSE failures END WHERE organization_id=? AND advertiser_id=? AND campaign_id=? AND lease_token=?",status,message,cp==null?null:repo.write(cp),Timestamp.from(Instant.now().plusSeconds(delay)),success,success,keys[0],keys[1],keys[2],lease);
    }
    private static Object[] keys(Map<String,Object> job){return new Object[]{job.get("organization_id"),job.get("advertiser_id"),job.get("campaign_id")};}
    private static String key(Reference reference){return reference.organization()+"\0"+reference.advertiser()+"\0"+reference.campaign();}
}
