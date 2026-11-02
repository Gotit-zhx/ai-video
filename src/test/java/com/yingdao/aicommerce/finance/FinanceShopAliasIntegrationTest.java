package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceShopAliasIntegrationTest extends FinanceQianyiIntegrationTest {
    @Test void aliasIsSharedPreservedByAuthorizationUsedInExportsAndCanBeCleared() throws Exception {
        qianyi();integration.save(owner,shop,form());page(FinanceQianyiEvidenceTest.receipt(),false);
        String original=text(service.ownedShop(owner,shop).get("name"));
        service.configure(owner,shop,true,"PHP","Asia/Manila","","  母婴菲律宾一店  ");
        var colleague=new Scope(owner.organizationId(),"colleague");
        assertEquals("母婴菲律宾一店",service.ownedShop(colleague,shop).get("display_name"));
        assertEquals(original,service.ownedShop(colleague,shop).get("name"));
        auth.persist(owner,com.yingdao.aicommerce.shop.domain.MarketplacePlatform.SHOPEE,grant("10001"));
        assertEquals("母婴菲律宾一店",service.ownedShop(owner,shop).get("display_name"));
        var controller=new FinanceController(service,repo,null,null);
        assertEquals("母婴菲律宾一店",controller.exportShopNames(colleague).get(shop));
        var result=new FinanceQianyiReturns(repo,service).list(colleague,Map.of());
        assertEquals("母婴菲律宾一店",rows(result.get("items")).getFirst().get("shop_name"));
        var bytes=new FinanceQianyiReturns(repo,service).export(colleague,Map.of());
        String sheet="";
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())
                if(entry.getName().equals("xl/worksheets/sheet2.xml"))sheet=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(sheet.contains("母婴菲律宾一店"));
        assertThrows(RuntimeException.class,()->service.configure(other,shop,true,"PHP","Asia/Manila","","外部"));
        assertThrows(RuntimeException.class,()->service.configure(owner,shop,true,"PHP","Asia/Manila","","x".repeat(101)));
        service.configure(owner,shop,true,"PHP","Asia/Manila","",(String)null);
        assertEquals("母婴菲律宾一店",service.ownedShop(owner,shop).get("display_name"));
        service.configure(colleague,shop,true,"PHP","Asia/Manila",""," ");
        assertNull(service.ownedShop(owner,shop).get("display_name"));
        assertEquals(service.ownedShop(owner,shop).get("name"),controller.exportShopNames(colleague).get(shop));
        var columns=FinanceController.exportColumns(List.of(new FinanceFields.Field("shop_id","店铺ID","text","",false,"*","")));
        assertEquals(List.of("shop_name","shop_id"),columns.stream().map(FinanceFields.Field::id).toList());
    }
}
