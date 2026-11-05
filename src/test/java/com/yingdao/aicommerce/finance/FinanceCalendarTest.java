package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceCalendarTest {
    @Test void monthSelectionDoesNotUseServerTimezone(){
        assertEquals(YearMonth.of(2026,10),FinanceCalendar.previousMonth(Instant.parse("2026-10-31T17:30:00Z")));
        assertEquals(YearMonth.of(2025,12),FinanceCalendar.previousMonth(Instant.parse("2025-12-31T16:00:00Z")));
    }
    @Test void oneCalendarSelectionHasDifferentShopBoundaries(){
        var f=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day","g");
        var windows=FinanceCalendar.windows(f,List.of(
                shop("ph","Asia/Manila","g"),shop("th","Asia/Bangkok","g"),shop("other","UTC","other")));
        assertEquals(2,windows.size());
        assertEquals("2026-09-30T16:00:00Z",windows.get(0).get("startInclusive"));
        assertEquals("2026-10-31T16:00:00Z",windows.get(0).get("endExclusive"));
        assertEquals("2026-10-01 01:00:00",windows.get(1).get("beijingStart"));
        assertEquals("2026-11-01 01:00:00",windows.get(1).get("beijingEndExclusive"));
        // Thai October 31 at 23:30 belongs to October even though Beijing is already November.
        assertTrue(Instant.parse("2026-10-31T16:30:00Z").isBefore(Instant.parse(text(windows.get(1).get("endExclusive")))));
        assertFalse(Instant.parse("2026-10-31T17:00:00Z").isBefore(Instant.parse(text(windows.get(1).get("endExclusive")))));
    }
    @Test void daylightSavingAndUnknownZonesAreNotFixedHourOffsets(){
        var f=new Filter(LocalDate.of(2026,3,8),LocalDate.of(2026,3,8),"","","","day");
        var w=FinanceCalendar.windows(f,List.of(shop("us","America/New_York",""),shop("invalid","bad-zone","")));
        assertEquals(23,Duration.between(Instant.parse(text(w.get(0).get("startInclusive"))),Instant.parse(text(w.get(0).get("endExclusive")))).toHours());
        assertTrue(w.get(1).containsKey("warning"));
        assertFalse(w.get(1).containsKey("startInclusive"));
    }
    @Test void timestampsAndOfficialDateRemainDistinct(){
        var row=new LinkedHashMap<String,Object>(Map.of("kind","BILL","platform","TIKTOK_SHOP","source","API",
                "record_date","2026-09-30","order_timezone","Asia/Bangkok","amount",new java.math.BigDecimal("12.30")));
        var raw=Map.<String,Object>of("_finance_statement_time",Instant.parse("2026-09-28T17:30:00Z").getEpochSecond(),
                "_finance_payment_time",Instant.parse("2026-09-28T18:30:00Z").getEpochSecond(),
                "_finance_payment_status","PAID","_finance_date_version",3,"_finance_export_statement_date","2026-09-30");
        FinanceFields.enrich(row,raw);
        assertEquals("2026-09-30",row.get("record_date"));
        assertEquals("2026-09-29 00:30:00",cell(row,"statement_time_local"));
        assertEquals("2026-09-29 01:30:00",cell(row,"statement_time_beijing"));
        assertEquals("2026-09-29 01:30:00",cell(row,"payment_time_local"));
        assertEquals("2026-09-29 02:30:00",cell(row,"payment_time_beijing"));
        assertEquals(new java.math.BigDecimal("12.30"),row.get("amount"));
        row.put("updated_at",java.sql.Timestamp.from(Instant.parse("2026-09-28T18:30:00Z")));
        assertEquals("2026-09-29 02:30:00",cell(row,"updated_at"));
        assertEquals("Asia/Shanghai",FinanceCalendar.contract().get("qianyiReceiptTimezone"));
    }
    static String cell(Map<String,Object> row,String id){
        return FinanceFields.cell(row,FinanceFields.CATALOG.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow());
    }
    static Map<String,Object> shop(String id,String zone,String group){
        return Map.of("id",id,"name",id,"timezone",zone,"platform","SHOPEE","group_id",group);
    }
}
