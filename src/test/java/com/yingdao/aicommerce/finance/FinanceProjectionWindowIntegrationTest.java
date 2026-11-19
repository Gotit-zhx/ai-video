package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceProjectionWindowIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void projectionDayRetainsCompleteMonthCorrectionButPartialMonthDoesNot(){
        String shop=seedMonth();var month=month(shop);
        assertEquals(0,new BigDecimal("60").compareTo(total(month)));
        assertEquals(0,new BigDecimal("30").compareTo(repo.inProjectionWindow(month,month.start(),()->total(month))));
        var partial=new Filter(month.start(),month.start(),shop,"","PHP","day");
        assertEquals(0,new BigDecimal("10").compareTo(total(partial)),"Partial calendar month must exclude month-only corrections");
    }

    @Test void adjacentDirtyDaysKeepTheOriginalMonthScopeAndVisitOnlyAffectedWindows(){
        var month=month(seedMonth());
        var days=new java.util.ArrayList<FinanceRepository.Query>();
        var end=month.start().plusDays(1);
        var amount=repo.inProjectionWindow(month,month.start(),end,()->{
            repo.aggregateWindows(month,repo.query(owner,month),"record_date",1,query->{
                days.add(query);return java.util.List.of();
            });
            return total(month);
        });
        assertEquals(0,new BigDecimal("60").compareTo(amount));
        assertEquals(2,days.size());
        assertTrue(days.getFirst().args().contains(java.sql.Date.valueOf(month.end())),
            "Physical windows cannot change month correction coverage");
    }

    @Test void batchedWindowsKeepTheirShopAndDatePairingAndSkipUnchangedDays(){
        String shop=seedMonth();
        String group=service.saveGroup(owner,null,"Projection window");
        String otherShop=groupedShop(owner,"10002",group);
        db.update("UPDATE finance_records SET shop_id=?,record_date='2026-09-05' WHERE id='second'",otherShop);
        var month=month("");
        var first=month.start();
        var last=first.plusDays(4);
        var slices=List.of(new FinanceSettlementCostDays.Window(first,first,List.of(shop)),
                new FinanceSettlementCostDays.Window(last,last,List.of(otherShop)));

        var queries=new java.util.ArrayList<FinanceRepository.Query>();
        BigDecimal amount=repo.inProjectionWindows(month,slices,()->{
            repo.aggregateWindows(month,repo.query(owner,month),"record_date",1,query->{
                queries.add(query);return List.of();
            });
            return total(month);
        });
        assertEquals(2,queries.size());
        assertEquals(0,new BigDecimal("60").compareTo(amount));
        var swapped=List.of(new FinanceSettlementCostDays.Window(first,first,List.of(otherShop)),
                new FinanceSettlementCostDays.Window(last,last,List.of(shop)));
        assertEquals(0,repo.inProjectionWindows(month,swapped,()->total(month)).signum());
    }

    @Test void nestedWindowsAndFailuresRestoreThePreviousReadBoundary(){
        String shop=seedMonth();var month=month(shop);
        repo.inProjectionWindow(month,month.start(),()->{
            assertEquals(0,new BigDecimal("30").compareTo(total(month)));
            repo.inProjectionWindow(month,month.start().plusDays(1),()->{
                assertEquals(0,new BigDecimal("30").compareTo(total(month)));return null;
            });
            assertEquals(0,new BigDecimal("30").compareTo(total(month)));return null;
        });
        assertThrows(IllegalStateException.class,()->repo.inProjectionWindow(month,month.start(),()->{throw new IllegalStateException("fixture");}));
        assertEquals(0,new BigDecimal("60").compareTo(total(month)),"Worker read window must not leak into a later HTTP query");
    }

    private Filter month(String shop){return new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),shop,"","PHP","day");}
    private BigDecimal total(Filter f){return repo.dashboardBuckets(owner,f).stream().filter(r->"FEE".equals(r.get("kind")))
            .map(r->decimal(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private String seedMonth(){
        String shop=connect(owner,false);
        for(var row:java.util.List.of(new Object[]{"ordinary","one",LocalDate.of(2026,9,1),10,"{}"},
                new Object[]{"corrected","corrected-order",LocalDate.of(2026,9,1),20,"{\"_finance_reporting_month\":\"2026-09\",\"_finance_original_settlement_date\":\"2026-08-15\"}"},
                new Object[]{"second","two",LocalDate.of(2026,9,2),30,"{}"})){
            db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,source,raw_json) VALUES(?,?,?,?, 'TIKTOK_SHOP','FEE',?,?, 'statement','fixture','SETTLED',?,'PHP',?,'API',?)",
                    row[0],owner.organizationId(),owner.userId(),shop,row[0],row[1],row[2],row[3],row[4]);
        }
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES(?,?,'corrected-order','statement','2026-09-01','manual','fixture','u')",owner.organizationId(),shop);
        return shop;
    }
}
