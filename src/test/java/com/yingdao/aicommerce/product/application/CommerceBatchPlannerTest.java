package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchPlanner.*;

class CommerceBatchPlannerTest {
    private final CommerceBatchPlanner planner = new CommerceBatchPlanner();
    private Request request(Mode mode, int products, int shops, int limit, String market, BigDecimal budget) {
        return new Request(mode, Platform.TIKTOK_SHOP, market, products, 10, 1, shops, limit,
                true, true, false, budget, 7, new BigDecimal("6.5"));
    }
    @Test void podExampleAllocates300DraftsWithoutMultiplyingAgainByShops() {
        var p = planner.preview(request(Mode.POD_DESIGN, 30, 6, 50, "PH", new BigDecimal("1000")));
        assertEquals(300, p.materialImages()); assertEquals(300, p.listingDrafts());
        assertEquals(java.util.List.of(50,50,50,50,50,50), p.schedule().getFirst().listingsPerShop());
        assertEquals(new BigDecimal("6000"), p.dailyBudgetAllShops());
        assertEquals(new BigDecimal("42000"), p.observationBudgetCeiling());
        assertTrue(p.blockers().stream().anyMatch(s -> s.contains("生产图")));
        assertEquals("PREVIEW_ONLY", p.status());
    }
    @Test void stockArtworkDoesNotInventProducts() {
        var p = planner.preview(request(Mode.STOCK_MARKETING, 30, 6, 50, "SG", BigDecimal.ZERO));
        assertEquals(300, p.materialImages()); assertEquals(30, p.listingDrafts());
        assertEquals("SGD", p.currency());
        assertEquals(java.util.List.of(5,5,5,5,5,5), p.schedule().getFirst().listingsPerShop());
        assertFalse(p.blockers().stream().anyMatch(s -> s.startsWith("POD")));
    }
    @Test void multiDayScheduleConservesDraftsAndHonorsQuota() {
        var p = planner.preview(request(Mode.POD_DESIGN, 31, 6, 20, "MY", BigDecimal.ONE));
        assertEquals(3, p.minimumPublishingDays());
        assertEquals(310, p.schedule().stream().flatMap(d -> d.listingsPerShop().stream()).mapToInt(Integer::intValue).sum());
        assertTrue(p.schedule().stream().flatMap(d -> d.listingsPerShop().stream()).allMatch(n -> n >= 0 && n <= 20));
    }
    @Test void rejectsInvalidCountsMarketsAndMoney() {
        assertThrows(BusinessException.class, () -> planner.preview(null));
        assertThrows(BusinessException.class, () -> planner.preview(request(Mode.POD_DESIGN, 0, 6, 50, "PH", BigDecimal.ONE)));
        assertThrows(BusinessException.class, () -> planner.preview(request(Mode.POD_DESIGN, 30, 0, 50, "PH", BigDecimal.ONE)));
        assertThrows(BusinessException.class, () -> planner.preview(request(Mode.POD_DESIGN, 30, 6, 50, "US", BigDecimal.ONE)));
        assertThrows(BusinessException.class, () -> planner.preview(request(Mode.POD_DESIGN, 30, 6, 50, "PH", new BigDecimal("-1"))));
        assertThrows(BusinessException.class, () -> planner.preview(request(Mode.POD_DESIGN, 30, 6, 50, "PH", new BigDecimal("0.001"))));
    }
    @Test void selfDeclarationsNeverUnlockExecution() {
        var p = planner.preview(new Request(Mode.POD_DESIGN, Platform.SHEIN, "PH", 1, 1, 1,
                1, 1, true, true, true, BigDecimal.ZERO, 1, BigDecimal.ONE));
        assertEquals("PREVIEW_ONLY", p.status());
        assertFalse(p.blockers().isEmpty());
        assertTrue(p.warnings().stream().anyMatch(s -> s.contains("尚未接入")));
    }
}
