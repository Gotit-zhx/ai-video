package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceWorkspaceRedisCacheTest {
    final FinanceReadCacheTest fixture=new FinanceReadCacheTest();

    @Test void viewsAndPageSelectionsCannotAliasTheOverviewOrEachOther(){
        var key=fixture.key("reader","",false);
        fixture.cache.get(key,()->fixture.data("1"));
        assertAmount("11",fixture.cache.getPage(key,"fees","revision-a","page=1",()->fixture.data("11")));
        assertAmount("22",fixture.cache.getPage(key,"ads","revision-a","page=1",()->fixture.data("22")));
        assertAmount("33",fixture.cache.getPage(key,"fees","revision-a","page=2",()->fixture.data("33")));
        assertAmount("11",fixture.cache.getPage(key,"fees","revision-a","page=1",()->{fail("Warm identical page must reuse Redis");return null;}));
        assertAmount("1",fixture.cache.get(key,()->{fail("Page reads must not overwrite overview");return null;}));
    }

    @Test void publishedProjectionRevisionSelectsANewResponse(){
        var key=fixture.key("reader","",false);
        assertAmount("11",fixture.cache.getPage(key,"settlements","revision-a","",()->fixture.data("11")));
        assertAmount("12",fixture.cache.getPage(key,"settlements","revision-b","",()->fixture.data("12")));
    }

    @Test void sharedPageCacheKeepsDecimalPrecisionAndAuthorizationScope(){
        var key=fixture.key("reader","",false);
        String amount="123456789012345678.123456";
        fixture.cache.getPage(key,"fees","revision-a","",()->fixture.data(amount));
        var otherInstance=new FinanceReadCache(fixture.redis,new ObjectMapper(),fixture.now::get,fixture.tasks::add,false);
        var hit=otherInstance.getPage(key,"fees","revision-a","",()->{fail("Second service instance must reuse the shared value");return null;});
        assertEquals(new BigDecimal(amount),hit.get("amount"));
        assertAmount("9",otherInstance.getPage(fixture.key("different-reader","",false),"fees","revision-a","",()->fixture.data("9")));
    }

    @Test void softInvalidationKeepsOriginalTimestampAndHardInvalidationDoesNotReuseOldPage(){
        var key=fixture.key("reader","",false);
        var original=fixture.cache.getPage(key,"fees","revision-a","",()->fixture.data("11"));
        var generated=map(original.get("cache")).get("generatedAt");
        fixture.now.addAndGet(2000);fixture.cache.refresh(key.scope());
        var stale=fixture.cache.getPage(key,"fees","revision-a","",()->fixture.data("12"));
        assertAmount("11",stale);assertEquals(true,map(stale.get("cache")).get("refreshing"));
        assertEquals(generated,map(stale.get("cache")).get("generatedAt"));
        fixture.cache.invalidate(key.scope());
        assertAmount("12",fixture.cache.getPage(key,"fees","revision-a","",()->fixture.data("12")));
    }

    private static void assertAmount(String expected,java.util.Map<String,Object> response){
        assertEquals(0,new BigDecimal(expected).compareTo(decimal(response.get("amount"))));
    }
}
