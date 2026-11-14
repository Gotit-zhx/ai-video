package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
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
