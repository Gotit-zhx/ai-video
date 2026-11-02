package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinancePendingBatchTest {
    final TikTokShopOfficialClient tk=mock(TikTokShopOfficialClient.class);
    final FinanceRepository repo=mock(FinanceRepository.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final FinanceMarketplace reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),mock(ShopeeOfficialClient.class),tk,repo);
    final MarketplaceAccess.Connection shop=new MarketplaceAccess.Connection(
        new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]"),
        new MarketplaceAccess.TokenSet("test-token","",Instant.now(),Instant.now(),List.of()));
    Map<String,Object> cursor(){return new LinkedHashMap<>(Map.of("from",1788220800L,"to",1788825600L,"run","sample"));}
    void response(String next,long total){
        when(repo.jdbc()).thenReturn(db);
        when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(total);
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",
            Map.of("transactions",List.of(),"next_page_token",next,"total_count",total)));
    }
    @Test void newPageChainUsesOneHundredAndPersistsPageSize(){
        response("next",0);
        var tx=Map.of("id","tx-1","order_id","order-1","type","ORDER","currency","PHP","order_create_time",1788307200L,"est_settlement_amount","1");
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",
            Map.of("transactions",List.of(tx),"next_page_token","next","total_count",2)));
        var first=reader.tik(new Scope("org","u"),shop,"PENDING","Asia/Manila",cursor());
        assertEquals(100,first.checkpoint().get("pendingPageSize"));
        assertEquals(1,number(first.checkpoint().get("pendingCount")));
        when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(2L);
        var secondRow=new LinkedHashMap<>(tx);secondRow.put("id","tx-2");
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",
            Map.of("transactions",List.of(secondRow),"next_page_token","","total_count",2)));
        assertTrue(reader.tik(new Scope("org","u"),shop,"PENDING","Asia/Manila",first.checkpoint()).done());
        verify(repo,times(2)).save(any(),any());
        verify(tk,times(2)).apiGet(anyString(),anyString(),anyString(),argThat(q->q.get("page_size").equals(100)));
    }
    @Test void oldInFlightCursorKeepsTwentyUntilRangeCompletes(){
        response("",0);var cp=cursor();cp.put("cursor","legacy");
        var result=reader.tik(new Scope("org","u"),shop,"PENDING","Asia/Manila",cp);
        assertTrue(result.done());assertEquals(20,result.checkpoint().get("pendingPageSize"));
        verify(tk).apiGet(anyString(),anyString(),anyString(),argThat(q->q.get("page_size").equals(20)&&q.get("page_token").equals("legacy")));
    }
    @Test void mismatchStillPreventsDestructiveCleanupAndDoesNotChangeCheckpoint(){
        response("",3);var cp=cursor();var before=new LinkedHashMap<>(cp);
        assertThrows(RuntimeException.class,()->reader.tik(new Scope("org","u"),shop,"PENDING","Asia/Manila",cp));
        assertEquals(before,cp);verifyNoInteractions(db);
    }
    @Test void duplicateAcrossPagesCannotPassCountCheckAndDeleteMissingTransactions(){
        response("",1);
        var cp=cursor();cp.put("pendingCount",1);
        when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        assertThrows(RuntimeException.class,()->reader.tik(new Scope("org","u"),shop,"PENDING","Asia/Manila",cp));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
