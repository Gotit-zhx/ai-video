package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceAdSpendIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    FinanceAdSpendService spend;String shop;
    final Filter filter=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","USD","day");
    final List<List<String>> file=List.of(List.of("日期","推广系列ID","现金消耗","信用额度消耗","广告费用赠款消耗","金额","币种"),List.of("2026-10-02","9012345678901234","0","-6.60","-1.21","-7.81","USD"));
    @BeforeAll void initialize()throws Exception{fixture.migrate();}
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();for(String t:List.of("finance_ad_spend_allocations","finance_ad_spend_allocation_sets","finance_ad_spend_imports"))fixture.db.update("DELETE FROM "+t);
        shop=fixture.groupedShop(fixture.owner,"spend-shop",fixture.service.saveGroup(fixture.owner,null,"服装"));fixture.db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shop);
        spend=new FinanceAdSpendService(fixture.repo,fixture.service);
    }
    @Test void persistedGmvNetCostFlowsIntoThreeTablesAndNativeNumericExportWithoutChangingProfitFacts()throws Exception{
        fixture.repo.save(fixture.owner,new Entry(shop,"TIKTOK_SHOP","AD_GMV","official-series","","","GMV Max","REPORTED",LocalDate.of(2026,10,2),"USD",new BigDecimal("10"),null,null,null,null,"API",Map.of("advertiser_id","123","dimensions",Map.of("campaign_id","9012345678901234"),"metrics",Map.of("cost","10","net_cost","8"))));
        var report=spend.report(fixture.owner,filter,false,"","","");for(String key:List.of("groups","shops","items")){
            var row=rows(report.get(key)).stream().filter(r->r.get("net_amount")!=null).findFirst().orElseThrow();assertEquals(0,new BigDecimal("8").compareTo(decimal(row.get("net_amount"))));assertNull(row.get("cash_amount"));assertNull(row.get("credit_amount"));assertNull(row.get("grant_amount"));
        }
        var controller=new FinanceAdSpendController(spend,fixture.controller());byte[] bytes=controller.export(Map.of("start","2026-10-01","end","2026-10-31","currency","USD"),fixture.principal()).getBody();
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("xl/worksheets/sheet3.xml")){
            var doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(zip.readAllBytes()));var cells=((org.w3c.dom.Element)doc.getElementsByTagName("row").item(1)).getElementsByTagName("c");var value=(org.w3c.dom.Element)cells.item(19);assertFalse(value.hasAttribute("t"));assertEquals(0,new BigDecimal("8").compareTo(new BigDecimal(value.getTextContent())));
        }}
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));assertEquals(new BigDecimal("10.000000"),fixture.db.queryForObject("SELECT amount FROM finance_records",BigDecimal.class));
    }
    @Test void importIsIdempotentAndDoesNotChangeProfitFactsOrCredentials(){
        var imported=FinanceAdSpendImport.parse(file,"TIKTOK_SHOP","123","共享账户",true);
        assertEquals(1,spend.saveImport(fixture.owner,"actor",imported,"report.xlsx").get("inserted"));
        assertEquals(0,spend.saveImport(fixture.owner,"actor",imported,"renamed.xlsx").get("inserted"));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
        assertEquals(1,rows(spend.report(fixture.owner,filter,false,"","","").get("items")).size());
        assertEquals(0,rows(spend.report(fixture.other,filter,false,"","","").get("items")).size());
        var conflict=new LinkedHashMap<>(imported.getFirst());conflict.put("amount",new BigDecimal("8"));conflict.put("grant_amount",new BigDecimal("1.4"));assertThrows(RuntimeException.class,()->spend.saveImport(fixture.owner,"actor",List.of(conflict),"changed.xlsx"));
        assertEquals(new BigDecimal("7.810000"),fixture.db.queryForObject("SELECT amount FROM finance_ad_spend_imports",BigDecimal.class));
        var noType=FinanceAdSpendImport.parse(file,"TIKTOK_SHOP","123","共享账户",true);assertEquals(0,spend.saveImport(fixture.owner,"actor",noType,"same-facts.xlsx").get("inserted"));
    }
    @Test void bulkImportAndThreeSheetExportCoverAllQueryPagesWithoutNumericIdentifierLoss()throws Exception{
        var table=new ArrayList<List<String>>();table.add(file.getFirst());for(int i=0;i<600;i++)table.add(List.of("2026-10-02",Long.toString(9012345678901234L+i),"0","-6.60","-1.21","-7.81","USD"));
        var imported=FinanceAdSpendImport.parse(table,"TIKTOK_SHOP","123","共享账户",true);assertEquals(600,spend.saveImport(fixture.owner,"actor",imported,"bulk.xlsx").get("inserted"));
        var controller=new FinanceAdSpendController(spend,fixture.controller());var q=Map.of("start","2026-10-01","end","2026-10-31","currency","USD","page","2");
        var response=map(controller.list(q,fixture.principal()).data());assertEquals(600,response.get("total"));assertEquals(50,rows(response.get("items")).size());
        byte[] bytes=controller.export(q,fixture.principal()).getBody();var files=new HashMap<String,String>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        assertTrue(files.get("xl/workbook.xml").contains("店铺消耗明细"));String detail=files.get("xl/worksheets/sheet3.xml");assertEquals(601,detail.split("<row",-1).length-1);assertTrue(detail.contains("<t xml:space=\"preserve\">9012345678901234</t>")||detail.contains("<t>9012345678901234</t>"));assertTrue(detail.contains("信用额度消耗"));
    }
    @Test void originManagementAndCompanySwitchGuardsProtectNewWriteRoutes(){
        var policy=org.mockito.Mockito.mock(com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy.class);
        var finance=new FinanceService(fixture.repo,policy,fixture.auth,fixture.fx);var guard=new FinanceController(finance,fixture.repo,org.mockito.Mockito.mock(FinanceImport.class),org.mockito.Mockito.mock(FinanceAds.class));var controller=new FinanceAdSpendController(spend,guard);
        var viewer=new com.yingdao.aicommerce.auth.PlatformPrincipal("u","org","viewer","viewer","viewer","",true,List.of(),List.of("finance:view"));
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/finance/advertising/imports");assertThrows(RuntimeException.class,()->controller.authorize(viewer,request));
        request.addHeader("Sec-Fetch-Site","cross-site");assertThrows(RuntimeException.class,()->controller.authorize(fixture.principal(),request));
        assertThrows(RuntimeException.class,()->controller.assign("a".repeat(64),new FinanceAdSpendController.Assignment("previous-company","fingerprint",List.of()),fixture.principal()));
    }
    @Test void shopeeNativeExpenseImportAutomaticallyUsesItsExactCompanyShopSource(){
        String sourceShop=fixture.groupedShop(fixture.owner,"SHOP_123",fixture.service.saveGroup(fixture.owner,null,"Shopee source"));
        var entries=FinanceAdSpendImport.parse(List.of(List.of("date","ad_id","expense","currency"),List.of("2026-10-02","9012345678901234","9.00","USD")),"SHOPEE","SHOP_123","Shopee source",false);
        spend.saveImport(fixture.owner,"actor",entries,"shopee.csv");var result=rows(spend.report(fixture.owner,filter,false,"","","").get("items")).getFirst();assertEquals(sourceShop,result.get("shop_id"));assertNull(result.get("cash_amount"));assertNull(result.get("amount"));assertEquals(new BigDecimal("9.000000"),result.get("source_reported_amount"));
        assertEquals(0,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records",Integer.class));
    }
    @Test void exactAssignmentsAreScopedVersionedAndReadAfterAllocationBeforeShopFilter(){
        var imported=FinanceAdSpendImport.parse(file,"TIKTOK_SHOP","123","共享账户",true);spend.saveImport(fixture.owner,"actor",imported,"report.xlsx");var source=imported.getFirst();
        var values=new LinkedHashMap<String,Object>(Map.of("shop_id",shop,"amount",new BigDecimal("7.81"),"cash_amount",BigDecimal.ZERO,"credit_amount",new BigDecimal("6.6"),"grant_amount",new BigDecimal("1.21")));
        assertThrows(RuntimeException.class,()->spend.assign(fixture.other,"actor",text(source.get("source_key")),FinanceAdSpendReport.fingerprint(source),List.of(values)));
        spend.assign(fixture.owner,"actor",text(source.get("source_key")),FinanceAdSpendReport.fingerprint(source),List.of(values));
        var selected=new Filter(filter.start(),filter.end(),shop,"","USD","day");var items=rows(spend.report(fixture.owner,selected,false,"","","").get("items"));assertEquals(1,items.size());assertEquals(shop,items.getFirst().get("shop_id"));
        spend.assign(fixture.owner,"actor",text(source.get("source_key")),FinanceAdSpendReport.fingerprint(source),List.of());
        assertEquals(2,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_ad_spend_allocation_sets",Integer.class));
        assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_ad_spend_allocations",Integer.class));
        assertEquals("UNASSIGNED",rows(spend.report(fixture.owner,filter,false,"","","").get("items")).getFirst().get("shop_id"));
    }
}
