package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Real MySQL transactions and indexes, mocked read-only upstream, disposable QA schema only. */
class FinanceQianyiIntegrationTest extends FinanceActualCostsIntegrationTest {
    FinanceQianyi integration;FinanceQianyiClient upstream;
    Map<String,Object> form(){
        return new LinkedHashMap<>(Map.of("environment","CN","appId","app","appSecret","private-test-secret",
            "remoteShopId","586208660231587825","remotePage",1,"costCurrency","CNY","enabled",true,"syncStart","2026-08-01"));
    }
    void qianyi(){
        fixture();upstream=mock(FinanceQianyiClient.class);
        when(upstream.query(any(),anyString(),anyMap())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"HTTP never holds a DB transaction");
            assertEquals("QUERY_SHOP_LIST",call.getArgument(1));
            return new FinanceQianyiClient.Page(List.of(Map.of("shopId","586208660231587825","name","QA remote","siteCode","PH","platform","SHOPEE")),1);
        });
        integration=new FinanceQianyi(repo,service,upstream,cipher);
    }
    Map<String,Object> lease(boolean verified){
        db.update("UPDATE finance_qianyi_connections SET lease_token='test-lease',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE),cost_basis_verified=? WHERE shop_id=?",verified,shop);
        return integration.connection(owner,shop);
    }
    void page(Map<String,Object> source,boolean verified){
        integration.acceptPage(lease(verified),"test-lease",new FinanceQianyiClient.Page(List.of(source),1),Map.of(),true);
    }
    Map<String,Object> document(){return db.queryForMap("SELECT * FROM finance_qianyi_returns WHERE shop_id=?",shop);}
    Map<String,Object> orderRow(){return db.queryForMap("SELECT id,currency FROM finance_records WHERE shop_id=? AND kind='ORDER'",shop);}
    void publish(){
        var result=FinanceQianyiEvidence.verify(FinanceQianyiEvidenceTest.receipt(),List.of(FinanceQianyiEvidenceTest.movement()));
        integration.acceptCosts(lease(true),"test-lease",document(),result,orderRow());
    }
    @Test void credentialsAreEncryptedIsolatedAndDuplicateBindingsCannotMutateOtherShop(){
        qianyi();integration.save(owner,shop,form());
        var saved=integration.connection(owner,shop);
        assertNotEquals("private-test-secret",saved.get("secret_encrypted"));
        assertEquals("private-test-secret",integration.credentials(saved).secret());
        assertFalse(repo.write(integration.configuration(owner,shop)).contains("private-test-secret"));
        assertFalse(integration.configuration(owner,shop).containsKey("secret_encrypted"));
        assertThrows(RuntimeException.class,()->integration.configuration(other,shop));
        String sibling=groupedShop(owner,"sibling",group);
        assertThrows(RuntimeException.class,()->integration.save(owner,sibling,form()));
        assertEquals(saved,integration.connection(owner,shop));
        assertTrue(integration.connection(owner,sibling).isEmpty());
        var checkpoint="{\"from\":\"2026-08-01\",\"until\":\"2026-10-30\",\"page\":2}";
        db.update("UPDATE finance_qianyi_connections SET checkpoint_json=?,last_success_at=UTC_TIMESTAMP(6) WHERE shop_id=?",checkpoint,shop);
        var retained=form();retained.put("appSecret","");integration.save(owner,shop,retained);
        assertEquals(2,number(repo.read(text(integration.connection(owner,shop).get("checkpoint_json"))).get("page")));
        assertEquals("private-test-secret",integration.credentials(integration.connection(owner,shop)).secret());
        retained.put("appId","another");
        assertThrows(RuntimeException.class,()->integration.save(owner,shop,retained));
    }
    @Test void companyCredentialsCanBeSavedBeforeRemotePermissionsWithoutEnablingCostSync(){
        qianyi();integration.saveCredentials(owner,shop,form());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_connections",Integer.class));
        var read=integration.configuration(owner,shop);
        assertEquals(true,read.get("hasSecret"));assertEquals(false,read.get("configured"));assertEquals("app",read.get("app_id"));
        assertFalse(repo.write(read).contains("private-test-secret"));assertTrue(integration.account(other).isEmpty());
        var emptySecret=form();emptySecret.put("appSecret","");
        assertEquals("private-test-secret",integration.credentials(owner,shop,emptySecret,Map.of()).secret());
        integration.save(owner,shop,emptySecret);
        assertTrue(Boolean.TRUE.equals(integration.configuration(owner,shop).get("configured")));
    }
    @Test void staleLeaseAndOlderSourcePagesCannotOverwriteAcceptedProgress(){
        qianyi();integration.save(owner,shop,form());var source=FinanceQianyiEvidenceTest.receipt();
        var old=lease(false);integration.save(owner,shop,form());
        integration.acceptPage(old,"test-lease",new FinanceQianyiClient.Page(List.of(source),1),Map.of(),true);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_returns",Integer.class));
        page(source,false);String hash=text(document().get("source_hash"));
        source.put("updateTime",1788400000000L);source.put("returnStatus","CLOSE");page(source,false);
        assertEquals(hash,document().get("source_hash"));assertEquals("PENDING",document().get("cost_status"));
        source.put("updateTime",1788570000000L);source.put("returnStatus","FINISH");
        var invalid=new LinkedHashMap<>(source);invalid.remove("returnNumber");
        var owned=lease(false);
        assertThrows(RuntimeException.class,()->integration.acceptPage(owned,"test-lease",
            new FinanceQianyiClient.Page(List.of(source,invalid),2),Map.of("page",2),false));
        assertEquals(hash,document().get("source_hash"),"whole page rolls back; no partial publication");
    }
    @Test void costPublicationIsIdempotentAndReplacesLegacyReturnCredits(){
        qianyi();String order="586208660231587825";addOrder(order,"COMPLETED");
        importData(evidence(List.of(shipment(order,"30")),List.of(receipt(order,"2026-09-02"))));compute();
        assertTrue(actual.returnTotals(owner,period(9)).isEmpty(),"legacy Excel return receipts are retained for audit only");
        integration.save(owner,shop,form());page(FinanceQianyiEvidenceTest.receipt(),false);
        var result=FinanceQianyiEvidence.verify(FinanceQianyiEvidenceTest.receipt(),List.of(FinanceQianyiEvidenceTest.movement()));
        assertThrows(RuntimeException.class,()->integration.acceptCosts(lease(false),"test-lease",document(),result,orderRow()));
        assertTrue(actual.returnTotals(owner,period(9)).isEmpty(),"unknown Qianyi costs must not fall back to Excel");
        publish();publish();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_return_costs",Integer.class));
        equalMoney("10.25",actual.returnTotals(owner,period(9)).getFirst().get("return_cost_cny"));
        assertTrue(actual.returns(other,period(9),"").isEmpty());
        var changed=FinanceQianyiEvidenceTest.receipt();changed.put("returnSkuList",List.of(Map.of("sku","SKU-A","quantity",3,"selected",1)));
        changed.put("updateTime",1788570000000L);page(changed,true);
        assertTrue(actual.returnTotals(owner,period(9)).isEmpty(),"a changed receipt cannot retain an obsolete published amount");
        changed.put("returnStatus","CLOSE");changed.put("updateTime",1788670000000L);page(changed,true);
        assertEquals("CLOSED",document().get("cost_status"));
    }
    @Test void missingOrdersAreRetriedAndUnverifiedCostsRemainExplicitlyPending(){
        qianyi();integration.save(owner,shop,form());page(FinanceQianyiEvidenceTest.receipt(),false);
        var worker=new FinanceQianyiWorker(integration);
        try{
            worker.readCosts(lease(false),"test-lease");
            assertEquals("PENDING",document().get("cost_status"));
            addOrder("586208660231587825","COMPLETED");integration.sync(owner,shop);
            worker.readCosts(lease(false),"test-lease");
            assertEquals("WAIT_COST_BASIS",document().get("cost_status"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_return_costs",Integer.class));
            var report=new LinkedHashMap<String,Object>(Map.of("total",new LinkedHashMap<String,Object>(),"shops",new ArrayList<>()));
            actual.attachReturns(owner,period(9),report);
            assertEquals(false,report.get("returnProcessing"),"first connection does not erase existing financial results");
            assertEquals(false,report.get("returnIntegrationPending"));assertEquals(false,report.get("returnCostReversalEnabled"));
            assertEquals(0,number(map(report.get("total")).get("return_pending_count")),"pending return evidence never blocks operating-cost results");
        }finally{worker.stop();}
    }
    @Test void verifiedHistoricalResultsSurviveLaterSyncErrorAndShopDeletionRemovesQianyiData(){
        qianyi();integration.save(owner,shop,form());addOrder("586208660231587825","COMPLETED");
        page(FinanceQianyiEvidenceTest.receipt(),true);publish();
        db.update("UPDATE finance_qianyi_connections SET status='ERROR',last_success_at='2026-10-29 15:00:00' WHERE shop_id=?",shop);
        var report=new LinkedHashMap<String,Object>(Map.of("total",new LinkedHashMap<String,Object>(),"shops",new ArrayList<>()));
        actual.attachReturns(owner,period(9),report);
        assertEquals(false,report.get("returnProcessing"));equalMoney("0",map(report.get("total")).get("return_cost_cny"));assertEquals(false,report.get("returnCostReversalEnabled"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_qianyi_return_costs",Integer.class),"historical evidence remains stored");
        var delayed=lease(true);service.removeShop(owner,shop);
        assertEquals("",db.queryForObject("SELECT secret_encrypted FROM finance_qianyi_connections WHERE shop_id=?",String.class,shop));
        integration.acceptPage(delayed,"test-lease",new FinanceQianyiClient.Page(List.of(FinanceQianyiEvidenceTest.receipt()),1),Map.of(),true);
        finishDeletion(shop);
        for(String table:List.of("finance_qianyi_connections","finance_qianyi_returns","finance_qianyi_return_costs"))
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),table);
    }
    @Test void identicalPagesDoNotRewriteSourcesOrEvictFinancialCaches(){
        qianyi();integration.save(owner,shop,form());
        var finance=mock(FinanceService.class);
        integration=new FinanceQianyi(repo,finance,upstream,cipher);
        var source=FinanceQianyiEvidenceTest.receipt();
        page(source,false);
        verify(finance,times(1)).refreshAfterCommit(any());
        verify(finance,never()).invalidateAfterCommit(any());
        clearInvocations(finance);
        var before=document();
        page(source,true);
        assertEquals(before.get("updated_at"),document().get("updated_at"));
        verify(finance,never()).invalidateAfterCommit(any());
        verify(finance,never()).refreshAfterCommit(any());
        source.put("returnStatus","CLOSE");source.put("updateTime",1788670000000L);
        page(source,true);
        verify(finance,times(1)).refreshAfterCommit(any());
        verify(finance,never()).invalidateAfterCommit(any());
        assertEquals("CLOSED",document().get("cost_status"));
    }
    @Test void aSlowShopDoesNotHoldAnotherShopOrAllowDuplicateLeases()throws Exception{
        qianyi();integration.save(owner,shop,form());
        String sibling=groupedShop(owner,"parallel-qianyi",group);
        when(upstream.query(any(),eq("QUERY_SHOP_LIST"),anyMap())).thenReturn(
            new FinanceQianyiClient.Page(List.of(Map.of("shopId","222","name","Second")),1));
        var second=form();second.put("remoteShopId","222");integration.save(owner,sibling,second);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        when(upstream.query(any(),eq("QUERY_RETURN_ORDER_LIST"),anyMap())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Map<String,Object> parameters=call.getArgument(2);
            String remote=text(((List<?>)parameters.get("shopIdList")).getFirst());calls.merge(remote,1,Integer::sum);
            if(remote.equals("586208660231587825")){
                entered.countDown();assertTrue(release.await(6,java.util.concurrent.TimeUnit.SECONDS));
            }
            return new FinanceQianyiClient.Page(List.of(),0);
        });
        db.update("UPDATE finance_qianyi_connections SET next_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE shop_id=?",shop);
        var worker=new FinanceQianyiWorker(integration);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=pool.submit(worker::tick);
            try{
                assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
                pool.submit(worker::tick).get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1,calls.get("222"));
                assertEquals(1,calls.get("586208660231587825"));
            }finally{release.countDown();}
            first.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }finally{worker.stop();}
    }
    @Test void globalConfigurationDiscoversExactIdsAndAutomaticallyIncludesLaterShops(){
        qianyi();var company=new FinanceQianyiAccount(integration);
        try{
            company.save(owner,form());
            when(upstream.query(any(),eq("QUERY_SHOP_LIST"),anyMap())).thenReturn(new FinanceQianyiClient.Page(List.of(
                Map.of("shopId","11","name","Exact","platform","SHOPEE","onlineShopId","actual"),
                Map.of("shopId","22","name","Future","platform","SHOPEE","onlineShopId","later"),
                Map.of("shopId","33","name","Wrong platform","platform","TIKTOK_SHOP","onlineShopId","actual")),3));
            company.tick();
            assertEquals("11",integration.connection(owner,shop).get("remote_shop_id"));
            assertFalse(repo.write(company.configuration(owner)).contains("private-test-secret"));
            String later=groupedShop(owner,"later",group);company.sync(owner);company.tick();
            assertEquals("22",integration.connection(owner,later).get("remote_shop_id"));
            assertEquals(2,number(company.configuration(owner).get("bound_count")));
            assertEquals(0,number(company.configuration(other).get("bound_count")));
            var changed=form();changed.put("appSecret","");changed.put("enabled",false);company.save(owner,changed);
            assertEquals(false,integration.connection(owner,shop).get("enabled"));
            assertEquals("private-test-secret",integration.credentials(integration.connection(owner,later)).secret());
            assertThrows(RuntimeException.class,()->company.sync(owner));
            changed.put("enabled",true);changed.put("appSecret","rotated-secret");company.save(owner,changed);
            assertEquals("rotated-secret",integration.credentials(integration.connection(owner,shop)).secret());
        }finally{company.stop();}
    }
    @Test void ambiguousRemoteIdsNeverBindAndGlobalSaveNeverRestoresRemovedShopSecrets(){
        qianyi();var company=new FinanceQianyiAccount(integration);
        try{
            company.save(owner,form());
            when(upstream.query(any(),eq("QUERY_SHOP_LIST"),anyMap())).thenReturn(new FinanceQianyiClient.Page(List.of(
                Map.of("shopId","11","platform","SHOPEE","onlineShopId","actual"),
                Map.of("shopId","22","platform","SHOPEE","onlineShopId","actual")),2));
            company.tick();assertTrue(integration.connection(owner,shop).isEmpty());
            when(upstream.query(any(),eq("QUERY_SHOP_LIST"),anyMap())).thenReturn(new FinanceQianyiClient.Page(List.of(
                Map.of("shopId","11","platform","SHOPEE","onlineShopId","actual")),1));
            company.sync(owner);company.tick();
            service.removeShop(owner,shop);company.save(owner,form());company.tick();
            assertEquals("",db.queryForObject("SELECT secret_encrypted FROM finance_qianyi_connections WHERE shop_id=?",String.class,shop));
            assertEquals(false,db.queryForObject("SELECT enabled FROM finance_qianyi_connections WHERE shop_id=?",Boolean.class,shop));
        }finally{company.stop();}
    }
    @Test void lateDiscoveryResponseCannotReenablePausedAccount(){
        qianyi();var company=new FinanceQianyiAccount(integration);
        try{
            company.save(owner,form());
            when(upstream.query(any(),eq("QUERY_SHOP_LIST"),anyMap())).thenAnswer(call->{
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                var changed=form();changed.put("enabled",false);company.save(owner,changed);
                return new FinanceQianyiClient.Page(List.of(Map.of("shopId","11","platform","SHOPEE","onlineShopId","actual")),1);
            });
            company.tick();assertTrue(integration.connection(owner,shop).isEmpty());
            assertEquals("PAUSED",company.configuration(owner).get("status"));
        }finally{company.stop();}
    }
    @Test void nativeReturnsFilterBothStatusesAndExposeSafeEvidenceWithoutRequiringLocalOrder(){
        qianyi();integration.save(owner,shop,form());var source=FinanceQianyiEvidenceTest.receipt();
        source.put("refundStatus","WAIT_PAYMENT");source.put("orderNumber","Q-SALE-1");source.put("buyerId","private-buyer");
        source.put("orderRefundAmount","36.8");source.put("currency","PHP");page(source,false);
        var read=new FinanceQianyiReturns(repo,service);
        var query=new HashMap<String,String>();query.put("receiptScope","ALL");query.put("returnStatus","FINISH");query.put("refundStatus","WAIT_PAYMENT");
        query.put("dateField","RECEIPT");query.put("start","2026-09-01");query.put("end","2026-09-30");
        var result=read.list(owner,query);assertEquals(1,number(result.get("total")));
        assertNull(rows(result.get("items")).getFirst().get("amount_cny"));
        assertEquals("586208660231587825",rows(result.get("items")).getFirst().get("order_id"));
        var detail=read.detail(owner,shop,"R1");
        assertEquals("WAIT_PAYMENT",map(detail.get("sourceFields")).get("refundStatus"));
        assertFalse(repo.write(detail).contains("private-buyer"));
        assertEquals("Q-SALE-1",map(detail.get("sourceFields")).get("orderNumber"));
        query.put("refundStatus","FINISH");assertEquals(0,number(read.list(owner,query).get("total")));
        query.put("refundStatus","WAIT_PAYMENT");query.put("numbers","586208660231587825 R1");
        assertEquals(1,number(read.list(owner,query).get("total")));
        query.put("start","2026-10-01");query.put("end","2026-10-31");
        assertEquals(0,number(read.list(owner,query).get("total")));
        assertEquals(0,number(read.list(other,Map.of()).get("total")));
        assertThrows(RuntimeException.class,()->read.detail(other,shop,"R1"));
        assertThrows(RuntimeException.class,()->read.list(other,Map.of("shopId",shop)));
    }
    @Test void nativeReturnsHaveBoundedStablePagesAndDistinctCostStates(){
        qianyi();integration.save(owner,shop,form());var sources=new ArrayList<Map<String,Object>>();
        for(int i=0;i<53;i++){var source=FinanceQianyiEvidenceTest.receipt();source.put("returnNumber",String.format("R%03d",i));sources.add(source);}
        integration.acceptPage(lease(false),"test-lease",new FinanceQianyiClient.Page(sources,53),Map.of(),true);
        var read=new FinanceQianyiReturns(repo,service);
        var first=read.list(owner,Map.of("page","1","receiptScope","ALL","start","","end",""));var second=read.list(owner,Map.of("page","2","receiptScope","ALL","start","","end",""));
        assertEquals(50,rows(first.get("items")).size());assertEquals(3,rows(second.get("items")).size());
        var numbers=new HashSet<String>();for(var row:rows(first.get("items")))assertTrue(numbers.add(text(row.get("return_number"))));
        for(var row:rows(second.get("items")))assertTrue(numbers.add(text(row.get("return_number"))));
        assertEquals(53,number(read.list(owner,Map.of("refundStatus","NOT_RETURNED","costStatus","PENDING","receiptScope","ALL","start","","end","")).get("total")));
        assertEquals(0,number(read.list(owner,Map.of("costStatus","READY")).get("total")));
        assertThrows(RuntimeException.class,()->read.list(owner,Map.of("returnStatus","' OR 1=1")));
    }
    @Test void newSourceFieldsTriggerResumableHistoricalPassWithoutUsingRecentWatermark(){
        qianyi();integration.save(owner,shop,form());
        db.update("UPDATE finance_qianyi_connections SET last_success_at=UTC_TIMESTAMP(6),source_version=1,checkpoint_json='{\"from\":\"2026-10-29\",\"until\":\"2026-10-30\",\"page\":2}' WHERE shop_id=?",shop);
        var requested=new ArrayList<Map<String,Object>>();
        when(upstream.query(any(),eq("QUERY_RETURN_ORDER_LIST"),anyMap())).thenAnswer(call->{requested.add(new HashMap<>(call.getArgument(2)));return new FinanceQianyiClient.Page(List.of(),0);});
        var worker=new FinanceQianyiWorker(integration);
        try{
            worker.readReturns(lease(false),"test-lease");
            var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            assertEquals(today.withDayOfMonth(1).minusMonths(1)+" 00:00:00",requested.getFirst().get("receiveTimeFrom"));
            var checkpoint=repo.read(text(integration.connection(owner,shop).get("checkpoint_json")));
            assertEquals(today.withDayOfMonth(1).minusMonths(1).plusDays(7).toString(),checkpoint.get("from"));
            assertEquals("2026-08-01",map(checkpoint.get("backfillResume")).get("from"));
            worker.readReturns(lease(false),"test-lease");
            assertEquals(checkpoint.get("from")+" 00:00:00",requested.getLast().get("receiveTimeFrom"),"do not restart the receipt pass on every page");
        }finally{worker.stop();}
    }
}
