package com.yingdao.aicommerce.finance;

import java.util.ArrayList;
import java.util.List;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Initialize and wake sync lanes inside the shop configuration/authorization transaction. */
final class FinanceSyncChannels {
    private FinanceSyncChannels(){}

    static void enable(FinanceRepository repo,Scope owner,String shopId,String platform){
        var channels=new ArrayList<>(List.of("ORDER","ORDER_LIVE","ORDER_REPAIR","REFUND","SETTLEMENT"));
        if("TIKTOK_SHOP".equals(platform))channels.add("PENDING");
        for(String channel:channels){
            repo.jdbc().update("""
                INSERT IGNORE INTO finance_sync_states(organization_id,user_id,shop_id,channel)
                VALUES(?,?,?,?)
                """,owner.organizationId(),owner.userId(),shopId,channel);
            // Keep checkpoints, coverage and active worker leases intact on reauthorization.
            repo.jdbc().update("""
                UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP(6),status='READY',
                  error_message=NULL,failures=0
                WHERE organization_id=? AND user_id=? AND shop_id=? AND channel=?
                  AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6))
                """,owner.organizationId(),owner.userId(),shopId,channel);
        }
    }
}
