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
        migration("V130__finance_query_indexes.sql");
        migration("V131__finance_shop_deletion_jobs.sql");
        db.update("""
            INSERT INTO finance_cost_books(id,organization_id,group_id,source_name,content_hash,data_json,created_by,created_at)
            VALUES('old1','migration-org','migration-group','old.csv','hash1','{"costs":[{"sku":"PACK","warehouse":"PH","prices":{"2026-09":10}}]}','u','2026-10-01'),
            ('old2','migration-org','migration-group','latest.csv','hash2','{"costs":[{"sku":"PACK","warehouse":"PH","prices":{"2026-09":10,"2026-10":12}}]}','u','2026-10-02')
            """);
        migration("V132__finance_cost_sources.sql");
        migration("V133__finance_order_charges.sql");
        migration("V134__finance_reporting_months.sql");
        migration("V135__finance_actual_fulfillment_costs.sql");
        migration("V136__finance_order_lookup_covering.sql");
        migration("V137__finance_label_invoice_imports.sql");
        migration("V138__finance_qianyi_returns.sql");
        migration("V139__finance_label_arrival_queue.sql");
        migration("V140__finance_qianyi_company_configuration.sql");
        migration("V141__finance_cost_date_covering.sql");
        migration("V142__finance_sync_priority.sql");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_sources",Integer.class));
        assertEquals(1,db.queryForObject("SELECT legacy_merged FROM finance_cost_sources WHERE id='old2'",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_cost_books",Integer.class),"legacy snapshots remain for audit");
        assertEquals(12,db.queryForObject("SELECT JSON_EXTRACT(data_json,'$.costs[0].prices.\"2026-10\"') FROM finance_cost_sources WHERE id='old2'",Integer.class));
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
        db.update("UPDATE finance_api_pacing SET next_allowed_at=UTC_TIMESTAMP(6),interval_seconds=5,throttle_count=0,success_count=0");
        for(String table:List.of("finance_qianyi_return_costs","finance_qianyi_returns","finance_qianyi_connections","finance_qianyi_accounts"))db.update("DELETE FROM "+table);
        for(String table:List.of("finance_label_arrivals","finance_label_order_totals","finance_label_invoice_rows","finance_label_imports","finance_label_state"))db.update("DELETE FROM "+table);
        db.update("UPDATE finance_label_worker_lock SET lease_token=NULL,lease_until=NULL");
        db.update("DELETE FROM finance_reporting_months");
        for(String table:List.of("finance_marketing_orders","finance_charge_rows","finance_charge_imports","finance_label_rules","finance_shop_deletion_ads","finance_shop_deletions","finance_order_cost_jobs","finance_order_costs","finance_cost_rebuilds","finance_workspace_audit","finance_expense_rules","finance_expenses","finance_cost_mappings","finance_cost_settings","finance_cost_sources","finance_manual_costs","finance_cost_books","finance_groups","finance_imports","finance_connections","finance_authorization_sessions","finance_records","finance_sync_states","finance_view_preferences","finance_ad_accounts","finance_ad_applications"))db.update("DELETE FROM "+table);
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
    void finishDeletion(String shop){
        for(int i=0;i<120;i++){
            if("COMPLETE".equals(db.queryForObject("SELECT status FROM finance_shop_deletions WHERE organization_id=? AND shop_id=?",String.class,owner.organizationId(),shop)))return;
            repo.transaction(()->new FinanceShopDeletion(repo).batch());
        }
        fail("Deletion did not finish within bounded test batches");
    }
    @Test void shopDeletionIsBoundedResumableAndPreservesOtherShopsAndSharedCosts(){
        String group=service.saveGroup(owner,null,"删除测试"),shop=groupedShop(owner,"delete-shop",group),sibling=groupedShop(owner,"keep-shop",group);
        db.update("INSERT INTO finance_reporting_months VALUES('org',?,'order-0','bill','2026-10-01','USER_CONFIRMED_MONTH','test','test',UTC_TIMESTAMP(6))",shop);
        String foreign=connect(other,false);
        var txs=new TransactionTemplate(tx);
        txs.executeWithoutResult(status->new FinanceCostWorkspace(repo,service).importCosts(owner,"u",group,
                "仓库,型号,202610\n海外仓-菲律宾,PACK,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"shared.csv"));
        record(owner,sibling,"ORDER","keep-order",BigDecimal.TEN,Map.of());record(other,foreign,"ORDER","foreign-order",BigDecimal.TEN,Map.of());
        var values=new ArrayList<Object[]>();
        for(int i=0;i<1201;i++)values.add(new Object[]{"delete-"+i,shop,"order-"+i});
        db.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,currency,amount,source,raw_json)
            VALUES(?,'org','u',?,'SHOPEE','ORDER',?,'2026-10-21','PHP',1,'API','{}')
            """,values);
        db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,source_hash,data_json) VALUES('delete-0','org',?,'order-0','2026-10-21','READY','hash','{}')",shop);
        db.update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES('delete-0','org',?)",shop);
        db.update("INSERT IGNORE INTO finance_cost_rebuilds(organization_id,shop_id) VALUES('org',?)",shop);
        db.update("INSERT INTO finance_cost_settings(organization_id,shop_id,data_json,updated_by) VALUES('org',?,'{}','u')",shop);
        db.update("INSERT INTO finance_cost_mappings(id,organization_id,shop_id,product_id,sku_id,warehouse,data_json,updated_by) VALUES('mapping','org',?,'p','sku','PH','{}','u')",shop);
        db.update("INSERT INTO finance_imports(id,organization_id,user_id,shop_id,file_name,content_hash,row_count,kind) VALUES('import','org','u',?,'private.csv','hash',1,'BILL')",shop);
        db.update("""
            INSERT INTO finance_expenses(id,organization_id,scope,target_id,category,source_ref,dedupe_key,amount,currency,start_date,end_date,record_date,treatment,data_json,created_by,updated_by)
            VALUES('expense','org','shop',?,'shipping','source','hash',1,'CNY','2026-10-21','2026-10-21','2026-10-21','extra','{}','u','u')
            """,shop);
        db.update("INSERT INTO finance_expense_rules(id,organization_id,data_json,created_by) VALUES('rule','org',?,'u'),('shared-rule','org',?,'u')",
                repo.write(Map.of("scope","shop","target",shop)),repo.write(Map.of("scope","group","target",group)));
        for(var pair:List.of(new String[]{"ORDER_STANDARD_COST","delete-0"},new String[]{"COST_MAPPING","mapping"},
                new String[]{"COST_SETTINGS",shop},new String[]{"COST_AUTO_MATCH",shop},new String[]{"EXPENSE","expense"},new String[]{"EXPENSE_RULE","rule"}))
            db.update("INSERT INTO finance_workspace_audit(id,organization_id,entity_type,entity_id,after_json,reason,actor_id) VALUES(?,'org',?,?,'{}','test','u')",
                    UUID.randomUUID().toString(),pair[0],pair[1]);
        db.update("INSERT INTO finance_ad_accounts(organization_id,user_id,advertiser_id,name,shop_id,currency,access_token_encrypted,lease_token) VALUES('org','u','123456','advertiser',?,'PHP','secret','old-lease')",shop);
        service.configure(owner,shop,true,"PHP","Asia/Manila");
        db.update("UPDATE finance_sync_states SET status='RUNNING',lease_token='old-lease',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE shop_id=?",shop);
        db.update("INSERT INTO finance_authorization_sessions(id,organization_id,user_id,platform,state_hash,expires_at,context_json) VALUES('session','org','u','SHOPEE','hash',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),?)",repo.write(Map.of("shopId",shop)));
        Instant oldLink=Instant.now().minusSeconds(10);
        assertThrows(BusinessException.class,()->service.removeShop(other,shop));
        service.removeShop(owner,shop);service.removeShop(owner,shop);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_shop_deletions",Integer.class));
        assertEquals("",db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,shop));
        assertNull(db.queryForObject("SELECT refresh_token_encrypted FROM finance_connections WHERE id=?",String.class,shop));
        assertEquals("",db.queryForObject("SELECT access_token_encrypted FROM finance_ad_accounts WHERE shop_id=?",String.class,shop));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE shop_id=? AND lease_token IS NOT NULL",Integer.class,shop));
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM finance_authorization_sessions WHERE id='session'",String.class));
        assertEquals(1201,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,shop));
        assertThrows(BusinessException.class,()->record(owner,shop,"ORDER","late-page",BigDecimal.ONE,Map.of()));
        assertThrows(BusinessException.class,()->auth.persistGrant(owner,MarketplacePlatform.SHOPEE,grant("delete-shop"),group,""));
        for(int i=0;i<60&&db.queryForObject("SELECT stage FROM finance_shop_deletions WHERE shop_id=?",Integer.class,shop)<10;i++)
            repo.transaction(()->new FinanceShopDeletion(repo).batch());
        assertEquals(10,db.queryForObject("SELECT stage FROM finance_shop_deletions WHERE shop_id=?",Integer.class,shop));
        assertThrows(IllegalStateException.class,()->repo.transaction(()->{
            new FinanceShopDeletion(repo).batch();throw new IllegalStateException("simulated interruption");
        }));
        assertEquals(1201,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,shop),"failed batch rolls back its data and checkpoint");
        repo.transaction(()->new FinanceShopDeletion(repo).batch());
        assertEquals(701,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,shop),"one batch removes at most 500 records");
        finishDeletion(shop);
        for(String table:List.of("finance_reporting_months","finance_records","finance_order_costs","finance_order_cost_jobs","finance_cost_rebuilds","finance_cost_mappings","finance_cost_settings","finance_imports","finance_ad_accounts","finance_sync_states"))
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE organization_id='org' AND shop_id=?",Integer.class,shop),table);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_connections WHERE id=?",Integer.class,shop));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_expenses",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_authorization_sessions WHERE id='session'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_expense_rules",Integer.class),"shared group rule remains");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit",Integer.class),"shared cost import audit remains");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_cost_books",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
        assertEquals("",db.queryForObject("SELECT shop_label FROM finance_shop_deletions WHERE shop_id=?",String.class,shop));
        service.removeShop(owner,shop);
        assertThrows(BusinessException.class,()->record(owner,shop,"ORDER","late-after-complete",BigDecimal.ONE,Map.of()));
        assertThrows(BusinessException.class,()->auth.persistGrant(owner,MarketplacePlatform.SHOPEE,grant("delete-shop"),group,"",oldLink));
        auth.persistGrant(owner,MarketplacePlatform.SHOPEE,grant("delete-shop"),group,"");
        String fresh=db.queryForObject("SELECT id FROM finance_connections WHERE external_shop_id='delete-shop'",String.class);
        assertNotEquals(shop,fresh,"explicit fresh authorization gets a new shop identity");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,fresh));
    }
    @Test void deletingShopWaitsForFinancialWritesAndFencesLateWork()throws Exception{
        String shop=connect(owner,false);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var writing=executor.submit(()->repo.transaction(()->{
                record(owner,shop,"ORDER","in-flight",BigDecimal.ONE,Map.of());entered.countDown();
                try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;
            }));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var deleting=executor.submit(()->service.removeShop(owner,shop));
            try{assertThrows(java.util.concurrent.TimeoutException.class,()->deleting.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));}
            finally{release.countDown();}
            writing.get(10,java.util.concurrent.TimeUnit.SECONDS);deleting.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThrows(BusinessException.class,()->repo.transaction(()->{repo.requireShopWrite(owner,shop);return null;}));
        repo.transaction(()->{new FinanceOrderCosts(repo).requestShop(owner.organizationId(),shop);return null;});
        finishDeletion(shop);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE shop_id=?",Integer.class,shop));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_rebuilds WHERE shop_id=?",Integer.class,shop));
    }
    @Test void lateAdvertisingResponseCannotRestoreDeletedShopOrOverwriteNewAuthorization()throws Exception{
        String shop=connect(owner,false);db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        var http=mock(com.yingdao.aicommerce.shop.infrastructure.MarketplaceHttp.class);
        var ads=new FinanceAds(repo,service,http,cipher);
        var advertiser=Map.<String,Object>of("advertiser_id","123456","advertiser_name","Old advertiser","currency","PHP","timezone","Asia/Manila");
        ads.connectVerified(owner,"123456",shop,"valid-advertiser-token","",advertiser);
        Instant oldLink=Instant.now().minusSeconds(10);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(http.get(any(URI.class),anyMap())).thenAnswer(invocation->{
            entered.countDown();assertTrue(release.await(60,java.util.concurrent.TimeUnit.SECONDS));
            return Map.of("code",0,"data",Map.of("list",List.of(Map.of("dimensions",Map.of("stat_time_day","2026-10-21"),
                    "metrics",Map.of("spend","10","impressions","1","clicks","1","conversion","0"))),"page_info",Map.of("total_page",1)));
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var running=executor.submit(ads::tick);assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            try{
                service.removeShop(owner,shop);finishDeletion(shop);
                assertThrows(BusinessException.class,()->ads.connectVerified(owner,"123456","","another-valid-token","",advertiser,oldLink));
                var newAdvertiser=new HashMap<>(advertiser);newAdvertiser.put("advertiser_name","New advertiser");
                ads.connectVerified(owner,"123456","","new-valid-advertiser-token","",newAdvertiser);
            }finally{release.countDown();}
            running.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }finally{ads.stop();}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        assertEquals("New advertiser",db.queryForObject("SELECT name FROM finance_ad_accounts WHERE advertiser_id='123456'",String.class));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_ad_accounts WHERE advertiser_id='123456'",String.class));
        assertNull(db.queryForObject("SELECT checkpoint_json FROM finance_ad_accounts WHERE advertiser_id='123456'",String.class));
    }
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
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP",kind,"sku-"+kind,"shared-order","sku-bill",kind.equals("SETTLEMENT")?"ORDER":"测试","SETTLED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
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
        assertEquals(List.of("order_id","product_skus","amount","order_created_time","label_cost_cny"),fields.get(owner,"ORDER").visible());
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
        assertEquals(settledTotals,map(reports.dashboardSummary(owner,q,reports.dashboardFacts(owner,q)).get("total")),"one dashboard scan must match deduplicated cost accounting");
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
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST'",Integer.class),
            "automatic recalculation keeps the current result without appending full snapshots");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='COST'",Integer.class),"standard cost snapshots stay separate from external expense ledger");
        assertEquals(0,new BigDecimal("91").compareTo(db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='SETTLEMENT'",BigDecimal.class)));
        var shippedRaw=Map.<String,Object>of("item_list",List.of(line),"shipped_time",1792551040L);
        var shipped=new Entry(shop,"SHOPEE","ORDER","cancelled-history","cancelled-history","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",shippedRaw);
        repo.save(owner,shipped);txs.execute(status->engine.processBatch(50));
        assertEquals(0,new BigDecimal("30").compareTo(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,id(owner,shipped))));
        var cancelled=new Entry(shop,"SHOPEE","ORDER","cancelled-history","cancelled-history","","订单","CANCELLED",filter.start(),"PHP",null,null,null,null,null,"API",shippedRaw);
        repo.save(owner,cancelled);
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","cancelled-tx","cancelled-history","cancelled-bill","结算","SETTLED",filter.start(),"PHP",BigDecimal.ZERO,null,null,null,null,"API",Map.of()));
        assertEquals(1,number(map(reports.summary(owner,q,"SETTLED_ORDER").get("total")).get("queued_count")),"queued corrections must hide stale cost");
        txs.execute(status->engine.processBatch(50));
        var corrected=repo.detail(owner,id(owner,cancelled));
        assertEquals("不计成本",corrected.get("product_cost_state"));assertEquals(0,decimal(corrected.get("product_cost_cny")).signum());
        var correctedSummary=map(reports.summary(owner,q,"SETTLED_ORDER").get("total"));
        assertEquals(correctedSummary,map(reports.dashboardSummary(owner,q,reports.dashboardFacts(owner,q)).get("total")));
        assertEquals(1,number(correctedSummary.get("excluded_count")));assertEquals(0,new BigDecimal("30").compareTo(decimal(correctedSummary.get("amount_cny"))));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_workspace_audit WHERE entity_type='ORDER_STANDARD_COST' AND entity_id=?",Integer.class,id(owner,cancelled)),
            "cancellation correction updates the current cost without a duplicate audit snapshot");
        repo.save(owner,cancelled);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));
        assertEquals(0,new BigDecimal("91").compareTo(db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='SETTLEMENT'",BigDecimal.class)),"cost correction must preserve the official ledger");
        String newGroup=service.saveGroup(owner,null,"未导入成本");
        txs.executeWithoutResult(status->service.configure(owner,shop,true,"PHP","Asia/Manila",newGroup));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds WHERE shop_id=?",String.class,shop));
        txs.execute(status->engine.seedAndFill());txs.execute(status->engine.processBatch(50));
        assertNull(repo.detail(owner,id(owner,entry)).get("product_cost_cny"),"changing group must not keep the previous group's cost");
    }
    @Test void sampleAndRefundCostSubsetsUseFirstSettlementAndNeverIncreaseTotal()throws Exception{
        String group=service.saveGroup(owner,null,"分类核对"),shop=groupedShop(owner,"classification",group);
        var reports=new FinanceCostReport(repo);
        var q=new Filter(filter.start(),filter.end(),shop,"","","day",group);
        for(int i=0;i<5;i++){
            String oid="subset-"+i;var raw=new LinkedHashMap<String,Object>();
            if(i==0)raw.put("order_type","SELLER_FUND_FREE_SAMPLE");
            if(i==1||i>=3)raw.put("is_sample_order",true);
            var order=new Entry(shop,"TIKTOK_SHOP","ORDER",oid,oid,"","ORDER","COMPLETED",filter.start().minusMonths(1),"PHP",BigDecimal.ZERO,null,null,null,null,"API",raw);
            repo.save(owner,order);
            db.update("INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json) VALUES(?,?,?,?,?,'READY',10,10,'test','{}')",
                    id(owner,order),owner.organizationId(),shop,oid,order.date());
            db.update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",id(owner,order));
            if(i!=3)repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx-"+i,oid,"bill","ORDER","SETTLED",i==4?filter.start().minusDays(1):filter.start(),"PHP",BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,"API",Map.of()));
        }
        // Two settlements and two refund records for the same sample still yield one order cost.
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx-repeat","subset-0","bill","ORDER","SETTLED",filter.start(),"PHP",BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,"API",Map.of()));
        for(int i=0;i<2;i++)repo.save(owner,new Entry(shop,"TIKTOK_SHOP","REFUND","refund-"+i,"subset-0","","退款","ACCEPTED",filter.start().plusDays(5),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        var all=map(reports.summary(owner,q,"SETTLED_ORDER").get("total"));
        var sample=map(reports.summary(owner,q,"SAMPLE_SETTLED").get("total"));
        var refunds=map(reports.summary(owner,q,"REFUND_SETTLED").get("total"));
        assertEquals(3,number(all.get("order_count")));assertEquals(0,decimal(all.get("amount_cny")).compareTo(decimal("30")));
        assertEquals(2,number(sample.get("order_count")));assertEquals(0,decimal(sample.get("amount_cny")).compareTo(decimal("20")));
        assertEquals(1,number(refunds.get("order_count")));assertEquals(0,decimal(refunds.get("amount_cny")).compareTo(decimal("10")));
        assertEquals(0,number(map(reports.summary(other,q,"SAMPLE_SETTLED").get("total")).get("order_count")));
        assertEquals(2,number(reports.orders(owner,q,"SAMPLE_SETTLED","",1).get("total")));
        var xlsx=new FinanceCostExport(repo).export(owner,q,"SAMPLE_SETTLED","");
        assertEquals(3,FinanceCostExportTest.sheet(xlsx,2).getElementsByTagName("row").getLength());
        var workspace=new FinanceCostWorkspace(repo,service);
        assertEquals(sample,map(workspace.costSummary(owner,q,"SAMPLE_SETTLED").get("total")));
        assertThrows(BusinessException.class,()->workspace.costSummary(owner,q,"unexpected"));
        assertEquals(all,map(reports.summary(owner,q,"SETTLED_ORDER").get("total")),"reading overlapping subsets cannot modify the main total");
        db.update("UPDATE finance_records SET revenue=120,amount=100 WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND order_id='subset-2'",owner.organizationId(),shop);
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","later-change","subset-2","bill","ORDER","SETTLED",filter.start().plusDays(1),"PHP",decimal("999"),decimal("999"),null,null,null,"API",Map.of()));
        var normal=rows(reports.orders(owner,q,"SETTLED_ORDER","",1).get("items")).stream().filter(r->"subset-2".equals(r.get("order_id"))).findFirst().orElseThrow();
        assertEquals(0,decimal(normal.get("amount")).signum(),"source buyer amount remains unchanged");
        assertEquals(0,decimal("120").compareTo(decimal(normal.get("settlement_goods_amount"))));
        assertEquals(0,decimal("100").compareTo(decimal(normal.get("settlement_net_amount"))));
        assertEquals("PHP",normal.get("settlement_currency"));
        assertEquals(filter.start().toString(),text(normal.get("first_settlement_date")));
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","missing-gross","subset-2","bill","ORDER","SETTLED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        normal=rows(reports.orders(owner,q,"SETTLED_ORDER","",1).get("items")).stream().filter(r->"subset-2".equals(r.get("order_id"))).findFirst().orElseThrow();
        assertNull(normal.get("settlement_goods_amount"),"partially known gross must not use buyer payment or net as a replacement");
        assertEquals(0,decimal("101").compareTo(decimal(normal.get("settlement_net_amount"))));
    }
    @Test void pendingCostFilterPagesExportsAndIncludesHistoricalMissingSkus()throws Exception{
        String group=service.saveGroup(owner,null,"历史成本"),shop=groupedShop(owner,"historical-cost-shop",group);
        var workspace=new FinanceCostWorkspace(repo,service);var txs=new TransactionTemplate(tx);
        txs.executeWithoutResult(s->workspace.importCosts(owner,"u",group,"仓库,型号,202610\n海外仓-菲律宾,PACK,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"unit.csv"));
        String oldId="";
        for(int i=0;i<60;i++){
            String id="historic-"+i,sku=i<55?"":"PACK",status=i==58?"CANCELLED":"COMPLETED";
            var raw=Map.<String,Object>of("buyer_username","do-not-expose","item_list",List.of(Map.of(
                "item_id",i<55?"old-product":"new-product","model_id",i<55?"old-sku":"new-sku",
                "model_sku",sku,"model_name","同名规格不可猜测成本","model_quantity_purchased",1)));
            var entry=new Entry(shop,"SHOPEE","ORDER",id,id,"","订单",status,filter.start().minusDays(1),"PHP",BigDecimal.TEN,null,null,null,null,"API",raw);
            repo.save(owner,entry);if(i==0)oldId=id(owner,entry);
            repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","settle-"+id,id,"","结算","SETTLED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",Map.of()));
        }
        var engine=new FinanceOrderCosts(repo);
        txs.execute(s->engine.seedAndFill());txs.execute(s->engine.processBatch(100));txs.execute(s->engine.processBatch(100));
        db.update("DELETE FROM finance_order_cost_jobs");
        db.update("UPDATE finance_cost_rebuilds SET status='DONE'");
        // A queued READY cost, an absent snapshot and an absent order must all remain pending.
        String queued=db.queryForObject("SELECT id FROM finance_records WHERE shop_id=? AND external_id='historic-55' AND kind='ORDER'",String.class,shop);
        db.update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES(?,?,?)",queued,owner.organizationId(),shop);
        db.update("DELETE FROM finance_order_costs WHERE shop_id=? AND order_id='historic-56'",shop);
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","no-order","missing-order","","结算","SETTLED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"API",Map.of()));
        var reports=new FinanceCostReport(repo);var q=new Filter(filter.start(),filter.end(),shop,"","","day",group);
        var first=reports.orders(owner,q,"SETTLED_ORDER","",1,"PENDING");
        var second=reports.orders(owner,q,"SETTLED_ORDER","",2,"PENDING");
        assertEquals(58,number(first.get("total")));assertEquals(50,rows(first.get("items")).size());assertEquals(8,rows(second.get("items")).size());
        var ids=new HashSet<String>();
        for(var page:List.of(first,second))for(var row:rows(page.get("items"))){
            assertTrue(number(row.get("product_cost_pending"))>0);assertTrue(ids.add(text(row.get("order_id"))));
            assertFalse(text(row.get("product_cost_issue")).isBlank());
            if("平台未返回商家 SKU".equals(row.get("product_cost_issue"))){
                var identity=rows(row.get("product_sku_details")).getFirst();
                assertEquals("old-sku",identity.get("skuId"));assertEquals("old-product",identity.get("productId"));
                assertEquals("同名规格不可猜测成本",identity.get("spec"));assertEquals("",identity.get("sellerSku"));
            }
        }
        assertEquals(61,number(reports.orders(owner,q,"SETTLED_ORDER","",1,"ALL").get("total")));
        assertEquals(0,number(reports.orders(other,q,"SETTLED_ORDER","",1,"PENDING").get("total")));
        var outside=new Filter(filter.end().plusDays(1),filter.end().plusDays(1),shop,"","","day");
        assertEquals(0,number(reports.orders(owner,outside,"SETTLED_ORDER","",1,"PENDING").get("total")));
        byte[] exported=new FinanceCostExport(repo).export(owner,q,"SETTLED_ORDER","","PENDING");
        assertEquals(59,FinanceCostExportTest.sheet(exported,2).getElementsByTagName("row").getLength());
        assertTrue(FinanceCostExportTest.sheet(exported,1).getDocumentElement().getTextContent().contains("仅待补齐订单"));
        // Push the unresolved identities beyond the recent-2000 sample without platform calls.
        var args=new ArrayList<Object[]>();
        for(int i=0;i<2001;i++)args.add(new Object[]{UUID.randomUUID().toString(),owner.organizationId(),owner.userId(),shop,"recent-"+i,"recent-"+i,filter.start().toString(),repo.write(Map.of("item_list",List.of(Map.of("item_id","recent-product","model_id","recent-sku","model_sku","PACK"))))});
        db.batchUpdate("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,category,status,record_date,currency,source,raw_json) VALUES(?,?,?,?,'SHOPEE','ORDER',?,?,'订单','COMPLETED',?,'PHP','API',?)",args);
        var data=workspace.costs(owner,shop);assertEquals(1,number(data.get("historicalPendingSkuCount")));
        var historic=rows(data.get("products")).stream().filter(p->"old-sku".equals(p.get("sku_id"))).findFirst().orElseThrow();
        assertEquals("",historic.get("seller_sku"));assertEquals(55,number(historic.get("pending_cost_orders")));
        assertEquals(true,historic.get("historical_cost_pending"));assertFalse(repo.write(data).contains("do-not-expose"));
        assertTrue(map(data.get("mappings")).isEmpty(),"blank merchant codes must not infer a match from a similar name");
        // An explicitly confirmed identity mapping repairs the historical orders through the existing queue.
        txs.executeWithoutResult(s->workspace.mapping(owner,"u",shop,Map.of("product_id","old-product","sku_id","old-sku","warehouse","海外仓-菲律宾","code","PACK","factor",1,"confirmed",true,"version",0)));
        String historicalRecord=oldId;
        db.update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES(?,?,?)",historicalRecord,owner.organizationId(),shop);
        txs.execute(s->engine.processBatch(100));
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_order_costs WHERE order_record_id=?",String.class,historicalRecord));
        assertEquals(0,BigDecimal.TEN.compareTo(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,historicalRecord)));
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
    @Test void independentFilesRestoreTheirOwnPricesAndNeverRemoveManualCosts(){
        String group=service.saveGroup(owner,null,"独立成本"),shop=groupedShop(owner,"independent",group);
        String another=service.saveGroup(owner,null,"其他分组");
        var workspace=new FinanceCostWorkspace(repo,service);var txs=new TransactionTemplate(tx);var engine=new FinanceOrderCosts(repo);
        byte[] a="仓库,型号,202610\n海外仓-菲律宾,PACK,10\n海外仓-菲律宾,ONLY-A,5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b="仓库,型号,202610\n海外仓-菲律宾,PACK,12\n海外仓-菲律宾,ONLY-B,8\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String aid=text(txs.execute(t->workspace.importCosts(owner,"u",group,a,"a.csv")).get("id"));
        String bid=text(txs.execute(t->workspace.importCosts(owner,"u",group,b,"b.csv")).get("id"));
        var catalog=workspace.book(owner,group);
        assertEquals(3,rows(catalog.get("costs")).size());
        assertEquals(0,new BigDecimal("12").compareTo(decimal(map(rows(catalog.get("costs")).getFirst().get("prices")).get("2026-10"))));
        assertEquals(2,rows(workspace.costs(owner,shop).get("imports")).size());
        var body=new LinkedHashMap<String,Object>(Map.of("product_id","p","sku_id","v","code","PACK","warehouse","海外仓-菲律宾","factor","2","unit","包","unitPriceCny","3.5","confirmed",true,"version",0));
        String manual=text(txs.execute(t->workspace.manualMapping(owner,"u",shop,body)).get("id"));
        var raw=Map.<String,Object>of("item_list",List.of(Map.of("item_id","p","model_id","v","model_sku","PACK","model_quantity_purchased",3)));
        var order=Map.<String,Object>of("platform","SHOPEE","status","COMPLETED","record_date",filter.start());
        assertEquals(0,new BigDecimal("21").compareTo(decimal(FinanceOrderCosts.calculate(order,raw,engine.context("org",shop)).get("amountCny"))));
        assertThrows(BusinessException.class,()->txs.executeWithoutResult(t->workspace.removeSource(other,"other",group,bid)));
        assertThrows(BusinessException.class,()->txs.executeWithoutResult(t->workspace.removeSource(owner,"u",another,bid)));
        txs.executeWithoutResult(t->workspace.removeSource(owner,"u",group,bid));
        catalog=workspace.book(owner,group);
        assertEquals(2,rows(catalog.get("costs")).size(),"only B-exclusive rows disappear");
        assertEquals(0,new BigDecimal("10").compareTo(decimal(map(rows(catalog.get("costs")).getFirst().get("prices")).get("2026-10"))));
        assertEquals(1,rows(catalog.get("manualCosts")).size());
        txs.executeWithoutResult(t->workspace.removeSource(owner,"u",group,aid));
        assertTrue(rows(workspace.book(owner,group).get("costs")).isEmpty());
        assertEquals(0,new BigDecimal("21").compareTo(decimal(FinanceOrderCosts.calculate(order,raw,engine.context("org",shop)).get("amountCny"))),"manual price survives deleting every Excel file");
        txs.executeWithoutResult(t->workspace.importCosts(owner,"u",group,b,"b.csv"));
        assertEquals(0,new BigDecimal("21").compareTo(decimal(FinanceOrderCosts.calculate(order,raw,engine.context("org",shop)).get("amountCny"))),"same-code Excel never replaces explicit manual price");
        assertThrows(BusinessException.class,()->txs.executeWithoutResult(t->workspace.changeManual(owner,"u",group,manual,Map.of("version",0),true)));
        txs.executeWithoutResult(t->workspace.changeManual(owner,"u",group,manual,Map.of("version",1,"unit","包","unitPriceCny","4","confirmed",true),false));
        assertEquals(0,new BigDecimal("24").compareTo(decimal(FinanceOrderCosts.calculate(order,raw,engine.context("org",shop)).get("amountCny"))));
        txs.executeWithoutResult(t->workspace.changeManual(owner,"u",group,manual,Map.of("version",2),true));
        var deleted=FinanceOrderCosts.calculate(order,raw,engine.context("org",shop));
        assertEquals("INCOMPLETE",deleted.get("status"));assertNull(deleted.get("amountCny"));
        assertTrue(text(rows(deleted.get("lines")).getFirst().get("issue")).contains("手动成本已删除"),"must not fall back to same-code Excel after manual deletion");
        assertEquals(2,rows(workspace.book(owner,group).get("costs")).size(),"deleting manual costs preserves Excel");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));
    }
    @Test void manualMappingIsAtomicVersionedAndSupportsGroupsWithoutFiles(){
        String group=service.saveGroup(owner,null,"手动专用"),shop=groupedShop(owner,"manual-only",group);
        String empty=service.saveGroup(owner,null,"没有店铺");
        var workspace=new FinanceCostWorkspace(repo,service);var txs=new TransactionTemplate(tx);var engine=new FinanceOrderCosts(repo);
        assertEquals(empty,workspace.costs(owner,"",empty).get("groupId"));
        assertEquals("",workspace.costs(owner,"",empty).get("shopId"));
        assertThrows(BusinessException.class,()->workspace.costs(other,"",empty));
        assertThrows(BusinessException.class,()->workspace.costs(owner,shop,empty));
        var body=new LinkedHashMap<String,Object>(Map.of("product_id","p","sku_id","v","code","CUSTOM","warehouse","海外仓-菲律宾","factor","2","unit","片","unitPriceCny","0","confirmed",true,"version",0));
        assertThrows(BusinessException.class,()->txs.execute(t->workspace.manualMapping(owner,"u",shop,body)),"zero is never inferred");
        body.put("unitPriceCny","3");body.put("version",1);
        assertThrows(BusinessException.class,()->txs.execute(t->workspace.manualMapping(owner,"u",shop,body)),"stale association rolls back the new manual cost and book");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_manual_costs",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_cost_books",Integer.class));
        body.put("version",0);
        String id=text(txs.execute(t->workspace.manualMapping(owner,"u",shop,body)).get("id"));
        assertEquals(1,rows(workspace.costs(owner,shop).get("manualCosts")).size());
        assertTrue(rows(workspace.costs(owner,shop).get("imports")).isEmpty());
        var entry=new Entry(shop,"SHOPEE","ORDER","manual-order","manual-order","","订单","COMPLETED",filter.start(),"PHP",BigDecimal.TEN,null,null,null,null,"API",
            Map.of("item_list",List.of(Map.of("item_id","p","model_id","v","model_sku","","model_quantity_purchased",2))));
        repo.save(owner,entry);
        txs.execute(t->engine.seedAndFill());txs.execute(t->engine.processBatch(50));
        assertEquals(0,new BigDecimal("12").compareTo(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,id(owner,entry))));
        var foreign= new LinkedHashMap<String,Object>(body);foreign.put("manualCostId",id);foreign.put("code","CUSTOM");
        String sibling=groupedShop(owner,"manual-sibling",empty);
        assertThrows(BusinessException.class,()->txs.executeWithoutResult(t->workspace.mapping(owner,"u",sibling,foreign)));
        txs.executeWithoutResult(t->workspace.changeManual(owner,"u",group,id,Map.of("version",1,"unit","片","unitPriceCny","0","confirmed",true,"zeroConfirmed",true),false));
        txs.execute(t->engine.seedAndFill());txs.execute(t->engine.processBatch(50));
        assertEquals(0,db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,id(owner,entry)).signum());
        txs.executeWithoutResult(t->workspace.changeManual(owner,"u",group,id,Map.of("version",2),true));
        txs.execute(t->engine.seedAndFill());txs.execute(t->engine.processBatch(50));
        assertNull(db.queryForObject("SELECT amount_cny FROM finance_order_costs WHERE order_record_id=?",BigDecimal.class,id(owner,entry)));
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
        record(owner,shop,"SETTLEMENT","kept-settlement",BigDecimal.TEN,Map.of());
        db.update("UPDATE finance_sync_states SET checkpoint_json='{\"from\":1,\"to\":2}' WHERE shop_id=?",shop);
        var repeated=auth.persistGrant(colleague,MarketplacePlatform.SHOPEE,grant("10001"),otherGroup,"");
        assertEquals(0,repeated.created());assertEquals(1,repeated.existing());assertEquals(1,service.shops(owner).size());
        assertEquals("u",db.queryForObject("SELECT user_id FROM finance_connections WHERE id=?",String.class,shop));
        assertEquals(original,service.shops(colleague).getFirst().get("group_id"));
        assertEquals(1,number(repo.details(colleague,filter,"ORDER",1,50).get("total")));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE checkpoint_json IS NOT NULL",Integer.class));
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
        record(owner,shop,"SETTLEMENT","refunded-order-settlement",BigDecimal.ZERO,Map.of());
        var details=rows(repo.details(owner,filter,"REFUND",1,50).get("items"));assertEquals(4,details.size());
        assertTrue(details.stream().allMatch(r->decimal(r.get("platform_refund_amount")).compareTo(new BigDecimal("209"))==0));
    }
    @Test void syncProgressNeverTurnsOrderRepairSentinelsIntoEpochDates(){
        String shop=connect(owner,false);
        db.update("""
            INSERT INTO finance_sync_states(organization_id,user_id,shop_id,channel,status,checkpoint_json)
            VALUES('org','u',?,'ORDER_REPAIR','SYNCING','{"from":0,"to":0,"until":0,"after":"keep-cursor"}')
            ON DUPLICATE KEY UPDATE status='SYNCING',checkpoint_json=VALUES(checkpoint_json)
            """,shop);
        var repair=service.syncStates(owner).stream().filter(r->r.get("channel").equals("ORDER_REPAIR")).findFirst().orElseThrow();
        assertEquals("ORDER_ID",repair.get("progress_mode"));
        for(String field:List.of("window_start","window_end","target_until"))assertNull(repair.get(field));
        assertEquals("keep-cursor",db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(checkpoint_json,'$.after')) FROM finance_sync_states WHERE shop_id=? AND channel='ORDER_REPAIR'",String.class,shop));
        db.update("UPDATE finance_sync_states SET checkpoint_json='{\"from\":1788192000,\"to\":1788796800,\"until\":1790784000}' WHERE shop_id=? AND channel='ORDER'",shop);
        var history=service.syncStates(owner).stream().filter(r->r.get("channel").equals("ORDER")).findFirst().orElseThrow();
        assertEquals("TIME_RANGE",history.get("progress_mode"));assertEquals("1788192000",history.get("window_start"));
    }
    @Test void compactDashboardPreservesEveryDimensionCurrencyAndVisibility(){
        String group=service.saveGroup(owner,null,"查询核对"),shop=groupedShop(owner,"speed-main",group);
        String ungrouped=groupedShop(owner,"speed-ungrouped",group),removed=groupedShop(owner,"speed-removed",group);
        db.update("UPDATE finance_connections SET group_id=NULL WHERE id=?",ungrouped);
        var fixtures=new ArrayList<Object[]>();
        for(String target:List.of(shop,ungrouped,removed,"UNASSIGNED","unknown-shop"))
            for(String kind:List.of("ORDER","FEE","SETTLEMENT","BILL","AD","REFUND"))
                for(String currency:List.of("PHP","CNY"))
                    for(int i=0;i<3;i++)for(Scope scope:List.of(owner,other))
                        fixtures.add(new Object[]{UUID.randomUUID().toString(),scope.organizationId(),scope.userId(),target,kind,kind+currency+i,
                            "category-"+i,"status-"+i,java.sql.Date.valueOf(filter.start().plusDays(i)),currency,
                            scope.equals(other)?new BigDecimal("999"):i==0?null:i==1?BigDecimal.ZERO:new BigDecimal("-1.235678"),
                            new BigDecimal("0.123456"),(long)i,new BigDecimal("0.25")});
        db.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,category,status,
              record_date,currency,amount,revenue,impressions,conversions,source,raw_json)
            VALUES(?,?,?,?,'SHOPEE',?,?,?,?,?,?,?,?,?,?,'API','{}')
            """,fixtures);
        repo.save(owner,new Entry(shop,"SHOPEE","BILL","imported","","","bill","IMPORTED",filter.start(),"PHP",new BigDecimal("99999"),null,null,null,null,"IMPORT",Map.of()));
        service.removeShop(owner,removed);
        for(String selectedShop:List.of("",shop,ungrouped))for(String selectedGroup:List.of("",group,"UNGROUPED"))
            for(String currency:List.of("","PHP","CNY")){
                var f=new Filter(filter.start(),filter.end().plusDays(2),selectedShop,"",currency,"day",selectedGroup);
                var buckets=repo.dashboardBuckets(owner,f);
                var historical=repo.dashboardBuckets(owner,f,false);
                var retained=buckets.stream().filter(r->!Set.of("ORDER","REFUND").contains(text(r.get("kind")))).toList();
                assertEquals(new HashSet<>(retained),new HashSet<>(historical),
                        "skip only creation-date order/refund buckets that the historical dashboard replaces");
                for(String dimension:List.of("kind","shop_id,kind","record_date,kind","category,kind","status,kind")){
                    var source=buckets.stream().filter(r->!dimension.equals("category,kind")||"FEE".equals(r.get("kind")))
                        .filter(r->!dimension.equals("status,kind")||"ORDER".equals(r.get("kind"))).toList();
                    var actual=FinanceService.aggregate(source,dimension.split(","));
                    var expected=repo.grouped(owner,f,dimension);
                    assertEquals(expected.size(),actual.size(),dimension);
                    var dimensions=new ArrayList<>(List.of(dimension.split(",")));dimensions.add("currency");
                    for(var row:expected){
                        var found=actual.stream().filter(a->dimensions.stream().allMatch(d->Objects.equals(a.get(d),row.get(d)))).findFirst().orElseThrow();
                        for(String field:List.of("records","known_amounts","amount","revenue","impressions","clicks","conversions")){
                            if(row.get(field)==null)assertNull(found.get(field),field);
                            else assertEquals(0,decimal(row.get(field)).compareTo(decimal(found.get(field))),dimension+" "+field);
                        }
                    }
                }
            }
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
        assertEquals(0,new BigDecimal("100.25").compareTo(decimal(rows(service.dashboard(owner,wider,false,null).get("totals")).stream().filter(r->"ORDER".equals(r.get("kind"))).findFirst().orElseThrow().get("amount"))),"unsettled older orders are excluded by the settlement-date scope");
        var converted=rows(service.dashboard(owner,filter,true,"fixed").get("totals"));
        assertEquals(0,new BigDecimal("12.53125").compareTo(decimal(converted.getFirst().get("amount"))));
        service.removeShop(owner,id);
        assertTrue(rows(service.dashboard(owner,filter,false,null).get("totals")).isEmpty());
        assertFalse(rows(service.dashboard(other,filter,false,null).get("totals")).isEmpty());
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_shop_dashboard'",Integer.class)>0);
    }
    @Test void authorizationIsIndependentEncryptedScopedAndStartsSyncAutomatically(){
        String id=connect(owner,false),second=connect(other,false);assertNotEquals(id,second);
        assertEquals(1,service.shops(owner).size());assertEquals(true,service.shops(owner).getFirst().get("enabled"));
        assertEquals(5,service.syncStates(owner).size());
        assertThrows(BusinessException.class,()->service.ownedShop(other,id));
        String secret=db.queryForObject("SELECT access_token_encrypted FROM finance_connections WHERE id=?",String.class,id);assertNotEquals("finance-access",secret);
        assertEquals("finance-access",cipher.decrypt(secret,"org","finance-shop:u:"+id));
        assertThrows(IllegalStateException.class,()->cipher.decrypt(secret,"org","legacy"));
        service.configure(owner,id,true,"PHP","Asia/Manila");assertEquals(5,service.syncStates(owner).size());
        assertEquals("commerce-secret-unchanged",db.queryForObject("SELECT secret FROM shop_credentials WHERE id='legacy'",String.class));
    }
    @Test void tiktokAuthorizationCreatesAllLanesAndReauthorizationPreservesActiveWork(){
        String group=service.saveGroup(owner,null,"母婴");
        var grant=new MarketplaceAccess.AuthorizationGrant(tokens(false),List.of(
                new MarketplaceAccess.RemoteShop("tk-auto",null,"shop-cipher","LOCAL","TK auto","PH",null,null)));
        auth.persistGrant(owner,MarketplacePlatform.TIKTOK_SHOP,grant,group,"");
        String shop=text(service.shops(owner).getFirst().get("id"));
        assertEquals(true,service.shops(owner).getFirst().get("enabled"));
        assertEquals(Set.of("ORDER","ORDER_LIVE","ORDER_REPAIR","REFUND","SETTLEMENT","PENDING"),
                service.syncStates(owner).stream().map(r->text(r.get("channel"))).collect(java.util.stream.Collectors.toSet()));
        db.update("""
            UPDATE finance_sync_states SET checkpoint_json='{"from":1,"to":2}',coverage_start='2026-08-01',
              next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),status='ERROR',failures=3,error_message='old token'
            WHERE shop_id=?
            """,shop);
        db.update("""
            UPDATE finance_sync_states SET status='RUNNING',lease_token='active-worker',
              lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE shop_id=? AND channel='ORDER'
            """,shop);
        var active=db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel='ORDER'",shop);
        service.configure(owner,shop,false,"PHP","Asia/Manila",group);
        auth.persistGrant(new Scope("org","colleague"),MarketplacePlatform.TIKTOK_SHOP,grant,group,shop);
        assertEquals(active,db.queryForMap("SELECT * FROM finance_sync_states WHERE shop_id=? AND channel='ORDER'",shop));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE shop_id=? AND checkpoint_json IS NOT NULL AND coverage_start='2026-08-01' AND user_id='u'",Integer.class,shop));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_sync_states WHERE shop_id=? AND status='READY' AND failures=0 AND error_message IS NULL AND next_at<=UTC_TIMESTAMP(6)",Integer.class,shop));
        assertEquals(true,service.shops(owner).getFirst().get("enabled"));
        // A later manual pause remains paused until this shop is explicitly enabled/reauthorized.
        service.configure(owner,shop,false,"PHP","Asia/Manila",group);
        auth.persistGrant(owner,MarketplacePlatform.SHOPEE,grant("another-shop"),group,"");
        assertEquals(0,db.queryForObject("SELECT enabled FROM finance_connections WHERE id=?",Integer.class,shop));
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
        record(owner,id,"SETTLEMENT","settlement",new BigDecimal("80"),Map.of());
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
    byte[] chargeCsv(String order,String shop,String fee){return ("订单编号,店铺,实际订单金额,代刷费CNY\n"+order+","+shop+",100,"+fee+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    Entry dated(String shop,String kind,String external,String order,String bill,LocalDate day,String net,Map<String,Object> raw){
        return new Entry(shop,"SHOPEE",kind,external,order,bill,"结算","SETTLED",day,"PHP",decimal(net),null,null,null,null,"API",raw);
    }
    @Test void batchNumberSearchPreservesSettlementScopeAndOriginalOrderTimes()throws Exception{
        String group=service.saveGroup(owner,null,"日期核对"),shop=groupedShop(owner,"date-search",group);
        String order="585766520348574952",later="586208660231587825";
        var created=Instant.parse("2026-08-28T05:27:54Z");
        var september=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),shop,"","","day",group);
        repo.save(owner,dated(shop,"ORDER",order,order,"",LocalDate.of(2026,8,28),"100",Map.of("create_time",created.getEpochSecond())));
        repo.save(owner,dated(shop,"ORDER",later,later,"",LocalDate.of(2026,9,23),"100",Map.of("create_time",Instant.parse("2026-09-23T01:33:43Z").getEpochSecond())));
        repo.save(owner,dated(shop,"SETTLEMENT","s1",order,"sep-bill",LocalDate.of(2026,9,1),"80",Map.of()));
        repo.save(owner,dated(shop,"SETTLEMENT","s2",later,"oct-bill",LocalDate.of(2026,10,4),"80",Map.of()));
        repo.save(owner,dated(shop,"BILL","sep-bill","","sep-bill",LocalDate.of(2026,9,1),"80",Map.of()));
        repo.save(owner,dated(shop,"REFUND","refund-id",order,"",LocalDate.of(2026,8,31),"10",Map.of("create_time",created.plusSeconds(86400).getEpochSecond())));
        repo.save(owner,dated(shop,"FEE","fee-id",order,"sep-bill",LocalDate.of(2026,9,1),"20",Map.of()));
        String query=order+"\n"+later+"，"+order;
        for(String kind:List.of("ORDER","REFUND","SETTLEMENT","FEE","BILL")){
            var result=repo.details(owner,september,kind,1,50,query);
            assertEquals(1,number(result.get("total")),kind);
            var item=rows(result.get("items")).getFirst();
            assertEquals(created.toString(),item.get("order_created_time"),kind+" uses the related order creation, even when it has no line items");
            assertEquals("Asia/Manila",item.get("order_timezone"));
            assertEquals(0,number(repo.details(other,september,kind,1,50,query).get("total")));
        }
        assertEquals(1,number(repo.details(owner,september,"REFUND",1,50,"refund-id").get("total")));
        assertEquals(0,number(repo.details(owner,september,"ORDER",1,50,later).get("total")));
        assertEquals(0,number(repo.details(owner,september,"ORDER",1,50,order.substring(0,12)).get("total")),"no partial identifier matching");
        assertEquals(5,number(repo.details(owner,september,null,1,50,query).get("total")));
        assertEquals(1,number(repo.reconciliationPage(owner,september,1,50,order).get("total")),"order search finds its entire bill without trimming reconciliation transactions");
        assertEquals(0,decimal(repo.reconciliation(owner,september,100,order).getFirst().get("difference")).compareTo(BigDecimal.ZERO));
        var costs=new FinanceCostReport(repo).orders(owner,september,"SETTLED_ORDER","",1,"ALL",query);
        assertEquals(1,number(costs.get("total")));assertEquals(order,rows(costs.get("items")).getFirst().get("order_id"));
        var exported=new FinanceCostExport(repo).export(owner,september,"SETTLED_ORDER","","ALL",query);
        assertEquals(2,FinanceCostExportTest.sheet(exported,2).getElementsByTagName("row").getLength());
        var csv=FinanceWorkbook.read(controller().export(Map.of("start","2026-09-01","end","2026-09-30","view","ORDER","format","csv","shopId",shop,"numbers",query),principal()).getBody(),"report.csv");
        assertEquals(2,csv.size());assertTrue(csv.getLast().contains("2026-08-28 13:27:54"));
        assertTrue(csv.getLast().contains(order));assertFalse(csv.getLast().contains(later));
        var fields=new FinanceFields(repo);
        assertTrue(fields.get(owner,"REFUND").visible().contains("order_created_time"));
        db.update("INSERT INTO finance_view_preferences(organization_id,user_id,view_key,preferences_json) VALUES(?,?,?,?)",
                owner.organizationId(),owner.userId(),"REFUND",repo.write(Map.of("visible",List.of("order_id"),"exported",List.of("order_id"),"productSkuColumnVersion",1)));
        assertTrue(fields.get(owner,"REFUND").visible().contains("order_created_time"),"old preferences receive the new default once");
        fields.save(owner,"REFUND",List.of("order_id"),List.of("order_id"));
        assertEquals(List.of("order_id"),fields.get(owner,"REFUND").visible(),"explicitly hiding a column remains respected");
    }
    @Test void actualSettlementDateIncludesPriorMonthOrdersAndKeepsCostAndExportScopeAligned()throws Exception{
        String group=service.saveGroup(owner,null,"跨月结算"),shop=groupedShop(owner,"cross-month",group);
        var august=dated(shop,"ORDER","august","august","",LocalDate.of(2026,8,15),"100",Map.of());
        var september=dated(shop,"ORDER","september","september","",LocalDate.of(2026,9,10),"200",Map.of());
        repo.save(owner,august);repo.save(owner,september);
        repo.save(owner,dated(shop,"SETTLEMENT","s-sep","august","bill-sep",LocalDate.of(2026,9,1),"80",
            Map.of("order_income",Map.of("original_price",150,"seller_discount",50,"commission_fee",20))));
        repo.save(owner,dated(shop,"SETTLEMENT","s-oct","september","bill-oct",LocalDate.of(2026,10,4),"170",
            Map.of("order_income",Map.of("original_price",300,"seller_discount",100,"commission_fee",30))));
        repo.save(owner,dated(shop,"REFUND","refund-aug","august","",LocalDate.of(2026,8,30),"10",Map.of()));
        repo.save(owner,dated(shop,"SETTLEMENT","later","august","adjustment-oct",LocalDate.of(2026,10,7),"5",Map.of()));
        for(var pair:List.of(Map.entry(9,"august"),Map.entry(10,"september"))){
            var start=LocalDate.of(2026,pair.getKey(),1);
            var f=new Filter(start,start.withDayOfMonth(start.lengthOfMonth()),shop,"","","day",group);
            var list=repo.details(new Scope("org","colleague"),f,"ORDER",1,50);
            assertEquals(1,number(list.get("total")));var item=rows(list.get("items")).getFirst();
            assertEquals(pair.getValue(),item.get("order_id"));assertEquals(pair.getKey(),LocalDate.parse(text(item.get("record_date"))).getMonthValue());
            assertNotEquals(item.get("record_date"),item.get("source_record_date"));
            assertEquals(item.get("record_date"),repo.detail(owner,text(item.get("id"))).get("record_date"));
            var mixed=rows(repo.details(owner,f,null,1,50).get("items"));
            assertEquals(1,mixed.stream().filter(r->"ORDER".equals(r.get("kind"))).count());
            assertEquals(1,number(map(new FinanceCostReport(repo).summary(owner,f,"ORDER").get("total")).get("order_count")));
            var csv=FinanceWorkbook.read(controller().export(Map.of("start",start.toString(),"end",f.end().toString(),"view","ORDER","format","csv","shopId",shop),principal()).getBody(),"report.csv");
            assertEquals(2,csv.size());assertTrue(csv.getLast().contains(pair.getValue()));
        }
        var septemberFilter=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),shop,"","","day",group);
        assertEquals(1,number(repo.details(owner,septemberFilter,"REFUND",1,50).get("total")));
        assertEquals(1,number(map(new FinanceCostReport(repo).summary(owner,septemberFilter,"REFUND").get("total")).get("order_count")));
        assertEquals(2,FinanceCostExportTest.sheet(new FinanceCostExport(repo).export(owner,septemberFilter,"REFUND",""),2).getElementsByTagName("row").getLength());
        assertEquals(0,number(repo.details(other,septemberFilter,"ORDER",1,50).get("total")));
    }
    @Test void rebuildingDerivedFeesIsIdempotentAndNeverAltersNetOrUnrelatedImportedFees(){
        String shop=connect(owner,false);
        var raw=Map.<String,Object>of("order_income",Map.of("original_price",899,"seller_discount",349,"voucher_from_seller",165,
            "commission_fee",54,"service_fee",33,"seller_transaction_fee",13,"order_ams_commission_fee",6,"seller_order_processing_fee",1));
        var settlement=dated(shop,"SETTLEMENT","tx_1","order","bill",filter.start(),"278",raw);
        repo.save(owner,settlement);repo.save(owner,settlement);
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='FEE'",BigDecimal.class).compareTo(decimal("107")));
        assertEquals(0,db.queryForObject("SELECT revenue FROM finance_records WHERE kind='SETTLEMENT'",BigDecimal.class).compareTo(decimal("385")));
        repo.save(owner,new Entry(shop,"SHOPEE","FEE","tx_1:import","order","bill","导入费用","IMPORTED",filter.start(),"PHP",BigDecimal.ONE,null,null,null,null,"IMPORT",Map.of()));
        repo.save(owner,dated(shop,"FEE","txX1:other","order","bill",filter.start(),"2",Map.of()));
        var updated=dated(shop,"SETTLEMENT","tx_1","order","bill",filter.start(),"278",
            Map.of("order_income",Map.of("original_price",300,"seller_discount",22)));
        repo.save(owner,updated);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE external_id='txX1:other'",Integer.class),"LIKE escapes prevent deleting another transaction");
        repo.save(owner,dated(shop,"BILL","bill","","bill",filter.start(),"278",Map.of()));
        var view=repo.detail(owner,id(owner,updated));assertEquals(0,decimal(view.get("amount")).compareTo(decimal("278")));
        assertEquals(0,decimal(view.get("settled_net_amount")).compareTo(decimal("278")));
        assertEquals(0,db.queryForObject("SELECT amount FROM finance_records WHERE kind='SETTLEMENT'",BigDecimal.class).compareTo(decimal("278")));
        assertEquals("MATCHED",repo.reconciliation(owner,filter).getFirst().get("result"));
        assertEquals(0,decimal(rows(repo.details(owner,filter,"BILL",1,50).get("items")).getFirst().get("amount")).compareTo(decimal("278")));
        var original=db.queryForMap("SELECT amount,raw_json,record_date,updated_at FROM finance_records WHERE id=?",id(owner,updated));
        assertTrue(repo.rebuildSettlement(owner,shop,id(owner,updated)));
        assertEquals(original,db.queryForMap("SELECT amount,raw_json,record_date,updated_at FROM finance_records WHERE id=?",id(owner,updated)),"backfill changes only derived revenue and fee rows");
    }
    @Test void repeatedSettlementSyncPreservesUnchangedFeesAndUpdatesOnlyChangedFields(){
        String shop=connect(owner,false);
        var raw=Map.<String,Object>of("order_income",Map.of("original_price",100,"seller_discount",10,"commission_fee",5));
        var first=dated(shop,"SETTLEMENT","stable","order","bill",filter.start(),"85",raw);
        repo.save(owner,first);
        db.update("UPDATE finance_records SET updated_at='2000-01-01 00:00:00' WHERE kind='FEE'");
        var before=db.queryForList("SELECT id,amount,raw_json,updated_at FROM finance_records WHERE kind='FEE' ORDER BY id");
        repo.save(owner,first);
        repo.rebuildSettlement(owner,shop,id(owner,first));
        assertEquals(before,db.queryForList("SELECT id,amount,raw_json,updated_at FROM finance_records WHERE kind='FEE' ORDER BY id"),
                "unchanged sync and backfill must not rewrite fee rows or timestamps");
        repo.save(owner,dated(shop,"SETTLEMENT","stable","order","bill",filter.start(),"82",
                Map.of("order_income",Map.of("original_price",100,"seller_discount",13,"commission_fee",5))));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE' AND updated_at='2000-01-01 00:00:00'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT SUM(amount) FROM finance_records WHERE kind='FEE'",BigDecimal.class).compareTo(decimal("5")));
        repo.save(owner,dated(shop,"SETTLEMENT","stable","order","bill",filter.start(),"95",
                Map.of("order_income",Map.of("original_price",100,"commission_fee",5))));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE'",Integer.class),"obsolete fields are removed");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE' AND updated_at='2000-01-01 00:00:00'",Integer.class),"unchanged commission survives other field removals");
    }
    @Test void tiktokHistoricalRebuildUsesExcelStatementDayAndPreservesProviderAmounts(){
        String shop=connect(owner,false);LocalDate generated=LocalDate.of(2026,10,1);
        long timestamp=generated.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","BILL","bill-month-end","","bill-month-end","","SETTLED",generated,"PHP",decimal("80"),null,null,null,null,"API",Map.of("statement_time",timestamp,"payment_time",timestamp+86400,"payment_status","PAID")));
        var raw=Map.<String,Object>of("type","ORDER","settlement_amount","80","revenue_amount","90","fee_and_tax_amount","-10","revenue_breakdown",Map.of("subtotal_before_discount_amount","120","seller_discount_amount","-30"));
        var entry=new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx-month-end","old-order","bill-month-end","ORDER","SETTLED",generated,"PHP",decimal("80"),null,null,null,null,"API",raw);
        repo.save(owner,entry);String record=id(owner,entry);
        var before=db.queryForMap("SELECT amount,updated_at FROM finance_records WHERE id=?",record);
        assertTrue(repo.rebuildSettlement(owner,shop,record));assertTrue(repo.rebuildSettlement(owner,shop,record));
        assertEquals("2026-10-02",db.queryForObject("SELECT record_date FROM finance_records WHERE id=?",String.class,record));
        assertEquals(before,db.queryForMap("SELECT amount,updated_at FROM finance_records WHERE id=?",record));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='FEE' AND record_date='2026-10-02'",Integer.class));
        assertEquals(LocalDate.of(2026,10,2),repo.tiktokBillDay(owner,shop,"bill-month-end"));
    }
    @Test void boundedSettlementBatchRollsBackEveryChangeWhenAnyRecordFails(){
        String shop=connect(owner,false);
        var good=dated(shop,"SETTLEMENT","good","one","bill",filter.start(),"80",Map.of("order_income",Map.of("original_price",100,"seller_discount",20)));
        var bad=new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","missing-bill","two","not-present","ORDER","SETTLED",
                filter.start(),"PHP",decimal("70"),null,null,null,null,"API",
                Map.of("type","ORDER","settlement_amount","70","revenue_breakdown",Map.of("subtotal_before_discount_amount","90")));
        repo.save(owner,good);repo.save(owner,bad);
        db.update("UPDATE finance_records SET revenue=NULL WHERE kind='SETTLEMENT'");
        var before=db.queryForList("SELECT id,amount,revenue,record_date,raw_json,updated_at FROM finance_records ORDER BY id");
        assertThrows(BusinessException.class,()->repo.rebuildSettlements(owner,shop,List.of(id(owner,good),id(owner,bad))));
        assertEquals(before,db.queryForList("SELECT id,amount,revenue,record_date,raw_json,updated_at FROM finance_records ORDER BY id"));
        assertEquals(1,repo.rebuildSettlements(owner,shop,List.of(id(owner,good))));
        assertThrows(IllegalArgumentException.class,()->repo.rebuildSettlements(owner,shop,Collections.nCopies(21,id(owner,good))));
    }
    @Test void completionRepairMovesCrossMonthOrdersFeesCostsAndExportsTogetherWithoutChangingAmounts(){
        String shop=connect(owner,false);
        for(String oid:List.of("aug-order","sept-order")){
            long generated=Instant.parse(oid.equals("aug-order")?"2026-08-31T00:00:00Z":"2026-09-30T00:00:00Z").getEpochSecond();
            var raw=Map.<String,Object>of("_finance_statement_time",generated,"type","ORDER","settlement_amount","80","revenue_amount","90","fee_and_tax_amount","-10",
                    "revenue_breakdown",Map.of("subtotal_before_discount_amount","100","seller_discount_amount","-10"));
            LocalDate placed=LocalDate.parse(oid.equals("aug-order")?"2026-08-28":"2026-09-28");
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP","ORDER",oid,oid,"","订单","COMPLETED",placed,"PHP",decimal("100"),null,null,null,null,"API",Map.of("id",oid,"create_time",placed.atStartOfDay(ZoneOffset.UTC).toEpochSecond())));
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx-"+oid,oid,"bill-"+oid,"ORDER","SETTLED",LocalDate.of(2026,8,31),"PHP",decimal("80"),null,null,null,null,"API",raw));
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP","BILL","bill-"+oid,"","bill-"+oid,"","SETTLED",LocalDate.of(2026,8,31),"PHP",decimal("80"),null,null,null,null,"API",Map.of("_finance_statement_time",generated)));
        }
        var before=db.queryForList("SELECT id,amount,revenue,updated_at FROM finance_records ORDER BY id");
        for(String oid:List.of("aug-order","sept-order")){
            long generated=Instant.parse(oid.equals("aug-order")?"2026-08-31T00:00:00Z":"2026-09-30T00:00:00Z").getEpochSecond();
            long completion=Instant.parse(oid.equals("aug-order")?"2026-09-01T04:36:26Z":"2026-10-03T17:30:00Z").getEpochSecond();
            var bill=Map.<String,Object>of("id","bill-"+oid,"statement_time",generated,"payment_time",completion,"payment_status","PAID");
            var ids=db.queryForList("SELECT id FROM finance_records WHERE statement_id=?",String.class,"bill-"+oid);
            assertEquals(3,repo.repairSettlementDates(owner,shop,bill,ids));
            assertEquals(0,repo.repairSettlementDates(owner,shop,bill,ids),"retry is idempotent");
        }
        assertEquals(before,db.queryForList("SELECT id,amount,revenue,updated_at FROM finance_records ORDER BY id"));
        for(var month:Map.of("2026-09-01","aug-order","2026-10-01","sept-order").entrySet()){
            LocalDate start=LocalDate.parse(month.getKey());var f=new Filter(start,start.withDayOfMonth(start.lengthOfMonth()),shop,"","","day");
            for(String kind:List.of("ORDER","SETTLEMENT","FEE","BILL")){
                var detail=repo.details(owner,f,kind,1,50);
                assertEquals(1,number(detail.get("total")),kind+" follows completion month");
                if(!kind.equals("BILL"))assertEquals(month.getValue(),rows(detail.get("items")).getFirst().get("order_id"));
            }
            assertEquals(1,number(map(new FinanceCostReport(repo).summary(owner,f,"ORDER").get("total")).get("order_count")));
            var csv=FinanceWorkbook.read(controller().export(Map.of("start",start.toString(),"end",f.end().toString(),"view","ORDER","format","csv","shopId",shop),principal()).getBody(),"report.csv");
            assertEquals(2,csv.size());assertTrue(csv.getLast().contains(month.getValue()));
            assertEquals(0,number(repo.details(other,f,"SETTLEMENT",1,50).get("total")));
        }
    }
    @Test void excelStatementDatesSupersedeLegacyMonthAndKeepAllViewsAndExportsAligned()throws Exception{
        String shop=connect(owner,false);
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP',timezone='Asia/Manila' WHERE id=?",shop);
        var entries=new ArrayList<Entry>();var bills=new ArrayList<Map<String,Object>>();
        for(String order:List.of("august-order","october-order","cancelled-order")){
            boolean september=order.equals("august-order"),cancelled=order.equals("cancelled-order");
            var placed=LocalDate.parse(september?"2026-08-28":"2026-09-23");
            var original=LocalDate.parse(september?"2026-08-31":"2026-09-30");
            long timestamp=original.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            String statement="bill-"+order;
            var bill=Map.<String,Object>of("id",statement,"statement_time",timestamp,"payment_time",timestamp+3600,"payment_status","PAID");
            bills.add(bill);
            var oe=new Entry(shop,"TIKTOK_SHOP","ORDER",order,order,"","订单",cancelled?"CANCELLED":"COMPLETED",placed,
                    "PHP",decimal("100"),null,null,null,null,"API",Map.of("id",order,"create_time",placed.atStartOfDay(ZoneOffset.UTC).toEpochSecond()));
            repo.save(owner,oe);
            var raw=new LinkedHashMap<String,Object>(Map.of("type","ORDER","settlement_amount",cancelled?"0":"80",
                    "revenue_amount",cancelled?"0":"90","fee_and_tax_amount",cancelled?"0":"-10",
                    "revenue_breakdown",Map.of("subtotal_before_discount_amount","100","seller_discount_amount",cancelled?"-100":"-10"),
                    "_finance_statement_time",timestamp,"_finance_payment_time",timestamp+3600,"_finance_payment_status","PAID","_finance_date_version",2));
            // Deliberately wrong old manually approved month must not override verified report dates.
            db.update("INSERT INTO finance_reporting_months VALUES('org',?, ?,?,'2026-10-01','USER_CONFIRMED_MONTH','legacy','test',UTC_TIMESTAMP(6))",shop,order,statement);
            var entry=new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","tx-"+order,order,statement,"ORDER","SETTLED",original,"PHP",decimal(cancelled?"0":"80"),null,null,null,null,"API",raw);
            repo.save(owner,entry);entries.add(entry);
            repo.save(owner,new Entry(shop,"TIKTOK_SHOP","BILL",statement,"",statement,"","SETTLED",original,"PHP",entry.amount(),null,null,null,null,"API",bill));
            db.update("""
                INSERT INTO finance_order_costs(order_record_id,organization_id,shop_id,order_id,record_date,status,amount_cny,known_amount_cny,source_hash,data_json)
                VALUES(?,?,?,?,?,?,?,?,'excel-test','{}')
                """,id(owner,oe),"org",shop,order,java.sql.Date.valueOf(placed),cancelled?"EXCLUDED":"READY",cancelled?0:10,cancelled?0:10);
        }
        var amounts=db.queryForList("SELECT id,amount,revenue FROM finance_records ORDER BY id");
        var costs=db.queryForList("SELECT * FROM finance_order_costs ORDER BY order_record_id");
        for(var bill:bills){
            var ids=db.queryForList("SELECT id FROM finance_records WHERE statement_id=?",String.class,bill.get("id"));
            assertTrue(repo.repairSettlementDates(owner,shop,bill,ids)>0);
            assertEquals(0,repo.repairSettlementDates(owner,shop,bill,ids));
        }
        for(var e:entries){assertTrue(repo.rebuildSettlement(owner,shop,id(owner,e)));assertTrue(repo.rebuildSettlement(owner,shop,id(owner,e)));}
        assertEquals(amounts,db.queryForList("SELECT id,amount,revenue FROM finance_records ORDER BY id"));
        assertEquals(costs,db.queryForList("SELECT * FROM finance_order_costs ORDER BY order_record_id"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE JSON_EXTRACT(raw_json,'$._finance_reporting_month') IS NOT NULL",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE kind='SETTLEMENT' AND JSON_EXTRACT(raw_json,'$._finance_legacy_reporting_month') IS NOT NULL",Integer.class));
        db.update("DELETE FROM finance_order_cost_jobs");
        for(int month:List.of(9,10)){
            var start=LocalDate.of(2026,month,1);var f=new Filter(start,start.withDayOfMonth(start.lengthOfMonth()),shop,"","","day");
            int expected=month==9?1:2;
            for(String kind:List.of("ORDER","SETTLEMENT","BILL"))assertEquals(expected,number(repo.details(owner,f,kind,1,50).get("total")),kind);
            assertEquals(1,number(repo.details(owner,f,"FEE",1,50).get("total")));
            assertTrue(repo.reconciliation(owner,f).stream().allMatch(r->"MATCHED".equals(r.get("result"))));
            for(String source:List.of("ORDER","BILL","RECONCILIATION")){
                var costReport=new FinanceCostReport(repo);
                assertEquals(expected,number(costReport.orders(owner,f,source,"",1).get("total")));
                var total=map(costReport.summary(owner,f,source).get("total"));
                assertEquals(0,decimal(total.get("amount_cny")).compareTo(decimal("10")));
                assertEquals(month==9?0:1,number(total.get("excluded_count")));
                var sheet=FinanceCostExportTest.sheet(new FinanceCostExport(repo).export(owner,f,source,""),2);
                assertEquals(expected+1,sheet.getElementsByTagName("row").getLength());
                assertEquals(start.toEpochDay()+25569,Double.parseDouble(FinanceCostExportTest.value(sheet,"G2")));
            }
            var csv=FinanceWorkbook.read(controller().export(Map.of("start",start.toString(),"end",f.end().toString(),"view","SETTLEMENT","format","csv","shopId",shop),principal()).getBody(),"report.csv");
            assertEquals(expected+1,csv.size());
            assertFalse(csv.toString().contains("按月校正"));
            assertEquals(0,number(repo.details(other,f,"SETTLEMENT",1,50).get("total")));
            var partial=new Filter(start,start.plusDays(14),shop,"","","day");
            assertEquals(expected,number(repo.details(owner,partial,"SETTLEMENT",1,50).get("total")),"precise report days can be queried within a month");
            assertTrue(new FinanceReportingMonths(repo).coverage(owner,partial).isEmpty());
            assertEquals("day",service.buildDashboard(owner,f,false,"",false).get("trendGranularity"));
        }
        // Later adjustments retain their own report date without repeating the first settlement's cost.
        var raw=Map.<String,Object>of("type","ORDER","revenue_amount","-5","settlement_amount","-5");
        repo.save(owner,new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","refund-later","august-order","refund-bill","REFUND","SETTLED",LocalDate.of(2026,11,3),"PHP",decimal("-5"),null,null,null,null,"API",raw));
        var november=new Filter(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),shop,"","","day");
        assertEquals(0,number(new FinanceCostReport(repo).orders(owner,november,"ORDER","",1).get("total")));
        assertEquals(0,decimal(rows(repo.details(owner,november,"SETTLEMENT",1,50).get("items")).getFirst().get("amount")).compareTo(decimal("-5")));
    }
    @Test void unconfirmedCompletionCannotEnterAnyDatedSummaryOrCostScope(){
        String shop=connect(owner,false);
        var raw=Map.<String,Object>of("type","ORDER","settlement_amount","80","revenue_breakdown",Map.of("subtotal_before_discount_amount","100","seller_discount_amount","-20"));
        var row=new Entry(shop,"TIKTOK_SHOP","SETTLEMENT","unconfirmed","old-order","pending-bill","ORDER",FinanceSettlementDates.PENDING,filter.start(),"PHP",decimal("80"),null,null,null,null,"API",raw);
        repo.save(owner,row);
        assertEquals(0,number(repo.details(owner,filter,"SETTLEMENT",1,50).get("total")));
        assertEquals(0,number(repo.details(owner,filter,"FEE",1,50).get("total")));
        assertTrue(repo.dashboardBuckets(owner,filter).isEmpty());
        assertEquals(0,number(map(new FinanceCostReport(repo).summary(owner,filter,"ORDER").get("total")).get("order_count")));
        assertNull(repo.detail(owner,id(owner,row)).get("record_date"));
    }
    BigDecimal bucketTotal(String kind){return repo.dashboardBuckets(owner,filter).stream().filter(r->kind.equals(r.get("kind"))).map(r->decimal(r.get("amount"))).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);}
    @Test void marketingTagsExcludeSummaryButPreserveLedgerAndUndoOnlyLastSource(){
        String group=service.saveGroup(owner,null,"母婴"),shop=groupedShop(owner,"charge-shop",group);
        record(owner,shop,"ORDER","order1",new BigDecimal("100"),Map.of());
        record(owner,shop,"SETTLEMENT","settle1",new BigDecimal("80"),Map.of());
        record(owner,shop,"FEE","fee1",new BigDecimal("20"),Map.of());
        repo.save(owner,new Entry(shop,"SHOPEE","ORDER","order2","order2","","","COMPLETED",filter.start(),"PHP",new BigDecimal("60"),null,null,null,null,"API",Map.of()));
        repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","settle2","order2","bill1","","SETTLED",filter.start(),"PHP",new BigDecimal("50"),null,null,null,null,"API",Map.of()));
        record(owner,shop,"BILL","bill1",new BigDecimal("130"),Map.of());
        var costs=new FinanceOrderCosts(repo);repo.transaction(()->{costs.requestShop("org",shop);costs.seedAndFill();costs.processBatch(50);return null;});
        assertEquals(0,bucketTotal("SETTLEMENT").compareTo(new BigDecimal("130")));
        var charges=new FinanceOrderCharges(repo,service);byte[] file=chargeCsv("order1","Shop charge-shop","7");
        var imported=repo.transaction(()->charges.upload(owner,"u",group,file,"cost.csv"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        assertEquals(0,bucketTotal("SETTLEMENT").compareTo(new BigDecimal("50")));
        assertEquals(0,bucketTotal("ORDER").compareTo(new BigDecimal("60")));
        assertEquals(0,bucketTotal("FEE").compareTo(BigDecimal.ZERO));
        assertEquals(1,number(map(new FinanceCostReport(repo).summary(owner,filter,"SETTLED_ORDER").get("total")).get("order_count")));
        var details=repo.details(owner,filter,"ORDER",1,50);assertEquals(2,number(details.get("total")));
        assertEquals("刷单",rows(details.get("items")).stream().filter(r->"order1".equals(r.get("order_id"))).findFirst().orElseThrow().get("marketing_label"));
        assertEquals(0,decimal(repo.reconciliation(owner,filter).getFirst().get("difference")).compareTo(BigDecimal.ZERO));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        assertEquals(true,repo.transaction(()->charges.upload(owner,"u",group,file,"renamed.csv")).get("duplicate"));
        byte[] second=(new String(file,java.nio.charset.StandardCharsets.UTF_8)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var duplicate=repo.transaction(()->charges.upload(owner,"u",group,second,"second.csv"));
        // New reference to an already matched order must preserve the one-order marker and fee.
        assertEquals(2,db.queryForObject("SELECT source_count FROM finance_marketing_orders",Integer.class));
        assertEquals(0,db.queryForObject("SELECT service_fee_cny FROM finance_marketing_orders",BigDecimal.class).compareTo(new BigDecimal("7")));
        repo.transaction(()->{charges.remove(owner,"u",group,text(imported.get("id")));return null;});
        assertEquals(1,db.queryForObject("SELECT source_count FROM finance_marketing_orders",Integer.class));
        repo.transaction(()->{charges.remove(owner,"u",group,text(duplicate.get("id")));return null;});
        assertEquals(0,bucketTotal("SETTLEMENT").compareTo(new BigDecimal("130")));
        assertEquals(2,number(map(new FinanceCostReport(repo).summary(owner,filter,"SETTLED_ORDER").get("total")).get("order_count")));
    }
    @Test void unmatchedSourcesAreRetriedAndCrossShopIdentitiesStaySeparate(){
        String group=service.saveGroup(owner,null,"后续匹配"),shop=groupedShop(owner,"later",group),otherShop=groupedShop(owner,"different",group);
        var charges=new FinanceOrderCharges(repo,service);
        repo.transaction(()->charges.upload(owner,"u",group,chargeCsv("order1","Shop later","7"),"pending.csv"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        record(owner,otherShop,"ORDER","order1",new BigDecimal("100"),Map.of());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        record(owner,shop,"ORDER","order1",new BigDecimal("100"),Map.of());
        assertEquals(shop,db.queryForObject("SELECT shop_id FROM finance_marketing_orders",String.class));
        assertThrows(BusinessException.class,()->charges.workspace(other,group,1));
        repo.transaction(()->charges.upload(owner,"u",group,chargeCsv("order1","Shop later","8"),"conflict.csv"));
        assertNull(db.queryForObject("SELECT service_fee_cny FROM finance_marketing_orders",BigDecimal.class));
        assertEquals(1,number(map(charges.workspace(owner,group,1).get("marked")).get("conflicts")));
        service.removeShop(owner,shop);finishDeletion(shop);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_charge_rows WHERE matched_shop_id=?",Integer.class,shop));
    }
    void publishedLabels(String shop,String amount,String...orders){
        db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES(?,'test-generation') ON DUPLICATE KEY UPDATE active_generation='test-generation'",owner.organizationId());
        for(String order:orders)db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES(?,'test-generation',?,?,?,0,1) ON DUPLICATE KEY UPDATE amount_cny=VALUES(amount_cny)",owner.organizationId(),shop,order,decimal(amount));
    }
    @Test void configuredLabelPricesAreRetiredAndNeverReenterReports(){
        String group=service.saveGroup(owner,null,"贴单"),shop=groupedShop(owner,"labels",group);var charges=new FinanceOrderCharges(repo,service);
        assertThrows(BusinessException.class,()->charges.saveRule(owner,"u",Map.of("groupId",group,"country","PH","unitPriceCny","2.5")));
        db.update("INSERT INTO finance_label_rules(id,organization_id,group_id,country,effective_date,unit_price_cny,created_by) VALUES('old-rule','org',?,'PH','2026-01-01',99,'u')",group);
        record(owner,shop,"ORDER","order1",decimal("100"),Map.of());record(owner,shop,"SETTLEMENT","bill",decimal("80"),Map.of());
        var total=map(new FinanceCostReport(repo).summary(owner,filter,"SETTLED_ORDER").get("total"));
        assertEquals(0,decimal(total.get("label_cost_cny")).signum());assertEquals(1,number(total.get("label_missing_count")));
        assertTrue(rows(charges.workspace(owner,group,1).get("rules")).isEmpty());
        publishedLabels(shop,"0","order1");
        total=map(new FinanceCostReport(repo).summary(owner,filter,"SETTLED_ORDER").get("total"));
        assertEquals(0,number(total.get("label_missing_count")),"an imported zero is confirmed, not missing");
    }
    @Test void invoiceGenerationChangesLabelsWithoutRebuildingOrBlankingSkuCosts(){
        String group=service.saveGroup(owner,null,"贴单独立更新"),shop=groupedShop(owner,"label-only",group);
        var workspace=new FinanceCostWorkspace(repo,service);
        repo.transaction(()->workspace.importCosts(owner,"u",group,"仓库,型号,202610\n海外仓-菲律宾,PACK,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"cost.csv"));
        var raw=Map.<String,Object>of("item_list",List.of(Map.of("item_id","product","model_id","sku","model_sku","PACK","model_quantity_purchased",2)));
        var entry=new Entry(shop,"SHOPEE","ORDER","label-order","label-order","","ORDER","COMPLETED",filter.start(),"PHP",decimal("100"),null,null,null,null,"API",raw);
        repo.save(owner,entry);repo.save(owner,new Entry(shop,"SHOPEE","SETTLEMENT","label-settlement","label-order","","结算","SETTLED",filter.start(),"PHP",decimal("80"),null,null,null,null,"API",Map.of()));
        var worker=new FinanceOrderCosts(repo);repo.transaction(()->{worker.seedAndFill();worker.processBatch(100);return null;});
        var before=db.queryForList("SELECT * FROM finance_order_costs");publishedLabels(shop,"1.95","label-order");
        var f=new Filter(filter.start(),filter.end(),shop,"","","day",group);
        var summary=new FinanceCostReport(repo).summary(owner,f,"SETTLED_ORDER");var total=map(summary.get("total"));
        assertEquals(false,summary.get("labelProcessing"));assertEquals(0,number(total.get("queued_count")));
        assertEquals(0,decimal("20").compareTo(decimal(total.get("amount_cny"))));assertEquals(0,decimal("1.95").compareTo(decimal(total.get("label_cost_cny"))));
        assertEquals(before,db.queryForList("SELECT * FROM finance_order_costs"),"invoice publication does not rewrite SKU costs");
        db.update("UPDATE finance_label_state SET active_generation='empty'");
        total=map(new FinanceCostReport(repo).summary(owner,f,"SETTLED_ORDER").get("total"));
        assertEquals(0,decimal(total.get("label_cost_cny")).signum());assertEquals(0,decimal("20").compareTo(decimal(total.get("amount_cny"))));
    }
    @Test void fullAndLiveCostRequestsTakePrecedenceOverLabelRebuilds(){
        String group=service.saveGroup(owner,null,"优先级"),shop=groupedShop(owner,"label-priority",group);
        var charges=new FinanceOrderCharges(repo,service);var worker=new FinanceOrderCosts(repo);
        repo.transaction(()->new FinanceCostWorkspace(repo,service).importCosts(owner,"u",group,"仓库,型号,202610\n海外仓-菲律宾,PACK,10\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),"cost.csv"));
        record(owner,shop,"ORDER","order1",BigDecimal.TEN,Map.of("ship_time",1790000000));
        repo.transaction(()->{worker.seedAndFill();return null;});
        assertEquals(0,db.queryForObject("SELECT priority FROM finance_order_cost_jobs",Integer.class),"historical label scan cannot downgrade a live order");
        repo.transaction(()->worker.processBatch(100));
        repo.transaction(()->{worker.requestLabelGroup(owner,group);worker.seedAndFill();return null;});
        assertEquals(2,db.queryForObject("SELECT priority FROM finance_order_cost_jobs",Integer.class));
        repo.transaction(()->{worker.requestGroup(owner,group);worker.requestLabelGroup(owner,group);return null;});
        assertEquals("READY",db.queryForObject("SELECT status FROM finance_cost_rebuilds",String.class));
        assertEquals(1,db.queryForObject("SELECT priority FROM finance_order_cost_jobs",Integer.class),"SKU changes upgrade queued label-only work immediately");
        repo.transaction(()->{worker.seedAndFill();worker.processBatch(100);return null;});
        repo.transaction(()->{worker.requestLabelGroup(owner,group);worker.seedAndFill();return null;});
        db.update("UPDATE finance_records SET status='CANCELLED' WHERE kind='ORDER'");
        repo.transaction(()->worker.processBatch(100));
        assertEquals("EXCLUDED",db.queryForObject("SELECT status FROM finance_order_costs",String.class),"source changes fall back to a full calculation");
        assertEquals(0,db.queryForObject("SELECT label_cost_cny FROM finance_order_costs",BigDecimal.class).signum());
    }
    @Test void chargePeriodUsesFirstSettlementAndNeverDuplicatesLabelsOrMarketingFees(){
        String group=service.saveGroup(owner,null,"期间费用"),shop=groupedShop(owner,"period-charge",group);
        var charges=new FinanceOrderCharges(repo,service);var worker=new FinanceOrderCosts(repo);
        publishedLabels(shop,"2.5","normal","marked","not-settled");
        LocalDate orderDay=LocalDate.of(2026,8,10),settledDay=LocalDate.of(2026,9,1);
        for(String order:List.of("normal","marked","not-settled")){
            repo.save(owner,dated(shop,"ORDER",order,order,"",orderDay,"100",Map.of("ship_time",1786406400)));
            if(!order.equals("not-settled")){
                repo.save(owner,dated(shop,"SETTLEMENT",order+"-first",order,"bill",settledDay,"80",Map.of("order_income",Map.of("original_price",100,"commission_fee",20))));
                repo.save(owner,dated(shop,"SETTLEMENT",order+"-second",order,"bill",settledDay,"0",Map.of()));
                repo.save(owner,dated(shop,"SETTLEMENT",order+"-adjustment",order,"bill2",LocalDate.of(2026,10,4),"5",Map.of()));
            }
        }
        repo.transaction(()->charges.upload(owner,"u",group,chargeCsv("marked","Shop period-charge","7"),"first.csv"));
        byte[] second=(new String(chargeCsv("marked","Shop period-charge","7"),java.nio.charset.StandardCharsets.UTF_8)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        repo.transaction(()->charges.upload(owner,"u",group,second,"second.csv"));
        repo.transaction(()->{worker.seedAndFill();worker.processBatch(50);return null;});
        for(int month:List.of(8,9,10)){
            LocalDate start=LocalDate.of(2026,month,1);var f=new Filter(start,start.withDayOfMonth(start.lengthOfMonth()),shop,"","","day",group);
            var response=charges.workspace(owner,group,1,f);var label=map(map(response.get("labelSummary")).get("total"));var marketing=map(response.get("periodMarked"));
            assertEquals(month==9?1:0,number(label.get("order_count")));
            assertEquals(0,decimal(label.get("label_cost_cny")).compareTo(decimal(month==9?"2.5":"0")));
            assertEquals(month==9?1:0,number(marketing.get("orders")));
            assertEquals(0,decimal(marketing.get("service_fee_cny")).compareTo(decimal(month==9?"7":"0")));
            assertEquals(1,number(map(response.get("marked")).get("orders")),"lifetime and period statistics remain distinguishable");
        }
        repo.transaction(()->{charges.rematch(owner,"u",group);charges.rematch(owner,"u",group);return null;});
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        assertEquals(2,db.queryForObject("SELECT source_count FROM finance_marketing_orders",Integer.class));
        assertEquals(0,db.queryForObject("SELECT service_fee_cny FROM finance_marketing_orders",BigDecimal.class).compareTo(decimal("7")));
    }
    @Test void wrongGroupChargeSourcesExplainTheirScopeAndNeverMarkAnotherGroup(){
        String group=service.saveGroup(owner,null,"母婴"),wrong=service.saveGroup(owner,null,"服装"),shop=groupedShop(owner,"group-match",group);
        record(owner,shop,"ORDER","order1",decimal("100"),Map.of());
        var charges=new FinanceOrderCharges(repo,service);
        repo.transaction(()->charges.upload(owner,"u",wrong,chargeCsv("order1","Shop group-match","7"),"wrong.csv"));
        var match=map(rows(charges.workspace(owner,wrong,1).get("rows")).getFirst().get("match"));
        assertEquals("REVIEW",match.get("matchStatus"));assertTrue(text(match.get("matchNote")).contains("母婴"));
        repo.transaction(()->{charges.rematch(owner,"u",wrong);return null;});
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_marketing_orders",Integer.class));
        assertThrows(BusinessException.class,()->charges.rematch(other,"u",wrong));
    }

}
