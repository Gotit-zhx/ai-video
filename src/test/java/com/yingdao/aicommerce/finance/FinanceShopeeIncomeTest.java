package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.infrastructure.ShopeeOfficialClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinanceShopeeIncomeTest {
    final FinanceRepository repo=new FinanceRepository(null,new tools.jackson.databind.ObjectMapper());
    @Test void thirtyRowsUseTwoBoundedRequestsAndPreserveEachOrderIdentity(){
        var client=mock(ShopeeOfficialClient.class);
        var input=new ArrayList<Map<String,Object>>();
        for(int i=0;i<30;i++)input.add(Map.of("order_sn","order-"+i));
        var sizes=new ArrayList<Integer>();
        when(client.shopPost(eq("/api/v2/payment/get_escrow_detail_batch"),isNull(),anyMap())).thenAnswer(call->{
            var q=(Map<?,?>)call.getArgument(2);
            assertInstanceOf(List.class,q.get("order_sn_list"));
            var ids=(List<?>)q.get("order_sn_list");
            sizes.add(ids.size());
            return Map.of("response",ids.stream().map(id->Map.of("escrow_detail",Map.of("order_sn",id,
                "order_income",Map.of("cost_of_goods_sold",100)))).toList());
        });
        assertEquals(30,FinanceShopeeIncome.details(client,null,repo,input).size());
        assertEquals(List.of(20,10),sizes);
    }
    @Test void documentedAlternativeEnvelopeWorksButPerOrderFailureIsNotACompleteBatch(){
        var client=mock(ShopeeOfficialClient.class);var rows=List.<Map<String,Object>>of(Map.of("order_sn","one"));
        when(client.shopPost(anyString(),isNull(),anyMap())).thenReturn(Map.of("escrow_detail_list",
            List.of(Map.of("escrow_detail",Map.of("order_sn","one","order_income",Map.of("cost_of_goods_sold",0))))));
        assertEquals(Set.of("one"),FinanceShopeeIncome.details(client,null,repo,rows).keySet());
        when(client.shopPost(anyString(),isNull(),anyMap())).thenReturn(Map.of("escrow_detail_list",
            List.of(Map.of("escrow_detail",Map.of("order_sn","one"),"fail_error","order_not_found"))));
        assertThrows(RuntimeException.class,()->FinanceShopeeIncome.details(client,null,repo,rows));
    }
    @Test void malformedUnknownDuplicateAndMissingOrdersNeverPassAsComplete(){
        var client=mock(ShopeeOfficialClient.class);var input=List.<Map<String,Object>>of(Map.of("order_sn","one"));
        var valid=Map.of("escrow_detail",Map.of("order_sn","one","order_income",Map.of("cost_of_goods_sold",100)));
        for(var response:List.<Map<String,Object>>of(Map.of(),Map.of("response",List.of()),
                Map.of("response",List.of("bad")),Map.of("response",List.of(valid,valid)),
                Map.of("response",List.of(Map.of("escrow_detail",Map.of("order_sn","another","order_income",Map.of("cost_of_goods_sold",100))))))){
            when(client.shopPost(anyString(),isNull(),anyMap())).thenReturn(response);
            assertThrows(RuntimeException.class,()->FinanceShopeeIncome.details(client,null,repo,input));
        }
    }
}
