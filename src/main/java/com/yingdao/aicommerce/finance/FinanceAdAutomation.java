package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One provider request per account lease. Independent streams retain failed pages and backoff. */
final class FinanceAdAutomation {
    static final List<String> GMV_CHANNELS=List.of("GMV");
    private final FinanceAds ads;
    private final FinanceRepository repo;
    private static final class ReportChanged extends RuntimeException {}
    private static final class EmptyReportRefresh extends RuntimeException {}
    FinanceAdAutomation(FinanceAds ads){this.ads=ads;this.repo=ads.repo;}

    void tick(Scope s,Map<String,Object> account,String lease){
        String advertiser=text(account.get("advertiser_id"));
        String token=ads.cipher.decrypt(text(account.get("access_token_encrypted")),s.organizationId(),"finance:"+s.userId()+":"+advertiser);
        // A blocked legacy page must not prevent store discovery or other report streams.
        if(account.get("discovery_next_at")==null||!instant(account.get("discovery_next_at")).isAfter(Instant.now())){
            discover(s,account,lease,token);return;
        }
        var due=repo.jdbc().queryForList("""
            SELECT t.* FROM finance_ad_streams t WHERE t.organization_id=? AND t.advertiser_id=?
              AND t.next_at<=UTC_TIMESTAMP(6) AND (t.channel='BASIC' OR EXISTS (
                SELECT 1 FROM finance_ad_stores d WHERE d.organization_id=t.organization_id AND d.advertiser_id=t.advertiser_id
                  AND d.store_id=t.store_id AND d.active=TRUE AND d.report_eligible=TRUE AND d.match_status<>'REMOVED'
                  AND (d.shop_id='' OR EXISTS (SELECT 1 FROM finance_connections c WHERE c.organization_id=d.organization_id AND c.id=d.shop_id AND c.status<>'REMOVED'))))
            ORDER BY t.next_at,t.channel,t.store_id LIMIT 1
            """,s.organizationId(),advertiser);
        if(due.isEmpty()){repo.transaction(()->{if(owns(s,advertiser,lease))release(s,advertiser,lease);return null;});return;}
        sync(s,account,lease,token,due.getFirst());
    }

    private boolean owns(Scope s,String advertiser,String lease){
        return !repo.jdbc().queryForList("""
            SELECT advertiser_id FROM finance_ad_accounts WHERE organization_id=? AND advertiser_id=?
              AND user_id=? AND enabled=TRUE AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE
            """,s.organizationId(),advertiser,s.userId(),lease).isEmpty();
    }
    private void discover(Scope s,Map<String,Object> account,String lease,String token){
        String advertiser=text(account.get("advertiser_id"));
        try{
            var data=ads.get("/gmv_max/store/list/",token,Map.of("advertiser_id",advertiser));
            var stores=requiredRows(data,"store_list");
            Set<String> identities=new HashSet<>();
            for(var store:stores)if(!text(store.get("store_id")).matches("\\d{5,30}")||!identities.add(text(store.get("store_id"))))
                throw bad("广告接口返回了无效或重复店铺标识");
            repo.transaction(()->{
                if(!owns(s,advertiser,lease))return null;
                repo.jdbc().update("UPDATE finance_ad_stores SET active=FALSE WHERE organization_id=? AND advertiser_id=?",s.organizationId(),advertiser);
                boolean rematched=false;
                for(var store:stores){
                    String storeId=text(store.get("store_id"));
                    var previous=repo.jdbc().queryForList("SELECT shop_id FROM finance_ad_stores WHERE organization_id=? AND advertiser_id=? AND store_id=?",s.organizationId(),advertiser,storeId);
                    var matches=repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND platform='TIKTOK_SHOP' AND external_shop_id=? AND status<>'REMOVED' FOR SHARE",s.organizationId(),storeId);
                    String shop=matches.size()==1?text(matches.getFirst().get("id")):"";
                    boolean removed=shop.isBlank()&&!repo.jdbc().queryForList("SELECT shop_id FROM finance_shop_deletions WHERE organization_id=? AND identity_hash=?",
                            s.organizationId(),FinanceShopDeletion.identity(s.organizationId(),"TIKTOK_SHOP",storeId)).isEmpty();
                    String match=removed?"REMOVED":matches.size()>1?"AMBIGUOUS":shop.isBlank()?"UNMATCHED":"MATCHED";
                    String exclusive=first(store,"exclusive_authorized_advertiser_info.advertiser_id");
                    // Current promotion ownership/availability does not prove historical report permission.
                    // Attempt the read for every accessible store; never change exclusive authorization.
                    boolean eligible=!removed;
                    repo.jdbc().update("""
                        INSERT INTO finance_ad_stores(organization_id,advertiser_id,store_id,store_name,store_code,shop_id,match_status,report_eligible,primary_advertiser_id,gmv_available)
                        VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE store_name=VALUES(store_name),store_code=VALUES(store_code),
                          shop_id=VALUES(shop_id),match_status=VALUES(match_status),report_eligible=VALUES(report_eligible),
                          primary_advertiser_id=VALUES(primary_advertiser_id),gmv_available=VALUES(gmv_available),active=TRUE,observed_at=UTC_TIMESTAMP(6)
                        """,s.organizationId(),advertiser,storeId,bounded(store.get("store_name"),250),bounded(store.get("store_code"),100),shop,match,eligible,
                            exclusive.matches("\\d{5,30}")?exclusive:"",store.get("is_gmv_max_available") instanceof Boolean available?available:null);
                    if(eligible&&!removed)for(String channel:GMV_CHANNELS)ensureStream(s,advertiser,channel,storeId);
                    // The stable record identity never includes the matched local shop.
                    // No amount or provider payload changes when a finance shop is connected later.
                    if(!removed&&!previous.isEmpty()&&!shop.equals(text(previous.getFirst().get("shop_id"))))rematched|=repo.jdbc().update("""
                        UPDATE finance_records SET shop_id=?,updated_at=UTC_TIMESTAMP(6)
                        WHERE organization_id=? AND kind='AD_GMV' AND source='API'
                          AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.advertiser_id'))=?
                          AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_store_id'))=?
                        """,shop.isBlank()?"UNASSIGNED":shop,s.organizationId(),advertiser,storeId)>0;
                }
                if(rematched)ads.finance.refreshAfterCommit(s);
                ensureStream(s,advertiser,"BASIC","");
                repo.jdbc().update("""
                    UPDATE finance_ad_accounts SET discovery_status=?,discovery_error=NULL,discovery_failures=0,
                      discovery_next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE organization_id=? AND advertiser_id=?
                    """,stores.isEmpty()?"EMPTY":"SUCCESS",s.organizationId(),advertiser);
                release(s,advertiser,lease);return null;
            });
        }catch(RuntimeException failure){
            repo.transaction(()->{
                if(!owns(s,advertiser,lease))return null;
                ensureStream(s,advertiser,"BASIC","");
                repo.jdbc().update("""
                    UPDATE finance_ad_accounts SET discovery_status='ERROR',discovery_error=?,discovery_failures=discovery_failures+1,
                      discovery_next_at=? WHERE organization_id=? AND advertiser_id=?
                    """,safeError("店铺发现",failure),Timestamp.from(Instant.now().plusSeconds(backoff(number(account.get("discovery_failures"))))),s.organizationId(),advertiser);
                release(s,advertiser,lease);return null;
            });
        }
    }
    private void ensureStream(Scope s,String advertiser,String channel,String store){
        repo.jdbc().update("INSERT IGNORE INTO finance_ad_streams(organization_id,advertiser_id,channel,store_id) VALUES(?,?,?,?)",s.organizationId(),advertiser,channel,store);
    }
    private void sync(Scope s,Map<String,Object> account,String lease,String token,Map<String,Object> stream){
        String advertiser=text(account.get("advertiser_id")),channel=text(stream.get("channel")),store=text(stream.get("store_id"));
        Object[] keys={s.organizationId(),advertiser,channel,store};
        try{
            var cp=stream.get("checkpoint_json")==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(stream.get("checkpoint_json"))));
            ZoneId zone=ZoneId.of(text(account.get("timezone")));
            LocalDate today=LocalDate.now(zone);
            if(cp.isEmpty()){
                boolean nightly=stream.get("last_success_at")!=null&&instant(stream.get("last_success_at")).atZone(zone).toLocalDate().isBefore(today);
                LocalDate start=today.minusDays(stream.get("last_success_at")==null?89:nightly?30:6);
                if(stream.get("last_success_at")==null&&account.get("checkpoint_json")!=null){
                    String legacy=text(repo.read(text(account.get("checkpoint_json"))).get("from"));
                    if(!legacy.isBlank()&&LocalDate.parse(legacy).isBefore(start))start=LocalDate.parse(legacy);
                }
                cp.put("from",start.toString());
                cp.put("until",today.toString());cp.put("page",1);
            }
            cp.putIfAbsent("run",UUID.randomUUID().toString());
            LocalDate from=LocalDate.parse(text(cp.get("from"))),until=LocalDate.parse(text(cp.get("until"))),to=from.plusDays(6);
            if(to.isAfter(until))to=until;
            int page=(int)number(cp.get("page"));
            if(page<1||page>1000)throw bad("报表页数超出安全边界");
            boolean owned=repo.transaction(()->{
                if(!owns(s,advertiser,lease))return false;
                repo.jdbc().update("UPDATE finance_ad_streams SET checkpoint_json=?,status='SYNCING' WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=?",
                        repo.write(cp),s.organizationId(),advertiser,channel,store);return true;
            });
            if(!owned)return;
            var data=ads.get(channel.equals("BASIC")?"/report/integrated/get/":"/gmv_max/report/get/",token,query(advertiser,channel,store,from,to,page));
            var rows=requiredRows(data,"list");
            int pages=pages(data,page,rows.size());
            var keysInPage=new HashSet<String>();
            for(var row:rows){validateRow(row,channel,from,to);if(!keysInPage.add(rowKey(row,channel)))throw bad("广告报表重复维度，保留进度");}
            LocalDate end=to;
            repo.transaction(()->{
                if(!owns(s,advertiser,lease))return null;
                if(!channel.equals("BASIC")&&!eligible(s,advertiser,store))return release(s,advertiser,lease);
                for(var row:rows){
                    String key=rowKey(row,channel);
                    if(!repo.jdbc().queryForList("SELECT row_key FROM finance_ad_report_stage WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=? AND run_id=? AND row_key=? AND page_number<>?",
                            s.organizationId(),advertiser,channel,store,cp.get("run"),key,page).isEmpty())throw new ReportChanged();
                    repo.jdbc().update("""
                        INSERT INTO finance_ad_report_stage(organization_id,advertiser_id,channel,store_id,run_id,row_key,page_number,record_date,raw_json)
                        VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE raw_json=VALUES(raw_json)
                        """,s.organizationId(),advertiser,channel,store,cp.get("run"),key,page,day(row).toString(),repo.write(row));
                }
                if(page<pages)cp.put("page",page+1);
                else{
                    publish(s,account,stream,cp,from,end,data);
                    if(end.isBefore(until)){
                        cp.put("from",end.plusDays(1).toString());cp.put("page",1);cp.remove("run");
                    }else cp.clear();
                }
                boolean done=cp.isEmpty();
                repo.jdbc().update("""
                    UPDATE finance_ad_streams SET checkpoint_json=?,status=?,error_message=NULL,failures=0,
                      last_success_at=CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE last_success_at END,next_at=?
                    WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=?
                    """,done?null:repo.write(cp),done?"SUCCESS":"SYNCING",done,Timestamp.from(Instant.now().plusSeconds(done?3600:30)),
                        s.organizationId(),advertiser,channel,store);
                return release(s,advertiser,lease);
            });
        }catch(RuntimeException failure){
            repo.transaction(()->{
                if(!owns(s,advertiser,lease))return null;
                if(failure instanceof ReportChanged){
                    var saved=repo.read(repo.jdbc().queryForObject("SELECT checkpoint_json FROM finance_ad_streams WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=?",String.class,keys));
                    var restart=new LinkedHashMap<>(saved);restart.put("page",1);restart.remove("run");
                    repo.jdbc().update("DELETE FROM finance_ad_report_stage WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=? AND run_id=?",keys[0],keys[1],keys[2],keys[3],saved.get("run"));
                    repo.jdbc().update("UPDATE finance_ad_streams SET checkpoint_json=? WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=?",repo.write(restart),keys[0],keys[1],keys[2],keys[3]);
                }
                repo.jdbc().update("""
                    UPDATE finance_ad_streams SET status='ERROR',error_message=?,failures=failures+1,next_at=?
                    WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=?
                    """,safeError(channel.equals("BASIC")?"普通广告报表":"GMV Max 报表",failure),Timestamp.from(Instant.now().plusSeconds(backoff(number(stream.get("failures"))))),
                        keys[0],keys[1],keys[2],keys[3]);
                return release(s,advertiser,lease);
            });
        }
    }
    private boolean eligible(Scope s,String advertiser,String store){
        var rows=repo.jdbc().queryForList("SELECT shop_id,match_status FROM finance_ad_stores WHERE organization_id=? AND advertiser_id=? AND store_id=? AND active=TRUE AND report_eligible=TRUE FOR SHARE",s.organizationId(),advertiser,store);
        if(rows.isEmpty()||"REMOVED".equals(rows.getFirst().get("match_status")))return false;
        String shop=text(rows.getFirst().get("shop_id"));
        return shop.isBlank()||repo.writableShop(s.organizationId(),shop);
    }
    Map<String,Object> query(String advertiser,String channel,String store,LocalDate from,LocalDate to,int page){
        var query=new LinkedHashMap<String,Object>(Map.of("advertiser_id",advertiser,"start_date",from.toString(),"end_date",to.toString(),"page",page,"page_size",100));
        if(channel.equals("BASIC")){
            query.put("report_type","BASIC");query.put("data_level","AUCTION_ADVERTISER");
            query.put("dimensions",repo.write(List.of("stat_time_day")));
            query.put("metrics",repo.write(List.of("spend","billed_cost","cash_spend","voucher_spend","impressions","clicks","conversion")));
        }else{
            query.put("store_ids",repo.write(List.of(store)));
            query.put("dimensions",repo.write(List.of("campaign_id","stat_time_day")));
            query.put("metrics",repo.write(List.of("cost","net_cost","orders","gross_revenue")));
            query.put("filtering",repo.write(Map.of("gmv_max_promotion_types",List.of("PRODUCT","LIVE"),
                    "campaign_statuses",List.of("STATUS_DELETE","STATUS_DELIVERY_OK","STATUS_DISABLE","STATUS_NOT_DELIVERY"))));
        }
        return query;
    }
    private void publish(Scope s,Map<String,Object> account,Map<String,Object> stream,Map<String,Object> cp,LocalDate from,LocalDate to,Map<String,Object> response){
        String advertiser=text(account.get("advertiser_id")),channel=text(stream.get("channel")),store=text(stream.get("store_id"));
        boolean basic=channel.equals("BASIC");
        var staged=repo.jdbc().queryForList("SELECT raw_json FROM finance_ad_report_stage WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=? AND run_id=? LIMIT 20001",
                s.organizationId(),advertiser,channel,store,cp.get("run"));
        if(staged.size()>=20000)throw bad("报表达到截断边界，不能标记完整");
        Object total=at(response,"page_info.total_number");
        if(total!=null&&number(total)!=staged.size())throw new ReportChanged();
        String shop=basic?text(account.get("shop_id")):repo.jdbc().queryForObject("SELECT shop_id FROM finance_ad_stores WHERE organization_id=? AND advertiser_id=? AND store_id=?",String.class,s.organizationId(),advertiser,store);
        if(shop.isBlank())shop="UNASSIGNED";
        if(!shop.equals("UNASSIGNED"))repo.requireShopWrite(s,shop);
        var daily=new HashMap<LocalDate,List<Map<String,Object>>>();
        for(var item:staged){var row=repo.read(text(item.get("raw_json")));daily.computeIfAbsent(day(row),k->new ArrayList<>()).add(row);}
        String sourceWindow="""
            WHERE organization_id=? AND user_id=? AND kind=? AND source='API'
              AND record_date BETWEEN ? AND ? AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.advertiser_id'))=?
              AND (JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_channel'))=?
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_store_id')),'')=?
                OR ? AND JSON_EXTRACT(raw_json,'$._finance_channel') IS NULL)
            """;
        Object[] windowKeys={s.organizationId(),s.userId(),basic?"AD":"AD_GMV",from.toString(),to.toString(),advertiser,channel,store,
                basic||store.equals(text(account.get("gmv_store_id")))};
        var previousDays=repo.jdbc().queryForList("SELECT DISTINCT record_date FROM finance_records "+sourceWindow,windowKeys);
        if(previousDays.stream().anyMatch(row->!daily.containsKey(LocalDate.parse(text(row.get("record_date"))))))
            throw new EmptyReportRefresh();
        // Empty means no available rows, not an explicit reversal of previously returned spend.
        // Only this source's completed non-conflicting window is replaced; imports remain intact.
        repo.jdbc().update("DELETE FROM finance_records "+sourceWindow,windowKeys);
        var metadataReferences=new ArrayList<Map<String,Object>>();
        for(var item:staged){
            var row=repo.read(text(item.get("raw_json")));var metrics=map(row.get("metrics"));
            var raw=new LinkedHashMap<>(row);raw.put("advertiser_id",advertiser);raw.put("_finance_channel",channel);raw.put("_finance_store_id",store);
            raw.put("_finance_coverage","API_REPORT");
            String external=advertiser+":"+channel+":"+store+":"+day(row)+(basic?"":":"+text(at(row,"dimensions.campaign_id")));
            var entry=new Entry(shop,"TIKTOK_SHOP",basic?"AD":"AD_GMV",external,"","",basic?"广告账户总消耗":"GMV Max 商品及直播推广",
                    "REPORTED",day(row),text(account.get("currency")),amount(metrics,basic?"spend":"cost"),basic?null:amount(metrics,"gross_revenue"),
                    basic?FinanceAds.metricCount(metrics,"impressions"):null,basic?FinanceAds.metricCount(metrics,"clicks"):null,amount(metrics,basic?"conversion":"orders"),"API",raw);
            validate(entry);
            Object[] args=repo.entryArgs(s,entry,entry.revenue());args[0]=hash(s.organizationId()+"\0advertising\0"+external);
            repo.jdbc().update(FinanceRepository.UPSERT.replace("order_id=VALUES(order_id)","shop_id=VALUES(shop_id),order_id=VALUES(order_id)"),args);
            if(!basic)metadataReferences.add(Map.of("platform","TIKTOK_SHOP","account_id",advertiser,"campaign_id",text(at(row,"dimensions.campaign_id")),"source","API_GMV"));
        }
        if(!metadataReferences.isEmpty())FinanceAdMetadataSync.enqueue(repo,s,metadataReferences);
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)){
            var records=daily.getOrDefault(date,List.of());
            var sum=records.stream().map(r->amount(map(r.get("metrics")),basic?"spend":"cost")).reduce(BigDecimal.ZERO,BigDecimal::add);
            repo.jdbc().update("""
                INSERT INTO finance_ad_coverage(organization_id,advertiser_id,channel,store_id,record_date,row_count,amount,currency)
                VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE row_count=VALUES(row_count),amount=VALUES(amount),currency=VALUES(currency),observed_at=UTC_TIMESTAMP(6)
                """,s.organizationId(),advertiser,channel,store,date.toString(),records.size(),sum,account.get("currency"));
        }
        repo.jdbc().update("DELETE FROM finance_ad_report_stage WHERE organization_id=? AND advertiser_id=? AND channel=? AND store_id=? AND run_id=?",
                s.organizationId(),advertiser,channel,store,cp.get("run"));
        ads.finance.refreshAfterCommit(s);
    }
    private Object release(Scope s,String advertiser,String lease){
        var states=repo.jdbc().queryForList("SELECT t.status,t.last_success_at,t.next_at,t.store_id FROM finance_ad_streams t WHERE organization_id=? AND advertiser_id=? AND (channel='BASIC' OR EXISTS (SELECT 1 FROM finance_ad_stores d WHERE d.organization_id=t.organization_id AND d.advertiser_id=t.advertiser_id AND d.store_id=t.store_id AND d.active=TRUE AND d.report_eligible=TRUE AND d.match_status<>'REMOVED'))",s.organizationId(),advertiser);
        boolean failed=states.stream().anyMatch(r->"ERROR".equals(r.get("status")));
        var account=repo.jdbc().queryForList("SELECT discovery_status,discovery_next_at,gmv_store_id FROM finance_ad_accounts WHERE organization_id=? AND advertiser_id=?",s.organizationId(),advertiser).getFirst();
        boolean discovered=Set.of("SUCCESS","EMPTY").contains(text(account.get("discovery_status")));
        boolean done=!states.isEmpty()&&states.stream().allMatch(r->"SUCCESS".equals(r.get("status")));
        Object success=done?states.stream().map(r->instant(r.get("last_success_at"))).min(Comparator.naturalOrder()).map(Timestamp::from).orElse(null):null;
        String legacyStore=text(account.get("gmv_store_id"));
        boolean retireLegacy=done&&discovered&&(legacyStore.isBlank()||states.stream().anyMatch(r->legacyStore.equals(r.get("store_id"))));
        Instant next=instant(account.get("discovery_next_at"));
        for(var state:states)if(instant(state.get("next_at")).isBefore(next))next=instant(state.get("next_at"));
        if(next.isBefore(Instant.now().plusSeconds(30)))next=Instant.now().plusSeconds(30);
        repo.jdbc().update("""
            UPDATE finance_ad_accounts SET status=?,error_message=NULL,failures=0,lease_token=NULL,lease_until=NULL,
              next_at=?,last_success_at=COALESCE(?,last_success_at),checkpoint_json=CASE WHEN ? THEN NULL ELSE checkpoint_json END
            WHERE organization_id=? AND advertiser_id=? AND lease_token=?
            """,failed||!discovered?"PARTIAL":done?"SUCCESS":"SYNCING",Timestamp.from(next),success,retireLegacy,s.organizationId(),advertiser,lease);
        return null;
    }
    static List<Map<String,Object>> requiredRows(Map<String,Object> data,String key){
        if(!(data.get(key) instanceof List<?> list)||list.stream().anyMatch(v->!(v instanceof Map)))throw bad("广告接口未返回完整列表");
        return rows(data.get(key));
    }
    static int pages(Map<String,Object> data,int page,int size){
        Object value=at(data,"page_info.total_page");
        if(value==null||!text(value).matches("\\d{1,6}"))throw bad("广告接口缺少有效分页状态");
        int total=(int)number(value);
        Object actual=at(data,"page_info.page");
        if(actual!=null&&number(actual)!=page)throw bad("广告接口页码不一致");
        Object records=at(data,"page_info.total_number");
        if(records!=null&&!text(records).matches("\\d{1,10}"))throw bad("广告接口记录总数无效");
        if(total==0&&page==1&&size==0)return 1;
        if(total<page||total>1000||size>100||page<total&&size==0)throw bad("广告接口分页不完整");
        return total;
    }
    static LocalDate day(Map<String,Object> row){
        String day=text(at(row,"dimensions.stat_time_day"));
        if(!day.matches("\\d{4}-\\d{2}-\\d{2}( 00:00:00)?"))throw bad("广告报表日期无效");
        return LocalDate.parse(day.substring(0,10));
    }
    static String rowKey(Map<String,Object> row,String channel){return hash(day(row)+":"+(channel.equals("BASIC")?"":text(at(row,"dimensions.campaign_id"))));}
    static void validateRow(Map<String,Object> row,String channel,LocalDate from,LocalDate to){
        LocalDate date=day(row);if(date.isBefore(from)||date.isAfter(to))throw bad("广告报表返回了范围外日期");
        if(!channel.equals("BASIC")&&!text(at(row,"dimensions.campaign_id")).matches("\\d{5,30}"))throw bad("GMV Max 计划标识无效");
        var metrics=map(row.get("metrics"));if(amount(metrics,channel.equals("BASIC")?"spend":"cost")==null)throw bad("广告报表缺少消耗字段");
    }
    static long backoff(long failures){return Math.min(21600,120*(1L<<Math.min(7,failures)));}
    static String safeError(String stage,RuntimeException failure){
        if(failure instanceof EmptyReportRefresh)return stage+"部分日期返回空报表；已保留上次成功数据和进度，待核对并自动重试。";
        if(failure instanceof FinanceAdFailures.ProviderFailure)return stage+"："+failure.getMessage()+" 系统将自动重试，不影响其他报表。";
        return stage+"暂未完成或返回不完整；已保留进度和上次数据，系统将自动重试。";
    }
    private static String bounded(Object value,int max){String text=text(value);return text.substring(0,Math.min(max,text.length()));}
}
