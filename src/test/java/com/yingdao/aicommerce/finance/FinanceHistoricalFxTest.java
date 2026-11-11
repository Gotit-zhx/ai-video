package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceHistoricalFxTest {
    @Test void dailyAmountsAreConvertedBeforeTheirDatesAreCollapsed() {
        var friday=LocalDate.of(2026,10,9);var monday=friday.plusDays(3);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(
            friday,new FinanceFx.Day(friday,Map.of("USD",decimal("7"),"CNY",BigDecimal.ONE)),
            monday,new FinanceFx.Day(monday,Map.of("USD",decimal("8"),"CNY",BigDecimal.ONE))));
        var rows=List.of(new LinkedHashMap<String,Object>(Map.of("record_date",friday.toString(),"kind","AD","currency","USD","amount",decimal("10"))),
            new LinkedHashMap<String,Object>(Map.of("record_date",monday.toString(),"kind","AD","currency","USD","amount",decimal("10"))));
        var result=FinanceFx.groups(new ArrayList<>(rows),quote,"kind");
        assertEquals(decimal("150.000000"),result.getFirst().get("amount"));
        assertEquals(decimal("10.000000"),quote.fromCny(decimal("80"),"USD",monday));
    }
    @Test void weekendUsesAnEarlierPublicationAndNeverAFutureDay() {
        var sunday=LocalDate.of(2026,10,11);var friday=sunday.minusDays(2);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(sunday,new FinanceFx.Day(friday,Map.of("USD",decimal("7")))));
        assertEquals(decimal("70.000000"),quote.convert("10","USD",sunday));
        assertEquals("2026-10-09",quote.metadata(sunday).get("rateDate"));
        assertNull(quote.convert("10","USD",sunday.minusDays(10)));
        assertNull(quote.convert(null,"USD",sunday));
        assertEquals(decimal("10.000000"),quote.convert("10","CNY",sunday));
        assertThrows(IllegalArgumentException.class,()->new FinanceFx.Quote("bad",Instant.EPOCH,Map.of(),Map.of(sunday,new FinanceFx.Day(sunday.plusDays(1),Map.of("USD",BigDecimal.ONE)))));
    }
    @Test void originalOrderDateIsIndependentOfFirstSettlementReportingDate() {
        var row=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","record_date","2026-10-10","source_record_date","2026-09-20"));
        assertEquals(LocalDate.of(2026,9,20),FinanceFx.businessDate(row));
        row.put("kind","SETTLEMENT");assertEquals(LocalDate.of(2026,10,10),FinanceFx.businessDate(row));
    }
    @Test void missingRateKeepsKnownConvertedPartButNeverClaimsACompleteTotal(){
        var first=LocalDate.of(2026,10,9);var second=first.plusDays(3);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(first,new FinanceFx.Day(first,Map.of("USD",decimal("7")))));
        var data=new ArrayList<Map<String,Object>>();
        for(var date:List.of(first,second))data.add(new LinkedHashMap<>(Map.of("record_date",date.toString(),"kind","AD","currency","USD","amount",decimal("10"),"records",1,"known_amounts",1)));
        var result=FinanceFx.groups(data,quote,"kind").getFirst();
        assertNull(result.get("amount"));assertEquals(decimal("70.000000"),result.get("known_amount"));
        assertEquals(decimal("1"),result.get("known_amounts"));assertEquals(List.of("2026-10-12"),quote.metadata().get("missingDates"));
    }
    @Test void costProjectionUsesFirstSettlementDayRatherThanOneScalarRate(){
        var first=LocalDate.of(2026,10,9);var second=first.plusDays(3);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(
            first,new FinanceFx.Day(first,Map.of("USD",decimal("7"))),second,new FinanceFx.Day(second,Map.of("USD",decimal("8")))));
        var cost=new LinkedHashMap<String,Object>(Map.of("amount_cny",decimal("300"),"known_amount_cny",decimal("300"),"label_cost_cny",decimal("30"),"pending_count",0));
        var summary=new LinkedHashMap<String,Object>(Map.of("total",cost,"shops",new ArrayList<>()));
        var daily=List.<Map<String,Object>>of(Map.of("shop_id","shop","record_date",first.toString(),"fx_date","2026-01-01","known_amount_cny",decimal("140"),"label_cost_cny",decimal("14")),
            Map.of("shop_id","shop","record_date",second.toString(),"fx_date","2026-01-01","known_amount_cny",decimal("160"),"label_cost_cny",decimal("16")));
        FinanceCostReport.displayAmounts(summary,daily,"USD",quote);
        assertEquals(decimal("40.000000"),cost.get("display_amount"));assertEquals(decimal("4.000000"),cost.get("label_display_amount"));
        var totals=List.<Map<String,Object>>of(Map.of("kind","SETTLEMENT","records",2,"known_amounts",2,"known_revenues",2,"amount",decimal("100"),"revenue",decimal("120")),
            Map.of("kind","AD","amount",decimal("5")),Map.of("kind","COST","amount",BigDecimal.ZERO));
        var profit=FinanceProfitReport.calculate(totals,summary,"USD",quote);
        assertEquals(decimal("51.000000"),profit.get("amount"));assertNull(profit.get("costCnyRate"));
    }
    @Test void reconciliationUsesUnderlyingTransactionDaysWithoutChangingNativeResult(){
        var first=LocalDate.of(2026,10,9);var second=first.plusDays(3);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(
            first,new FinanceFx.Day(first,Map.of("USD",decimal("7"))),second,new FinanceFx.Day(second,Map.of("USD",decimal("8")))));
        var bill=new LinkedHashMap<String,Object>(Map.of("kind","BILL","record_date",second.toString(),"currency","USD","amount",decimal("100"),
            "settled_net_amount",decimal("90"),"difference",BigDecimal.ZERO,"detail_amount",decimal("90"),"result","MATCHED",
            "_fx_detail_daily",List.of(Map.of("fx_date",first.toString(),"currency","USD","amount",decimal("40"),"records",1,"known_amounts",1),
                Map.of("fx_date",second.toString(),"currency","USD","amount",decimal("50"),"records",1,"known_amounts",1))));
        new FinanceFx(mock(FinanceRepository.class),new tools.jackson.databind.ObjectMapper()).enrich(List.of(bill),quote);
        assertEquals(decimal("680.000000"),bill.get("cny_detail_amount"));assertEquals(decimal("40.000000"),bill.get("cny_difference"));
        assertEquals("MATCHED",bill.get("result"));assertEquals(BigDecimal.ZERO,bill.get("difference"));
    }
    @Test void ecbEuroCrossRatesRejectFutureAndIncompleteProviderRows(){
        String raw=providerRows("2026-10-09","7");
        var rates=FinanceFxHistory.parse(raw,LocalDate.of(2026,10,9)).firstEntry().getValue().rates();
        assertEquals(0,decimal("7").compareTo(rates.get("USD")));assertEquals(0,decimal("0.875").compareTo(rates.get("PHP")));
        assertThrows(RuntimeException.class,()->FinanceFxHistory.parse(raw,LocalDate.of(2026,10,8)));
        assertThrows(RuntimeException.class,()->FinanceFxHistory.parse(raw.replace(",{\"date\":\"2026-10-09\",\"base\":\"EUR\",\"quote\":\"MYR\",\"rate\":0.5}",""),LocalDate.of(2026,10,9)));
    }
    @Test void addingCoverageCannotReplaceAFrozenBusinessDay(){
        var day=LocalDate.of(2026,10,9);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of(day,new FinanceFx.Day(day,Map.of("USD",decimal("7")))));
        assertThrows(IllegalStateException.class,()->quote.addDays(Map.of(day,new FinanceFx.Day(day,Map.of("USD",decimal("9"))))));
        assertEquals(decimal("70.000000"),quote.convert("10","USD",day));
    }
    @Test void unavailableCurrentMarketNeverInventsRatesOrZeroes(){
        var service=new FinanceFxHistory(mock(FinanceRepository.class),new tools.jackson.databind.ObjectMapper(),uri->{throw new IllegalStateException("provider unavailable");});
        var market=service.market();assertEquals("UNAVAILABLE",market.get("status"));assertEquals(Map.of(),market.get("rates"));
        assertNull(market.get("rateDate"));assertNotNull(market.get("error"));
    }
    @Test void bulkPreloadDoesNotFetchAgainForEachConvertedAmount(){
        var first=LocalDate.of(2026,10,9);var second=first.plusDays(3);
        var quote=new FinanceFx.Quote("history",Instant.EPOCH,Map.of(),Map.of());int[] batches={0};
        quote.loader(dates->{batches[0]++;quote.addDays(Map.of(first,new FinanceFx.Day(first,Map.of("USD",decimal("7"))),
            second,new FinanceFx.Day(second,Map.of("USD",decimal("8")))));});
        quote.preload(List.of(first,second,first));BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<100;i++)total=total.add(quote.convert("10","USD",i%2==0?first:second));
        quote.preload(List.of(first,second));
        assertEquals(decimal("7500.000000"),total);assertEquals(1,batches[0]);
    }
    @Test void unverifiedMonthlyAnchorCannotUseEitherDayOneOrUnverifiedOriginalDate(){
        var row=new LinkedHashMap<String,Object>(Map.of("kind","SETTLEMENT","record_date","2026-10-01","source","API"));
        FinanceFields.enrich(row,Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-20"));
        assertNull(FinanceFx.businessDate(row));assertEquals("2026-10-01",row.get("record_date"));
        var quote=new FinanceFx.Quote("h",Instant.EPOCH,Map.of(),Map.of());
        assertTrue(text(quote.metadata(FinanceFx.businessDate(row)).get("error")).contains("真实业务日期尚未核实"));
        FinanceFields.enrich(row,Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-20",
            "_finance_date_version",4,"_finance_export_statement_date","2026-09-22"));
        assertEquals(LocalDate.of(2026,9,22),FinanceFx.businessDate(row));
    }
    @Test void nullProjectedZeroCostCannotLeaveProfitReadyOrThrow(){
        var cost=new LinkedHashMap<String,Object>(Map.of("currency","USD","amount_cny",BigDecimal.ZERO,"known_amount_cny",BigDecimal.ZERO,
            "known_display_amount",BigDecimal.ZERO,"label_cost_cny",BigDecimal.ZERO,"label_display_amount",BigDecimal.ZERO));
        cost.put("display_amount",null);
        var totals=List.<Map<String,Object>>of(Map.of("kind","SETTLEMENT","amount",decimal("10"),"revenue",decimal("10"),"records",1,"known_amounts",1,"known_revenues",1));
        var result=assertDoesNotThrow(()->FinanceProfitReport.calculate(totals,Map.of("total",cost),"USD",new FinanceFx.Quote("h",Instant.EPOCH,Map.of(),Map.of())));
        assertNull(result.get("amount"));assertEquals("MISSING_FX",result.get("status"));
    }
    @Test void nativeOverviewReturnsTheSameHistoricalSnapshotUsedForItsCnyCosts()throws Exception{
        var day=LocalDate.of(2026,10,1);var scope=new Scope("org","u");
        var filter=new Filter(day,day,"","","USD","day");
        var quote=new FinanceFx.Quote("snapshot",Instant.EPOCH,Map.of(),Map.of(day,new FinanceFx.Day(day,Map.of("USD",decimal("7"))),
            day.plusDays(1),new FinanceFx.Day(day.plusDays(1),Map.of("USD",decimal("8")))));
        var fx=mock(FinanceFx.class);when(fx.historical(eq("snapshot"),any(LocalDate.class),any(LocalDate.class))).thenReturn(quote);
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);var repo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
        var service=new FinanceService(repo,null,null,fx);var cache=mock(FinanceReadCache.class);
        var field=FinanceService.class.getDeclaredField("sharedSnapshots");field.setAccessible(true);field.set(service,cache);
        var ledger=new LinkedHashMap<String,Object>(Map.of("shop_id","s","record_date",day.toString(),"kind","SETTLEMENT","currency","USD",
            "amount",decimal("100"),"revenue",decimal("100"),"records",1,"known_amounts",1,"known_revenues",1));
        var cost=new LinkedHashMap<String,Object>(Map.of("shop_id","s","record_date",day.toString(),"settlement_currency","USD",
            "amount_cny",decimal("70"),"known_amount_cny",decimal("70"),"pending_count",0,"label_cost_cny",BigDecimal.ZERO));
        when(cache.facts(any(),any())).thenReturn(Map.of("buckets",List.of(ledger),"orders",List.of(cost),"advertising",List.of(),"reportingMonths",List.of()));
        var overview=service.buildDashboard(scope,filter,false,"snapshot",false);
        assertEquals("snapshot",map(overview.get("fx")).get("id"));
        assertEquals(map(map(overview.get("productCosts")).get("fx")).get("id"),map(overview.get("fx")).get("id"));
        assertEquals(decimal("10.000000"),map(overview.get("profit")).get("productCost"));
        cost.put("amount_cny",BigDecimal.ZERO);cost.put("known_amount_cny",BigDecimal.ZERO);cost.put("label_cost_cny",decimal("70"));
        var credit=new LinkedHashMap<>(cost);credit.put("record_date",day.plusDays(1).toString());credit.put("label_cost_cny",decimal("-70"));
        when(cache.facts(any(),any())).thenReturn(Map.of("buckets",List.of(ledger),"orders",List.of(cost,credit),"advertising",List.of(),"reportingMonths",List.of()));
        var offset=service.buildDashboard(scope,new Filter(day,day.plusDays(1),"","","USD","day"),false,"snapshot",false);
        assertEquals("snapshot",map(offset.get("fx")).get("id"));
        assertEquals(decimal("1.250000"),map(offset.get("profit")).get("logisticsLabelCost"),"Zero native-CNY net still needs daily conversion of its nonzero entries");
        assertEquals(decimal("98.750000"),map(offset.get("profit")).get("amount"));
    }
    @Test void actualZeroStaysKnownWithoutARateWhileAbsentMoneyStaysUnknown(){
        var day=LocalDate.of(2026,10,9);var quote=new FinanceFx.Quote("h",Instant.EPOCH,Map.of(),Map.of());
        assertEquals(decimal("0.000000"),quote.convert("0","USD",day));assertEquals(decimal("0.000000"),quote.fromCny("0","USD",day));
        assertNull(quote.convert(null,"USD",day));assertNull(quote.fromCny(null,"USD",day));
        var row=new LinkedHashMap<String,Object>(Map.of("kind","AD","record_date",day.toString(),"currency","USD","amount",BigDecimal.ZERO,"records",1,"known_amounts",1));
        var total=FinanceFx.groups(List.of(row),quote,"kind").getFirst();
        assertEquals(decimal("0.000000"),total.get("amount"));assertEquals(decimal("1"),total.get("known_amounts"));
        new FinanceFx(mock(FinanceRepository.class),new tools.jackson.databind.ObjectMapper()).enrich(List.of(row),quote);
        assertEquals(decimal("0.000000"),row.get("cny_amount"));assertEquals("MISSING",map(row.get("fx")).get("status"));
    }
    static String providerRows(String date,String cny){
        return "[{\"date\":\""+date+"\",\"base\":\"EUR\",\"quote\":\"CNY\",\"rate\":"+cny+"},"
            +"{\"date\":\""+date+"\",\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1},"
            +"{\"date\":\""+date+"\",\"base\":\"EUR\",\"quote\":\"PHP\",\"rate\":8},"
            +"{\"date\":\""+date+"\",\"base\":\"EUR\",\"quote\":\"THB\",\"rate\":5},"
            +"{\"date\":\""+date+"\",\"base\":\"EUR\",\"quote\":\"MYR\",\"rate\":0.5}]";
    }
}
