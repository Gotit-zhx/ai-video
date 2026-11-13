package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceShopeeAdsApiTest {
    final LocalDate from=LocalDate.of(2026,10,1),to=from.plusDays(6);
    Map<String,Object> envelope(Object... data){return Map.of("response",List.of(data));}
    Map<String,Object> row(String day,Object amount){return Map.of("date",day,"expense",amount,"clicks",2);}
    @Test void usesOfficialDateFormatAndRejectsSameDayAndOversizedWindows(){
        assertEquals(Map.of("start_date","01-10-2026","end_date","07-10-2026"),FinanceShopeeAdsApi.query(from,to));
        assertThrows(RuntimeException.class,()->FinanceShopeeAdsApi.query(from,from));
        assertEquals(Map.of("start_date","01-10-2026","end_date","30-10-2026"),FinanceShopeeAdsApi.query(from,from.plusDays(29)));
        assertThrows(RuntimeException.class,()->FinanceShopeeAdsApi.query(from,from.plusDays(30)));
    }
    @Test void readsArrayResponseAndPreservesRealZeroRefundsAndDecimalPrecision(){
        var rows=FinanceShopeeAdsApi.daily(envelope(row("01-10-2026","12.340001"),row("02-10-2026",0),row("03-10-2026","-1.2")),from,to);
        assertEquals(3,rows.size());assertEquals(new BigDecimal("12.340001"),rows.getFirst().get("amount"));
        assertEquals(0,decimal(rows.get(1).get("amount")).signum());assertEquals(new BigDecimal("-1.2"),rows.getLast().get("amount"));
        assertEquals("2026-10-01",rows.getFirst().get("record_date"));
        assertFalse(rows.getFirst().containsKey("cash_amount"));
    }
    @Test void emptyIsUnknownAndMalformedOrIncompleteWindowsAreRejected(){
        assertTrue(FinanceShopeeAdsApi.daily(envelope(),from,to).isEmpty());
        for(var invalid:List.of(Map.of(),Map.of("response",Map.of()),envelope("invalid"),envelope(Map.of("date","01-10-2026")),envelope(row("01-10-2026","NaN")),envelope(row("01-10-2026","0.0000001")),envelope(row("01-10-2026",Map.of("value",1))),envelope(row("01-11-2026",1)),envelope(row("01-10-2026",1),row("01-10-2026",2)),Map.of("warning","partial data","response",List.of(row("01-10-2026",1)))))
            assertThrows(RuntimeException.class,()->FinanceShopeeAdsApi.daily(map(invalid),from,to),invalid.toString());
    }
    @Test void initialBackfillStartsWithLastCalendarMonthAndRetainsOlderAndCurrentRanges(){
        LocalDate today=LocalDate.of(2026,11,12);
        var plan=FinanceShopeeAdsApi.plan(today,null);
        assertEquals("2026-10-01",plan.get("from"));assertEquals("2026-10-31",plan.get("until"));
        var remaining=rows(plan.get("remaining"));assertEquals(2,remaining.size());
        assertEquals("2026-11-01",remaining.getFirst().get("from"));assertEquals(today.toString(),remaining.getFirst().get("until"));
        assertEquals(today.minusDays(89).toString(),remaining.getLast().get("from"));assertEquals("2026-09-30",remaining.getLast().get("until"));
        assertEquals(LocalDate.of(2026,10,30),FinanceShopeeAdsApi.windowEnd(LocalDate.of(2026,10,1),today));
        assertEquals(today,FinanceShopeeAdsApi.windowEnd(LocalDate.of(2026,11,1),today));
        assertEquals(today.minusDays(6).toString(),FinanceShopeeAdsApi.plan(today,today).get("from"));
    }
    @Test void thirtyOneDayMonthsSplitTheLastDayWithoutLosingExistingCheckpointCoverage(){
        LocalDate last=LocalDate.of(2026,10,31),first=last.withDayOfMonth(1);
        LocalDate end=FinanceShopeeAdsApi.windowEnd(first,last);
        assertEquals(last.minusDays(1),end);
        LocalDate next=end.plusDays(1),lastEnd=FinanceShopeeAdsApi.windowEnd(next,last);
        assertEquals(last,lastEnd);
        assertEquals(Map.of("start_date","30-10-2026","end_date","31-10-2026"),FinanceShopeeAdsApi.query(next.minusDays(1),lastEnd));
        assertEquals(LocalDate.of(2026,2,28),FinanceShopeeAdsApi.windowEnd(LocalDate.of(2026,2,1),LocalDate.of(2026,3,1)));
    }
    @Test void scheduleKeepsNinetyDaysInitiallyAndRefreshesSevenOrThirtyOneDays(){
        LocalDate today=LocalDate.of(2026,11,11);
        assertEquals(today.minusDays(89),FinanceShopeeAdsApi.start(today,null));
        assertEquals(today.minusDays(6),FinanceShopeeAdsApi.start(today,today));
        assertEquals(today.minusDays(30),FinanceShopeeAdsApi.start(today,today.minusDays(1)));
    }
    @Test void closedWindowCannotPassWithMissingFirstLastOrMiddleDay(){
        LocalDate end=from.plusDays(2),today=end.plusDays(1);
        for(String missing:List.of(from.toString(),from.plusDays(1).toString(),end.toString())){
            var facts=new ArrayList<Map<String,Object>>();
            from.datesUntil(end.plusDays(1)).filter(day->!day.toString().equals(missing)).forEach(day->facts.add(Map.of("record_date",day.toString(),"amount",BigDecimal.ZERO)));
            var failure=assertThrows(FinanceShopeeAdsApi.IncompleteReport.class,()->FinanceShopeeAdsApi.requireClosedDays(facts,from,end,today));
            assertTrue(failure.getMessage().contains(missing));
        }
        assertThrows(FinanceShopeeAdsApi.IncompleteReport.class,()->FinanceShopeeAdsApi.requireClosedDays(List.of(),from,end,today));
        assertDoesNotThrow(()->FinanceShopeeAdsApi.requireClosedDays(from.datesUntil(end.plusDays(1)).map(day->Map.<String,Object>of("record_date",day.toString(),"amount",BigDecimal.ZERO)).toList(),from,end,today));
    }
    @Test void unfinishedTodayMayBeAbsentButYesterdayStillMustBeReturned(){
        LocalDate today=from.plusDays(1);
        assertDoesNotThrow(()->FinanceShopeeAdsApi.requireClosedDays(List.of(Map.of("record_date",from.toString())),from,today,today));
        assertThrows(FinanceShopeeAdsApi.IncompleteReport.class,()->FinanceShopeeAdsApi.requireClosedDays(List.of(Map.of("record_date",today.toString())),from,today,today));
    }
}
