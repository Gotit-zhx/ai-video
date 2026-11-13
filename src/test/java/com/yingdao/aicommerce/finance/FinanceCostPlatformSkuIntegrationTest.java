package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.shop.application.MarketplaceAccess;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real cost imports and workers; inherited guard permits only disposable MySQL schemas. */
class FinanceCostPlatformSkuIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final String SPU="9007199254740993123",VARIANT="9007199254740993124";
    Entry purchase(String shop,String platform,String order,String seller,String variant,int quantity){
        Map<String,Object> item=platform.equals("SHOPEE")?
            Map.of("item_id",SPU,"model_id",variant,"model_sku",seller,"item_sku","","model_quantity_purchased",quantity):
            Map.of("id","line-"+order,"product_id",SPU,"sku_id",variant,"seller_sku",seller,"quantity",quantity);
        return new Entry(shop,platform,"ORDER",order,order,"","订单","COMPLETED",filter.start(),"PHP",new BigDecimal("100"),null,null,null,null,"API",
            Map.of(platform.equals("SHOPEE")?"item_list":"line_items",List.of(item)));
    }
    void calculateQueued(){
        var worker=new FinanceOrderCosts(repo);var transactions=new TransactionTemplate(tx);
        for(int i=0;i<4;i++)transactions.execute(status->worker.seedAndFill());
        transactions.execute(status->worker.processBatch(100));
    }
    @Test void mixedMerchantAndPlatformSkuImportFlowsThroughBothPlatformsAndKeepsCompanyIsolation(){
        String group=service.saveGroup(owner,null,"混合型号"),shopee=groupedShop(owner,"spu-shopee",group);
        var remote=new MarketplaceAccess.RemoteShop("spu-tiktok",null,"synthetic-shop-cipher","LOCAL","测试 TK 店","PH","SHOP","spu-tiktok");
        auth.persistGrant(owner,MarketplacePlatform.TIKTOK_SHOP,new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(remote)),group,"");
        String tiktok=db.queryForObject("SELECT id FROM finance_connections WHERE organization_id='org' AND external_shop_id='spu-tiktok'",String.class);
        var workspace=new FinanceCostWorkspace(repo,service);var transactions=new TransactionTemplate(tx);
        transactions.execute(status->workspace.importCosts(owner,"u",group,("仓库,型号,202610\n菲律宾,PACK,7\n菲律宾,"+VARIANT+",11\n菲律宾,"+SPU+",99\n").getBytes(StandardCharsets.UTF_8),"mixed.csv"));
        var entries=new ArrayList<Entry>();
        for(String platform:List.of("SHOPEE","TIKTOK_SHOP")){
            String shop=platform.equals("SHOPEE")?shopee:tiktok;
            var sku=purchase(shop,platform,"sku-"+platform,"PACK","sku-variant",2);
            var spu=purchase(shop,platform,"spu-"+platform,"",VARIANT,3);entries.add(sku);entries.add(spu);
            for(var entry:List.of(sku,spu)){
                repo.save(owner,entry);
                repo.save(owner,new Entry(shop,platform,"SETTLEMENT","settled-"+entry.orderId(),entry.orderId(),"bill",platform.equals("TIKTOK_SHOP")?"ORDER":"结算","SETTLED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",Map.of()));
            }
        }
        String foreignGroup=service.saveGroup(other,null,"其他公司"),foreign=groupedShop(other,"foreign-spu",foreignGroup);
        transactions.execute(status->workspace.importCosts(other,"other",foreignGroup,("仓库,型号,202610\n菲律宾,"+VARIANT+",100\n").getBytes(StandardCharsets.UTF_8),"foreign.csv"));
        var foreignOrder=purchase(foreign,"SHOPEE","foreign","",VARIANT,1);repo.save(other,foreignOrder);
        calculateQueued();
        for(var entry:entries){
            var snapshot=new FinanceOrderCosts(repo).decode(db.queryForObject("SELECT data_json FROM finance_order_costs WHERE organization_id=? AND order_record_id=?",String.class,owner.organizationId(),id(owner,entry)));
            assertEquals("READY",snapshot.get("status"));
            assertEquals(0,new BigDecimal(entry.orderId().startsWith("sku-")?"14":"33").compareTo(decimal(snapshot.get("amountCny"))));
            assertEquals(entry.orderId().startsWith("sku-")?"SKU":"PLATFORM_SKU",rows(snapshot.get("lines")).getFirst().get("matchedBy"));
        }
        assertEquals(0,new BigDecimal("100").compareTo(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE organization_id=? AND order_record_id=?",BigDecimal.class,other.organizationId(),id(other,foreignOrder))));
        for(String shop:List.of(shopee,tiktok)){
            var result=new FinanceCostReport(repo).orders(owner,new Filter(filter.start(),filter.end(),shop,"","","day",group),"SETTLED_ORDER","",1);
            assertEquals(2,number(result.get("total")),"both settled orders remain visible for "+shop);
            for(var row:rows(result.get("items"))){assertEquals(List.of(SPU),row.get("product_spu_values"));assertEquals(List.of(text(row.get("order_id")).startsWith("sku-")?"sku-variant":VARIANT),row.get("platform_sku_values"));}
            assertEquals(0,new BigDecimal("47").compareTo(decimal(at(result,"costAmountSummary.amount_cny"))));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class));
    }
    @Test void automaticRelationshipsUseMerchantThenPlatformSkuPerWarehouseAndPreserveManualChoices(){
        String group=service.saveGroup(owner,null,"逐仓匹配"),shop=groupedShop(owner,"spu-mapping",group);
        var workspace=new FinanceCostWorkspace(repo,service);var transactions=new TransactionTemplate(tx);
        transactions.execute(status->workspace.importCosts(owner,"u",group,("仓库,型号,202610\n菲律宾,PACK,7\n菲律宾,"+SPU+",9\n泰国,"+VARIANT+",11\n").getBytes(StandardCharsets.UTF_8),"mixed.csv"));
        repo.save(owner,purchase(shop,"SHOPEE","auto-order","PACK",VARIANT,1));
        repo.save(owner,purchase(shop,"SHOPEE","manual-order","PACK","manual",1));
        transactions.executeWithoutResult(status->workspace.mapping(owner,"u",shop,Map.of("product_id",SPU,"sku_id","manual","warehouse","菲律宾","code",SPU,"factor",3,"confirmed",true,"version",0)));
        var result=transactions.execute(status->workspace.autoMatch(owner,"u",shop,Map.of()));var mappings=map(result.get("mappings"));
        assertEquals("PACK",at(mappings,shop+":"+SPU+":"+VARIANT+":菲律宾.code"));
        var fallback=map(mappings.get(shop+":"+SPU+":"+VARIANT+":泰国"));assertEquals(VARIANT,fallback.get("code"));assertEquals("PLATFORM_SKU_CODE",fallback.get("method"));
        var manual=map(mappings.get(shop+":"+SPU+":manual:菲律宾"));assertEquals(SPU,manual.get("code"));assertEquals(3,number(manual.get("factor")));assertFalse(manual.containsKey("method"));
        assertThrows(RuntimeException.class,()->transactions.execute(status->workspace.autoMatch(other,"other",shop,Map.of())));
    }
    @Test void unchangedOrderSyncQueuesAnOldSpuPolicyResultForPlatformSkuRecalculation(){
        String group=service.saveGroup(owner,null,"旧结果升级"),shop=groupedShop(owner,"spu-upgrade",group);
        var workspace=new FinanceCostWorkspace(repo,service);var transactions=new TransactionTemplate(tx);
        transactions.execute(status->workspace.importCosts(owner,"u",group,("仓库,型号,202610\n菲律宾,"+VARIANT+",11\n菲律宾,"+SPU+",99\n").getBytes(StandardCharsets.UTF_8),"spu.csv"));
        var entry=purchase(shop,"SHOPEE","historical","",VARIANT,2);repo.save(owner,entry);calculateQueued();
        String legacyHash=hash("sku-spu-standard-v1:cancelled-excluded-v2:"+repo.write(FinanceOrderCosts.canonical(entry.raw()))+entry.status());
        db.update("UPDATE finance_order_costs SET source_hash=?,status='INCOMPLETE',amount_cny=NULL,known_amount_cny=0,data_json=? WHERE organization_id=? AND order_record_id=?",
            legacyHash,"{\"status\":\"INCOMPLETE\",\"lines\":[]}",owner.organizationId(),id(owner,entry));
        repo.save(owner,entry);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs WHERE order_record_id=?",Integer.class,id(owner,entry)));
        transactions.execute(status->new FinanceOrderCosts(repo).processBatch(100));
        assertEquals(0,new BigDecimal("22").compareTo(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,id(owner,entry))));
        assertEquals("",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.item_list[0].model_sku')) FROM finance_records WHERE id=?",String.class,id(owner,entry)));
    }
}
