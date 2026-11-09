package com.yingdao.aicommerce.finance;

import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Local finance data only. Shared group cost books and commerce grants are never deleted. */
final class FinanceShopDeletion {
    static final int BATCH=500;
    private final FinanceRepository repo;
    FinanceShopDeletion(FinanceRepository repo){this.repo=repo;}
    static String identity(String organization,String platform,String external){
        return hash(organization+"\0"+platform+"\0"+external);
    }
    void request(Scope scope,String shopId){
        request(scope,shopId,scope.userId());
    }
    void request(Scope scope,String shopId,String actor){
        repo.transaction(()->{
            var shops=repo.jdbc().queryForList("SELECT * FROM finance_connections WHERE organization_id=? AND id=? FOR UPDATE",
                    scope.organizationId(),shopId);
            if(shops.isEmpty()){
                if(!repo.jdbc().queryForList("SELECT shop_id FROM finance_shop_deletions WHERE organization_id=? AND shop_id=?",
                        scope.organizationId(),shopId).isEmpty())return null;
                throw bad("没有找到本公司的财务店铺");
            }
            var shop=shops.getFirst();
            repo.jdbc().update("""
                INSERT IGNORE INTO finance_shop_deletions(organization_id,shop_id,identity_hash,shop_label,requested_by)
                VALUES(?,?,?,?,?)
                """,scope.organizationId(),shopId,identity(scope.organizationId(),text(shop.get("platform")),text(shop.get("external_shop_id"))),
                    first(shop,"display_name","name"),actor);
            for(String advertiser:repo.jdbc().queryForList("SELECT advertiser_id FROM finance_ad_accounts WHERE organization_id=? AND shop_id=? FOR UPDATE",String.class,scope.organizationId(),shopId))
                repo.jdbc().update("INSERT IGNORE INTO finance_shop_deletion_ads(organization_id,advertiser_hash,shop_id) VALUES(?,?,?)",
                        scope.organizationId(),hash(scope.organizationId()+"\0"+advertiser),shopId);
            repo.jdbc().update("""
                UPDATE finance_connections SET enabled=FALSE,status='REMOVED',access_token_encrypted='',
                  refresh_token_encrypted=NULL,access_token_expires_at=NULL,refresh_token_expires_at=NULL,
                  scopes_json=JSON_ARRAY(),updated_at=UTC_TIMESTAMP(6)
                WHERE organization_id=? AND id=?
                """,scope.organizationId(),shopId);
            // Invalidate outstanding worker ownership before returning to the user.
            repo.jdbc().update("UPDATE finance_ad_stores SET match_status='REMOVED',report_eligible=FALSE WHERE organization_id=? AND shop_id=?",scope.organizationId(),shopId);
            repo.jdbc().update("""
                UPDATE finance_sync_states SET status='DISABLED',lease_token=NULL,lease_until=NULL,checkpoint_json=NULL,error_message=NULL
                WHERE organization_id=? AND shop_id=?
                """,scope.organizationId(),shopId);
            repo.jdbc().update("""
                UPDATE finance_ad_accounts SET enabled=FALSE,status='DISABLED',access_token_encrypted='',
                  lease_token=NULL,lease_until=NULL,checkpoint_json=NULL,error_message=NULL
                WHERE organization_id=? AND shop_id=?
                """,scope.organizationId(),shopId);
            repo.jdbc().update("""
                UPDATE finance_qianyi_connections SET enabled=FALSE,status='DISABLED',secret_encrypted='',
                  lease_token=NULL,lease_until=NULL,checkpoint_json=NULL,revision=revision+1
                WHERE organization_id=? AND shop_id=?
                """,scope.organizationId(),shopId);
            repo.jdbc().update("""
                UPDATE finance_authorization_sessions SET status='CANCELLED',error_message=NULL
                WHERE organization_id=? AND JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.shopId'))=?
                """,scope.organizationId(),shopId);
            return null;
        });
    }
    List<Map<String,Object>> progress(Scope scope){
        return repo.jdbc().queryForList("""
            SELECT shop_id,shop_label,status,deleted_rows,requested_at,completed_at FROM finance_shop_deletions
            WHERE organization_id=? AND (status<>'COMPLETE' OR completed_at>=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))
            ORDER BY requested_at DESC LIMIT 30
            """,scope.organizationId());
    }
    void checkAuthorization(Scope scope,String platform,String external,Instant started){
        var jobs=repo.jdbc().queryForList("""
            SELECT status,requested_at FROM finance_shop_deletions
            WHERE organization_id=? AND identity_hash=? ORDER BY requested_at DESC LIMIT 1 FOR SHARE
            """,scope.organizationId(),identity(scope.organizationId(),platform,external));
        if(!jobs.isEmpty()&&(!"COMPLETE".equals(jobs.getFirst().get("status"))
                ||!started.isAfter(instant(jobs.getFirst().get("requested_at")))))
            throw bad("该店铺正在删除，或授权链接已失效；删除完成后请重新生成授权链接");
    }
    void checkAdAuthorization(Scope scope,String advertiser,Instant started){
        var jobs=repo.jdbc().queryForList("""
            SELECT d.status,d.requested_at FROM finance_shop_deletion_ads a
            JOIN finance_shop_deletions d ON d.organization_id=a.organization_id AND d.shop_id=a.shop_id
            WHERE a.organization_id=? AND a.advertiser_hash=? ORDER BY d.requested_at DESC LIMIT 1 FOR SHARE
            """,scope.organizationId(),hash(scope.organizationId()+"\0"+advertiser));
        if(!jobs.isEmpty()&&(!"COMPLETE".equals(jobs.getFirst().get("status"))||!started.isAfter(instant(jobs.getFirst().get("requested_at")))))
            throw bad("该广告账户正在随店铺删除，或授权链接已失效；请在删除完成后重新授权");
    }
    /** Called inside a short transaction; a failed batch rolls back with its progress. */
    String batch(){return batch(job->{});}
    String batch(java.util.function.Consumer<Map<String,Object>> selected){
        var jobs=repo.jdbc().queryForList("""
            SELECT * FROM finance_shop_deletions WHERE status IN ('QUEUED','RUNNING','RETRY')
              AND next_at<=UTC_TIMESTAMP(6) ORDER BY next_at,requested_at LIMIT 1 FOR UPDATE SKIP LOCKED
            """);
        if(jobs.isEmpty())return null;
        selected.accept(jobs.getFirst());
        var job=jobs.getFirst();String org=text(job.get("organization_id")),shop=text(job.get("shop_id"));
        int stage=(int)number(job.get("stage")),count;
        Object[] keys={org,shop};
        count=switch(stage){
            case 0 -> delete("finance_sync_states","organization_id=? AND shop_id=?",keys);
            case 1 -> delete("finance_cost_rebuilds","organization_id=? AND shop_id=?",keys);
            case 2 -> delete("finance_order_cost_jobs","organization_id=? AND shop_id=?",keys);
            case 3 -> {
                int removed=delete("finance_qianyi_return_costs","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_qianyi_returns","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_qianyi_connections","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_marketing_orders","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_charge_rows","organization_id=? AND matched_shop_id=?",keys);
                if(removed==0)removed=delete("finance_label_order_totals","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_label_invoice_rows","organization_id=? AND matched_shop_id=?",keys);
                yield removed>0?removed:entities("finance_order_costs","order_record_id","ORDER_STANDARD_COST","organization_id=? AND shop_id=?",keys);
            }
            case 4 -> entities("finance_cost_mappings","id","COST_MAPPING","organization_id=? AND shop_id=?",keys);
            case 5 -> delete("finance_workspace_audit","organization_id=? AND entity_id=? AND entity_type IN ('COST_SETTINGS','COST_AUTO_MATCH','ACTUAL_COST_IMPORT')",keys);
            case 6 -> delete("finance_cost_settings","organization_id=? AND shop_id=?",keys);
            case 7 -> entities("finance_expenses","id","EXPENSE","organization_id=? AND target_id=? AND scope IN ('shop','order')",keys);
            case 8 -> entities("finance_expense_rules","id","EXPENSE_RULE",
                    "organization_id=? AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.target'))=? AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.scope'))='shop'",keys);
            case 9 -> delete("finance_imports","organization_id=? AND shop_id=?",keys);
            case 10 -> {
                int removed=delete("finance_records","organization_id=? AND shop_id=?",keys);
                if(removed==0)removed=delete("finance_reporting_months","organization_id=? AND shop_id=?",keys);
                yield removed;
            }
            case 11 -> {
                int removed=0;
                for(String table:List.of("finance_ad_report_stage","finance_ad_coverage","finance_ad_streams")){
                    removed=delete(table,"organization_id=? AND (EXISTS (SELECT 1 FROM finance_ad_stores d WHERE d.organization_id="+table+".organization_id AND d.advertiser_id="+table+".advertiser_id AND d.store_id="+table+".store_id AND d.shop_id=?) OR EXISTS (SELECT 1 FROM finance_ad_accounts a WHERE a.organization_id="+table+".organization_id AND a.advertiser_id="+table+".advertiser_id AND a.shop_id=?))",org,shop,shop);
                    if(removed>0)break;
                }
                if(removed==0)removed=delete("finance_ad_stores","organization_id=? AND shop_id=?",keys);
                yield removed>0?removed:delete("finance_ad_accounts","organization_id=? AND shop_id=?",keys);
            }
            case 12 -> delete("finance_authorization_sessions","organization_id=? AND JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.shopId'))=?",keys);
            case 13 -> {
                repo.jdbc().update("DELETE FROM finance_connections WHERE organization_id=? AND id=? AND status='REMOVED'",keys);
                repo.jdbc().update("""
                    UPDATE finance_shop_deletions SET status='COMPLETE',shop_label='',completed_at=UTC_TIMESTAMP(6),
                      updated_at=UTC_TIMESTAMP(6),failures=0 WHERE organization_id=? AND shop_id=?
                    """,keys);
                yield -1;
            }
            default -> throw new IllegalStateException("Unknown finance deletion stage");
        };
        if(count>=0)repo.jdbc().update("""
            UPDATE finance_shop_deletions SET status='RUNNING',stage=?,deleted_rows=deleted_rows+?,
              failures=0,updated_at=UTC_TIMESTAMP(6),next_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND shop_id=?
            """,count==0?stage+1:stage,count,org,shop);
        return count==-1?org:"";
    }
    private int delete(String table,String where,Object...args){
        return repo.jdbc().update("DELETE FROM "+table+" WHERE "+where+" LIMIT "+BATCH,args);
    }
    private int entities(String table,String key,String type,String where,Object...args){
        var ids=repo.jdbc().queryForList("SELECT "+key+" FROM "+table+" WHERE "+where+" LIMIT "+BATCH,String.class,args);
        if(ids.isEmpty())return 0;
        var values=new ArrayList<Object>();values.add(args[0]);values.add(type);values.addAll(ids);
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        int audit=delete("finance_workspace_audit","organization_id=? AND entity_type=? AND entity_id IN ("+slots+")",values.toArray());
        if(audit>0)return audit;
        values.remove(1);
        return delete(table,"organization_id=? AND "+key+" IN ("+slots+")",values.toArray());
    }
}
