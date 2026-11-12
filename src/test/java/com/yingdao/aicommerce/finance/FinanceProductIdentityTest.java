package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceProductIdentityTest {
    @Test void orderSpusAreAlwaysAvailableAsDistinctExactStrings(){
        for(String platform:List.of("TIKTOK_SHOP","SHOPEE")){
            var row=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","source","API","platform",platform));
            boolean tk=platform.equals("TIKTOK_SHOP");var items=new ArrayList<Map<String,Object>>();
            for(String id:List.of("9007199254740993123","9007199254740993123","9007199254740993124"))
                items.add(Map.of(tk?"product_id":"item_id",id,tk?"seller_sku":"model_sku","PACK",tk?"sku_id":"model_id","variant"));
            FinanceFields.enrich(row,Map.of(tk?"line_items":"item_list",items));
            assertEquals(List.of("9007199254740993123","9007199254740993124"),row.get("product_spu_values"));
            assertEquals("PACK",row.get("product_skus"));assertFalse(row.containsKey("product_sku_details"));
        }
    }
    @Test void emptyMerchantSkuKeepsOriginalNamesAndExactPlatformIdsWithoutBuyerData(){
        var row=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","source","API","platform","SHOPEE"));
        var item=Map.<String,Object>of("item_id",40026185876L,"model_id",400076697058L,"model_sku","","item_sku","",
            "item_name","历史商品","model_name","50片1包 M-L","buyer_phone","never-expose");
        FinanceFields.enrich(row,Map.of("item_list",List.of(item),"buyer_username","never-expose"));
        assertEquals("",row.get("product_skus"),"platform IDs must not become merchant codes");
        var identity=rows(row.get("product_sku_details")).getFirst();
        assertEquals("400076697058",identity.get("skuId"));assertEquals("40026185876",identity.get("productId"));
        assertEquals("历史商品",identity.get("name"));assertEquals("50片1包 M-L",identity.get("spec"));
        assertEquals("",identity.get("sellerSku"));assertFalse(row.toString().contains("never-expose"));
        assertFalse(row.containsKey("raw_json"));
    }
    @Test void mixedOrdersKeepBothKnownAndMissingSkusAndRejectNestedPayloads(){
        var row=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","source","API","platform","TIKTOK_SHOP"));
        var raw=Map.<String,Object>of("line_items",List.of(
            Map.of("product_id","p","sku_id","90071992547409931","seller_sku","PACK","product_name","已知商品","sku_name","单包","id","line-a"),
            Map.of("product_id","p","sku_id","90071992547409932","seller_sku","","product_name","旧商品","sku_name","旧规格","id","line-b")));
        FinanceFields.enrich(row,raw);
        assertEquals(List.of("PACK"),row.get("product_sku_values"));
        var values=rows(row.get("product_sku_details"));assertEquals(2,values.size());
        assertEquals("90071992547409932",values.get(1).get("skuId"));assertEquals("line-b",values.get(1).get("lineId"));
        assertEquals("PACK",values.getFirst().get("sellerSku"));
        var malformed=FinanceProductSkus.identities("TIKTOK_SHOP",Map.of("line_items",List.of(Map.of("sku_id",Map.of("buyer","secret"),"product_name",Map.of("token","secret")))));
        assertEquals("",malformed.getFirst().get("skuId"));assertFalse(malformed.toString().contains("secret"));
        assertTrue(FinanceProductSkus.identities("UNKNOWN",raw).isEmpty());
    }
    @Test void completeMerchantSkusKeepTheExistingCompactShape(){
        var row=new LinkedHashMap<String,Object>(Map.of("kind","ORDER","source","API","platform","SHOPEE"));
        FinanceFields.enrich(row,Map.of("item_list",List.of(Map.of("model_sku"," ","item_sku","PACK","model_id","v","item_id","p"))));
        assertEquals("PACK",row.get("product_skus"));assertFalse(row.containsKey("product_sku_details"));
    }
}
