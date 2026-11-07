package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the disposable MySQL fixture, including settlement and pending-cost rules. */
class FinanceDashboardCacheIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void companySnapshotServesShopGroupCurrencyAndChartChangesWithEqualAmounts(){
        String baby=service.saveGroup(owner,null,"baby"),clothes=service.saveGroup(owner,null,"clothes");
        String a=groupedShop(owner,"cache-a",baby),b=groupedShop(owner,"cache-b",clothes);
        for(String shop:List.of(a,b))for(String kind:List.of("ORDER","SETTLEMENT","FEE","REFUND"))
            record(owner,shop,kind,kind+"-cache",new BigDecimal(shop.equals(a)?"100.123456":"200.654321"),Map.of());
        var counted=spy(repo);var cached=new FinanceService(counted,null,null,fx);
        var all=new Filter(filter.start(),filter.end(),"","","","day");
        cached.dashboard(owner,all,true,"fixed");
        for(var selected:List.of(
                new Filter(all.start(),all.end(),a,"","","day"),
                new Filter(all.start(),all.end(),"","","","day",clothes),
                new Filter(all.start(),all.end(),a,"","PHP","month"),
                new Filter(all.start(),all.end(),a,"","","day",clothes))){
            for(boolean cny:List.of(false,true)){
                var actual=cached.dashboard(owner,selected,cny,"fixed");
                var direct=new FinanceService(repo,null,null,fx).dashboard(owner,selected,cny,"fixed");
                for(String key:List.of("totals","ranking","fees","statuses","trend","profit","productCosts","reportingMonths","currencies","advertising"))
                    assertEquals(direct.get(key),actual.get(key),key+" "+selected);
            }
        }
        verify(counted,times(1)).dashboardBuckets(eq(owner),any(),eq(false),anyList());
        cached.refreshCostSnapshots(owner);
        cached.dashboard(owner,new Filter(all.start(),all.end(),a,"","","day"),true,"fixed");
        verify(counted,times(2)).dashboardBuckets(eq(owner),any(),eq(false),anyList());
    }

    @Test void unassignedPlatformExpensesAreFilteredBySqlInsteadOfDroppedFromAProjection(){
        String group=service.saveGroup(owner,null,"baby"),shop=groupedShop(owner,"cache-platform",group);
        record(owner,shop,"SETTLEMENT","settled",BigDecimal.TEN,Map.of());
        for(String platform:List.of("SHOPEE","TIKTOK_SHOP"))
            repo.save(owner,new Entry("UNASSIGNED",platform,"AD","ad-"+platform,"","","ads","ACTIVE",
                    filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        var counted=spy(repo);var cached=new FinanceService(counted,null,null,fx);
        var all=new Filter(filter.start(),filter.end(),"","","","day");
        cached.dashboard(owner,all,true,"fixed");
        var selected=new Filter(all.start(),all.end(),"","SHOPEE","","day");
        var actual=cached.dashboard(owner,selected,true,"fixed");
        var direct=new FinanceService(repo,null,null,fx).dashboard(owner,selected,true,"fixed");
        assertEquals(direct.get("totals"),actual.get("totals"));
        verify(counted,times(2)).dashboardBuckets(eq(owner),any(),eq(false),anyList());
    }
}
