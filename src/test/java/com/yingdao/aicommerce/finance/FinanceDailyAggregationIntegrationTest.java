package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceDailyAggregationIntegrationTest extends FinanceConfigurationIntegrationTest {
    FinanceRepository unsplit(){
        return new FinanceRepository(db,new ObjectMapper()){
            @Override List<Map<String,Object>> aggregateWindows(Filter f,Query q,String column,int days,
                    Function<Query,List<Map<String,Object>>> read){return read.apply(q);}
        };
    }
    Map<List<Object>,Map<String,Object>> indexed(List<Map<String,Object>> values){
        var result=new HashMap<List<Object>,Map<String,Object>>();
        for(var row:values)assertNull(result.put(FinanceRepository.bucketKey(row),row),"A daily group must occur only once");
        return result;
    }
    @Test void partitionedLedgerPreservesFiltersNullsExclusionsAndAllDailyGroups(){
        String group=service.saveGroup(owner,null,"partition"),a=groupedShop(owner,"day-a",group),b=connect(other,false);
        for(var scope:List.of(owner,other))for(int day=1;day<=31;day++)for(String kind:KINDS){
            String shop=scope.equals(owner)?a:b,order="order-"+day;
            repo.save(scope,new Entry(shop,"SHOPEE",kind,kind+day,order,"bill"+day,"fee-"+(day%3),"SETTLED",
                LocalDate.of(2026,10,day),day%2==0?"PHP":"THB",day%5==0?null:decimal(day%3==0?"-1.234567":"2.000001"),
                day%4==0?null:decimal("3.123456"),null,0L,decimal("1"),kind.equals("BILL")&&day%2==0?"IMPORT":"API",Map.of()));
        }
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,source_count) VALUES(?,?,?,1)",owner.organizationId(),a,"order-8");
        for(var f:List.of(new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day"),
                new Filter(LocalDate.of(2026,10,3),LocalDate.of(2026,10,27),a,"SHOPEE","PHP","day",group),
                new Filter(LocalDate.of(2026,10,7),LocalDate.of(2026,10,8),a,"","","day"),
                new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day","UNGROUPED")))
            for(boolean live:List.of(false,true))assertEquals(indexed(unsplit().dashboardBuckets(owner,f,live)),indexed(repo.dashboardBuckets(owner,f,live)));
    }
    @Test void internalWeekBoundariesNeverChangeConfirmedMonthInclusion(){
        String shop=connect(owner,false);
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES(?,?,?,?,?,'TEST','fixture','qa')",
            owner.organizationId(),shop,"corrected","bill",java.sql.Date.valueOf("2026-10-01"));
        for(String kind:List.of("SETTLEMENT","FEE"))repo.save(owner,new Entry(shop,"TIKTOK_SHOP",kind,"corrected-"+kind,"corrected","bill","ORDER","SETTLED",
            LocalDate.of(2026,9,20),"PHP",decimal("12.345678"),null,null,null,null,"API",Map.of()));
        var full=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
        var partial=new Filter(full.start(),LocalDate.of(2026,10,20),"","","","day");
        assertEquals(2,repo.dashboardBuckets(owner,full,false).size());
        assertTrue(repo.dashboardBuckets(owner,partial,false).isEmpty());
        assertEquals(indexed(unsplit().dashboardBuckets(owner,full,false)),indexed(repo.dashboardBuckets(owner,full,false)));
    }
    @Test void partitionFailureDoesNotReturnAnIncompleteMonth(){
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
        var q=repo.query(owner,f);var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,()->repo.dailyAggregate(f,q,"record_date",part->{
            if(calls.incrementAndGet()==2)throw new IllegalStateException("query failed");
            return List.of(new HashMap<>(Map.of("records",1)));
        }));
        assertEquals(2,calls.get());
    }
    @Test void backgroundFreshnessPublishesAfterCommitAndNeverAfterRollback()throws Exception{
        var cache=mock(FinanceReadCache.class);var target=new FinanceService(repo,null,null,fx);
        var field=FinanceService.class.getDeclaredField("sharedSnapshots");field.setAccessible(true);field.set(target,cache);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(tx);
        transaction.execute(status->{target.refreshAfterCommit(owner);verifyNoInteractions(cache);status.setRollbackOnly();return null;});
        verifyNoInteractions(cache);
        transaction.execute(status->{target.refreshAfterCommit(owner);verifyNoInteractions(cache);return null;});
        verify(cache).refresh(companyScope(owner.organizationId()));verify(cache,never()).invalidate(any());
        clearInvocations(cache);
        transaction.execute(status->{target.invalidateAfterCommit(owner);verifyNoInteractions(cache);return null;});
        verify(cache).invalidate(companyScope(owner.organizationId()));verify(cache,never()).refresh(any());
    }
    @Test void firstSettlementCostsAreDeduplicatedAcrossPartitionsAndPreservePending(){
        String shop=connect(owner,false);
        for(int day:List.of(1,7,8,14,15,28,31)){
            String order="partition-cost-"+day;
            for(String kind:List.of("ORDER","SETTLEMENT","REFUND"))
                repo.save(owner,new Entry(shop,"SHOPEE",kind,kind+order,order,"bill"+day,"ORDER","SETTLED",
                    LocalDate.of(2026,10,day),"PHP",decimal("12.345678"),null,null,null,null,"API",Map.of()));
            // A second transaction on the first day and another in a later week
            // must never create a second product-cost occurrence.
            for(int at:List.of(day,Math.min(day+8,31)))
                repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","duplicate-"+order+at,order,"other-bill","ORDER","SETTLED",
                    LocalDate.of(2026,10,at),"PHP",decimal("1.000001"),null,null,null,null,"API",Map.of()));
        }
        var full=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
        for(String kind:List.of("ORDER","SETTLEMENT","REFUND")){
            var expected=new FinanceCostReport(unsplit()).summary(owner,full,kind);
            var actual=new FinanceCostReport(repo).summary(owner,full,kind);
            assertEquals(expected,actual,kind);
            assertEquals(7,number(map(actual.get("total")).get("order_count")));
            assertNull(map(actual.get("total")).get("amount_cny"));
        }
    }
    @Test void concurrentWritesCannotMixDifferentSnapshotsWithinOneMonth(){
        String shop=connect(owner,false);
        for(int day:List.of(1,20))repo.save(owner,new Entry(shop,"SHOPEE","AD","ad-"+day,"","","ads","ACTIVE",
            LocalDate.of(2026,10,day),"PHP",decimal("10"),null,null,null,null,"API",Map.of()));
        var full=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");
        var expected=indexed(unsplit().dashboardBuckets(owner,full,false));
        var observed=new FinanceRepository(db,new ObjectMapper()){
            @Override List<Map<String,Object>> dailyAggregate(Filter f,Query q,String column,Function<Query,List<Map<String,Object>>> read){
                var once=new java.util.concurrent.atomic.AtomicBoolean();
                return super.dailyAggregate(f,q,column,part->{
                    var rows=read.apply(part);
                    if(once.compareAndSet(false,true))java.util.concurrent.CompletableFuture.runAsync(()->db.update(
                        "UPDATE finance_records SET amount=999 WHERE organization_id=? AND shop_id=? AND external_id='ad-20'",
                        owner.organizationId(),shop)).orTimeout(5,java.util.concurrent.TimeUnit.SECONDS).join();
                    return rows;
                });
            }
        };
        assertEquals(expected,indexed(observed.dashboardBuckets(owner,full,false)));
        assertNotEquals(expected,indexed(unsplit().dashboardBuckets(owner,full,false)));
    }
    @Test void partitionedAdvertisingKeepsAllLedgerFeesBeforeOperatingExclusions(){
        String shop=connect(owner,false),foreign=connect(other,false);
        for(var scope:List.of(owner,other))for(int day:List.of(1,7,8,15,31)){
            String id=scope.equals(owner)?shop:foreign,order="ad-order-"+day;
            for(var values:List.of(List.of("FEE","达人广告佣金","2"),List.of("AD","独立消耗","3"),
                    List.of("FEE","普通佣金","9"),List.of("AD_GMV","专项","11")))
                repo.save(scope,new Entry(id,"SHOPEE",values.get(0),values.get(1)+day,order,"bill"+day,values.get(1),"SETTLED",
                        LocalDate.of(2026,10,day),"PHP",decimal(values.get(2)),null,null,null,null,"API",Map.of()));
        }
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,source_count) VALUES(?,?,?,1)",owner.organizationId(),shop,"ad-order-8");
        var full=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","PHP","day");
        var expected=new ArrayList<Map<String,Object>>();unsplit().dashboardBuckets(owner,full,false,expected);
        var actual=new ArrayList<Map<String,Object>>();var operating=repo.dashboardBuckets(owner,full,false,actual);
        assertEquals(indexed(expected),indexed(actual));
        var report=FinanceAdvertisingReport.build(actual,List.of(Map.of("id",shop,"group_id","")),full,false,null,"day");
        assertEquals(0,decimal("25").compareTo(decimal(map(report.get("total")).get("amount"))));
        assertEquals(10,number(map(report.get("total")).get("records")));
        assertEquals(0,decimal("10").compareTo(decimal(map(report.get("commission")).get("amount"))));
        assertEquals(0,decimal("55").compareTo(decimal(map(report.get("gmv")).get("amount"))));
        assertEquals(0,decimal("8").compareTo(operating.stream().filter(r->"达人广告佣金".equals(r.get("category")))
                .map(r->decimal(r.get("amount"))).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add)));
    }
}
