package com.yingdao.aicommerce.finance;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Uses only the disposable finance_qa schema. Whole-file publication and order identity are tested end to end. */
class FinanceLabelCostsIntegrationTest extends FinanceConfigurationIntegrationTest {
    FinanceLabelCosts labels;Path directory;String group,shop;
    void fixture()throws Exception{
        directory=Files.createTempDirectory("label-invoice-qa-");labels=new FinanceLabelCosts(repo,service,directory.toString());
        group=service.saveGroup(owner,null,"仓库账单");shop=groupedShop(owner,"labels",group);
    }
    byte[] file(String amount,String...orders){
        var rows=new ArrayList<List<String>>();int i=0;
        for(String order:orders)rows.add(FinanceLabelInvoiceTest.line("W"+(++i),order,amount));
        return FinanceLabelInvoiceTest.invoice(rows,decimal(amount).multiply(decimal(orders.length)).toPlainString(),"CNY");
    }
    Map<String,Object> upload(byte[] bytes)throws Exception{return labels.upload(owner,"u","invoice.xlsx",new ByteArrayInputStream(bytes));}
    void add(String order,int settlementMonth){
        repo.save(owner,dated(shop,"ORDER",order,order,"",LocalDate.of(2026,8,10),"100",Map.of()));
        if(settlementMonth>0){
            repo.save(owner,dated(shop,"SETTLEMENT",order+"-settled",order,"bill",LocalDate.of(2026,settlementMonth,2),"80",Map.of()));
            repo.save(owner,dated(shop,"SETTLEMENT",order+"-later",order,"bill2",LocalDate.of(2026,10,4),"5",Map.of()));
        }
    }
    Filter month(int month){return new Filter(LocalDate.of(2026,month,1),LocalDate.of(2026,month,1).plusMonths(1).minusDays(1),shop,"","","day",group);}
    Object amount(int month){return map(new FinanceCostReport(repo).summary(owner,month(month),"SETTLED_ORDER").get("total")).get("label_cost_cny");}
    void money(String expected,Object value){assertEquals(0,decimal(expected).compareTo(decimal(value)));}
    void process(String id){
        labels.tick();
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_label_imports WHERE id=?",String.class,id),
            ()->db.queryForObject("SELECT COALESCE(error_message,'') FROM finance_label_imports WHERE id=?",String.class,id));
        assertNull(db.queryForObject("SELECT error_message FROM finance_label_state WHERE organization_id='org'",String.class));
    }
    @Test void retiredGenerationBacklogMustDrainBeforeAnotherBuild()throws Exception{
        fixture();add("order",9);process(text(upload(file("2","order")).get("id")));
        String active=db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class);
        var staleRows=new ArrayList<Object[]>();
        for(int i=0;i<6001;i++)staleRows.add(new Object[]{"org","retired",shop,"old-"+i,7});
        db.batchUpdate("""
            INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
            VALUES(?,?,?,?,?,0,1)
            """,staleRows);
        FinanceLabelCosts.dirty(repo,"org");
        var bounded=new FinanceLabelCosts(repo,service,directory.toString()){
            @Override boolean cleanupGenerations(String token){return super.cleanupGenerations(token,1);}
        };
        try{
            bounded.tick();
            assertEquals(active,db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class));
            assertEquals(1001,db.queryForObject("SELECT COUNT(*) FROM finance_label_order_totals WHERE generation='retired'",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_state WHERE requested_revision>applied_revision",Integer.class));
            money("2",amount(9));
            bounded.tick();
            assertNotEquals(active,db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_order_totals",Integer.class));
            money("2",amount(9));
        }finally{bounded.stop();}
    }
    @Test void repeatedRebuildsRetainOnlyPublishedValuesAndSourceEvidence()throws Exception{
        fixture();add("order",9);process(text(upload(file("2","order")).get("id")));
        for(int i=0;i<6;i++){
            FinanceLabelCosts.dirty(repo,"org");labels.tick();
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_order_totals",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows",Integer.class));
            money("2",amount(9));
        }
    }
    @Test void lowDiskPausesNewGenerationWithoutLosingPublishedTotals()throws Exception{
        fixture();add("order",9);process(text(upload(file("2","order")).get("id")));
        String active=db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class);
        FinanceLabelCosts.dirty(repo,"org");
        var fullDisk=new FinanceLabelCosts(repo,service,directory.toString()){
            @Override void requireRebuildSpace(){throw bad("服务器可用空间不足 2GB，已暂停贴单重算；当前已生效金额保留");}
        };
        try{fullDisk.tick();}finally{fullDisk.stop();}
        assertEquals(active,db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_state WHERE requested_revision>applied_revision AND retry_after>UTC_TIMESTAMP(6)",Integer.class));
        assertTrue(db.queryForObject("SELECT error_message FROM finance_label_state WHERE organization_id='org'",String.class).contains("可用空间不足"));
        money("2",amount(9));
    }
    @Test void uploadsAreAtomicIdempotentRmbAndFollowFirstSettlement()throws Exception{
        fixture();add("august-order",9);add("october-order",10);add("pending",0);
        var bytes=file("2","august-order","october-order","pending");
        String id=text(upload(bytes).get("id"));money("0",amount(9));
        process(id);money("2",amount(9));money("2",amount(10));
        assertEquals(true,upload(bytes).get("duplicate"));
        assertEquals(0,Files.list(directory).count(),"raw upload removed after successful publication");
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows",Integer.class));
        // Different file encoding/zero-value metadata, same warehouse events: never charge twice.
        var sheets=FinanceLabelInvoiceTest.invoice(List.of(FinanceLabelInvoiceTest.line("W1","august-order","2.000000"),
            FinanceLabelInvoiceTest.line("W2","october-order","2.000000"),FinanceLabelInvoiceTest.line("W3","pending","2.000000")),"6.000000","CNY");
        String second=text(upload(sheets).get("id"));process(second);money("2",amount(9));
        labels.remove(owner,id);labels.tick();money("2",amount(9));
        labels.remove(owner,second);labels.tick();money("0",amount(9));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class),"invoice import does not request a historical SKU recalculation");
    }
    @Test void missingOrdersMatchWhenSynchronizedAndSameNumbersNeverCrossOrganizations()throws Exception{
        fixture();
        String anotherGroup=service.saveGroup(other,null,"他司"),otherShop=groupedShop(other,"same-order",anotherGroup);
        repo.save(other,dated(otherShop,"ORDER","later","later","",LocalDate.of(2026,8,10),"100",Map.of()));
        String id=text(upload(file("2","later")).get("id"));process(id);
        assertEquals(0,db.queryForObject("SELECT matched_count FROM finance_label_imports WHERE id=?",Integer.class,id));
        add("later",9);labels.tick();money("2",amount(9));
        assertEquals(shop,db.queryForObject("SELECT matched_shop_id FROM finance_label_invoice_rows",String.class));
        assertThrows(RuntimeException.class,()->labels.remove(other,id));
        assertTrue(rows(labels.workspace(other,month(9),Map.of()).get("files")).isEmpty());
        service.removeShop(owner,shop);finishDeletion(shop);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_label_order_totals",Integer.class));
    }
    @Test void amountConflictsAreNotSummedAndRemovingBadFileRestoresConfirmedCost()throws Exception{
        fixture();add("order",9);
        String original=text(upload(file("2","order")).get("id"));process(original);
        String changed=text(upload(file("3","order")).get("id"));process(changed);
        money("0",amount(9));
        var total=map(new FinanceCostReport(repo).summary(owner,month(9),"SETTLED_ORDER").get("total"));
        assertEquals(1,number(total.get("charges_pending")));
        labels.remove(owner,changed);labels.tick();money("2",amount(9));
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES('org',?,'order',7,1)",shop);
        money("0",amount(9));
    }
    @Test void failedWholeFileAndDuplicateWmsNeverPublishPartialStagingRows()throws Exception{
        fixture();add("order",9);String valid=text(upload(file("2","order")).get("id"));process(valid);
        var invalid=FinanceLabelInvoiceTest.invoice(List.of(FinanceLabelInvoiceTest.line("W2","order","3")),"4","CNY");
        String bad=text(upload(invalid).get("id"));labels.tick();
        assertEquals("FAILED",db.queryForObject("SELECT status FROM finance_label_imports WHERE id=?",String.class,bad));
        money("2",amount(9));
        var duplicate=FinanceLabelInvoiceTest.invoice(List.of(FinanceLabelInvoiceTest.line("W2","order","3"),FinanceLabelInvoiceTest.line("W2","order","3")),"6","CNY");
        String repeated=text(upload(duplicate).get("id"));labels.tick();
        assertEquals("FAILED",db.queryForObject("SELECT status FROM finance_label_imports WHERE id=?",String.class,repeated));money("2",amount(9));
    }
    @Test void sameOrderWithMultipleShipmentsAddsChargesOnceAndExportsCanonicalInvoiceValue()throws Exception{
        fixture();add("order",9);
        var bytes=FinanceLabelInvoiceTest.invoice(List.of(FinanceLabelInvoiceTest.line("W1","order","2"),FinanceLabelInvoiceTest.line("W2","order","3")),"5","CNY");
        process(text(upload(bytes).get("id")));money("5",amount(9));
        var exported=new FinanceCostExport(repo).export(owner,month(9),"SETTLED_ORDER","");
        var sheet=FinanceCostExportTest.sheet(exported,2);
        assertTrue(sheet.getDocumentElement().getTextContent().contains("按导入账单金额"));
        var workspace=labels.workspace(owner,month(9),Map.of("numbers","order"));
        money("5",map(workspace.get("period")).get("amount_cny"));
        assertEquals(2,number(workspace.get("total")));
    }
    @Test void multipleQueuedFilesEachPublishEvenIfEarlierFileAlreadyAppliedTheUploadRevision()throws Exception{
        fixture();add("first",9);add("second",9);
        String first=text(upload(file("2","first")).get("id"));
        String second=text(upload(FinanceLabelInvoiceTest.invoice(
            List.of(FinanceLabelInvoiceTest.line("W2","second","3")),"3","CNY")).get("id"));
        process(first);money("2",amount(9));
        process(second);money("5",amount(9));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_label_imports WHERE status='READY'",Integer.class));
    }
    @Test void flatFeesOverrideZeroPlaceholdersAndWarehouseCopyWithoutDoubleCharge()throws Exception{
        fixture();add("order",9);add("failed",9);
        process(text(upload(file("2","order")).get("id")));
        byte[] flat=FinanceLabelInvoiceTest.flat(List.of(
            FinanceLabelInvoiceTest.flatLine("order","计算成功","0","","0",""),
            FinanceLabelInvoiceTest.flatLine("order","计算成功","1.95","1","1.95","出库费"),
            FinanceLabelInvoiceTest.flatLine("order","计算成功","1.95","1","1.95","出库费"),
            FinanceLabelInvoiceTest.flatLine("failed","计费失败","0","","0","")));
        String id=text(upload(flat).get("id"));process(id);money("1.95",amount(9));
        assertEquals(4,db.queryForObject("SELECT row_count FROM finance_label_imports WHERE id=?",Integer.class,id));
        var total=map(new FinanceCostReport(repo).summary(owner,month(9),"SETTLED_ORDER").get("total"));
        assertEquals(0,number(total.get("charges_pending")),"explicit zero is a confirmed amount");
        assertEquals(0,db.queryForObject("SELECT billing_failed_count FROM finance_label_imports WHERE id=?",Integer.class,id));
        // Sources imported by the previous release remain valid without changing raw rows.
        db.update("UPDATE finance_label_invoice_rows SET billing_priority=0 WHERE order_id='failed'");
        FinanceLabelCosts.dirty(repo,"org");labels.tick();
        assertEquals(0,db.queryForObject("SELECT pending_count FROM finance_label_order_totals lc JOIN finance_label_state ls ON ls.organization_id=lc.organization_id AND ls.active_generation=lc.generation WHERE lc.order_id='failed'",Integer.class));
        labels.remove(owner,id);labels.tick();money("2",amount(9));
    }
    @Test void laterShopAndOrderBindStoredFeesWithoutRematchingUnrelatedUnresolvedRows()throws Exception{
        fixture();
        String id=text(upload(FinanceLabelInvoiceTest.flat(List.of(
            FinanceLabelInvoiceTest.flatLine("future","计算成功","2","1","2","出库费"),
            FinanceLabelInvoiceTest.flatLine("unrelated","计算成功","3","1","3","出库费")))).get("id"));
        process(id);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows WHERE match_checked=TRUE",Integer.class));
        var lateGroup=service.saveGroup(owner,null,"后来新增");var late=groupedShop(owner,"later-shop",lateGroup);
        repo.save(owner,dated(late,"ORDER","future","future","",LocalDate.of(2026,8,20),"20",Map.of()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class));
        labels.arrivals();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows WHERE match_checked=FALSE",Integer.class));
        labels.tick();
        assertEquals(late,db.queryForObject("SELECT matched_shop_id FROM finance_label_invoice_rows WHERE order_id='future'",String.class));
        assertEquals(1,db.queryForObject("SELECT unmatched_count FROM finance_label_imports WHERE id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("SELECT match_revision FROM finance_label_invoice_rows WHERE order_id='unrelated'",Integer.class));
    }
    @Test void orderArrivalDuringBuildDoesNotStarvePublicationButSourceRemovalInvalidatesIt()throws Exception{
        fixture();add("order",9);
        String id=text(upload(file("2","order")).get("id"));process(id);
        String original=db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class);
        var advancing=new FinanceLabelCosts(repo,null,directory.toString()){
            @Override int timedUpdate(String sql,Object...arguments){
                int changed=super.timedUpdate(sql,arguments);
                dirty(repo,"org");return changed;
            }
        };
        String token=UUID.randomUUID().toString();
        db.update("UPDATE finance_label_worker_lock SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 3 MINUTE) WHERE id=1",token);
        FinanceLabelCosts.dirty(repo,"org");
        long revision=db.queryForObject("SELECT requested_revision FROM finance_label_state WHERE organization_id='org'",Long.class);
        try{advancing.reconcile("org",revision,token);}finally{advancing.stop();}
        String published=db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class);
        assertNotEquals(original,published);
        assertEquals(revision,db.queryForObject("SELECT applied_revision FROM finance_label_state WHERE organization_id='org'",Long.class));
        assertTrue(db.queryForObject("SELECT requested_revision>applied_revision FROM finance_label_state WHERE organization_id='org'",Boolean.class));
        money("2",amount(9));
        var removing=new FinanceLabelCosts(repo,null,directory.toString()){
            @Override int timedUpdate(String sql,Object...arguments){
                int changed=super.timedUpdate(sql,arguments);remove(owner,id);return changed;
            }
        };
        try{removing.reconcile("org",revision+1,token);}finally{removing.stop();}
        assertEquals(published,db.queryForObject("SELECT active_generation FROM finance_label_state WHERE organization_id='org'",String.class));
        money("2",amount(9));
        db.update("UPDATE finance_label_worker_lock SET lease_token=NULL,lease_until=NULL WHERE id=1");
        labels.tick();money("0",amount(9));
    }
    @Test void sourcePagesRemainOrderedAndIsolatedWithFileAndStatusFilters()throws Exception{
        fixture();add("order-0",9);add("order-40",9);
        var first=new ArrayList<List<String>>();var second=new ArrayList<List<String>>();
        for(int i=0;i<61;i++)(i<30?first:second).add(FinanceLabelInvoiceTest.flatLine(
            "order-"+i,i==2?"计费失败":"计算成功",i==2?"0":"2",i==2?"":"1",i==2?"0":"2",i==2?"":"出库费"));
        String older=text(upload(FinanceLabelInvoiceTest.flat(first)).get("id"));process(older);
        String newer=text(upload(FinanceLabelInvoiceTest.flat(second)).get("id"));process(newer);
        db.update("UPDATE finance_label_imports SET created_at='2026-10-01' WHERE id=?",older);
        db.update("UPDATE finance_label_imports SET created_at='2026-10-02' WHERE id=?",newer);
        var all=new Filter(month(9).start(),month(9).end(),"","","","day","");
        var page=labels.workspace(owner,all,Map.of("detailsOnly","true"));
        assertFalse(page.containsKey("period"));assertFalse(page.containsKey("files"));
        assertEquals(61,number(page.get("total")));assertEquals(50,rows(page.get("rows")).size());
        assertEquals("order-30",rows(page.get("rows")).getFirst().get("order_id"));
        assertEquals("order-18",rows(page.get("rows")).getLast().get("order_id"));
        var next=labels.sourcePage(owner,all,Map.of("page","2"));
        assertEquals(11,rows(next.get("rows")).size());assertEquals("order-19",rows(next.get("rows")).getFirst().get("order_id"));
        assertEquals(30,number(labels.sourcePage(owner,all,Map.of("fileId",older)).get("total")));
        assertEquals(61,number(labels.sourcePage(owner,all,Map.of("matchStatus","BILLING_FAILED")).get("total")),"retired filters from old tabs degrade to all records");
        assertEquals(2,number(labels.sourcePage(owner,all,Map.of("matchStatus","MATCHED")).get("total")));
        assertEquals(59,number(labels.sourcePage(owner,all,Map.of("matchStatus","UNMATCHED")).get("total")));
        assertEquals(1,number(labels.sourcePage(owner,all,Map.of("numbers","order-40")).get("total")));
        assertEquals(0,number(labels.sourcePage(other,month(9),Map.of("fileId",newer)).get("total")));
        assertEquals(61,number(labels.sourcePage(owner,all,Map.of()).get("total")));
        service.removeShop(owner,shop);
        assertEquals(59,number(labels.sourcePage(owner,all,Map.of()).get("total")),"removed shop rows are hidden before physical cleanup");
    }
    @Test void concurrentOrderArrivalsDoNotUpgradeSharedLabelStateLocks()throws Exception{
        fixture();add("seed",9);
        process(text(upload(file("2","seed","parallel-a","parallel-b")).get("id")));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var pending=new ArrayList<java.util.concurrent.Future<?>>();
            for(String order:List.of("parallel-a","parallel-b"))pending.add(workers.submit(()->repo.transaction(()->{
                var entry=dated(shop,"ORDER",order,order,"",LocalDate.of(2026,9,2),"100",Map.of());
                db.update(FinanceRepository.UPSERT,repo.entryArgs(owner,entry,entry.revenue()));
                FinanceOrderCosts.enqueue(repo,owner,entry);
                try{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}
                catch(Exception failure){throw new RuntimeException(failure);}
                FinanceLabelCosts.orderArrived(repo,owner,entry);
                return null;
            })));
            for(var future:pending)future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        labels.tick();
        assertEquals(3,db.queryForObject("SELECT matched_count FROM finance_label_imports",Integer.class));
    }
    @Test void orderWriteRetriesOnlyRolledBackOwnedTransactions()throws Exception{
        fixture();
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var retrying=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper()){
            @Override void saveEntry(Scope s,Entry e){
                super.saveEntry(s,e);
                if(attempts.incrementAndGet()==1)throw new org.springframework.dao.CannotAcquireLockException("test deadlock");
            }
        };
        var entry=dated(shop,"ORDER","retry-order","retry-order","",LocalDate.of(2026,9,2),"100",Map.of());
        retrying.save(owner,entry);
        assertEquals(2,attempts.get());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='ORDER'",Integer.class));
        attempts.set(0);
        assertThrows(org.springframework.dao.CannotAcquireLockException.class,()->repo.transaction(()->{
            retrying.save(owner,entry);return null;
        }));
        assertEquals(1,attempts.get(),"a caller-owned transaction must not be retried from inside");
    }
    @Test void lockedInvoicesCannotBlockOrderIngestionAndNotificationsSurviveRollback()throws Exception{
        fixture();process(text(upload(file("2","locked-order")).get("id")));
        var held=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var blocker=workers.submit(()->repo.transaction(()->{
                db.queryForList("SELECT source_row FROM finance_label_invoice_rows FOR UPDATE");
                held.countDown();
                try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException failure){throw new RuntimeException(failure);}
                return null;
            }));
            try{
                assertTrue(held.await(3,java.util.concurrent.TimeUnit.SECONDS));
                workers.submit(()->add("locked-order",0)).get(3,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class));
                assertEquals(1,workers.submit(()->labels.timedUpdate("""
                    INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
                    SELECT organization_id,'nonlocking-test',?,order_id,amount_cny,0,1 FROM finance_label_invoice_rows
                    WHERE organization_id='org'
                    """,shop)).get(3,java.util.concurrent.TimeUnit.SECONDS),
                    "generation reads use committed source values without waiting for source row locks");
            }finally{release.countDown();}
            blocker.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }
        add("locked-order",0);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class),"duplicate arrivals coalesce");
        assertThrows(IllegalStateException.class,()->repo.transaction(()->{
            labels.arrivals();throw new IllegalStateException("interrupted batch");
        }));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class));
        labels.tick();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class));
        assertEquals(shop,db.queryForObject("SELECT matched_shop_id FROM finance_label_invoice_rows",String.class));
        add("locked-order",0);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_label_arrivals",Integer.class),"already matched orders do not rebuild invoices");
    }
}
