package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same guarded disposable schema as the existing finance regression fixtures. */
class FinanceCostExportFilesIntegrationTest extends FinanceConfigurationIntegrationTest {
    @TempDir Path directory;
    FinanceCostExport proxied(FinanceCostExport target){
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
        var advice=new TransactionInterceptor();advice.setTransactionManager(tx);advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(advice);return (FinanceCostExport)factory.getProxy();
    }
    @Test void stableSnapshotPaginatesNullableKeysWithoutLosingOrdersDuringConcurrentRecalculation()throws Exception{
        String group=service.saveGroup(owner,null,"分片快照"),shop=groupedShop(owner,"batch",group);
        String otherShop=groupedShop(other,"batch-other",service.saveGroup(other,null,"另一公司"));
        var expected=new TreeSet<String>();
        repo.transaction(()->{
            for(int i=0;i<505;i++){
                String order=String.format(Locale.ROOT,"000000000000%06d",i);expected.add(order);
                repo.saveEntry(owner,dated(shop,"SETTLEMENT","s-"+i,order,"bill",filter.start(),"80",Map.of()));
                if(i==0)repo.saveEntry(owner,dated(shop,"SETTLEMENT","s-duplicate",order,"bill2",filter.start(),"1",Map.of()));
            }
            for(String order:List.of("present-a","present-b")){
                expected.add(order);repo.saveEntry(owner,dated(shop,"ORDER",order,order,"",filter.start(),"100",Map.of()));
                repo.saveEntry(owner,dated(shop,"SETTLEMENT","s-"+order,order,"bill",filter.start(),"80",Map.of()));
            }
            repo.saveEntry(other,dated(otherShop,"SETTLEMENT","foreign","foreign","bill",filter.start(),"80",Map.of()));
            repo.saveEntry(owner,dated(shop,"SETTLEMENT","outside","outside","bill",filter.start().minusDays(1),"80",Map.of()));
            return null;
        });
        db.update("DELETE FROM finance_order_cost_jobs");
        String updatedId=db.queryForObject("SELECT id FROM finance_records WHERE kind='ORDER' AND order_id='present-b'",String.class);
        var totals=new ArrayList<Long>();
        var renderer=proxied(new FinanceCostExport(repo,new FinanceCostExport.Limits(250,300,1000,8*1024*1024)));
        var result=renderer.exportFile(owner,filter,"SETTLED_ORDER","","PENDING","",directory,(processed,total)->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            totals.add(total);
            if(processed==500){
                try(var executor=Executors.newSingleThreadExecutor()){
                    executor.submit(()->{
                        db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json) VALUES(?,?,?,?,?,'READY',99,99,?,?)",
                            updatedId,owner.organizationId(),shop,"present-b",filter.start(),"a".repeat(64),"{\"lines\":[]}");
                        repo.save(owner,dated(shop,"SETTLEMENT","new-after-snapshot","new-after-snapshot","bill",filter.start(),"80",Map.of()));
                    }).get(15,TimeUnit.SECONDS);
                }catch(Exception e){throw new AssertionError(e);}
            }
        });
        assertEquals(507,result.orders());assertEquals(507,result.lines());assertEquals(3,result.parts());
        assertEquals(Set.of(507L),new HashSet<>(totals));
        var actual=new ArrayList<String>();int references=0;
        try(var zip=new ZipInputStream(Files.newInputStream(result.path()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            if(!entry.getName().endsWith(".xlsx"))continue;
            byte[] bytes=zip.readAllBytes();var orders=FinanceCostExportTest.sheet(bytes,2);
            for(int row=2;row<=orders.getElementsByTagName("row").getLength();row++){
                actual.add(FinanceCostExportTest.value(orders,"C"+row));assertEquals("",FinanceCostExportTest.value(orders,"N"+row));
            }
            try{FinanceCostExportTest.sheet(bytes,4);references++;}catch(AssertionError missing){}
        }}
        assertEquals(expected,new TreeSet<>(actual));assertEquals(507,actual.size());assertEquals(1,references);
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_order_costs WHERE order_record_id=?",String.class,updatedId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE order_id='new-after-snapshot'",Integer.class));
    }
}
