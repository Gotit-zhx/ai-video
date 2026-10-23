package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.web.ShopAuthorizationController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only against a disposable, empty MySQL schema provisioned by the release QA runner. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceConfigurationIntegrationTest {
    JdbcTemplate db;FinanceRepository repo;DataSourceTransactionManager tx;FinanceShopAuthorization auth;
    com.zaxxer.hikari.HikariDataSource dataSource;
    MarketplaceAccess.Client client;FinanceService service;FinanceFx fx;
    Scope owner=new Scope("org","u"),other=new Scope("other-org","other");
    final ShopCredentialCipher cipher=new ShopCredentialCipher("finance-test-key-not-a-production-secret-12345");
    final Filter filter=new Filter(LocalDate.of(2026,10,21),LocalDate.of(2026,10,21),"","","PHP","day");
    PlatformPrincipal principal(){return new PlatformPrincipal("u","org","heyi","admin","admin","",true,List.of("ADMIN"),List.of());}
    @BeforeAll void migrate()throws Exception{
        assertTrue(System.getenv("FINANCE_QA_JDBC").matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"),"Only disposable finance_qa schemas are permitted");
        var ds=new com.zaxxer.hikari.HikariDataSource();dataSource=ds;
        ds.setJdbcUrl(System.getenv("FINANCE_QA_JDBC"));ds.setUsername(System.getenv("FINANCE_QA_USER"));ds.setPassword(System.getenv("FINANCE_QA_PASSWORD"));
        ds.setMaximumPoolSize(6);ds.setMinimumIdle(1);
        db=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);repo=new FinanceRepository(db,new ObjectMapper());
        db.execute("CREATE TABLE shops(id CHAR(36) PRIMARY KEY,marker VARCHAR(40))");
        db.execute("CREATE TABLE shop_credentials(id VARCHAR(36),secret VARCHAR(100))");
        db.update("INSERT INTO shops VALUES('legacy','commerce-unchanged')");db.update("INSERT INTO shop_credentials VALUES('legacy','commerce-secret-unchanged')");
        migration("V117__finance_bi.sql");
        db.update("INSERT INTO finance_shops(organization_id,user_id,shop_id,enabled,currency,timezone) VALUES('org','u','legacy',TRUE,'PHP','Asia/Manila')");
        migration("V118__finance_independent_configuration.sql");
        migration("V119__finance_ad_authorization.sql");
        migration("V120__finance_dashboard_covering_index.sql");
        migration("V121__finance_live_order_lane.sql");
        migration("V122__finance_reconciliation_indexes.sql");
        migration("V124__finance_sync_claim_token.sql");
        db.execute("CREATE TABLE permissions(id CHAR(36),code VARCHAR(80) PRIMARY KEY,name VARCHAR(120),module VARCHAR(40),resource_type VARCHAR(20),description VARCHAR(500),sort_order INT)");
        migration("V125__finance_company_groups.sql");
        migration("V127__finance_cost_workspace.sql");
        migration("V128__finance_order_standard_costs.sql");
        assertEquals(0,db.queryForObject("SELECT enabled FROM finance_shops WHERE shop_id='legacy'",Integer.class));
        assertEquals("commerce-unchanged",db.queryForObject("SELECT marker FROM shops WHERE id='legacy'",String.class));
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
    }
    @AfterAll void closeDataSource(){if(dataSource!=null)dataSource.close();}
    void migration(String name)throws Exception{
        String sql=Files.readString(Path.of("src/main/resources/db/migration/"+name)).replaceAll("(?m)^--.*$","");
        for(String statement:sql.split(";"))if(!statement.isBlank())db.execute(statement);
    }
    @BeforeEach void setup(){
        for(String table:List.of("finance_order_cost_jobs","finance_order_costs","finance_cost_rebuilds","finance_workspace_audit","finance_expense_rules","finance_expenses","finance_cost_mappings","finance_cost_settings","finance_cost_books","finance_groups","finance_imports","finance_connections","finance_authorization_sessions","finance_records","finance_sync_states","finance_view_preferences","finance_ad_accounts","finance_ad_applications"))db.update("DELETE FROM "+table);
        client=mock(MarketplaceAccess.Client.class);when(client.platform()).thenReturn(MarketplacePlatform.SHOPEE);when(client.configured()).thenReturn(true);
        var settings=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        auth=new FinanceShopAuthorization(repo,new MarketplaceClientRegistry(List.of(client)),cipher,settings,tx);
        var policy=mock(OrganizationAccessPolicy.class);when(policy.isPlatformAdmin(any())).thenReturn(true);
        fx=spy(new FinanceFx(repo,new ObjectMapper()));doReturn(new FinanceFx.Quote("fixed",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125")))).when(fx).quote(any());
        service=FinanceTest.classProxy(new FinanceService(FinanceTest.classProxy(repo),policy,FinanceTest.classProxy(auth),fx));
    }
    MarketplaceAccess.TokenSet tokens(boolean expired){return new MarketplaceAccess.TokenSet("finance-access","finance-refresh",Instant.now().plusSeconds(expired?-1:3600),Instant.now().plusSeconds(86400),List.of("order","finance"));}
    String connect(Scope s,boolean expired){
        auth.persist(s,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(expired),List.of(new MarketplaceAccess.RemoteShop("10001",null,null,"LOCAL","测试财务店铺","PH","SHOP","10001"))));
        return text(service.shops(s).getFirst().get("id"));
    }
    void record(Scope s,String shop,String kind,String external,BigDecimal amount,Map<String,Object> raw){repo.save(s,new Entry(shop,"SHOPEE",kind,external,"order1","bill1","测试","SETTLED",filter.start(),"PHP",amount,null,null,null,null,"API",raw));}
    MarketplaceAccess.AuthorizationGrant grant(String external){
        return new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(new MarketplaceAccess.RemoteShop(external,null,null,"LOCAL","Shop "+external,"PH","SHOP",external)));
    }
    String groupedShop(Scope s,String external,String group){
        auth.persistGrant(s,MarketplacePlatform.SHOPEE,grant(external),group,"");
        return db.queryForObject("SELECT id FROM finance_connections WHERE organization_id=? AND external_shop_id=?",String.class,s.organizationId(),external);
    }
    Filter grouped(String id){return new Filter(filter.start(),filter.end(),"","","PHP","day",id);}
    @Test void merchantSkuColumnsFollowOrdersAndBillsWithoutCrossingShopOrCompany(){
        String group=service.saveGroup(owner,null,"SKU 列测试"),shop=groupedShop(owner,"sku-column-shop",group),sibling=groupedShop(owner,"sku-column-sibling",group);
        var order=new Entry(shop,"TIKTOK_SHOP","ORDER","shared-order","shared-order","","订单","COMPLETED",filter.start().minusDays(20),"PHP",BigDecimal.TEN,null,null,null,null,"API",
                Map.of("line_items",List.of(Map.of("seller_sku","SKU-A"),Map.of("seller_sku","（SKU-B）*2"),Map.of("seller_sku","SKU-A"),Map.of("sku_id","not-a-merchant-code")),"buyer_email","never-expose"));
        repo.save(owner,order);
        repo.save(other,order);
        db.update("UPDATE finance_records SET raw_json=? WHERE organization_id=?",repo.write(Map.of("line_items",List.of(Map.of("seller_sku","OTHER-COMPANY")))),other.organizationId());
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","ORDER","shared-order","shared-order","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"IMPORT",Map.of("line_items",List.of(Map.of("seller_sku","IMPORTED-COPY")))));
        var shopee=new Entry(sibling,"SHOPEE","ORDER","shared-order","shared-order","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",
                Map.of("item_list",List.of(Map.of("model_sku","  ","item_sku","SHOPEE-FALLBACK"),Map.of("model_sku","SHOPEE-VARIANT","item_sku","not-the-variant"))));
        repo.save(owner,shopee);
        for(String kind:List.of("REFUND","SETTLEMENT","PENDING","FEE","COST"))
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP",kind,"sku-"+kind,"shared-order","sku-bill","测试","SETTLED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","second-tx","shared-order","sku-bill","测试","SETTLED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","BILL","sku-bill","","sku-bill","测试","SETTLED",filter.start(),"PHP",new BigDecimal("2"),null,null,null,null,"API",Map.of()));
        var selected=new Filter(filter.start(),filter.end(),shop,"","","day");
        for(String kind:List.of("REFUND","SETTLEMENT","PENDING","FEE","COST","BILL")){
            var records=rows(repo.details(new Scope("org","colleague"),selected,kind,1,50).get("items"));
            assertFalse(records.isEmpty());
            for(var row:records)assertEquals(List.of("SKU-A","（SKU-B）*2"),row.get("product_sku_values"),kind);
            assertFalse(repo.write(records).contains("never-expose"));
        }
        assertEquals("SKU-A\n（SKU-B）*2",repo.reconciliation(owner,selected).getFirst().get("product_skus"));
        var drill=new FinanceCostReport(repo).orders(owner,selected,"BILL","",1);
        assertEquals(1,number(drill.get("total")));
        assertEquals("SKU-A\n（SKU-B）*2",rows(drill.get("items")).getFirst().get("product_skus"),"bill drill-down includes orders created outside the selected bill dates");
        assertEquals(0,number(new FinanceCostReport(repo).orders(other,selected,"BILL","",1).get("total")));
        assertEquals(1,number(new FinanceCostReport(repo).orders(owner,selected,"ORDER",id(owner,order),1).get("total")),"direct order links ignore the current calendar");
        assertEquals(0,number(new FinanceCostReport(repo).orders(other,selected,"ORDER",id(owner,order),1).get("total")),"record links cannot cross company boundaries");
        assertEquals("SHOPEE-FALLBACK\nSHOPEE-VARIANT",repo.detail(owner,id(owner,shopee)).get("product_skus"));
        var direct=repo.detail(owner,id(owner,order));
        assertEquals("SKU-A\n（SKU-B）*2",direct.get("product_skus"));
        var expenses=new ArrayList<Map<String,Object>>(List.of(new LinkedHashMap<>(Map.of("scope","order","target",shop,"order","shared-order")),
                new LinkedHashMap<>(Map.of("scope","shop","target",shop,"order",""))));
        new FinanceProductSkus(repo).attachExpenses(owner,expenses);
        assertEquals(direct.get("product_skus"),expenses.getFirst().get("product_skus"));assertEquals("",expenses.getLast().get("product_skus"));
        var fields=new FinanceFields(repo);
        db.update("INSERT INTO finance_view_preferences(organization_id,user_id,view_key,preferences_json) VALUES(?,?,?,?)",
                owner.organizationId(),owner.userId(),"ORDER",repo.write(Map.of("visible",List.of("order_id","amount"),"exported",List.of("amount"))));
        assertEquals(List.of("order_id","product_skus","amount"),fields.get(owner,"ORDER").visible());
        assertTrue(fields.get(owner,"ORDER").exported().contains("product_skus"));
        fields.save(owner,"ORDER",List.of("order_id","amount"),List.of("amount"));
        assertFalse(fields.get(owner,"ORDER").visible().contains("product_skus"),"users can hide the new column after upgrading");
        assertTrue(fields.get(owner,"BILL").exported().contains("product_skus"));
    }
    @Test void standardCostsAreIncrementalCompanyScopedAndDeduplicateSettlementOrders()throws Exception{
        String group=service.saveGroup(owner,null,"标准成本"),shop=groupedShop(owner,"standard-cost-shop",group);
        var workspace=new FinanceCostWorkspace(repo,service);var txs=new TransactionTemplate(tx);var engine=new FinanceOrderCosts(repo);
        txs.executeWithoutResult(status->workspace.importCosts(owner,"u",group,"仓库,型号,202610\n海外仓-菲律宾,PACK,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"unit.csv"));
        var line=Map.<String,Object>of("item_id","p","model_id","v","model_sku","(PACK)*2","model_quantity_purchased",3);
        var entry=new Entry(shop,"SHOPEE","ORDER","order-a","order-a","","订单","COMPLETED",filter.start(),"PHP",new BigDecimal("100"),null,null,null,null,"API",Map.of("item_list",List.of(line),"buyer_username","do-not-expose"));
        repo.save(owner,entry);
        repo.save(owner,new Entry(shop,"SHOPEE","ORDER","order-b","order-b","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",Map.of("item_list",List.of(Map.of("item_id","p2","model_id","v2","model_sku","MISSING","model_quantity_purchased",1)))));
        txs.execute(status->engine.seedAndFill());txs.execute(status->engine.processBatch(50));
        var q=new Filter(filter.start(),filter.end(),shop,"","","day",group);var reports=new FinanceCostReport(repo);
        var totals=map(reports.summary(new Scope("org","colleague"),q,"ORDER").get("total"));
        assertEquals(0,number(totals.get("order_count")));assertEquals(0,number(totals.get("pending_count")),"unsettled orders are not missing settled costs");
        assertEquals(0,decimal(totals.get("known_amount_cny")).signum());
        var detail=repo.detail(owner,id(owner,entry));assertEquals("未结算，不计入",detail.get("product_cost_state"));
        assertFalse(repo.write(detail).contains("do-not-expose"));
        assertNull(detail.get("product_cost_cny"));assertFalse(detail.containsKey("productCost"));
        repo.save(owner,entry);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class),"unchanged sync does not recompute costs");
        for(int i=1;i<=2;i++){
            repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","tx-"+i,"order-a","bill-"+i,"结算","SETTLED",filter.start(),"PHP",new BigDecimal("45"),null,null,null,null,"API",Map.of()));
            repo.save(owner,new Entry(shop,"SHOPEE","BILL","bill-"+i,"","bill-"+i,"账单","SETTLED",filter.start(),"PHP",new BigDecimal("45"),null,null,null,null,"API",Map.of()));
            repo.save(owner,new Entry(shop,"SHOPEE","REFUND","refund-"+i,"order-a","","退款","ACCEPTED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        }
        for(String kind:List.of("SETTLEMENT","BILL","REFUND","RECONCILIATION")){
            var summary=map(reports.summary(owner,q,kind).get("total"));assertEquals(1,number(summary.get("order_count")),kind+" must deduplicate the same order");
            assertEquals(0,new BigDecimal("60").compareTo(decimal(summary.get("amount_cny"))));
            var drill=reports.orders(owner,q,kind,"",1);assertEquals(1,number(drill.get("total")));
            assertEquals(0,new BigDecimal("60").compareTo(decimal(rows(drill.get("items")).getFirst().get("product_cost_cny"))));
            assertEquals(summary,map(map(drill.get("productCosts")).get("total")));
        }
        var settledTotals=map(reports.summary(owner,q,"SETTLED_ORDER").get("total"));
        assertEquals(1,number(settledTotals.get("order_count")));assertEquals(0,number(settledTotals.get("pending_count")));
        assertEquals(0,new BigDecimal("60").compareTo(decimal(settledTotals.get("amount_cny"))));
        var txRows=rows(repo.details(owner,q,"SETTLEMENT",1,50).get("items"));
        assertEquals(1,txRows.stream().filter(r->r.get("product_cost_cny")!=null).count(),"multiple settlement rows recognize cost only once");
        assertEquals(0,number(map(reports.summary(owner,q,"PENDING").get("total")).get("order_count")));
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","later-adjustment","order-a","later-bill","调整","SETTLED",filter.start().plusDays(1),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        var later=new Filter(filter.start().plusDays(1),filter.end().plusDays(1),shop,"","","day",group);
        assertEquals(0,number(map(reports.summary(owner,later,"SETTLED_ORDER").get("total")).get("order_count")),"later adjustments must not recognize the entire order cost again");
        assertEquals(0,number(reports.orders(owner,later,"SETTLED_ORDER","",1).get("total")));
        var exported=new FinanceCostExport(repo).export(owner,q,"SETTLED_ORDER","");
        assertTrue(exported.length>1000);
        assertEquals(2,FinanceCostExportTest.sheet(exported,2).getElementsByTagName("row").getLength());
        assertEquals(0,number(map(reports.summary(other,q,"ORDER").get("total")).get("order_count")));
        var rec=repo.reconciliation(owner,q);assertEquals(2,rec.size());assertTrue(rec.stream().allMatch(r->"MATCHED".equals(r.get("result"))),"costs never change platform reconciliation");
        var dashboard=service.dashboard(owner,q,true,"fixed");assertEquals(0,new BigDecimal("60").compareTo(decimal(at(dashboard,"productCosts.total.known_amount_cny"))),"yuan costs are not multiplied by the PHP conversion rate");
        txs.executeWithoutResult(status->workspace.mapping(owner,"u",shop,Map.of("product_id","p","sku_id","v","warehouse","海外仓-菲律宾","code","PACK","factor",1,"confirmed",true,"version",0)));
        txs.execute(status->engine.seedAndFill());txs.execute(status->engine.processBatch(50));
        assertEquals(0,new BigDecimal("30").compareTo(decimal(repo.detail(owner,id(owner,entry)).get("product_cost_cny"))));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class),"standard cost snapshots stay separate from external expense ledger");
        assertEquals(0,new BigDecimal("91").compareTo(db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='SETTLEMENT'",BigDecimal.class)));
        String newGroup=service.saveGroup(owner,null,"未导入成本");
        txs.executeWithoutResult(status->service.configure(owner,shop,true,"PHP","Asia/Manila",newGroup));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        txs.execute(status->engine.seedAndFill());txs.execute(status->engine.processBatch(50));
        assertNull(repo.detail(owner,id(owner,entry)).get("product_cost_cny"),"changing group must not keep the previous group's cost");
    }
    @Test void costWorkspacePreservesVersionsAndScopesSkuByPlatformIdentity(){
        String group=service.saveGroup(owner,null,"成本测试"),shop=groupedShop(owner,"cost-shop",group);
        var workspace=new FinanceCostWorkspace(repo,service);var transactions=new TransactionTemplate(tx);
        String emptyGroup=service.saveGroup(owner,null,"无成本资料"),emptyShop=groupedShop(owner,"00-empty-shop",emptyGroup);
        assertEquals(emptyShop,service.shops(owner).getFirst().get("id"),"fixture starts with a shop without costs");
        assertEquals("",workspace.costs(other,null).get("shopId"),"companies without shops receive an empty workspace");
        byte[] file=("仓库,型号,20269,202610\n菲律宾仓,PACK,2.50,3.25\n菲律宾仓,PACK*5,13,16\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var imported=transactions.execute(status->workspace.importCosts(owner,"u",group,file,"cost.csv"));
        assertEquals(2,imported.get("rows"));
        var preferred=workspace.costs(new Scope("org","colleague"),null);
        assertEquals(shop,preferred.get("shopId"),"default selection opens a shop with an imported cost book");
        assertEquals("CNY",preferred.get("currency"),"cost files are yuan regardless of shop settlement currency");
        assertEquals(2,number(map(map(preferred.get("shopCatalogs")).get(shop)).get("costRows")));
        assertEquals(0,number(map(map(preferred.get("shopCatalogs")).get(emptyShop)).get("costRows")));
        assertEquals(emptyShop,workspace.costs(owner,emptyShop).get("shopId"),"explicit shop selection is respected even without costs");
        assertEquals(true,transactions.execute(status->workspace.importCosts(new Scope("org","colleague"),"colleague",group,file,"cost.csv")).get("duplicate"));
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.importCosts(other,"x",group,file,"cost.csv")));
        record(owner,shop,"ORDER","sku-order",BigDecimal.TEN,Map.of("item_list",List.of(
                Map.of("item_id","product1","model_id","variant1","model_sku","PACK","model_name","50 PCS","item_name","Diapers","image_info",Map.of("image_url","https://example.test/image.jpg")),
                Map.of("item_id","product2","model_id","variant2","model_sku","PACK","model_name","50 PCS","item_name","Diapers")),"buyer_username","never-return"));
        var current=workspace.costs(owner,shop);assertEquals(2,rows(current.get("products")).size());assertFalse(repo.write(current).contains("never-return"));
        assertEquals(0,new BigDecimal("2.50").compareTo(decimal(map(rows(current.get("costs")).getFirst().get("prices")).get("2026-09"))));
        var mapping=new LinkedHashMap<String,Object>(Map.of("product_id","product1","sku_id","variant1","warehouse","菲律宾仓","code","PACK","factor",1,"confirmed",true,"version",0));
        transactions.executeWithoutResult(status->workspace.mapping(owner,"u",shop,mapping));
        assertEquals(1,map(workspace.costs(new Scope("org","colleague"),shop).get("mappings")).size());
        String sibling=groupedShop(owner,"sibling-cost-shop",group);
        record(owner,sibling,"ORDER","sibling-order",BigDecimal.ONE,Map.of("item_list",List.of(Map.of("item_id","product1","model_id","variant1","model_sku","PACK","model_name","50 PCS"))));
        var siblingData=workspace.costs(owner,sibling);
        assertEquals(1,rows(siblingData.get("products")).size());
        assertTrue(map(siblingData.get("mappings")).isEmpty(),"same product and SKU identities in another shop never reuse a mapping");
        var auto=transactions.execute(status->workspace.autoMatch(owner,"u",shop,Map.of()));
        var associations=map(auto.get("mappings"));assertEquals(2,associations.size());
        assertEquals(1,number(map(associations.get(shop+":product1:variant1:菲律宾仓")).get("factor")),"automatic matching preserves an existing manual override");
        var automatic=map(associations.get(shop+":product2:variant2:菲律宾仓"));
        assertEquals("EXACT_CODE",automatic.get("method"));assertNull(automatic.get("factor"),"matching a code must not invent the price unit conversion");
        assertTrue(map(workspace.costs(owner,sibling).get("mappings")).isEmpty(),"automatic matching never writes another shop");
        transactions.executeWithoutResult(status->workspace.autoMatch(owner,"u",shop,Map.of()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_AUTO_MATCH'",Integer.class),"a repeated run is idempotent");
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.autoMatch(other,"x",shop,Map.of())));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class),"linking SKUs must not generate financial costs");
        record(owner,shop,"ORDER","bundle-order",BigDecimal.TEN,Map.of("item_list",List.of(
                Map.of("item_id","product3","model_id","bundle1","model_sku","(PACK) *5","model_name","5 Packs"),
                Map.of("item_id","product3","model_id","bundle2","model_sku","（PACK） × 5","model_name","5 Packs"),
                Map.of("item_id","product3","model_id","bundle3","model_sku","(PACK)*3","model_name","3 Packs"))));
        var bundles=map(transactions.execute(status->workspace.autoMatch(owner,"u",shop,Map.of())).get("mappings"));
        assertEquals(5,bundles.size(),"formatted bundle SKUs and the unique base model are linked");
        for(String sku:List.of("bundle1","bundle2")){
            var linked=map(bundles.get(shop+":product3:"+sku+":菲律宾仓"));
            assertEquals("PACK*5",linked.get("code"),"use the independent bundle cost row");
            assertEquals("NORMALIZED_CODE",linked.get("method"));assertNull(linked.get("factor"));
        }
        var baseBundle=map(bundles.get(shop+":product3:bundle3:菲律宾仓"));
        assertEquals("PACK",baseBundle.get("code"));assertEquals("BUNDLE_CODE",baseBundle.get("method"));
        assertEquals(3,number(baseBundle.get("bundleQuantity")));assertNull(baseBundle.get("factor"),"encoded quantity is retained without activating an unconfirmed accounting conversion");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class));
        transactions.executeWithoutResult(status->workspace.autoMatch(owner,"u",shop,Map.of()));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='COST_AUTO_MATCH'",Integer.class));
        assertTrue(map(workspace.costs(owner,sibling).get("mappings")).isEmpty());
        assertThrows(BusinessException.class,()->transactions.executeWithoutResult(status->workspace.mapping(owner,"u",shop,mapping)));
        assertThrows(BusinessException.class,()->workspace.costs(other,shop));
        byte[] next="仓库,型号,202610\n菲律宾仓,PACK,4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        transactions.executeWithoutResult(status->workspace.importCosts(owner,"u",group,next,"next.csv"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_cost_books",Integer.class));
        var prices=map(rows(workspace.costs(owner,shop).get("costs")).getFirst().get("prices"));
        assertEquals(0,new BigDecimal("2.5").compareTo(decimal(prices.get("2026-09"))));assertEquals(0,new BigDecimal("4").compareTo(decimal(prices.get("2026-10"))));
    }
    @Test void expenseLedgerPostsExactlyOnceAndRetainsAdjustmentsAndCompanyBoundaries(){
        String group=service.saveGroup(owner,null,"费用测试"),shop=groupedShop(owner,"expense-shop",group);
        record(owner,shop,"ORDER","known-order",BigDecimal.TEN,Map.of());
        var workspace=new FinanceCostWorkspace(repo,service);var transactions=new TransactionTemplate(tx);
        var body=new LinkedHashMap<String,Object>(Map.of("scope","order","target",shop,"category","shipping","source","WAYBILL-1","treatment","extra","currency","PHP","order","known-order","start","2026-10-21","end","2026-10-21","recordDate","2026-10-21"));body.put("amount","12.50");body.put("note","运费");
        var saved=transactions.execute(status->workspace.expense(owner,"u",null,body));String id=text(saved.get("id"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class));
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.expense(owner,"u",null,body)));
        var edited=new LinkedHashMap<>(saved);edited.put("amount","15.75");edited.put("note","运单核对");
        transactions.executeWithoutResult(status->workspace.expense(new Scope("org","colleague"),"colleague",id,edited));
        assertEquals(0,new BigDecimal("15.75").compareTo(db.queryForObject("SELECT amount FROM finance_records WHERE kind='COST'",BigDecimal.class)));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='EXPENSE'",Integer.class));
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.expense(owner,"u",id,edited)));
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.expense(other,"x",id,edited)));
        edited.put("version",2);edited.put("treatment","settlement");
        transactions.executeWithoutResult(status->workspace.expense(owner,"u",id,edited));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class),"already included costs must not be deducted a second time");
        var q=Map.of("start","2026-10-21","end","2026-10-21","currency","PHP","group",group);
        assertEquals(1,rows(workspace.expenses(owner,q).get("rows")).size());
        assertTrue(rows(workspace.expenses(owner,Map.of("start","2026-10-21","end","2026-10-21","currency","PHP","group","UNGROUPED")).get("rows")).isEmpty());
        assertTrue(rows(workspace.expenses(other,Map.of("start","2026-10-21","end","2026-10-21","currency","PHP")).get("rows")).isEmpty());
        body.put("order","unknown-order");body.put("source","WAYBILL-2");
        assertThrows(BusinessException.class,()->transactions.execute(status->workspace.expense(owner,"u",null,body)));
        body.put("scope","group");body.put("target",group);
        var pending=transactions.execute(status->workspace.expense(owner,"u",null,body));assertEquals("待分摊",pending.get("status"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class));
    }
    @Test void companyMembersShareGroupsRecordsExportsAndPersonalColumnsStayPrivate(){
        Scope colleague=new Scope("org","colleague");
        String clothes=service.saveGroup(owner,null,"服饰"),shoes=service.saveGroup(owner,null,"鞋靴");
        String a=groupedShop(owner,"100",clothes),b=groupedShop(colleague,"200",shoes);
        service.configure(owner,a,true,"PHP","Asia/Manila",clothes);
        service.configure(owner,b,true,"PHP","Asia/Manila",shoes);
        for(String kind:KINDS){
            record(owner,a,kind,kind.equals("BILL")?"bill1":kind,BigDecimal.TEN,Map.of("create_time",1792548000L));
            record(colleague,b,kind,kind.equals("BILL")?"bill1":kind,new BigDecimal("99"),Map.of("create_time",1792548000L));
        }
        record(owner,"UNASSIGNED","AD","unassigned",BigDecimal.ONE,Map.of());
        assertEquals(2,service.shops(colleague).size());assertEquals(2,service.groups(colleague).size());
        assertEquals(9,number(repo.details(colleague,grouped(clothes),null,1,50).get("total")));
        assertEquals(9,number(repo.details(owner,grouped(shoes),null,1,50).get("total")));
        assertEquals(1,number(repo.details(owner,grouped("UNGROUPED"),null,1,50).get("total")));
        assertEquals(0,number(repo.details(other,grouped(clothes),null,1,50).get("total")));
        assertEquals("MATCHED",repo.reconciliation(colleague,grouped(clothes)).getFirst().get("result"));
        assertEquals(a,repo.recentOrders(colleague,grouped(clothes)).getFirst().get("shop_id"));
        assertEquals(a,repo.hourlyOrders(colleague,grouped(clothes)).getFirst().get("shop_id"));
        assertTrue(service.syncStates(colleague,grouped(shoes)).stream().allMatch(row->b.equals(row.get("shop_id"))));
        var dashboard=service.dashboard(companyScope("org"),grouped(clothes),false,null);
        assertTrue(rows(dashboard.get("ranking")).stream().allMatch(row->a.equals(row.get("shop_id"))));
        assertEquals(clothes,map(dashboard.get("filters")).get("groupId"));
        var screen=service.screen(companyScope("org"),grouped(shoes),false,null);
        assertTrue(rows(screen.get("recent")).stream().allMatch(row->b.equals(row.get("shop_id"))));
        var selection=Map.of("start","2026-10-21","end","2026-10-21","view","ORDER","format","csv","groupId",shoes);
        var exported=FinanceWorkbook.read(controller().export(selection,principal()).getBody(),"groups.csv");
        assertEquals(2,exported.size());assertTrue(exported.get(1).contains(b));assertFalse(exported.get(1).contains(a));
        var preferences=new FinanceFields(repo);preferences.save(owner,"ORDER",List.of("external_id"),List.of("external_id"));
        assertNotEquals(preferences.get(owner,"ORDER"),preferences.get(colleague,"ORDER"));
        var importer=new FinanceImport(service,repo);byte[] file="id,date,currency,amount\nbill2,2026-10-21,PHP,10".getBytes();
        var mapping=Map.<String,Object>of("external_id",0,"date",1,"currency",2,"amount",3);
        assertEquals(false,importer.commit(colleague,a,"BILL",mapping,file,"bill.csv").get("duplicate"));
        assertEquals(true,importer.commit(owner,a,"BILL",mapping,file,"bill.csv").get("duplicate"));
    }
    @Test void duplicateShopAcrossMembersPreservesOwnerHistoryAndGroupWhileExplicitReauthorizationMovesGroup(){
        Scope colleague=new Scope("org","colleague");
        String original=service.saveGroup(owner,null,"服饰"),otherGroup=service.saveGroup(owner,null,"家居");
        String shop=groupedShop(owner,"10001",original);service.configure(owner,shop,true,"PHP","Asia/Manila",original);
        record(owner,shop,"ORDER","kept",BigDecimal.TEN,Map.of());
        db.update("UPDATE finance_sync_states SET checkpoint_json='{\"from\":1,\"to\":2}' WHERE shop_id=?",shop);
        var repeated=auth.persistGrant(colleague,MarketplacePlatform.SHOPEE,grant("10001"),otherGroup,"");
        assertEquals(0,repeated.created());assertEquals(1,repeated.existing());assertEquals(1,service.shops(owner).size());
        assertEquals("u",db.queryForObject("SELECT user_id FROM finance_connections WHERE id=?",String.class,shop));
        assertEquals(original,service.shops(colleague).getFirst().get("group_id"));
        assertEquals(1,number(repo.details(colleague,filter,"ORDER",1,50).get("total")));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE checkpoint_json IS NOT NULL",Integer.class));
        assertEquals("finance-access",new TransactionTemplate(tx).execute(status->auth.connection(owner,shop)).tokens().accessToken());
        auth.persistGrant(colleague,MarketplacePlatform.SHOPEE,grant("10001"),otherGroup,shop);
        assertEquals(otherGroup,service.shops(owner).getFirst().get("group_id"));
        assertEquals(1,number(repo.details(owner,grouped(otherGroup),"ORDER",1,50).get("total")));
        assertEquals(0,number(repo.details(owner,grouped(original),"ORDER",1,50).get("total")));
    }
    @Test void mandatoryGroupsAreCompanyScopedAndCannotDeleteOccupiedGroup(){
        String group=service.saveGroup(owner,null,"服饰");
        assertThrows(BusinessException.class,()->service.saveGroup(owner,null,"服饰"));
        assertThrows(BusinessException.class,()->auth.start("SHOPEE",null,owner,"u","",""));
        assertThrows(BusinessException.class,()->auth.start("SHOPEE",null,other,"other",group,""));
        String shop=groupedShop(owner,"100",group);
        assertThrows(BusinessException.class,()->service.deleteGroup(owner,group));
        assertThrows(BusinessException.class,()->service.configure(other,shop,true,"PHP","Asia/Manila",group));
        service.saveGroup(owner,group,"女装");assertEquals("女装",service.groups(owner).getFirst().get("name"));
        service.configure(owner,shop,false,"PHP","Asia/Manila","");
        service.deleteGroup(owner,group);assertTrue(service.groups(owner).isEmpty());
        assertEquals(1,service.shops(owner).size());
    }
    @Test void callbackShowsDuplicateNoticeAndRejectsDeletedGroupBeforeExchangingCode(){
        String group=service.saveGroup(owner,null,"服饰");String shop=groupedShop(owner,"10001",group);
        final String[] state={null};when(client.authorizationUrl(anyString(),anyString())).thenAnswer(i->{state[0]=i.getArgument(1);return "https://official.test/";});
        auth.start("SHOPEE",null,companyScope("org"),"colleague",group,"");
        when(client.exchangeAuthorizationCode(eq("duplicate"),anyMap())).thenReturn(grant("10001"));
        String redirect=java.net.URLDecoder.decode(auth.complete("SHOPEE",state[0],"duplicate",Map.of(),null),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(redirect.contains("店铺已存在"));assertEquals(shop,service.shops(owner).getFirst().get("id"));
        String deleted=service.saveGroup(owner,null,"即将删除");auth.start("SHOPEE",null,owner,"u",deleted,"");service.deleteGroup(owner,deleted);
        assertTrue(auth.complete("SHOPEE",state[0],"must-not-exchange",Map.of(),null).contains("financeAuth=error"));
        verify(client,never()).exchangeAuthorizationCode(eq("must-not-exchange"),anyMap());
    }
    @Test void refundMigrationRepairsExistingAmountsWithoutChangingIdsOrRawPayloads()throws Exception{
        String shop=connect(owner,false);
        for(String status:List.of("ACCEPTED","PROCESSING","CANCELLED","CLOSED")){
            repo.save(owner,new Entry(shop,"SHOPEE","REFUND","refund-"+status,"order1","","refund",status,filter.start(),"PHP",
                    status.equals("CLOSED")?new BigDecimal("209"):null,null,null,null,null,"API",Map.of("refund_amount",209,"status",status)));
        }
        var before=db.queryForList("SELECT id,raw_json FROM finance_records ORDER BY id");
        migration("V126__finance_shopee_refund_status.sql");
        assertEquals(before,db.queryForList("SELECT id,raw_json FROM finance_records ORDER BY id"));
        assertEquals(0,new BigDecimal("209").compareTo(db.queryForObject("SELECT amount FROM finance_records WHERE status='ACCEPTED'",BigDecimal.class)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE amount IS NOT NULL",Integer.class));
        var details=rows(repo.details(owner,filter,"REFUND",1,50).get("items"));
        assertTrue(details.stream().allMatch(r->decimal(r.get("platform_refund_amount")).compareTo(new BigDecimal("209"))==0));
    }
    @Test void dashboardAggregatesMatchSqlAndCachedReadsRemainOwnerScoped(){
        String id=connect(owner,false),foreign=connect(other,false);
        for(String kind:List.of("ORDER","SETTLEMENT","FEE","REFUND")){
            record(owner,id,kind,kind+"1",new BigDecimal("100.25"),Map.of());
            record(owner,id,kind,kind+"2",null,Map.of());
            record(other,foreign,kind,"private-"+kind,new BigDecimal("999"),Map.of());
        }
        for(var day:List.of(filter.start().minusDays(1),filter.end().plusDays(1)))
            repo.save(owner,new Entry(id,"SHOPEE","ORDER","outside-"+day,"","","订单","PAID",day,"PHP",new BigDecimal("99999"),null,null,null,null,"API",Map.of()));
        var first=service.dashboard(owner,filter,false,null);
        var actual=rows(first.get("totals"));
        for(var expected:repo.grouped(owner,filter,"kind")){
            var total=actual.stream().filter(r->r.get("kind").equals(expected.get("kind"))).findFirst().orElseThrow();
            for(String field:List.of("amount","records","known_amounts"))
                assertEquals(0,decimal(expected.get(field)).compareTo(decimal(total.get(field))));
        }
        assertSame(first,service.dashboard(owner,filter,false,null));
        assertEquals(0,new BigDecimal("999").compareTo(decimal(rows(service.dashboard(other,filter,false,null).get("totals")).getFirst().get("amount"))));
        assertTrue(rows(first.get("statuses")).stream().allMatch(r->"ORDER".equals(r.get("kind"))));
        assertTrue(rows(first.get("fees")).stream().allMatch(r->"FEE".equals(r.get("kind"))));
        assertEquals(1,rows(first.get("trend")).size());
        assertEquals("2026-10-21",rows(first.get("trend")).getFirst().get("period"));
        assertEquals(0,new BigDecimal("100.25").compareTo(decimal(rows(first.get("trend")).getFirst().get("ORDER"))));
        var wider=new Filter(filter.start().minusDays(1),filter.end(),filter.shopId(),filter.platform(),filter.currency(),"day");
        assertEquals(0,new BigDecimal("100099.25").compareTo(decimal(rows(service.dashboard(owner,wider,false,null).get("totals")).stream().filter(r->"ORDER".equals(r.get("kind"))).findFirst().orElseThrow().get("amount"))));
        var converted=rows(service.dashboard(owner,filter,true,"fixed").get("totals"));
        assertEquals(0,new BigDecimal("12.53125").compareTo(decimal(converted.getFirst().get("amount"))));
        service.removeShop(owner,id);
        assertTrue(rows(service.dashboard(owner,filter,false,null).get("totals")).isEmpty());
        assertFalse(rows(service.dashboard(other,filter,false,null).get("totals")).isEmpty());
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_dashboard'",Integer.class)>0);
    }
    @Test void authorizationIsIndependentEncryptedScopedAndPausedUntilEnabled(){
        String id=connect(owner,false),second=connect(other,false);assertNotEquals(id,second);
        assertEquals(1,service.shops(owner).size());assertEquals(false,service.shops(owner).getFirst().get("enabled"));
        assertThrows(BusinessException.class,()->service.ownedShop(other,id));
        String secret=db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,id);assertNotEquals("finance-access",secret);
        assertEquals("finance-access",cipher.decrypt(secret,"org","finance-shop:u:"+id));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(secret,"org","legacy"));
        service.configure(owner,id,true,"PHP","Asia/Manila");assertEquals(4,service.syncStates(owner).size());
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
    }
    @Test void legacyAndOtherUsersRecordsAreHiddenFromEveryReadPath(){
        String id=connect(owner,false);connect(other,false);
        for(String kind:List.of("ORDER","SETTLEMENT","BILL")){
            record(owner,id,kind,kind.equals("BILL")?"bill1":kind,BigDecimal.TEN,Map.of());
            record(owner,"legacy",kind,"old-"+kind,new BigDecimal("999"),Map.of());
            record(other,id,kind,"foreign-"+kind,new BigDecimal("999"),Map.of());
        }
        assertEquals(3,number(repo.details(owner,filter,null,1,50).get("total")));
        assertEquals(3,repo.grouped(owner,filter,"kind").size());
        var bills=repo.reconciliation(owner,filter);assertEquals(1,bills.size());assertEquals("MATCHED",bills.getFirst().get("result"));
        String hidden=db.queryForObject("SELECT id FROM finance_records WHERE shop_id='legacy' LIMIT 1",String.class);
        assertThrows(BusinessException.class,()->repo.detail(owner,hidden));
        service.removeShop(owner,id);assertEquals(0,number(repo.details(owner,filter,null,1,50).get("total")));assertTrue(repo.reconciliation(owner,filter).isEmpty());
        assertEquals(9,db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
    }
    @Test void safeExtraFieldsStayTypedAndRawBuyerDataNeverEscapes(){
        String id=connect(owner,false);
        record(owner,id,"ORDER","order",new BigDecimal("100"),Map.of("payment_method","CARD","create_time",1792533600L,"item_list",List.of(Map.of("item_name","Dress")),"buyer_username","private","recipient_address",Map.of("phone","private"),"access_token","private"));
        var row=rows(repo.details(owner,filter,"ORDER",1,50).get("items")).getFirst();
        assertEquals("CARD",row.get("payment_method"));assertEquals("Dress",row.get("item_names"));assertEquals(1,row.get("item_count"));assertNotNull(row.get("create_time"));
        assertFalse(repo.write(row).contains("private"));assertFalse(row.containsKey("raw_json"));assertFalse(row.containsKey("estimated_shipping"));
        assertFalse(repo.write(repo.detail(owner,text(row.get("id")))).contains("private"));
    }
    @Test void reconciliationPagesBillsWithoutLimitingTheirTransactionsOrExport(){
        String shop=connect(owner,false);connect(other,false);
        for(int i=0;i<57;i++){
            String bill="paged-bill-"+i;
            repo.save(owner,new Entry(shop,"SHOPEE","BILL",bill,"",bill,"","SETTLED",filter.start(),"PHP",
                    BigDecimal.TEN,null,null,null,null,"API",Map.of()));
            // Transactions can have another business date; the entire bill must be reconciled.
            repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","tx-"+i,"",bill,"","SETTLED",filter.start().minusDays(4),"PHP",
                    BigDecimal.TEN,null,null,null,null,"API",Map.of()));
            repo.save(other,new Entry(shop,"SHOPEE","SETTLEMENT","foreign-"+i,"",bill,"","SETTLED",filter.start(),"PHP",
                    new BigDecimal("999"),null,null,null,null,"API",Map.of()));
        }
        var first=repo.reconciliationPage(owner,filter,1,50);var second=repo.reconciliationPage(owner,filter,2,50);
        assertEquals(57L,first.get("total"));assertEquals(50,rows(first.get("items")).size());assertEquals(7,rows(second.get("items")).size());
        var ids=new HashSet<String>();
        for(var row:rows(first.get("items"))){assertEquals("MATCHED",row.get("result"));assertTrue(ids.add(text(row.get("id"))));}
        for(var row:rows(second.get("items"))){assertEquals("MATCHED",row.get("result"));assertTrue(ids.add(text(row.get("id"))));}
        assertEquals(57,repo.reconciliation(owner,filter,100001).size());
        assertTrue(rows(repo.reconciliationPage(owner,filter,3,50).get("items")).isEmpty());
        var q=Map.of("start","2026-10-21","end","2026-10-21","page","2","displayCurrency","CNY","fxSnapshot","fixed");
        var response=map(controller().reconciliation(q,principal()).data());
        assertEquals(7,rows(response.get("items")).size());assertEquals("CNY",map(response.get("filters")).get("displayCurrency"));
        assertEquals(new BigDecimal("1.250000"),rows(response.get("items")).getFirst().get("cny_amount"));
        assertEquals(List.of("PHP"),response.get("currencies"));assertFalse(response.containsKey("trend"));
    }
    @Test void reconciliationPreservesPartialUnknownMissingAndCurrencyBoundaries(){
        String shop=connect(owner,false);
        for(String bill:List.of("partial","unknown","missing","different","zero")){
            repo.save(owner,new Entry(shop,"SHOPEE","BILL",bill,"",bill,"",bill.equals("partial")?"PARTIAL":"SETTLED",filter.start(),"PHP",
                    bill.equals("zero")?BigDecimal.ZERO:BigDecimal.TEN,null,null,null,null,"API",Map.of()));
            if(!bill.equals("missing"))repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","tx-"+bill,"",bill,"","SETTLED",filter.start(),"PHP",
                    bill.equals("unknown")?null:bill.equals("different")?BigDecimal.ONE:bill.equals("zero")?BigDecimal.ZERO:BigDecimal.TEN,
                    null,null,null,null,"API",Map.of()));
        }
        // An imported bill must inherit the corresponding API bill's incomplete status.
        repo.save(owner,new Entry(shop,"SHOPEE","BILL","partial","","partial","","IMPORTED",filter.start(),"PHP",
                BigDecimal.TEN,null,null,null,null,"IMPORT",Map.of()));
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","wrong-currency","","missing","","SETTLED",filter.start(),"USD",
                BigDecimal.TEN,null,null,null,null,"API",Map.of()));
        var result=repo.reconciliation(owner,filter);
        for(var row:result){
            String expected=switch(text(row.get("external_id"))){case "partial"->"PARTIAL";case "different"->"DIFFERENCE";case "zero"->"MATCHED";default->"MISSING";};
            assertEquals(expected,row.get("result"));
            if(Set.of("PARTIAL","MISSING").contains(expected))assertNull(row.get("difference"));
        }
        assertEquals(6,result.size());
    }
    @Test void preferencesArePerUserPerTypeAndRejectUnknownPaths(){
        var fields=new FinanceFields(repo);
        fields.save(owner,"ORDER",List.of("external_id","amount"),List.of("external_id","payment_method"));
        assertEquals(List.of("external_id","amount"),fields.get(owner,"ORDER").visible());
        assertTrue(fields.get(other,"ORDER").visible().contains("record_date"));assertTrue(fields.get(owner,"REFUND").exported().contains("amount"));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("raw_json"),List.of("amount")));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("amount"),List.of("shopee_commission")));
        assertThrows(BusinessException.class,()->fields.save(owner,"ORDER",List.of("cny_amount"),List.of("amount")));
    }
    FinanceController controller(){return new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));}
    @Test void csvAndXlsxHonorIndependentExportFieldsAndReconciliation(){
        String id=connect(owner,false);record(owner,id,"ORDER","00123456789012345678",new BigDecimal("100"),Map.of("payment_method","CARD"));
        record(owner,id,"BILL","bill1",BigDecimal.TEN,Map.of());record(owner,id,"SETTLEMENT","tx1",BigDecimal.TEN,Map.of());
        var fields=new FinanceFields(repo);fields.save(owner,"ORDER",List.of("external_id","amount"),List.of("external_id","payment_method","cny_amount"));
        fields.save(owner,"RECONCILIATION",List.of("external_id","result"),List.of("external_id","detail_amount","difference","result"));
        for(String format:List.of("csv","xlsx")){
            var q=Map.of("start","2026-10-21","end","2026-10-21","view","ORDER","format",format,"displayCurrency","CNY","fxSnapshot","fixed");
            var content=controller().export(q,principal()).getBody();var table=FinanceWorkbook.read(content,"export."+format);
            assertEquals(List.of("记录编号","支付方式","折算人民币"),table.getFirst());assertEquals(List.of("00123456789012345678","CARD","12.500000"),table.get(1));
        }
        var q=Map.of("start","2026-10-21","end","2026-10-21","view","RECONCILIATION","format","csv");
        var table=FinanceWorkbook.read(controller().export(q,principal()).getBody(),"rec.csv");
        assertEquals(List.of("记录编号","结算明细金额","差异","核对结果"),table.getFirst());assertEquals("MATCHED",table.get(1).getLast());assertEquals(2,table.size());
    }
    @Test void callbackConsumesOnlyItsOwnStateOnceAndRedirectsToFinance(){
        final String[] rawState={null};when(client.authorizationUrl(anyString(),anyString())).thenAnswer(i->{rawState[0]=i.getArgument(1);return "https://official.test/?state="+rawState[0];});
        auth.start("SHOPEE",null,owner,principal().userId(),service.saveGroup(owner,null,"服饰"),"");
        assertNotEquals(rawState[0],db.queryForObject("SELECT state_hash FROM finance_authorization_sessions",String.class));
        when(client.exchangeAuthorizationCode(eq("code"),anyMap())).thenReturn(new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(new MarketplaceAccess.RemoteShop("10001",null,null,"LOCAL","New finance","PH"))));
        var commerce=mock(ShopAuthorizationService.class);var callback=new ShopAuthorizationController(commerce,auth);
        var result=callback.callback("SHOPEE",Map.of("state",rawState[0],"code","code"));
        assertTrue(result.getHeaders().getLocation().toString().contains("financeAuth=success"));assertTrue(result.getHeaders().getLocation().toString().endsWith("#finance-shops"));
        assertTrue(callback.callback("SHOPEE",Map.of("state",rawState[0],"code","code")).getHeaders().getLocation().toString().contains("financeAuth=error"));
        verify(client,times(1)).exchangeAuthorizationCode(eq("code"),anyMap());verifyNoInteractions(commerce);
        assertEquals(1,service.shops(owner).size());assertTrue(service.shops(other).isEmpty());
    }
    @Test void expiredOrDifferentPlatformStateCannotExchangeCode(){
        final String[] state={null};when(client.authorizationUrl(anyString(),anyString())).thenAnswer(i->{state[0]=i.getArgument(1);return "https://official.test/";});auth.start("SHOPEE",null,owner,principal().userId(),service.saveGroup(owner,null,"服饰"),"");
        assertThrows(BusinessException.class,()->auth.complete("TIKTOK_SHOP",state[0],"code",Map.of(),null));
        db.update("UPDATE finance_authorization_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        assertThrows(BusinessException.class,()->auth.complete("SHOPEE",state[0],"code",Map.of(),null));verify(client,never()).exchangeAuthorizationCode(any(),anyMap());
    }
    @Test void tokenRefreshLocksAndRotatesFinanceGroupOnly(){
        auth.persist(owner,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(true),List.of(new MarketplaceAccess.RemoteShop("1","merchant",null,"LOCAL","One","PH","MERCHANT","merchant"),new MarketplaceAccess.RemoteShop("2","merchant",null,"LOCAL","Two","PH","MERCHANT","merchant"))));
        String id=text(service.shops(owner).getFirst().get("id"));
        var fresh=new MarketplaceAccess.TokenSet("rotated-access","rotated-refresh",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(86400),List.of());when(client.refresh(any(),any())).thenReturn(fresh);
        var connection=new TransactionTemplate(tx).execute(status->auth.connection(owner,id));assertEquals(id,connection.shop().getId());assertEquals("rotated-access",connection.tokens().accessToken());
        for(var shop:service.shops(owner))assertEquals("rotated-access",new TransactionTemplate(tx).execute(status->auth.connection(owner,text(shop.get("id")))).tokens().accessToken());
        verify(client,times(1)).refresh(any(),any());assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials",String.class));
    }
    @Test void sharedGrantRefreshRotatesAllOriginalCredentialOwnersTogether(){
        String group=service.saveGroup(owner,null,"服饰"),old=groupedShop(owner,"1",group);
        Scope shared=companyScope("org");
        auth.persistGrant(shared,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(true),List.of(
                new MarketplaceAccess.RemoteShop("1","merchant",null,"LOCAL","One","PH","MERCHANT","merchant"),
                new MarketplaceAccess.RemoteShop("2","merchant",null,"LOCAL","Two","PH","MERCHANT","merchant"))),group,"");
        var fresh=new MarketplaceAccess.TokenSet("shared-rotated-access","shared-rotated-refresh",Instant.now().plusSeconds(3600),Instant.now().plusSeconds(86400),List.of());
        when(client.refresh(any(),any())).thenReturn(fresh);
        assertEquals("shared-rotated-access",new TransactionTemplate(tx).execute(status->auth.connection(owner,old)).tokens().accessToken());
        for(var shop:service.shops(shared)){
            Scope storage=service.shopScope(shared,text(shop.get("id")));
            assertEquals("shared-rotated-access",new TransactionTemplate(tx).execute(status->auth.connection(storage,text(shop.get("id")))).tokens().accessToken());
        }
        verify(client,times(1)).refresh(any(),any());
    }
    @Test void liveScreenRefreshesOrdersWithoutBypassingFailuresOrCheckpoints(){
        String id=connect(owner,false);service.configure(owner,id,true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET status='SUCCESS',last_success_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 MINUTE),next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE shop_id=?",id);
        record(owner,id,"ORDER","o1",new BigDecimal("100"),Map.of("create_time",LocalDateTime.of(2026,10,21,10,0).atZone(ZoneId.of("Asia/Manila")).toEpochSecond()));
        var screen=service.screen(owner,filter,true,"fixed");
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_sync_states WHERE shop_id=? AND channel='ORDER_LIVE'",String.class,id));
        assertEquals("SUCCESS",db.queryForObject("SELECT status FROM finance_sync_states WHERE shop_id=? AND channel='ORDER'",String.class,id));
        assertEquals("SUCCESS",db.queryForObject("SELECT status FROM finance_sync_states WHERE shop_id=? AND channel='SETTLEMENT'",String.class,id));
        var hour=rows(screen.get("hourly")).get(10);assertEquals(new BigDecimal("12.500000"),hour.get("amount"));assertEquals(1L,hour.get("count"));assertEquals(1,rows(screen.get("recent")).size());
        db.update("UPDATE finance_sync_states SET status='ERROR',next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 20 MINUTE) WHERE channel='ORDER_LIVE'");
        service.screen(owner,filter,false,null);assertEquals("ERROR",db.queryForObject("SELECT status FROM finance_sync_states WHERE channel='ORDER_LIVE'",String.class));
        db.update("UPDATE finance_sync_states SET status='SYNCING',checkpoint_json='{\"cursor\":\"retain\"}' WHERE channel='ORDER_LIVE'");service.screen(owner,filter,false,null);
        assertTrue(db.queryForObject("SELECT checkpoint_json FROM finance_sync_states WHERE channel='ORDER_LIVE'",String.class).contains("retain"));
    }
    @Test void liveOrdersContinueWhileHistoricalCursorIsPausedAndKeepRequestWatermark(){
        String id=connect(owner,false);service.configure(owner,id,true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)");
        String historical="{\"from\":1784822400,\"to\":1785427200,\"until\":1792512000,\"cursor\":\"history-page\"}";
        db.update("UPDATE finance_sync_states SET status='SYNCING',checkpoint_json=? WHERE channel='ORDER'",historical);
        migrationUncheckedLive();
        assertEquals("history-page",map(repo.read(db.queryForObject("SELECT checkpoint_json FROM finance_sync_states WHERE channel='ORDER'",String.class))).get("cursor"));
        Instant boundary=Instant.now().minusSeconds(120);
        var cp=Map.<String,Object>of("from",boundary.minusSeconds(3600).getEpochSecond(),"to",boundary.getEpochSecond(),"until",boundary.getEpochSecond(),"coverage",LocalDate.now().toString(),"cursor","live-page");
        db.update("UPDATE finance_sync_states SET checkpoint_json=?,next_at=UTC_TIMESTAMP() WHERE channel='ORDER_LIVE'",repo.write(cp));
        var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenAnswer(invocation->new FinanceMarketplace.Step(invocation.getArgument(2),true));
        var worker=new FinanceSync(repo,market);
        try{
            worker.tick();
            assertEquals(boundary.getEpochSecond(),db.queryForObject("SELECT UNIX_TIMESTAMP(last_success_at) FROM finance_sync_states WHERE channel='ORDER_LIVE'",Long.class));
            assertEquals("history-page",repo.read(db.queryForObject("SELECT checkpoint_json FROM finance_sync_states WHERE channel='ORDER'",String.class)).get("cursor"));
            db.update("UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP() WHERE channel='ORDER_LIVE'");
            worker.tick();
            verify(market).step(eq(owner),argThat(row->"ORDER_LIVE".equals(row.get("channel"))),argThat(checkpoint->number(checkpoint.get("from"))==boundary.minusSeconds(300).getEpochSecond()&&!checkpoint.containsKey("cursor")));
        }finally{worker.stop();}
    }
    void migrationUncheckedLive(){try{migration("V121__finance_live_order_lane.sql");}catch(Exception e){throw new RuntimeException(e);}}
    @Test void workersRunDifferentShopsConcurrentlyButNeverTwoChannelsOfOneShop()throws Exception{
        auth.persist(owner,MarketplacePlatform.SHOPEE,new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(
                new MarketplaceAccess.RemoteShop("10001",null,null,"LOCAL","one","PH","SHOP","10001"),
                new MarketplaceAccess.RemoteShop("10002",null,null,"LOCAL","two","PH","SHOP","10002"))));
        for(var shop:service.shops(owner))service.configure(owner,text(shop.get("id")),true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)");
        db.update("UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP() WHERE channel IN ('ORDER','ORDER_LIVE')");
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var returned=new java.util.concurrent.CountDownLatch(2);
        Set<String> active=java.util.concurrent.ConcurrentHashMap.newKeySet();
        var peak=new java.util.concurrent.atomic.AtomicInteger();var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenAnswer(i->{
            var row=map(i.getArgument(1));String shop=text(row.get("shop_id"));
            assertTrue(active.add(shop),"two channels entered the same shop");assertEquals("ORDER_LIVE",row.get("channel"));
            peak.accumulateAndGet(active.size(),Math::max);entered.countDown();
            try{assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));}
            finally{active.remove(shop);}
            return new FinanceMarketplace.Step(i.getArgument(2),true);
        });
        var worker=new FinanceSync(repo,market);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var runs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++)runs.add(threads.submit(()->{try{worker.tick();}finally{returned.countDown();}}));
            try{
                assertTrue(entered.await(15,java.util.concurrent.TimeUnit.SECONDS),"different shops should run concurrently");
                assertTrue(returned.await(15,java.util.concurrent.TimeUnit.SECONDS),"idle workers should finish while both shops remain locked");
            }
            finally{release.countDown();}
            for(var run:runs)run.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2,peak.get());verify(market,times(2)).step(any(),anyMap(),anyMap());
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE channel='ORDER_LIVE' AND status='SUCCESS' AND lease_token IS NULL",Integer.class));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE channel='ORDER' AND status='READY'",Integer.class));
        }finally{release.countDown();worker.stop();}
    }
    @Test void expiredWorkerCannotOverwriteTheReplacementClaim()throws Exception{
        String shop=connect(owner,false);service.configure(owner,shop,true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)");
        db.update("UPDATE finance_sync_states SET next_at=UTC_TIMESTAMP() WHERE channel='ORDER_LIVE'");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var market=mock(FinanceMarketplace.class);
        when(market.step(any(),anyMap(),anyMap())).thenAnswer(i->{
            entered.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS));
            return new FinanceMarketplace.Step(i.getArgument(2),true);
        });
        var worker=new FinanceSync(repo,market);
        try(var thread=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var run=thread.submit(worker::tick);
            try{
                assertTrue(entered.await(15,java.util.concurrent.TimeUnit.SECONDS));
                db.update("UPDATE finance_sync_states SET lease_token='replacement',checkpoint_json='{\"cursor\":\"retained\"}' WHERE channel='ORDER_LIVE'");
            }finally{release.countDown();}
            run.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("RUNNING",db.queryForObject("SELECT status FROM finance_sync_states WHERE channel='ORDER_LIVE'",String.class));
            assertEquals("retained",repo.read(db.queryForObject("SELECT checkpoint_json FROM finance_sync_states WHERE channel='ORDER_LIVE'",String.class)).get("cursor"));
        }finally{release.countDown();worker.stop();}
    }
    com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp adHttp;
    FinanceAdAuthorization adAuth(){
        return adAuth(new FinanceAdApplicationSettings("","",""));
    }
    FinanceAdAuthorization adAuth(FinanceAdApplicationSettings applicationSettings){
        adHttp=mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp.class);
        var ads=new FinanceAds(FinanceTest.classProxy(repo),service,adHttp,cipher);
        var settings=new ShopIntegrationSettings(Duration.ofMinutes(30),Duration.ofMinutes(5),URI.create("https://example.test"),URI.create("https://example.test"),false,"DRY_RUN");
        return new FinanceAdAuthorization(FinanceTest.classProxy(repo),FinanceTest.classProxy(ads),adHttp,cipher,settings,auth,tx,applicationSettings);
    }
    String adState(Map<String,Object> start){
        var query=URI.create(text(start.get("authorizationUrl"))).getRawQuery();
        return Arrays.stream(query.split("&")).filter(p->p.startsWith("state=")).map(p->java.net.URLDecoder.decode(p.substring(6),java.nio.charset.StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }
    void adGrant(List<String> ids,List<Map<String,Object>> accounts){
        when(adHttp.postJson(any(),anyMap(),anyString())).thenReturn(Map.of("code",0,"data",Map.of("access_token","verified-ad-access","advertiser_ids",ids)));
        when(adHttp.get(any(),anyMap())).thenReturn(Map.of("code",0,"data",Map.of("list",accounts)));
    }
    Map<String,Object> advertiser(String id){return Map.of("advertiser_id",id,"advertiser_name","Test ads "+id,"currency","PHP","timezone","Asia/Manila");}
    @Test void serverAdvertisingApplicationCreatesCopiedLinksWithoutCompanySetupAndKeepsAccountsIsolated(){
        var a=adAuth(new FinanceAdApplicationSettings("12345678","server-ad-private-secret",""));
        assertEquals(true,a.configuration(owner).get("configured"));
        assertFalse(repo.write(a.configuration(owner)).contains("server-ad-private"));
        assertFalse(a.configuration(owner).containsKey("appId"));
        assertThrows(BusinessException.class,()->a.configure(owner,"87654321","user-ad-private-secret"));
        String state=adState(a.start(owner,"","",""));
        String otherState=adState(a.start(other,"","",""));
        assertNotEquals(state,otherState);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_applications",Integer.class));
        assertFalse(db.queryForObject("SELECT context_json FROM finance_authorization_sessions WHERE state_hash=?",String.class,FinanceShopAuthorization.hash(state)).contains("server-ad-private"));
        adGrant(List.of("100001"),List.of(advertiser("100001")));
        assertTrue(a.complete(state,"code",null).contains("financeAuth=success"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE organization_id='org'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE organization_id='other-org'",Integer.class));
        assertThrows(BusinessException.class,()->a.complete(state,"code",null));
        verify(adHttp,times(1)).postJson(any(),anyMap(),argThat(body->body.contains("\"secret\":\"server-ad-private-secret\"")));
    }
    @Test void rotatingServerAdvertisingCredentialsRejectsPendingLinkBeforeExchangingCode()throws Exception{
        Path file=Files.createTempFile("finance-ad-qa-",".properties");
        try{
            Files.writeString(file,"app-id=12345678\napp-secret=first-ad-private-secret\n");
            var a=adAuth(new FinanceAdApplicationSettings("","",file.toString()));
            String state=adState(a.start(owner,"","",""));
            Files.writeString(file,"app-id=12345678\napp-secret=rotated-ad-private-secret\n");
            assertTrue(a.complete(state,"code",null).contains("financeAuth=error"));
            verifyNoInteractions(adHttp);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts",Integer.class));
        }finally{Files.deleteIfExists(file);}
    }
    @Test void existingAdvertisingLinkStillUsesItsOriginalApplicationAfterServerConfiguration(){
        var legacy=adAuth();legacy.configure(owner,"87654321","legacy-ad-private-secret");
        String state=adState(legacy.start(owner,"","",""));
        var managed=adAuth(new FinanceAdApplicationSettings("12345678","server-ad-private-secret",""));
        adGrant(List.of("100001"),List.of(advertiser("100001")));
        assertTrue(managed.complete(state,"code",null).contains("financeAuth=success"));
        verify(adHttp).postJson(any(),anyMap(),argThat(body->body.contains("\"app_id\":\"87654321\"")&&body.contains("\"secret\":\"legacy-ad-private-secret\"")));
        assertEquals("12345678",URI.create(text(managed.start(owner,"","","").get("authorizationUrl"))).getQuery().split("app_id=")[1].split("&")[0]);
    }
    @Test void advertisingApplicationIsPrivateEncryptedAndDoesNotReuseShopCredentials(){
        var a=adAuth();assertThrows(BusinessException.class,()->a.start(owner,"","",""));
        a.configure(owner,"12345678","private-ad-application-secret");
        assertEquals(true,a.configuration(owner).get("configured"));assertEquals(false,a.configuration(other).get("configured"));
        assertFalse(repo.write(a.configuration(owner)).contains("private-ad"));
        String secret=db.queryForObject("SELECT secret_encrypted FROM finance_ad_applications",String.class);
        assertNotEquals("private-ad-application-secret",secret);
        assertEquals("private-ad-application-secret",cipher.decrypt(secret,"org","finance-ads-app:"+companyScope("org").userId()));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(secret,"org","finance-ads-app:other"));
        a.configure(owner,"12345678","");assertEquals(true,a.configuration(owner).get("configured"));
        assertThrows(BusinessException.class,()->a.configure(owner,"87654321",""));
        assertThrows(BusinessException.class,()->a.start(other,"","",""));
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials",String.class));
    }
    @Test void copiedAdAuthorizationLinkCompletesExactlyOnceAndConnectsScopedAccounts(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        var started=a.start(owner,"","","");String state=adState(started);
        assertTrue(text(started.get("authorizationUrl")).startsWith("https://business-api.tiktok.com/portal/auth?"));
        assertFalse(text(started.get("authorizationUrl")).contains("private-ad"));
        assertNotEquals(state,db.queryForObject("SELECT state_hash FROM finance_authorization_sessions",String.class));
        adGrant(List.of("100001","100002"),List.of(advertiser("100001"),advertiser("100002")));
        var callback=new FinanceAdAuthorizationCallback(a);
        var result=callback.callback(Map.of("state",state,"auth_code","one-time-code"));
        assertTrue(result.getHeaders().getLocation().toString().contains("financeAuth=success"));
        assertTrue(result.getHeaders().getLocation().toString().endsWith("#finance-shops"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE organization_id='org'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE user_id='other'",Integer.class));
        String token=db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts WHERE advertiser_id='100001'",String.class);
        assertEquals("verified-ad-access",cipher.decrypt(token,"org","finance:"+companyScope("org").userId()+":100001"));
        assertTrue(callback.callback(Map.of("state",state,"auth_code","one-time-code")).getHeaders().getLocation().toString().contains("financeAuth=error"));
        verify(adHttp,times(1)).postJson(any(),anyMap(),argThat(body->body.contains("\"app_id\":\"12345678\"")&&body.contains("\"auth_code\":\"one-time-code\"")&&body.contains("\"secret\":\"private-ad-application-secret\"")));
    }
    @Test void expiredCancelledAndShopStatesCannotExchangeAdvertisingCodes(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String expired=adState(a.start(owner,"","",""));
        db.update("UPDATE finance_authorization_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");
        assertThrows(BusinessException.class,()->a.complete(expired,"code",null));
        String cancelled=adState(a.start(owner,"","",""));a.configure(owner,"12345678","");
        assertThrows(BusinessException.class,()->a.complete(cancelled,"code",null));
        assertThrows(BusinessException.class,()->a.complete("finance_other-shop-state","code",null));
        verifyNoInteractions(adHttp);
    }
    @Test void incompleteAdGrantRollsBackAllAccountsAndDoesNotLeakErrors(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String state=adState(a.start(owner,"","",""));
        adGrant(List.of("100001","100002"),List.of(advertiser("100001"),Map.of("advertiser_id","100002","currency","invalid")));
        String result=a.complete(state,"code",null);
        assertTrue(result.contains("financeAuth=error"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts",Integer.class));
        assertEquals("FAILED",db.queryForObject("SELECT status FROM finance_authorization_sessions",String.class));
        String second=adState(a.start(owner,"","",""));
        String rejected=a.complete(second,null,"private-provider-secret");
        assertFalse(rejected.contains("private-provider-secret"));
        assertFalse(db.queryForObject("SELECT error_message FROM finance_authorization_sessions WHERE state_hash=?",String.class,FinanceShopAuthorization.hash(second)).contains("private"));
    }
    @Test void advertisingGrantMustIncludeRequestedAccount(){
        var a=adAuth();a.configure(owner,"12345678","private-ad-application-secret");
        String state=adState(a.start(owner,"100003","",""));
        adGrant(List.of("100001"),List.of(advertiser("100001")));
        assertTrue(a.complete(state,"code",null).contains("financeAuth=error"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_ad_accounts",Integer.class));
        assertThrows(BusinessException.class,()->a.start(owner,"","foreign-shop",""));
    }
}
