package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSyncPlanTest {
    final ZoneId zone=ZoneId.of("Asia/Manila");
    long time(String day){return LocalDate.parse(day).atStartOfDay(zone).toEpochSecond();}
    final Instant now=Instant.parse("2026-11-03T07:00:00Z");
    Map<String,Object> range(String start,String end){
        long from=time(start),until=time(end);
        return new LinkedHashMap<>(Map.of("from",from,"to",Math.min(from+604800,until),"until",until,
                "coverage",start,"run","original-run"));
    }
    List<Map<String,Object>> finish(Map<String,Object> plan){
        var windows=new ArrayList<Map<String,Object>>();var cp=plan;
        for(int i=0;i<100;i++){
            windows.add(cp);
            var next=FinanceSync.nextWindow(cp,new FinanceMarketplace.Step(cp,true));
            if(next.done())return windows;
            cp=next.checkpoint();
        }
        fail("scan did not finish");return windows;
    }
    @Test void fullPreviousCalendarMonthPrecedesHistoryAndEveryOriginalSecondIsCoveredOnce(){
        for(String channel:List.of("ORDER","SETTLEMENT")){
            var old=range("2026-08-06","2026-11-03");var original=new LinkedHashMap<>(old);
            var plan=FinanceSyncPlan.previousMonthFirst(old,channel,zone,now);
            assertEquals(time("2026-10-01"),number(plan.get("from")));
            assertEquals(time("2026-11-01"),number(plan.get("until")));
            assertEquals(original,old);
            var windows=finish(plan);
            assertEquals(time("2026-10-29"),number(windows.get(4).get("from")));
            assertEquals(time("2026-11-01"),number(windows.get(4).get("to")));
            assertEquals(time("2026-08-06"),number(windows.get(5).get("from")));
            long seconds=0,previous=time("2026-08-06");
            for(var w:windows.stream().sorted(Comparator.comparingLong(v->number(v.get("from")))).toList()){
                assertEquals(previous,number(w.get("from")),"no gaps or duplicate full-month scan");
                assertTrue(number(w.get("to"))-number(w.get("from"))<=604800);
                previous=number(w.get("to"));seconds+=previous-number(w.get("from"));
                assertSame(w,FinanceSyncPlan.previousMonthFirst(w,channel,zone,now));
            }
            assertEquals(time("2026-11-03"),previous);
            assertEquals(time("2026-11-03")-time("2026-08-06"),seconds);
        }
    }
    @Test void pageAndBillResumeWithExactWindowRunAndCountersAfterPriority(){
        var old=range("2026-09-28","2026-11-03");
        old.put("cursor","page-7");old.put("transactionCursor","bill-page-2");
        old.put("transactionCount",43);old.put("bills",List.of(Map.of("id","b1")));old.put("page",5);
        var plan=FinanceSyncPlan.previousMonthFirst(old,"SETTLEMENT",zone,now);
        var windows=finish(plan);var resumed=windows.get(5);
        for(String key:old.keySet())assertEquals(old.get(key),resumed.get(key),key);
        assertEquals(time("2026-11-01"),number(windows.get(6).get("from")),
                "after finishing the saved overlapping window, do not refetch the rest of October");
    }
    @Test void legacyRecentFirstPlanRetainsBothNestedCursorsAndCannotBecomeAReplanningLoop(){
        var historical=range("2026-08-06","2026-10-05");historical.put("cursor","old-page");
        historical.put("recentWindowDays",30);
        var recent=range("2026-10-28","2026-11-03");recent.put("transactionCursor","bill-page");
        recent.put("backfillResume",historical);recent.put("recentWindowDays",30);
        var plan=FinanceSyncPlan.previousMonthFirst(recent,"SETTLEMENT",zone,now);
        var windows=finish(plan);
        assertEquals("bill-page",windows.get(5).get("transactionCursor"));
        assertEquals("old-page",windows.get(6).get("cursor"));
        for(var w:windows)assertSame(w,FinanceSyncPlan.previousMonthFirst(w,"SETTLEMENT",zone,now));
        var finalWindow=windows.getLast();
        assertEquals(time("2026-10-01"),number(finalWindow.get("to")));
    }
    @Test void retryKeepsPriorityPagesAndSerializedPlan(){
        var plan=FinanceSyncPlan.previousMonthFirst(range("2026-08-06","2026-11-03"),"ORDER",zone,now);
        var page=new LinkedHashMap<>(plan);page.put("cursor","next-page");
        var pending=new FinanceMarketplace.Step(page,false);
        assertSame(pending,FinanceSync.nextWindow(plan,pending));
        var repo=new FinanceRepository(null,new tools.jackson.databind.ObjectMapper());
        var restored=repo.read(repo.write(page));
        assertSame(restored,FinanceSyncPlan.previousMonthFirst(restored,"ORDER",zone,now));
        assertEquals(14,finish(restored).size());
    }
    @Test void calendarUsesShopTimezoneAcrossYearAndLeapMonthBoundaries(){
        for(var sample:List.of(
                List.of("2027-01-01T00:30:00Z","Asia/Manila","2026-12-01","2027-01-01"),
                List.of("2027-01-01T00:30:00Z","America/Los_Angeles","2026-11-01","2026-12-01"),
                List.of("2028-03-01T00:00:00Z","Asia/Manila","2028-02-01","2028-03-01"))){
            var z=ZoneId.of(sample.get(1));var clock=Instant.parse(sample.get(0));
            var plan=FinanceSyncPlan.previousMonthFirst(range("2026-08-06","2026-11-03"),"SETTLEMENT",z,clock);
            assertEquals(LocalDate.parse(sample.get(2)).atStartOfDay(z).toEpochSecond(),number(plan.get("from")));
            assertEquals(LocalDate.parse(sample.get(3)).atStartOfDay(z).toEpochSecond(),number(plan.get("until")));
        }
    }
    @Test void currentIncrementalRepairRefundPendingAndInvalidLegacyRangesStayUnchanged(){
        var current=range("2026-11-02","2026-11-03");
        assertSame(current,FinanceSyncPlan.previousMonthFirst(current,"ORDER",zone,now));
        var old=range("2026-08-06","2026-11-03");
        for(String channel:List.of("ORDER_LIVE","ORDER_REPAIR","REFUND","PENDING"))
            assertSame(old,FinanceSyncPlan.previousMonthFirst(old,channel,zone,now));
        for(var invalid:List.<Map<String,Object>>of(Map.of(),Map.of("from",100,"to",200,"until",300),
                Map.of("from",time("2026-10-01"),"to",time("2026-11-03"),"until",time("2026-11-01"))))
            assertSame(invalid,FinanceSyncPlan.previousMonthFirst(invalid,"ORDER",zone,now));
    }
    @Test void nightlyAndManualRefreshIncludeMonthStartEvenLateInTheMonth(){
        LocalDate today=LocalDate.of(2026,11,30);
        for(String channel:List.of("ORDER","SETTLEMENT")){
            assertEquals(LocalDate.of(2026,10,1),FinanceSyncPlan.start(today,false,true,false,channel));
            assertEquals(LocalDate.of(2026,10,1),FinanceSyncPlan.start(today,false,false,true,channel));
            assertEquals(today.minusDays(89),FinanceSyncPlan.start(today,true,false,false,channel));
            assertEquals(today.minusDays(1),FinanceSyncPlan.start(today,false,false,false,channel));
        }
        assertEquals(today.minusDays(30),FinanceSyncPlan.start(today,false,true,false,"REFUND"));
    }
    @Test void alreadyHistoricalOnlyCheckpointFirstFetchesCurrentPreviousMonthWithoutResettingHistory(){
        var old=range("2026-08-06","2026-08-20");old.put("cursor","keep");
        var windows=finish(FinanceSyncPlan.previousMonthFirst(old,"ORDER",zone,now));
        assertEquals(time("2026-10-01"),number(windows.getFirst().get("from")));
        assertEquals("keep",windows.get(5).get("cursor"));
        assertEquals(time("2026-08-20"),number(windows.getLast().get("to")));
    }
    @Test void uncertifiedIncrementalLaneMustFetchFullMonthButCertifiedLaneKeepsItsIncrement(){
        var current=range("2026-11-02","2026-11-03");current.put("cursor","current-page");
        var plan=FinanceSyncPlan.previousMonthFirst(current,"ORDER",zone,now,true);
        assertEquals(time("2026-10-01"),number(plan.get("from")));
        var windows=finish(plan);
        for(int i=0;i<5;i++){
            var cp=windows.get(i);
            assertNull(FinanceSyncPlan.completedMonth(cp,new FinanceMarketplace.Step(cp,false),zone));
            assertEquals(i==4?LocalDate.of(2026,10,1):null,
                FinanceSyncPlan.completedMonth(cp,new FinanceMarketplace.Step(cp,true),zone));
        }
        var resumed=windows.get(5);
        assertEquals("current-page",resumed.get("cursor"));
        assertNull(FinanceSyncPlan.completedMonth(resumed,new FinanceMarketplace.Step(resumed,true),zone));
        assertSame(current,FinanceSyncPlan.previousMonthFirst(current,"ORDER",zone,now,false));
    }
    @Test void missingPassCannotCertifyMonthAndResumesTheExactFullRefreshCursor(){
        var original=FinanceSyncPlan.previousMonthFirst(range("2026-08-06","2026-11-03"),"SETTLEMENT",zone,now,true);
        original.put("cursor","keep-month-page-19");
        var plan=FinanceSyncPlan.missingSettlementsFirst(original,"SHOPEE","SETTLEMENT",zone,now,true);
        var windows=finish(plan);
        for(int i=0;i<5;i++){
            var cp=windows.get(i);
            assertTrue(Boolean.TRUE.equals(cp.get("missingSettlementPass")));
            assertNull(FinanceSyncPlan.completedMonth(cp,new FinanceMarketplace.Step(cp,true),zone));
            assertSame(cp,FinanceSyncPlan.missingSettlementsFirst(cp,"SHOPEE","SETTLEMENT",zone,now,true));
        }
        var restored=windows.get(5);
        for(String key:original.keySet())assertEquals(original.get(key),restored.get(key),key);
        assertSame(restored,FinanceSyncPlan.missingSettlementsFirst(restored,"SHOPEE","SETTLEMENT",zone,now,true));
        assertFalse(restored.containsKey("missingSettlementPass"));
        assertEquals(LocalDate.of(2026,10,1),
            FinanceSyncPlan.completedMonth(windows.get(9),new FinanceMarketplace.Step(windows.get(9),true),zone));
        assertSame(original,FinanceSyncPlan.missingSettlementsFirst(original,"TIKTOK_SHOP","SETTLEMENT",zone,now,true));
        assertSame(original,FinanceSyncPlan.missingSettlementsFirst(original,"SHOPEE","ORDER",zone,now,true));
        assertSame(original,FinanceSyncPlan.missingSettlementsFirst(original,"SHOPEE","SETTLEMENT",zone,now,false));
    }
}
