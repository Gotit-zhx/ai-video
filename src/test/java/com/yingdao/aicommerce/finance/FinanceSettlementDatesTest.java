package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.infrastructure.*;

class FinanceSettlementDatesTest {
    static Map<String,Object> bill(long statement,long payment){
        return Map.of("id","bill","statement_time",statement,"payment_time",payment,"payment_status","SETTLED");
    }
    @Test void samePhilippineTimezoneDoesNotMeanSameOfficialReportCalendar(){
        var raw=bill(1793318400L,1793407377L);
        assertEquals("2026-10-31",FinanceSettlementDates.day(raw,"Asia/Manila","LOCAL").toString());
        var september=bill(Instant.parse("2026-09-29T00:00:00Z").getEpochSecond(),Instant.parse("2026-09-29T04:00:00Z").getEpochSecond());
        assertEquals("2026-09-30",FinanceSettlementDates.day(september,"Asia/Manila","CROSS_BORDER").toString());
        assertEquals("2026-09-29",FinanceSettlementDates.day(september,"Asia/Manila","LOCAL").toString());
    }
    @Test void localNegativeAdjustmentPaidAfterMidnightBelongsToNovember(){
        var adjustment=bill(1793404800L,1793477583L);
        assertEquals("2026-11-01",FinanceSettlementDates.day(adjustment,"Asia/Manila","LOCAL").toString());
        var metadata=FinanceSettlementDates.metadata(adjustment,"Asia/Manila","LOCAL");
        assertEquals(FinanceSettlementDates.LOCAL,metadata.get("_finance_date_basis"));
        assertEquals(FinanceSettlementDates.day(adjustment,"Asia/Manila","LOCAL"),FinanceSettlementDates.day(metadata,"Asia/Manila"));
        assertEquals(1793477583L,metadata.get("_finance_payment_time"));
    }
    @Test void thaiMonthBoundaryUsesLocalCalendarNotBeijingDate(){
        long payment=Instant.parse("2026-10-31T16:30:00Z").getEpochSecond();
        var raw=bill(payment-3600,payment);
        assertEquals("2026-10-31",FinanceSettlementDates.day(raw,"Asia/Bangkok","LOCAL").toString());
        assertEquals("2026-11-01",FinanceSettlementDates.day(raw,"Asia/Manila","LOCAL").toString());
        assertEquals("2026-11-01",FinanceSettlementDates.day(raw,"Asia/Kuala_Lumpur","LOCAL").toString());
        assertThrows(RuntimeException.class,()->FinanceSettlementDates.day(raw,"invalid","LOCAL"));
    }
    @Test void legacyVerifiedDatesKeepPrecedenceAndUnfinishedBillsCannotAcquireACompletionDate(){
        assertTrue(FinanceSettlementDates.exportBasis(Map.of("_finance_date_version",3,"_finance_export_statement_date","2026-09-30")));
        var pending=Map.<String,Object>of("statement_time",1793404800L,"payment_time",0,"payment_status","PROCESSING");
        assertThrows(RuntimeException.class,()->FinanceSettlementDates.day(pending,"Asia/Manila","LOCAL"));
        assertFalse(FinanceSettlementDates.metadata(pending,"Asia/Manila","LOCAL").containsKey("_finance_export_statement_date"));
    }
    @Test void dateAndAdjustmentIdentityAreExplainedConsistentlyInRecordAndExportFields(){
        var raw=new LinkedHashMap<>(FinanceSettlementDates.metadata(bill(1793404800L,1793477583L),"Asia/Manila","LOCAL"));
        raw.put("adjustment_id","3714031409303815320");
        var row=new LinkedHashMap<String,Object>(Map.of("platform","TIKTOK_SHOP","source","API","kind","SETTLEMENT","amount",decimal("-42.30")));
        FinanceFields.enrich(row,raw);
        assertTrue(text(row.get("settlement_date_source")).contains("余额"));
        assertEquals("3714031409303815320",row.get("tk_adjustment_id"));
    }
    @Test void settlementWindowOverlapNeverChangesTheWindowOfAnExistingPageCursor(){
        var tk=mock(TikTokShopOfficialClient.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),mock(ShopeeOfficialClient.class),tk,mock(FinanceRepository.class));
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]");
        var c=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("statements",List.of(),"next_page_token","next")));
        long from=1790784000L,to=from+86400;
        var page=reader.tik(new Scope("org","u"),c,"SETTLEMENT","Asia/Manila",Map.of("from",from,"to",to));
        verify(tk).apiGet(anyString(),anyString(),anyString(),argThat(q->q.get("statement_time_ge").equals(from-172800)));
        assertEquals(from-172800,number(page.checkpoint().get("statementWindowFrom")));
        clearInvocations(tk);
        reader.tik(new Scope("org","u"),c,"SETTLEMENT","Asia/Manila",Map.of("from",from,"to",to,"cursor","old"));
        verify(tk).apiGet(anyString(),anyString(),anyString(),argThat(q->q.get("statement_time_ge").equals(from)));
    }
}
