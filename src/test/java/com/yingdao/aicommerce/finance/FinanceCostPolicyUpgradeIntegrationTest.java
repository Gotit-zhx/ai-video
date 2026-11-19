package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceCostPolicyUpgradeIntegrationTest extends FinanceCostPlatformSkuIntegrationTest {
    String policyShop(String name){
        String group=service.saveGroup(owner,null,name),shop=groupedShop(owner,name,group);
        repo.transaction(()->new FinanceCostWorkspace(repo,service).importCosts(owner,"u",group,
            ("仓库,型号,202610\n菲律宾,"+SPU+",99\n菲律宾,"+VARIANT+",11\n菲律宾,PACK,7\n").getBytes(StandardCharsets.UTF_8),"policy.csv"));
        return shop;
    }
    @Test void completedHistoricalResultsUpgradeWithoutAnotherOrderSyncOrManualImport(){
        String shop=policyShop("old-complete");var entry=purchase(shop,"SHOPEE","historical","",VARIANT,2);
        repo.save(owner,entry);calculateQueued();
        String record=id(owner,entry),legacy=hash("sku-spu-standard-v1:cancelled-excluded-v2:"+repo.write(FinanceOrderCosts.canonical(entry.raw()))+entry.status());
        db.update("UPDATE finance_order_costs SET source_hash=?,amount_cny=198,known_amount_cny=198,data_json=? WHERE order_record_id=?",
            legacy,repo.write(Map.of("status","READY","amountCny",198,"lines",List.of(Map.of("issue","商品 SKU 与平台 SPU 均未匹配到唯一成本型号，请人工确认")))),record);
        db.update("DELETE FROM finance_order_cost_jobs");
        db.update("DELETE FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE',cursor_id=? WHERE shop_id=?",record,shop);
        var rawBefore=db.queryForObject("SELECT raw_json FROM finance_records WHERE id=?",String.class,record);
        calculateQueued();
        var result=repo.read(db.queryForObject("SELECT data_json FROM finance_order_costs WHERE order_record_id=?",String.class,record));
        assertEquals("sku-platform-sku-standard-v1",result.get("policyVersion"));
        assertEquals(0,decimal("22").compareTo(decimal(result.get("amountCny"))));
        assertEquals("PLATFORM_SKU",rows(result.get("lines")).getFirst().get("matchedBy"));
        assertEquals(rawBefore,db.queryForObject("SELECT raw_json FROM finance_records WHERE id=?",String.class,record));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD' AND entity_id=?",Integer.class,shop));
        for(int i=0;i<3;i++)calculateQueued();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        assertEquals("DONE",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD' AND entity_id=?",Integer.class,shop));
    }
    @Test void policyUpgradeLeavesActiveCheckpointAndRemovedShopUntouched()throws Exception{
        String active=policyShop("active-policy"),done=policyShop("done-policy"),removed=policyShop("removed-policy");
        db.update("DELETE FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE',cursor_id='retained'");
        db.update("UPDATE finance_cost_rebuilds SET status='READY',cursor_id='active-cursor' WHERE shop_id=?",active);
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT cursor_id FROM finance_cost_rebuilds WHERE organization_id=? AND shop_id=? FOR UPDATE")){
                lock.setString(1,owner.organizationId());lock.setString(2,active);try(var ignored=lock.executeQuery()){assertTrue(ignored.next());}
                new TransactionTemplate(tx).execute(status->new FinanceOrderCosts(repo).seedAndFill());
                assertEquals("active-cursor",db.queryForObject("SELECT cursor_id FROM finance_cost_rebuilds WHERE shop_id=?",String.class,active));
                assertEquals("retained",db.queryForObject("SELECT cursor_id FROM finance_cost_rebuilds WHERE shop_id=?",String.class,removed));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD' AND entity_id IN (?,?)",Integer.class,active,removed));
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD' AND entity_id=?",Integer.class,done));
            }finally{connection.rollback();}
        }
    }
    @Test void oldAutomaticMappingIsUpdatedAndARepeatDoesNotRewriteIt(){
        String shop=policyShop("old-auto"),group=db.queryForObject("SELECT group_id FROM finance_connections WHERE id=?",String.class,shop);
        var entry=purchase(shop,"SHOPEE","automatic","",VARIANT,1);repo.save(owner,entry);
        String mapping=hash(owner.organizationId()+"\0"+shop+"\0"+SPU+"\0"+VARIANT+"\0菲律宾");
        db.update("INSERT INTO finance_cost_mappings(id,organization_id,shop_id,product_id,sku_id,warehouse,data_json,updated_by) VALUES(?,?,?,?,?,'菲律宾',?,'u')",
            mapping,owner.organizationId(),shop,SPU,VARIANT,repo.write(Map.of("code",SPU,"warehouse","菲律宾","groupId",group,"method","SPU_CODE")));
        var workspace=new FinanceCostWorkspace(repo,service);
        repo.transaction(()->workspace.autoMatch(owner,"u",shop,Map.of()));
        var saved=repo.read(db.queryForObject("SELECT data_json FROM finance_cost_mappings WHERE id=?",String.class,mapping));
        assertEquals(VARIANT,saved.get("code"));assertEquals("PLATFORM_SKU_CODE",saved.get("method"));
        long version=db.queryForObject("SELECT version FROM finance_cost_mappings WHERE id=?",Long.class,mapping);
        repo.transaction(()->workspace.autoMatch(owner,"u",shop,Map.of()));
        assertEquals(version,db.queryForObject("SELECT version FROM finance_cost_mappings WHERE id=?",Long.class,mapping));
    }
    @Test void policyAndMappingTransactionsRollbackTogether(){
        String shop=policyShop("rollback-policy");
        db.update("DELETE FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE',cursor_id='retained' WHERE shop_id=?",shop);
        new TransactionTemplate(tx).execute(status->{new FinanceOrderCosts(repo).seedAndFill();status.setRollbackOnly();return null;});
        assertEquals("retained",db.queryForObject("SELECT cursor_id FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'",Integer.class));
    }
    @Test void oldPolicyIssueIsShownAsRecalculatingWhileTheHistoryScanIsActive(){
        String shop=policyShop("old-policy-display");var entry=purchase(shop,"SHOPEE","old-issue","",VARIANT,1);
        repo.save(owner,entry);
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","settled-old-issue",entry.orderId(),"bill","结算","SETTLED",filter.start(),"PHP",decimal("100"),null,null,null,null,"API",Map.of()));
        String record=id(owner,entry);
        db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",record);
        db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,source_hash,data_json) VALUES(?,?,?,?,?,'INCOMPLETE','old',?)",
            record,owner.organizationId(),shop,entry.orderId(),entry.date(),repo.write(Map.of("status","INCOMPLETE","lines",List.of(Map.of("issue","商品 SKU 与平台 SPU 均未匹配到唯一成本型号，请人工确认")))));
        var result=new FinanceCostReport(repo).orders(owner,new Filter(filter.start(),filter.end(),shop,"","","day",""),"SETTLED_ORDER","",1);
        String issue=text(rows(result.get("items")).getFirst().get("product_cost_issue"));
        assertTrue(issue.contains("重新计算")&&issue.contains("平台 SKU"),issue);assertFalse(issue.contains("SPU"),issue);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class),"reading a page must not enqueue historical work");
    }
    @Test void policySeedingIsBoundedAndDoesNotRepeatAlreadyScheduledShops(){
        for(int i=0;i<12;i++)policyShop("bounded-policy-"+i);
        db.update("DELETE FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE',cursor_id='old-finished-cursor'");
        var worker=new FinanceOrderCosts(repo);
        repo.transaction(()->{worker.seedPolicyUpgrades();return null;});
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM finance_cost_rebuilds WHERE status='READY'",Integer.class));
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'",Integer.class));
        repo.transaction(()->{worker.seedPolicyUpgrades();return null;});
        assertEquals(12,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'",Integer.class));
        repo.transaction(()->{worker.seedPolicyUpgrades();return null;});
        assertEquals(12,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'",Integer.class));
    }
    @Test void firstPolicySchedulingInvalidatesExistingProjectionAndRollbackKeepsItAtomic(){
        // The shared fixture now installs the real V155/V156 inbox and source triggers.
        // Distinguish policy invalidation from ordinary connection/configuration events.
        java.util.function.IntSupplier policyEvents=()->db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE organization_id=? AND event_scope='ALL' AND source_table='finance_cost_policy'",Integer.class,owner.organizationId());
        String shop=policyShop("policy-events");
        assertEquals(1,policyEvents.getAsInt());
        repo.transaction(()->{new FinanceOrderCosts(repo).requestShop(owner.organizationId(),shop);return null;});
        assertEquals(1,policyEvents.getAsInt(),"a repeated request must not append another policy event");
        policyShop("same-company-events");
        assertEquals(1,policyEvents.getAsInt(),"another shop in the same company must not repeat the policy invalidation");
        db.update("DELETE FROM finance_workspace_audit WHERE entity_type IN ('COST_POLICY_REBUILD','COST_POLICY_INVALIDATE')");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE',cursor_id='retained' WHERE shop_id=?",shop);
        new TransactionTemplate(tx).execute(status->{new FinanceOrderCosts(repo).seedAndFill();status.setRollbackOnly();return null;});
        assertEquals(1,policyEvents.getAsInt(),"failed scheduling must not publish an invalidation event");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_REBUILD'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_POLICY_INVALIDATE'",Integer.class));
    }
}
