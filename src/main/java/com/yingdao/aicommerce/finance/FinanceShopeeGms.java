package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Independent read-only reference facts; shares Ads token leases and pacing, never CPC checkpoints. */
@Service
public class FinanceShopeeGms {
    private final FinanceRepository repo;
    private final FinanceShopeeAdsSettings settings;
    private final FinanceShopeeAds ads;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"finance-shopee-gms");t.setDaemon(true);return t;});

    public FinanceShopeeGms(FinanceRepository repo,FinanceShopeeAdsSettings settings,FinanceShopeeAds ads){this.repo=repo;this.settings=settings;this.ads=ads;}
    @PostConstruct void startWorker(){worker.scheduleWithFixedDelay(()->{try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Shopee GMS dispatcher deferred: {}",failure.getClass().getSimpleName());}},60,5,java.util.concurrent.TimeUnit.SECONDS);}
    @PreDestroy void stopWorker(){worker.shutdownNow();}

    void tick(){
        var app=settings.current();if(app==null)return;
        repo.jdbc().update("INSERT IGNORE INTO finance_shopee_gms_sync(organization_id,external_shop_id) SELECT organization_id,external_shop_id FROM finance_shopee_ad_grants WHERE status<>'REMOVED'");
        var due=repo.jdbc().queryForList("""
            SELECT g.organization_id,g.external_shop_id,g.token_group_id FROM finance_shopee_ad_grants g
            JOIN finance_shopee_gms_sync s ON s.organization_id=g.organization_id AND s.external_shop_id=g.external_shop_id
            WHERE g.enabled=TRUE AND g.status<>'REMOVED' AND g.partner_id=? AND s.next_at<=UTC_TIMESTAMP(6)
              AND (g.lease_until IS NULL OR g.lease_until<=UTC_TIMESTAMP(6))
              AND EXISTS(SELECT 1 FROM finance_connections c WHERE c.organization_id=g.organization_id AND c.external_shop_id=g.external_shop_id AND c.platform='SHOPEE' AND c.enabled=TRUE AND c.status='ACTIVE')
            ORDER BY s.next_at,g.organization_id,g.external_shop_id LIMIT 30
            """,app.partnerId());
        for(var candidate:due){
            String org=text(candidate.get("organization_id")),external=text(candidate.get("external_shop_id")),lease=UUID.randomUUID().toString();
            var grant=repo.transaction(()->claim(app,candidate,lease));if(grant.isEmpty())continue;
            try{collect(app,grant,lease);}catch(RuntimeException failure){failed(grant,lease,failure);}
            finally{
                repo.jdbc().update("UPDATE finance_shopee_ad_grants SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND external_shop_id=? AND lease_token=?",org,external,lease);
                repo.jdbc().update("UPDATE finance_shopee_ad_groups SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND token_group_id=? AND lease_token=?",org,grant.get("token_group_id"),lease);
            }
            return; // One business HTTP per tick, including discovery-only ticks.
        }
    }

    private Map<String,Object> claim(FinanceShopeeAdsSettings.Application app,Map<String,Object> candidate,String lease){
        String org=text(candidate.get("organization_id")),external=text(candidate.get("external_shop_id"));Object group=candidate.get("token_group_id");
        if(repo.jdbc().queryForList("SELECT token_group_id FROM finance_shopee_ad_groups WHERE organization_id=? AND token_group_id=? AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) FOR UPDATE SKIP LOCKED",org,group).isEmpty())return Map.of();
        var grants=repo.jdbc().queryForList("SELECT * FROM finance_shopee_ad_grants WHERE organization_id=? AND external_shop_id=? AND token_group_id=? AND enabled=TRUE AND status<>'REMOVED' AND partner_id=? AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) FOR UPDATE",org,external,group,app.partnerId());
        if(grants.isEmpty())return Map.of();
        var sync=repo.jdbc().queryForList("SELECT * FROM finance_shopee_gms_sync WHERE organization_id=? AND external_shop_id=? AND next_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED",org,external);
        if(sync.isEmpty()||repo.jdbc().queryForList("SELECT platform FROM finance_api_pacing WHERE platform='SHOPEE_ADS' AND channel='CPC' AND next_allowed_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED").isEmpty())return Map.of();
        repo.jdbc().update("UPDATE finance_api_pacing SET next_allowed_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL GREATEST(5,interval_seconds) SECOND) WHERE platform='SHOPEE_ADS' AND channel='CPC'");
        repo.jdbc().update("UPDATE finance_shopee_ad_groups SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND token_group_id=?",lease,org,group);
        repo.jdbc().update("UPDATE finance_shopee_ad_grants SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND external_shop_id=?",lease,org,external);
        repo.jdbc().update("UPDATE finance_shopee_gms_sync SET status='SYNCING' WHERE organization_id=? AND external_shop_id=?",org,external);
        var grant=new LinkedHashMap<>(grants.getFirst());grant.put("gms",sync.getFirst());return grant;
    }

    private void collect(FinanceShopeeAdsSettings.Application app,Map<String,Object> grant,String lease){
        var connection=ads.connection(app,grant,lease);if(connection==null)return;
        String org=text(grant.get("organization_id")),external=text(grant.get("external_shop_id"));var sync=map(grant.get("gms"));
        ZoneId zone=ZoneId.of(text(grant.get("timezone")));LocalDate today=LocalDate.now(zone);
        var cp=text(sync.get("checkpoint_json")).isBlank()?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(sync.get("checkpoint_json"))));
        if(!cp.containsKey("from"))cp.putAll(FinanceShopeeAdsApi.plan(today,sync.get("last_success_at")==null?null:instant(sync.get("last_success_at")).atZone(zone).toLocalDate()));
        if(LocalDate.parse(text(cp.get("from"))).isBefore(today.minusMonths(6))){cp.clear();cp.put("from",today.minusMonths(6).toString());cp.put("until",today.toString());cp.put("unavailableBefore",today.minusMonths(6).toString());}
        LocalDate from=LocalDate.parse(text(cp.get("from"))),until=LocalDate.parse(text(cp.get("until"))),end=FinanceShopeeAdsApi.windowEnd(from,until);
        if(from.isAfter(end)||until.isAfter(today)||from.isBefore(today.minusMonths(6)))throw bad("GMS 历史窗口超出官方可读取范围");
        boolean forward=from.equals(end)&&from.equals(today.minusMonths(6));
        LocalDate requestFrom=from.equals(end)&&!forward?from.minusDays(1):from,requestTo=forward?end.plusDays(1):end;
        if(!"DAILY".equals(cp.get("phase"))){
            // This fixed POST is Shopee's read-only GMS report. Its aggregate expense is never subtracted.
            String id=FinanceShopeeGmsApi.campaign(app.client().shopPost(FinanceShopeeGmsApi.DISCOVERY,connection,FinanceShopeeAdsApi.query(requestFrom,requestTo)));
            repo.transaction(()->{
                if(!lockPublication(app,grant,lease))return null;
                var current=lockedSync(org,external);var ids=new TreeSet<>(ids(current));boolean growth=ids.add(id);String revision=hash(String.join("\0",ids));
                if(growth&&!text(current.get("campaign_revision")).isBlank()){
                    String unavailable=text(cp.get("unavailableBefore"));cp.clear();cp.putAll(historicalPlan(org,external,today));if(!unavailable.isBlank())cp.put("unavailableBefore",unavailable);
                }
                boolean oversized=ids.size()>100;
                if(oversized){cp.remove("phase");cp.remove("campaignIds");cp.remove("campaignRevision");}
                else{cp.put("phase","DAILY");cp.put("campaignIds",List.copyOf(ids));cp.put("campaignRevision",revision);}
                // Publish the enlarged scope even when it cannot be read in one call: old 100-ID facts are no longer complete.
                repo.jdbc().update("UPDATE finance_shopee_gms_sync SET campaign_ids_json=?,campaign_revision=?,checkpoint_json=?,status=?,error_message=?,failures=IF(?,failures+1,failures),next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE organization_id=? AND external_shop_id=?",repo.write(List.copyOf(ids)),revision,repo.write(cp),oversized?"ERROR":"SYNCING",oversized?"已识别 GMS 计划超过 100 个，需完整分批采集；旧范围金额已标记待补齐，不能截断或补零":null,oversized,oversized?21600:5,org,external);
                return null;
            });return;
        }
        List<String> ids=ids(sync);
        if(ids.isEmpty()||!ids.equals(stringList(cp.get("campaignIds")))||!text(sync.get("campaign_revision")).equals(cp.get("campaignRevision")))throw bad("GMS 计划范围已变化，等待重新核对");
        var facts=FinanceShopeeGmsApi.daily(app.client().shopGetExact(FinanceShopeeGmsApi.DAILY,connection,FinanceShopeeGmsApi.query(requestFrom,requestTo,ids)),external,ids,requestFrom,requestTo,today);
        repo.transaction(()->{
            if(!lockPublication(app,grant,lease))return null;
            var current=lockedSync(org,external);String revision=text(current.get("campaign_revision"));
            if(!revision.equals(cp.get("campaignRevision"))||!ids.equals(ids(current)))return null;
            var returned=new HashSet<String>();facts.forEach(row->returned.add(text(row.get("record_date"))));
            var previous=repo.jdbc().queryForList("SELECT record_date,currency FROM finance_shopee_gms_daily WHERE organization_id=? AND external_shop_id=? AND record_date BETWEEN ? AND ? FOR UPDATE",org,external,requestFrom,requestTo);
            if(previous.stream().anyMatch(row->!returned.contains(text(row.get("record_date")))||!text(grant.get("currency")).equals(row.get("currency"))))throw bad("GMS 刷新缺少已有日期或币种变化，保留原费用");
            for(var row:facts)repo.jdbc().update("""
                INSERT INTO finance_shopee_gms_daily(organization_id,external_shop_id,record_date,currency,amount,campaign_revision,source_json)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount=VALUES(amount),campaign_revision=VALUES(campaign_revision),source_json=VALUES(source_json),observed_at=UTC_TIMESTAMP(6)
                """,org,external,row.get("record_date"),grant.get("currency"),row.get("amount"),revision,repo.write(row.get("raw")));
            cp.remove("phase");cp.remove("campaignIds");cp.remove("campaignRevision");
            boolean done=!end.isBefore(until);
            if(done){var remaining=new ArrayList<>(rows(cp.get("remaining")));String unavailable=text(cp.get("unavailableBefore"));cp.clear();if(!unavailable.isBlank())cp.put("unavailableBefore",unavailable);if(!remaining.isEmpty()){cp.putAll(remaining.removeFirst());if(!remaining.isEmpty())cp.put("remaining",remaining);done=false;}}
            else cp.put("from",end.plusDays(1).toString());
            boolean partial=!text(cp.get("unavailableBefore")).isBlank();
            repo.jdbc().update("UPDATE finance_shopee_gms_sync SET checkpoint_json=?,status=?,error_message=?,failures=0,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),last_success_at=IF(?,UTC_TIMESTAMP(6),last_success_at) WHERE organization_id=? AND external_shop_id=?",cp.isEmpty()?null:repo.write(cp),done?(partial?"PARTIAL":"SUCCESS"):"SYNCING",partial?"部分 GMS 历史已超出接口范围；未取得日期仍未知，保留旧事实":null,done?3600:5,done,org,external);
            return null;
        });
    }

    private boolean lockPublication(FinanceShopeeAdsSettings.Application app,Map<String,Object> grant,String lease){
        String org=text(grant.get("organization_id")),external=text(grant.get("external_shop_id"));var current=settings.current();
        if(current==null||!app.revision().equals(current.revision()))return false;
        // Deletion takes the same connection-before-grant order; late paused or reauthorized replies cannot publish.
        var connected=repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND external_shop_id=? AND platform='SHOPEE' AND status='ACTIVE' AND enabled=TRUE FOR SHARE",org,external);
        var owned=repo.jdbc().queryForList("SELECT external_shop_id FROM finance_shopee_ad_grants WHERE organization_id=? AND external_shop_id=? AND enabled=TRUE AND status<>'REMOVED' AND partner_id=? AND token_group_id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",org,external,app.partnerId(),grant.get("token_group_id"),lease);
        return connected.size()==1&&!owned.isEmpty();
    }
    private Map<String,Object> lockedSync(String org,String external){return repo.jdbc().queryForMap("SELECT * FROM finance_shopee_gms_sync WHERE organization_id=? AND external_shop_id=? FOR UPDATE",org,external);}
    private List<String> ids(Map<String,Object> sync){return stringList(repo.read("{\"ids\":"+(text(sync.get("campaign_ids_json")).isBlank()?"[]":text(sync.get("campaign_ids_json")))+"}").get("ids"));}
    private static List<String> stringList(Object value){return value instanceof List<?> values?values.stream().map(FinanceData::text).sorted().toList():List.of();}
    private Map<String,Object> historicalPlan(String org,String external,LocalDate today){
        var cp=new LinkedHashMap<>(FinanceShopeeAdsApi.plan(today,null));
        var dates=repo.jdbc().queryForList("SELECT MIN(record_date) oldest FROM (SELECT record_date FROM finance_shopee_ad_daily WHERE organization_id=? AND external_shop_id=? UNION ALL SELECT record_date FROM finance_shopee_gms_daily WHERE organization_id=? AND external_shop_id=?) d",org,external,org,external);
        String oldest=text(dates.getFirst().get("oldest"));if(oldest.isBlank())return cp;
        LocalDate start=LocalDate.parse(oldest);if(start.isBefore(today.minusMonths(6)))start=today.minusMonths(6);
        LocalDate previous=today.withDayOfMonth(1).minusMonths(1);if(start.isBefore(previous)){
            var remaining=new ArrayList<Map<String,Object>>();remaining.add(Map.of("from",today.withDayOfMonth(1).toString(),"until",today.toString()));remaining.add(Map.of("from",start.toString(),"until",previous.minusDays(1).toString()));cp.put("remaining",remaining);
        }return cp;
    }
    private void failed(Map<String,Object> grant,String lease,RuntimeException failure){
        String message=text(failure.getMessage()).toLowerCase(Locale.ROOT);boolean permission=message.contains("403")||message.contains("permission")||message.contains("whitelist");boolean auth=message.contains("token")||message.contains("auth")||message.contains("401");boolean limited=message.contains("429")||message.contains("rate_limit");
        String status=permission?"NEEDS_PERMISSION":auth?"NEEDS_AUTHORIZATION":limited?"RATE_LIMITED":"ERROR";
        String safe=permission?"GMS 报表权限或白名单未开通；缺失费用保持未知":auth?"Shopee Ads 独立授权需更新":limited?"GMS 请求限流，保留原窗口等待重试":failure instanceof FinanceShopeeAdsApi.IncompleteReport?failure.getMessage():"GMS 报表未完成或计划/日期范围不完整，保留旧数据与窗口";
        long delay=permission||auth?86400:Math.min(21600,60L*(1L<<Math.min(8,number(map(grant.get("gms")).get("failures"))+1)));
        if(limited)repo.jdbc().update("UPDATE finance_api_pacing SET next_allowed_at=GREATEST(next_allowed_at,?),throttle_count=throttle_count+1 WHERE platform='SHOPEE_ADS' AND channel='CPC'",Timestamp.from(Instant.now().plusSeconds(delay)));
        repo.jdbc().update("UPDATE finance_shopee_gms_sync s JOIN finance_shopee_ad_grants g USING(organization_id,external_shop_id) SET s.status=?,s.error_message=?,s.failures=s.failures+1,s.next_at=? WHERE s.organization_id=? AND s.external_shop_id=? AND g.lease_token=? AND g.enabled=TRUE",status,safe,Timestamp.from(Instant.now().plusSeconds(delay)),grant.get("organization_id"),grant.get("external_shop_id"),lease);
    }
}
