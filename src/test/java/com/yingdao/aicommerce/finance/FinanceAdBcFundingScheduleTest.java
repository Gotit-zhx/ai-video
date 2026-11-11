package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinanceAdBcFundingScheduleTest {
    @Test void midnightUsesTheAccountDateForBothSuccessAndCurrentTime(){
        var zones=List.of(ZoneId.of("Asia/Manila"));
        assertTrue(FinanceAdBcFunding.nightly(Instant.parse("2026-11-10T15:59:00Z"),Instant.parse("2026-11-10T16:00:00Z"),zones));
        assertFalse(FinanceAdBcFunding.nightly(Instant.parse("2026-11-10T17:00:00Z"),Instant.parse("2026-11-10T18:00:00Z"),zones),"the same account day must not repeatedly trigger the 31-day refresh before UTC midnight");
    }
    @Test void mixedAccountZonesApplyTheSameLatestLocalDayRuleToBothInstants(){
        var zones=List.of(ZoneId.of("Asia/Manila"),ZoneId.of("America/Los_Angeles"));
        assertTrue(FinanceAdBcFunding.nightly(Instant.parse("2026-11-10T12:00:00Z"),Instant.parse("2026-11-10T17:00:00Z"),zones));
        assertFalse(FinanceAdBcFunding.nightly(Instant.parse("2026-11-10T17:00:00Z"),Instant.parse("2026-11-11T01:00:00Z"),zones));
        assertFalse(FinanceAdBcFunding.nightly(null,Instant.parse("2026-11-11T01:00:00Z"),zones));
    }
}
