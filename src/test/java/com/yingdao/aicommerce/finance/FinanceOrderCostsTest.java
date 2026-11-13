package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceOrderCostsTest {
    Map<String,Object> cost(String code,String warehouse,String month,String price){return Map.of("sku",code,"warehouse",warehouse,"prices",Map.of(month,new BigDecimal(price)));}
    FinanceOrderCosts.Context context(List<Map<String,Object>> costs,Map<String,Object> settings,Map<String,Object> mappings){
        return new FinanceOrderCosts.Context("shop","group","PH","Asia/Manila","book",costs,settings,mappings);
    }
    Map<String,Object> tk(String id,String code){return new LinkedHashMap<>(Map.of("id",id,"product_id","product","sku_id","sku","seller_sku",code,"sku_name","Pants-XXXL"));}
    Map<String,Object> order(String platform,String status){return Map.of("platform",platform,"status",status,"record_date","2026-10-21");}
    void amountEquals(String expected,Object value){assertNotNull(value);assertEquals(0,new BigDecimal(expected).compareTo(decimal(value)));}
    @Test void sellerSkuWinsAndPlatformSkuIsTheSecondAutomaticChoice(){
        String spu="9007199254740993123",variant="9007199254740993124";
        var costs=List.of(cost("PACK","菲律宾","2026-10","7"),cost(variant,"菲律宾","2026-10","11"),cost(spu,"菲律宾","2026-10","99"));
        for(String platform:List.of("TIKTOK_SHOP","SHOPEE"))for(String seller:List.of("PACK","UNKNOWN","")){
            var item=platform.equals("TIKTOK_SHOP")?Map.<String,Object>of("id","line","product_id",spu,"sku_id",variant,"seller_sku",seller,"quantity",2):
                Map.<String,Object>of("item_id",spu,"model_id",variant,"model_sku",seller,"item_sku","","model_quantity_purchased",2);
            var result=FinanceOrderCosts.calculate(order(platform,"COMPLETED"),Map.of(platform.equals("SHOPEE")?"item_list":"line_items",List.of(item)),context(costs,Map.of(),Map.of()));
            assertEquals("READY",result.get("status"),platform+" / "+seller);
            amountEquals(seller.equals("PACK")?"14":"22",result.get("amountCny"));
            var line=rows(result.get("lines")).getFirst();assertEquals(seller,line.get("sellerSku"));
            assertEquals(seller.equals("PACK")?"PACK":variant,line.get("costCode"));
            assertEquals(seller.equals("PACK")?"SKU":"PLATFORM_SKU",line.get("matchedBy"));
        }
    }
    @Test void localPlatformSkuCanMatchWhenMerchantCodeOnlyExistsInAnotherWarehouse(){
        var item=tk("line","PACK");item.put("sku_id","400000000002");
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
            context(List.of(cost("PACK","泰国","2026-10","100"),cost("400000000002","菲律宾","2026-10","8")),Map.of(),Map.of()));
        assertEquals("READY",result.get("status"));amountEquals("8",result.get("amountCny"));
        assertEquals("菲律宾",rows(result.get("lines")).getFirst().get("warehouse"));
    }
    @Test void productSpuNeverSubstitutesForPlatformSkuAndBothMissesNeedConfirmation(){
        for(String platform:List.of("TIKTOK_SHOP","SHOPEE"))for(String variant:List.of("400000000002","")){
            var item=platform.equals("TIKTOK_SHOP")?Map.<String,Object>of("id","line","product_id","40000000001","sku_id",variant,"seller_sku","","quantity",1):
                Map.<String,Object>of("item_id","40000000001","model_id",variant,"model_sku","","model_quantity_purchased",1);
            var result=FinanceOrderCosts.calculate(order(platform,"COMPLETED"),Map.of(platform.equals("SHOPEE")?"item_list":"line_items",List.of(item)),
                context(List.of(cost("40000000001","菲律宾","2026-10","8")),Map.of(),Map.of()));
            assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));
            String issue=text(rows(result.get("lines")).getFirst().get("issue"));assertTrue(issue.contains("平台 SKU")&&issue.contains("人工确认"));
        }
    }
    @Test void aMatchedSkuWithInvalidPriceDoesNotSwitchToThePlatformSkuPrice(){
        var item=tk("line","PACK");item.put("sku_id","400000000002");
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
            context(List.of(cost("PACK","菲律宾","2026-10","0"),cost("400000000002","菲律宾","2026-10","8")),Map.of(),Map.of()));
        assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));
        assertEquals("PACK",rows(result.get("lines")).getFirst().get("costCode"));
    }
    @Test void oldSpuAutomaticMappingIsReevaluatedButManualMappingStillWins(){
        var item=tk("line","");item.put("product_id","40000000001");item.put("sku_id","400000000002");
        var costs=List.of(cost("400000000002","菲律宾","2026-10","7"),cost("40000000001","菲律宾","2026-10","11"));
        for(boolean manual:List.of(false,true)){
            var saved=new LinkedHashMap<String,Object>(Map.of("code","40000000001","warehouse","菲律宾","factor",1,"groupId","group"));
            if(!manual)saved.put("method","SPU_CODE");
            var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
                context(costs,Map.of(),Map.of("shop:40000000001:400000000002:菲律宾",saved)));
            amountEquals(manual?"11":"7",result.get("amountCny"));
            assertEquals(manual?"MANUAL":"PLATFORM_SKU",rows(result.get("lines")).getFirst().get("matchedBy"));
        }
    }
    @Test void platformSkuAutomaticMappingRechecksMerchantSkuPriority(){
        var item=tk("line","PACK");item.put("sku_id","400000000002");
        var saved=Map.of("code","400000000002","warehouse","菲律宾","factor",1,"groupId","group","method","PLATFORM_SKU_CODE");
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
            context(List.of(cost("PACK","菲律宾","2026-10","7"),cost("400000000002","菲律宾","2026-10","11")),Map.of(),Map.of("shop:product:400000000002:菲律宾",saved)));
        amountEquals("7",result.get("amountCny"));assertEquals("SKU",rows(result.get("lines")).getFirst().get("matchedBy"));
    }
    @Test void anAmbiguousMerchantSkuCanUseUniquePlatformSkuWithoutBorrowingBundleFactor(){
        var item=tk("line","（PACK） ×2");item.put("sku_id","400000000002");item.put("quantity",3);
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
            context(List.of(cost("PACK*2","菲律宾","2026-10","5"),cost("(PACK)*2","菲律宾","2026-10","6"),cost("400000000002","菲律宾","2026-10","7")),Map.of(),Map.of()));
        assertEquals("READY",result.get("status"));amountEquals("21",result.get("amountCny"));
        var line=rows(result.get("lines")).getFirst();assertEquals("PLATFORM_SKU",line.get("matchedBy"));amountEquals("1",line.get("factor"));
    }
    @Test void ambiguousPlatformSkuModelsRemainIncompleteInsteadOfChoosingAPrice(){
        var item=tk("line","");item.put("sku_id","400000000002");
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(item)),
            context(List.of(cost("(400000000002)","菲律宾","2026-10","5"),cost("（400000000002）","菲律宾","2026-10","6")),Map.of(),Map.of()));
        assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));
    }
    @Test void giftIsFixedZeroOnBothPlatformsWithoutCatalogueDateOrWarehouse(){
        for(String platform:List.of("TIKTOK_SHOP","SHOPEE"))for(String sku:List.of("GIFT","gift"," Gift ")){
            var item=platform.equals("SHOPEE")?Map.<String,Object>of("item_id","i","model_id","m","item_sku",sku):tk("gift",sku);
            var result=FinanceOrderCosts.calculate(Map.of("platform",platform,"status","COMPLETED"),
                Map.of(platform.equals("SHOPEE")?"item_list":"line_items",List.of(item)),context(List.of(),Map.of(),Map.of()));
            assertEquals("READY",result.get("status"));amountEquals("0",result.get("amountCny"));
            assertEquals(1,result.get("matchedLines"));assertEquals(0,result.get("fallbackLines"));
            var line=rows(result.get("lines")).getFirst();
            assertEquals(true,line.get("fixedGift"));assertFalse(line.containsKey("issue"));amountEquals("0",line.get("unitPriceCny"));
        }
    }
    @Test void giftOverridesOldNonzeroMappingWithoutMakingOtherGoodsFree(){
        var costs=List.of(cost("PACK","海外仓-菲律宾","2026-10","7"),cost("GIFT","海外仓-菲律宾","2026-10","100"));
        var mapping=Map.<String,Object>of("shop:product:sku:海外仓-菲律宾",Map.of("code","PACK","factor",3,"groupId","group"));
        var gift=tk("gift","GIFT");var ordinary=tk("ordinary","PACK");ordinary.put("sku_id","normal");
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(gift,ordinary)),
            context(costs,Map.of(),mapping));
        assertEquals("READY",result.get("status"));amountEquals("7",result.get("amountCny"));
        var missing=tk("missing","GIFT-BOX");missing.put("sku_id","missing");
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(gift,ordinary,missing)),
            context(costs,Map.of(),mapping));
        assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));amountEquals("7",result.get("knownAmountCny"));
        for(String sku:List.of("GIFT-BOX","MYGIFT","GIFT*2","GI FT",""))assertFalse(FinanceOrderCosts.giftSku(sku));
    }
    @Test void merchantSkuControlsSizeAndIndependentBundlePriceWins(){
        var context=context(List.of(cost("YUXI-BPants-XXL","海外仓-菲律宾","2026-10","10"),cost("YUXI-BPants-XXL*2","海外仓-菲律宾","2026-10","23")),Map.of(),Map.of());
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("line","（YUXI-BPants-XXL） *2"))),context);
        assertEquals("READY",result.get("status"));amountEquals("23",result.get("amountCny"));
        var line=rows(result.get("lines")).getFirst();assertEquals("YUXI-BPants-XXL*2",line.get("costCode"));amountEquals("1",line.get("factor"));
    }
    @Test void perUnitTiktokLinesExplicitQuantitiesAndDuplicatesDoNotDoubleCount(){
        var context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","4.125")),Map.of(),Map.of());
        var one=tk("a","(PACK)*2");var two=tk("b","(PACK)*2");two.put("quantity",3);
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(one,one,two),"buyer_username","private"),context);
        amountEquals("33",result.get("amountCny"));assertEquals(2,result.get("lineCount"));assertFalse(result.toString().contains("private"));
        var missing=new LinkedHashMap<>(one);missing.remove("id");
        assertEquals("INCOMPLETE",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(missing)),context).get("status"));
    }
    @Test void shopeeQuantityIsRequiredAndBaseBundlesRetainTheMultiplier(){
        var context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","10")),Map.of(),Map.of());
        var item=new LinkedHashMap<String,Object>(Map.of("item_id","i","model_id","m","model_sku","(PACK) *2","model_quantity_purchased",3));
        var result=FinanceOrderCosts.calculate(order("SHOPEE","COMPLETED"),Map.of("item_list",List.of(item)),context);
        amountEquals("60",result.get("amountCny"));item.remove("model_quantity_purchased");
        var incomplete=FinanceOrderCosts.calculate(order("SHOPEE","COMPLETED"),Map.of("item_list",List.of(item)),context);
        assertNull(incomplete.get("amountCny"));assertEquals("INCOMPLETE",incomplete.get("status"));
    }
    @Test void historicalPriceIsLabelledFuturePriceIsNeverUsedAndMissingDoesNotMeanZero(){
        var costs=List.of(cost("PACK","海外仓-菲律宾","2026-09","7"),cost("FUTURE","海外仓-菲律宾","2026-11","8"));
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("a","PACK"))),context(costs,Map.of(),Map.of()));
        assertEquals("ESTIMATED",result.get("status"));amountEquals("7",result.get("amountCny"));
        assertEquals("2026-09",rows(result.get("lines")).getFirst().get("priceMonth"));
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("a","PACK"),tk("b","FUTURE"))),context(costs,Map.of(),Map.of()));
        assertNull(result.get("amountCny"));amountEquals("7",result.get("knownAmountCny"));
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("a","PACK"))),context(costs,Map.of("rules",Map.of("missing","pending")),Map.of()));
        assertEquals("INCOMPLETE",result.get("status"));
    }
    @Test void warehouseCountryNeedsUniqueMatchAndExplicitWarehouseTakesPriority(){
        var costs=List.of(cost("PACK","海外仓-菲律宾","2026-10","7"),cost("PACK","成品总仓","2026-10","5"));
        assertEquals("海外仓-菲律宾",FinanceOrderCosts.resolveWarehouse(context(costs,Map.of(),Map.of())));
        assertEquals("成品总仓",FinanceOrderCosts.resolveWarehouse(context(costs,Map.of("warehouse","成品总仓"),Map.of())));
        var ambiguous=new ArrayList<>(costs);ambiguous.add(cost("PACK","海外仓-菲律宾2","2026-10","8"));
        assertEquals("",FinanceOrderCosts.resolveWarehouse(context(ambiguous,Map.of(),Map.of())));
    }
    @Test void countryNamedWarehousesCalculateLocalCostsWithoutAnOverseasPrefix(){
        var costs=List.of(cost("PACK","菲律宾","2026-10","7"),cost("PACK","泰国","2026-10","100"),
            cost("PACK","马来西亚","2026-10","200"));
        for(var entry:Map.of("PH","菲律宾","TH","泰国","MY","马来西亚").entrySet()){
            var context=new FinanceOrderCosts.Context("shop","group",entry.getKey(),"Asia/Manila","book",costs,Map.of(),Map.of());
            var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("line","PACK"))),context);
            assertEquals(entry.getValue(),result.get("warehouse"));assertEquals("READY",result.get("status"));
        }
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("line","PACK"))),context(costs,Map.of(),Map.of()));
        amountEquals("7",result.get("amountCny"));
    }
    @Test void plainAndOverseasWarehousesInTheSameCountryRemainAmbiguous(){
        var costs=List.of(cost("PACK","菲律宾","2026-10","7"),cost("PACK","海外仓-菲律宾","2026-10","8"));
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("line","PACK"))),context(costs,Map.of(),Map.of()));
        assertEquals("",result.get("warehouse"));assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("line","PACK"))),context(costs,Map.of("warehouse","菲律宾"),Map.of()));
        assertEquals("READY",result.get("status"));amountEquals("7",result.get("amountCny"));
    }
    @Test void recognizedBundleWithoutLocalCountryCostIsExplicitAndNeverBorrowsAnotherCountry(){
        var context=new FinanceOrderCosts.Context("shop","group","MY","Asia/Kuala_Lumpur","book",
            List.of(cost("YUXI-BDiaper-NB","海外仓-菲律宾","2026-08","9.1"),cost("YUXI-BDiaper-NB","海外仓-泰国","2026-08","9"),
                cost("OTHER","海外仓-马来","2026-08","5")),Map.of(),Map.of());
        var result=FinanceOrderCosts.calculate(Map.of("platform","TIKTOK_SHOP","status","COMPLETED","record_date","2026-08-10"),
            Map.of("line_items",List.of(tk("line","（YUXI-BDiaper-NB）*2"))),context);
        assertNull(result.get("amountCny"));assertEquals("INCOMPLETE",result.get("status"));
        String issue=text(rows(result.get("lines")).getFirst().get("issue"));
        assertTrue(issue.contains("海外仓-马来"));assertTrue(issue.contains("YUXI-BDiaper-NB"));assertTrue(issue.contains("不能自动使用其他国家成本"));
    }
    @Test void manualConversionsAreScopedToShopAndGroup(){
        var costs=List.of(cost("PACK","海外仓-菲律宾","2026-10","7"),cost("OTHER","海外仓-菲律宾","2026-10","5"));
        var key="shop:product:sku:海外仓-菲律宾";
        var mapping=Map.<String,Object>of(key,Map.of("code","OTHER","factor",3,"groupId","group"));
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("a","PACK"))),context(costs,Map.of(),mapping));
        amountEquals("15",result.get("amountCny"));
        mapping=Map.of(key,Map.of("code","OTHER","factor",3,"groupId","other-group"));
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(tk("a","PACK"))),context(costs,Map.of(),mapping));
        amountEquals("7",result.get("amountCny"));
    }
    @Test void manualPriceCannotSwitchToAnotherWarehouseAfterDeletionOrExcelChanges(){
        var manual=Map.<String,Object>of("sku","PACK","warehouse","海外仓-菲律宾","manualId","manual","unitPriceCny",new BigDecimal("3"),"unit","包");
        var foreign=cost("PACK","海外仓-泰国","2026-10","100");
        var mapping=Map.<String,Object>of("shop:product:sku:海外仓-菲律宾",Map.of("code","PACK","warehouse","海外仓-菲律宾","factor",2,"groupId","group","manualCostId","manual"));
        var raw=Map.<String,Object>of("line_items",List.of(tk("a","PACK")));
        var result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context(List.of(manual,foreign),Map.of(),mapping));
        amountEquals("6",result.get("amountCny"));assertEquals("海外仓-菲律宾",result.get("warehouse"));
        result=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context(List.of(foreign),Map.of(),mapping));
        assertEquals("INCOMPLETE",result.get("status"));assertNull(result.get("amountCny"));
        assertTrue(text(rows(result.get("lines")).getFirst().get("issue")).contains("手动成本已删除"));
    }
    @Test void cancelledOrdersAreExcludedRegardlessOfShipmentWhileCompletedOrdersKeepTheirCost(){
        var context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","7")),Map.of(),Map.of());
        var raw=Map.<String,Object>of("line_items",List.of(tk("a","PACK")));
        assertEquals("EXCLUDED",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","CANCELLED"),raw,context).get("status"));
        amountEquals("7",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
        raw=Map.of("line_items",List.of(tk("a","PACK")),"collection_time",1792551040L);
        assertEquals("EXCLUDED",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","CANCELLED"),raw,context).get("status"));
        var cancelled=tk("a","PACK");cancelled.put("status","CANCELLED");
        raw=Map.of("line_items",List.of(cancelled),"collection_time",1792551040L);
        amountEquals("0",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","CANCELLED"),raw,context).get("amountCny"));
        for(String platform:List.of("TIKTOK_SHOP","SHOPEE"))for(String status:List.of("CANCELLED","CANCELED","UNPAID")){
            var excluded=FinanceOrderCosts.calculate(order(platform,status),raw,context(List.of(),Map.of(),Map.of()));
            assertEquals("EXCLUDED",excluded.get("status"));amountEquals("0",excluded.get("knownAmountCny"));
        }
        var zeroPaid=new LinkedHashMap<>(order("TIKTOK_SHOP","COMPLETED"));zeroPaid.put("amount",BigDecimal.ZERO);
        amountEquals("7",FinanceOrderCosts.calculate(zeroPaid,Map.of("line_items",List.of(tk("a","PACK"))),context).get("amountCny"));
    }
    @Test void zeroPriceAndMissingShipmentDateRemainIncomplete(){
        var context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","0")),Map.of(),Map.of());
        var raw=Map.<String,Object>of("line_items",List.of(tk("a","PACK")));
        assertNull(FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
        context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","7")),Map.of("rules",Map.of("basis","shipment")),Map.of());
        assertNull(FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
    }
    @Test void tiktokPartialCancellationUsesLineDisplayStatusNotOtherItemsCollection(){
        var context=context(List.of(cost("PACK","海外仓-菲律宾","2026-10","7")),Map.of(),Map.of());
        var cancelled=tk("cancelled","PACK");cancelled.put("display_status","CANCELLED");cancelled.put("package_status","TO_FULFILL");
        var raw=Map.<String,Object>of("line_items",List.of(tk("valid","PACK"),cancelled),"collection_time",1792551040L);
        amountEquals("7",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
        cancelled.put("package_status","CANCELLED");
        amountEquals("7",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
        cancelled.put("collection_time",1792551040L);
        amountEquals("7",FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),raw,context).get("amountCny"));
        var excluded=FinanceOrderCosts.calculate(order("TIKTOK_SHOP","COMPLETED"),Map.of("line_items",List.of(cancelled)),context);
        assertEquals("EXCLUDED",excluded.get("status"));amountEquals("0",excluded.get("amountCny"));
    }
}
