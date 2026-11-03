package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceSyncMonthTest {
    final Instant now=Instant.parse("2026-11-03T03:00:00Z");
    Map<String,Object> state(){
        return new LinkedHashMap<>(Map.of("shop_id","a","channel","SETTLEMENT","timezone","Asia/Manila",
            "status","SUCCESS","coverage_start","2026-08-01","last_success_at",Instant.parse("2026-11-02T15:35:11Z")));
    }
    Map<String,Object> describe(Map<String,Object> row,List<Map<String,Object>> bills,Instant at){
        return FinanceSyncMonth.describe(List.of(row),bills,at).getFirst();
    }
    boolean complete(Map<String,Object> row){return Boolean.TRUE.equals(describe(row,List.of(),now).get("previous_month_complete"));}
    @Test void priorSuccessMustNotHideAnUnfinishedOctoberSettlementRange(){
        var row=state();row.put("status","SYNCING");
        row.put("window_start",Instant.parse("2026-10-17T16:00:00Z").getEpochSecond());
        row.put("target_until",Instant.parse("2026-11-02T16:36:48Z").getEpochSecond());
        assertFalse(complete(row));
        assertTrue(row.get("previous_month_reason").toString().contains("结算明细仍在抓取"));
        row.put("status","ERROR");assertFalse(complete(row));
        row.remove("window_start");assertFalse(complete(row),"unknown unfinished range cannot be green");
    }
    @Test void onlyCurrentMonthWorkDoesNotReopenThePreviousMonth(){
        var row=state();row.put("status","SYNCING");
        row.put("window_start",Instant.parse("2026-10-31T16:00:00Z").getEpochSecond());
        row.put("target_until",now.getEpochSecond());
        assertTrue(complete(row));
        row.put("window_start",Instant.parse("2026-10-31T15:59:59Z").getEpochSecond());
        assertFalse(complete(row));
    }
    @Test void openBillsBlockOnlyTheirOwnShopAndPreviousMonth(){
        var row=state();
        var bills=List.<Map<String,Object>>of(Map.of("shop_id","a","record_date","2026-10-20","bills",2),
            Map.of("shop_id","a","record_date","2026-11-01","bills",5),
            Map.of("shop_id","other","record_date","2026-10-20","bills",9));
        describe(row,bills,now);
        assertEquals(false,row.get("previous_month_complete"));assertEquals(2L,row.get("previous_month_open_bills"));
        describe(row,bills.subList(1,3),now);assertEquals(true,row.get("previous_month_complete"));
        row.put("channel","ORDER");describe(row,bills,now);assertEquals(true,row.get("previous_month_complete"));
    }
    @Test void localMonthBoundaryAndYearRolloverUseShopTime(){
        var row=state();row.put("last_success_at",Instant.parse("2026-10-31T16:00:00Z"));
        assertTrue(complete(row));assertEquals("2026-10",row.get("previous_month"));
        row.put("timezone","Asia/Bangkok");assertFalse(complete(row));
        row.put("timezone","Asia/Manila");row.put("last_success_at",Instant.parse("2026-12-31T16:00:00Z"));
        describe(row,List.of(),Instant.parse("2027-01-02T00:00:00Z"));
        assertEquals(true,row.get("previous_month_complete"));assertEquals("2026-12",row.get("previous_month"));
    }
    @Test void missingPartialAndInvalidEvidenceNeverCompletes(){
        var row=state();row.put("coverage_start","2026-10-02");assertFalse(complete(row));
        row.put("coverage_start","2026-08-01");row.remove("last_success_at");assertFalse(complete(row));
        row.put("last_success_at",now.plusSeconds(60));assertFalse(complete(row));
        row.put("last_success_at",now.minusSeconds(60));row.put("timezone","invalid");
        assertFalse(complete(row));assertEquals(false,row.get("previous_month_checked"));
    }
}
