package com.yingdao.aicommerce.finance;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Short database transactions serialize claims for one shop across all application instances. */
final class FinanceSyncClaims {
    record Claim(Map<String,Object> row,String token,Semaphore permit) implements AutoCloseable {
        @Override public void close(){permit.release();}
    }
    private final FinanceRepository repo;
    private final Map<String,Semaphore> platforms=new ConcurrentHashMap<>();
    FinanceSyncClaims(FinanceRepository repo){this.repo=repo;}
    Claim claim(){
        var db=repo.jdbc();
        var due=db.queryForList("""
            WITH candidates AS (
            SELECT st.organization_id,st.user_id,st.shop_id,st.channel,f.platform,
              st.priority_requested_at,
              TIMESTAMPADD(SECOND,CASE WHEN st.channel='ORDER_LIVE' THEN -30 ELSE 0 END,st.next_at) due_at,
              ROW_NUMBER() OVER (PARTITION BY f.platform ORDER BY
                st.priority_requested_at IS NULL,st.priority_requested_at,
                TIMESTAMPADD(SECOND,CASE WHEN st.channel='ORDER_LIVE' THEN -30 ELSE 0 END,st.next_at),
                st.shop_id,st.channel) platform_rank
            FROM finance_sync_states st JOIN finance_connections f
              ON f.id=st.shop_id AND f.organization_id=st.organization_id AND f.user_id=st.user_id
            WHERE f.enabled=TRUE AND f.status='ACTIVE' AND st.next_at<=UTC_TIMESTAMP(6)
              AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6))
              AND NOT EXISTS(SELECT 1 FROM finance_api_pacing pace
                WHERE pace.platform=f.platform AND pace.channel=st.channel AND pace.next_allowed_at>UTC_TIMESTAMP(6))
              AND NOT EXISTS(SELECT 1 FROM finance_sync_states busy
                WHERE busy.organization_id=st.organization_id AND busy.user_id=st.user_id AND busy.shop_id=st.shop_id
                  AND busy.lease_until>UTC_TIMESTAMP(6))
            )
            SELECT organization_id,user_id,shop_id,channel,platform FROM candidates WHERE platform_rank<=32
            ORDER BY priority_requested_at IS NULL,priority_requested_at,due_at,shop_id,channel
            """);
        if(due.isEmpty())return null;
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        for(var candidate:due){
            var permit=platforms.computeIfAbsent(text(candidate.get("platform")),ignored->new Semaphore(2));
            if(!permit.tryAcquire())continue;
            Claim result=null;
            try{
                result=tx.execute(transaction->{
                    Object[] shop={candidate.get("organization_id"),candidate.get("user_id"),candidate.get("shop_id")};
                    var locked=db.queryForList("""
                        SELECT id FROM finance_connections WHERE organization_id=? AND user_id=? AND id=?
                          AND enabled=TRUE AND status='ACTIVE' FOR UPDATE SKIP LOCKED
                        """,shop);
                    if(locked.isEmpty())return null;
                    Long busy=db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE organization_id=? AND user_id=? AND shop_id=? AND lease_until>UTC_TIMESTAMP(6)",Long.class,shop);
                    if(busy!=null&&busy>0)return null;
                    var rows=db.queryForList("""
                        SELECT st.*,f.timezone,f.currency,f.platform FROM finance_sync_states st JOIN finance_connections f
                          ON f.id=st.shop_id AND f.organization_id=st.organization_id AND f.user_id=st.user_id
                        WHERE st.organization_id=? AND st.user_id=? AND st.shop_id=? AND st.channel=?
                          AND st.next_at<=UTC_TIMESTAMP(6) AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6))
                        """,shop[0],shop[1],shop[2],candidate.get("channel"));
                    if(rows.isEmpty())return null;
                    if(!FinanceRefundPacing.reserve(repo,rows.getFirst()))return null;
                    String token=UUID.randomUUID().toString();
                    int updated=db.update("""
                        UPDATE finance_sync_states SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),
                          lease_token=?,status='RUNNING' WHERE organization_id=? AND user_id=? AND shop_id=? AND channel=?
                          AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
                        """,token,shop[0],shop[1],shop[2],candidate.get("channel"));
                    return updated==1?new Claim(rows.getFirst(),token,permit):null;
                });
                if(result!=null)return result;
            }finally{if(result==null)permit.release();}
        }
        return null;
    }
}
