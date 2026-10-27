package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.common.exception.GlobalExceptionHandler;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FinanceTest {
    @SuppressWarnings("unchecked")
    static <T>T classProxy(T target){
        var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor)invocation->invocation.proceed());
        return (T)factory.getProxy();
    }
    PlatformPrincipal user(String org){return new PlatformPrincipal("user",org,org,"admin","admin","",true,List.of("ADMIN"),List.of("finance:view","finance:manage"));}
    Entry entry(String id,BigDecimal amount){return new Entry("shop","SHOPEE","SETTLEMENT",id,"order","bill","结算","SETTLED",LocalDate.of(2026,10,20),"PHP",amount,null,null,null,null,"API",Map.of());}
    @Test void idsAreStableAndIsolated(){
        var e=entry("a",BigDecimal.TEN);
        assertEquals(id(new Scope("org","u1"),e),id(new Scope("org","u1"),entry("a",BigDecimal.ONE)));
        assertNotEquals(id(new Scope("org","u1"),e),id(new Scope("org","u2"),e));
        assertNotEquals(id(new Scope("org","u1"),e),id(new Scope("other","u1"),e));
    }
    PlatformPrincipal member(String org,String user,String...permissions){return new PlatformPrincipal(user,org,org,user,user,"",true,List.of("STAFF"),List.of(permissions));}
    @Test void financialRolesShareCompanyScopeAndOtherCompaniesRemainIsolated(){
        assertThrows(BusinessException.class,()->scope(member("org","u"),false));
        assertThrows(BusinessException.class,()->scope(null,false));
        assertEquals(scope(member("org","a","finance:view"),false),scope(member("org","b","finance:manage"),false));
        assertNotEquals(scope(member("org","a","finance:view"),false),scope(member("other","a","finance:view"),false));
        assertEquals(companyScope("heyi"),scope(user("heyi"),true));
        assertFalse(canManage(member("org","a","finance:view")));assertTrue(canManage(member("org","a","finance:manage")));
    }
    @Test void everyEndpointRejectsUnpermittedUserBeforeBusinessLogic() throws Exception {
        var policy=mock(OrganizationAccessPolicy.class);var repo=mock(FinanceRepository.class);
        var service=new FinanceService(repo,policy,mock(FinanceShopAuthorization.class),mock(FinanceFx.class));
        var controller=new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var principal=member("other","user");
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(MethodParameter p){return p.getParameterType()==PlatformPrincipal.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory b){return principal;}
                }).build();
        for(String path:List.of("/overview","/shops","/records","/records/a","/reconciliation","/ads","/export","/fields/ALL"))
            mvc.perform(get("/api/v1/finance"+path)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/finance/shops/a/sync")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/finance/screen")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/finance/shops/a")).andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }
    @Test void viewerCanReadAndChooseFieldsButCannotManage()throws Exception{
        var policy=mock(OrganizationAccessPolicy.class);var repo=mock(FinanceRepository.class);var db=mock(JdbcTemplate.class);when(repo.jdbc()).thenReturn(db);
        var service=new FinanceService(repo,policy,mock(FinanceShopAuthorization.class),mock(FinanceFx.class));
        var controller=new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var principal=member("org","viewer","finance:view");
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(MethodParameter p){return p.getParameterType()==PlatformPrincipal.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory b){return principal;}
                }).build();
        mvc.perform(get("/api/v1/finance/groups")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/finance/shops")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/finance/shops/authorize").contentType("application/json").content("{\"platform\":\"SHOPEE\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/finance/groups").contentType("application/json").content("{\"name\":\"Dress\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/finance/shops/id")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/finance/fields/ORDER").contentType("application/json").content("{\"visible\":[\"external_id\"],\"exported\":[\"external_id\"]}")).andExpect(status().isOk());
        verify(db).update(contains("finance_view_preferences"),eq("org"),eq("viewer"),eq("ORDER"),nullable(String.class));
    }
    @Test void periodsRespectNaturalHalfYearAndIsoWeeks(){
        assertEquals("2025-12-29",bucket(LocalDate.of(2026,1,1),"week"));
        assertEquals("2026 H1",bucket(LocalDate.of(2026,6,30),"halfyear"));
        assertEquals("2026 H2",bucket(LocalDate.of(2026,7,1),"halfyear"));
        assertEquals("2026 Q4",bucket(LocalDate.of(2026,10,20),"quarter"));
        assertThrows(BusinessException.class,()->new Filter(LocalDate.now(),LocalDate.now().minusDays(1),"","","","day"));
    }
    @Test void unknownAmountsRemainUnknown(){
        assertNull(decimal("not-an-amount"));assertNull(amount(Map.of(),"fee"));
        assertDoesNotThrow(()->validate(entry("a",null)));
        assertThrows(BusinessException.class,()->validate(entry("",BigDecimal.ONE)));
    }
    @Test void fxConvertsBeforeGroupingAndPreservesNull(){
        var q=new FinanceFx.Quote("1",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125"),"THB",new BigDecimal("0.2"),"CNY",BigDecimal.ONE));
        var result=FinanceFx.groups(List.of(
                new HashMap<>(Map.of("kind","AD","currency","PHP","amount",new BigDecimal("100"),"records",1,"clicks",10)),
                new HashMap<>(Map.of("kind","AD","currency","THB","amount",new BigDecimal("100"),"records",2,"clicks",20))),q,"kind");
        assertEquals(1,result.size());assertEquals(new BigDecimal("32.500000"),result.getFirst().get("amount"));
        assertEquals(new BigDecimal("30"),result.getFirst().get("clicks"));
        assertNull(q.convert(null,"PHP"));assertThrows(BusinessException.class,()->q.convert("10","EUR"));
    }
    @Test void exportsAreFormulaSafeAndCsvRoundTrips(){
        var rows=List.of(List.of("编号","金额","备注"),List.of("001234","12.34","a,\"b\"\nsecond"),List.of("=HYPERLINK(\"bad\")","-1","x"));
        var csv=FinanceWorkbook.csv(rows);assertTrue(csv.contains("'=HYPERLINK"));assertEquals("a,\"b\"\nsecond",FinanceWorkbook.parseCsv(csv).get(1).get(2));
        var xlsx=FinanceWorkbook.xlsx(Map.of("数据",rows));var parsed=FinanceWorkbook.read(xlsx,"report.xlsx");
        assertEquals(rows,parsed);assertThrows(BusinessException.class,()->FinanceWorkbook.parseCsv("a,\"unterminated"));
    }
    @Test void xlsxBlocksXmlExternalEntities()throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write("<!DOCTYPE a [<!ENTITY xx SYSTEM \"file:///etc/passwd\">]><worksheet><row><c t=\"inlineStr\">&xx;</c></row></worksheet>".getBytes());
        }
        assertThrows(BusinessException.class,()->FinanceWorkbook.read(bytes.toByteArray(),"bad.xlsx"));
    }
    @Test void tiktokStatementPaginationDoesNotAdvanceBeforeItsTransactions(){
        var auth=mock(FinanceShopAuthorization.class);var tk=mock(TikTokShopOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(auth,mock(ShopeeOfficialClient.class),tk,repo);
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]");
        var connection=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now().plusSeconds(100),Instant.now().plusSeconds(100),List.of()));
        var bill=Map.<String,Object>of("id","bill-1","statement_time",1760918400L);
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("currency","PHP","total_settlement_amount","30",
                "transactions",List.of(Map.of("id","tx1","type","ORDER","order_id","order1","settlement_amount","30","fee_tax_amount","2")),"next_page_token","cursor2")));
        var cp=new LinkedHashMap<String,Object>(Map.of("from",1,"to",2,"bills",List.of(bill),"lastBillPage",true));
        var step=reader.tik(new Scope("org","u"),connection,"SETTLEMENT","Asia/Manila",cp);
        assertFalse(step.done());assertEquals("cursor2",step.checkpoint().get("transactionCursor"));
        verify(repo,times(2)).save(any(),any()); // Fee rows are now atomically derived by the repository.
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("currency","PHP","total_settlement_amount","30","transactions",List.of())));
        assertTrue(reader.tik(new Scope("org","u"),connection,"SETTLEMENT","Asia/Manila",step.checkpoint()).done());
    }
    @Test void missingSettlementDateNeverFallsBackToTodayOrOrderDate(){
        assertThrows(BusinessException.class,()->FinanceMarketplace.settlementDay(null,"UTC"));
        assertThrows(BusinessException.class,()->FinanceMarketplace.settlementDay(0,"UTC"));
        assertEquals(LocalDate.of(2026,10,4),FinanceMarketplace.settlementDay(Instant.parse("2026-10-03T17:30:00Z").getEpochSecond(),"Asia/Manila"));
        assertEquals(LocalDate.of(2026,9,30),FinanceMarketplace.tiktokSettlementDay(Instant.parse("2026-10-01T00:00:00Z").getEpochSecond()));
    }
    @Test void settledOrderRepairFetchesOldIdsDirectlyAndRetainsCheckpointOnPartialResponse(){
        var tk=mock(TikTokShopOfficialClient.class);var repo=mock(FinanceRepository.class);var db=mock(JdbcTemplate.class);
        when(repo.jdbc()).thenReturn(db);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id","record-a","order_id","123"),Map.of("id","record-b","order_id","456")));
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),mock(ShopeeOfficialClient.class),tk,repo);
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"shop","","cipher","LOCAL","test","PH","[]");
        var connection=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        var first=Map.of("id","123","status","COMPLETED","create_time",Instant.parse("2026-08-01T00:00:00Z").getEpochSecond(),"payment",Map.of("currency","PHP","total_amount","100"));
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("orders",List.of(first))));
        var cp=Map.<String,Object>of("repairCursor","previous");
        assertThrows(BusinessException.class,()->reader.repairSettledOrders(new Scope("org","u"),connection,"Asia/Manila",cp));
        assertEquals("previous",cp.get("repairCursor"));
        var second=new LinkedHashMap<>(first);second.put("id","456");
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("orders",List.of(first,second))));
        var step=reader.repairSettledOrders(new Scope("org","u"),connection,"Asia/Manila",cp);
        assertTrue(step.done());assertEquals("record-b",step.checkpoint().get("repairCursor"));
        verify(tk,times(2)).apiGet(eq("/order/202309/orders"),eq("token"),eq("cipher"),eq(Map.of("ids","123,456")));
    }
    @Test void pendingRefundIsNotRecognizedAsSuccessfulCashRefund(){
        var tk=mock(TikTokShopOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),mock(ShopeeOfficialClient.class),tk,repo);
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]");
        var c=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        var pending=Map.of("return_id","r1","order_id","o1","return_status","RETURN_OR_REFUND_REQUEST_SUCCESS","create_time",1760918400L,"refund_amount",Map.of("currency","PHP","refund_total","100"));
        when(tk.apiPost(anyString(),any(),anyMap(),anyMap())).thenReturn(Map.of("data",Map.of("return_orders",List.of(pending))));
        reader.tik(new Scope("org","u"),c,"REFUND","Asia/Manila",Map.of("from",1,"to",2));
        var capture=org.mockito.ArgumentCaptor.forClass(Entry.class);verify(repo).save(any(),capture.capture());assertNull(capture.getValue().amount());
    }
    @Test void shopeeRefundConfirmsAcceptedAndKeepsCancelledOrClosedAmountsSeparate(){
        var shopee=mock(ShopeeOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),shopee,mock(TikTokShopOfficialClient.class),repo);
        var shop=new Shop("org",MarketplacePlatform.SHOPEE,"123","","","LOCAL","test","PH","[]");
        var connection=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        var returns=List.of("ACCEPTED","PROCESSING","CANCELLED","CLOSED").stream().map(status->Map.<String,Object>of(
                "status",status,"return_sn","return-"+status,"order_sn","order1","create_time",1760918400L,"currency","PHP","refund_amount","209")).toList();
        when(shopee.shopGet(eq("/api/v2/returns/get_return_list"),any(),anyMap())).thenReturn(Map.of("response",Map.of("return",returns,"more",false)));
        reader.sho(new Scope("org","u"),connection,"REFUND","Asia/Manila",Map.of("from",1,"to",2));
        var entries=org.mockito.ArgumentCaptor.forClass(Entry.class);verify(repo,times(4)).save(any(),entries.capture());
        assertEquals(new BigDecimal("209"),entries.getAllValues().getFirst().amount());
        assertTrue(entries.getAllValues().subList(1,4).stream().allMatch(e->e.amount()==null));
        for(var entry:entries.getAllValues()){
            var row=new HashMap<String,Object>();row.put("kind","REFUND");row.put("platform","SHOPEE");row.put("source","API");row.put("status",entry.status());row.put("amount",entry.amount());
            FinanceFields.enrich(row,entry.raw());assertEquals(new BigDecimal("209"),row.get("platform_refund_amount"));assertNotNull(row.get("refund_amount_note"));
        }
        assertTrue(FinanceFields.defaults("REFUND").visible().contains("platform_refund_amount"));
    }
    @Test void tiktokOrdersAcceptSparseTerminalPageAndResumeHistoricalWindow(){
        var tk=mock(TikTokShopOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),mock(ShopeeOfficialClient.class),tk,repo);
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]");
        var c=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        var resume=Map.<String,Object>of("from",1L,"to",2L,"until",3L);
        var cp=Map.<String,Object>of("from",10L,"to",20L,"until",20L,"cursor","last-page","backfillResume",resume);
        when(tk.apiPost(anyString(),any(),anyMap(),anyMap()))
                .thenReturn(Map.of("data",Map.of("total_count",133,"next_page_token","")));
        var terminal=reader.tik(new Scope("org","u"),c,"ORDER","Asia/Manila",cp);
        assertTrue(terminal.done());assertEquals("",terminal.checkpoint().get("cursor"));
        var next=FinanceSync.nextWindow(cp,terminal);
        assertFalse(next.done());assertEquals(resume,next.checkpoint());
        verifyNoInteractions(repo);
        verify(tk).apiPost(eq("/order/202309/orders/search"),eq(c),
                argThat(q->"last-page".equals(q.get("page_token"))),
                eq(Map.of("update_time_ge",10L,"update_time_lt",20L)));
    }
    @Test void tiktokSparseTerminalExceptionDoesNotHideMalformedOrIncompletePages(){
        assertEquals(List.of(),FinanceMarketplace.tikOrderRows(Map.of("total_count",0),""));
        assertEquals(List.of(),FinanceMarketplace.tikOrderRows(Map.of("orders",List.of()),""));
        for(var page:List.of(Map.<String,Object>of(),
                Map.<String,Object>of("total_count",133),
                Map.<String,Object>of("total_count",133,"next_page_token","another"),
                Map.<String,Object>of("total_count",-1,"next_page_token",""),
                Map.<String,Object>of("total_count","invalid","next_page_token",""),
                Map.<String,Object>of("total_count",133,"next_page_token","","orders",Map.of()),
                Map.<String,Object>of("orders",List.of("bad"))))
            assertThrows(BusinessException.class,()->FinanceMarketplace.tikOrderRows(page,"previous"));
        assertThrows(BusinessException.class,()->FinanceMarketplace.tikOrderRows(
                Map.of("total_count",133,"next_page_token",""),""));
    }
    @Test void liveOrdersUseOfficialOrderEndpointNewestFirst(){
        var tk=mock(TikTokShopOfficialClient.class);var repo=mock(FinanceRepository.class);var authorization=mock(FinanceShopAuthorization.class);
        var reader=new FinanceMarketplace(authorization,mock(ShopeeOfficialClient.class),tk,repo);
        var shop=new Shop("org",MarketplacePlatform.TIKTOK_SHOP,"123","","cipher","LOCAL","test","PH","[]");
        var c=new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
        when(authorization.connection(any(),anyString())).thenReturn(c);
        when(tk.apiPost(anyString(),any(),anyMap(),anyMap())).thenReturn(Map.of("data",Map.of("orders",List.of())));
        reader.step(new Scope("org","u"),Map.of("shop_id","shop","channel","ORDER_LIVE","timezone","Asia/Manila"),Map.of("from",1L,"to",2L));
        verify(tk).apiPost(eq("/order/202309/orders/search"),eq(c),argThat(q->"DESC".equals(q.get("sort_order"))),eq(Map.of("update_time_ge",1L,"update_time_lt",2L)));
    }
    @Test void sqlScopeCannotBeReplacedByRequestedShop(){
        var repo=new FinanceRepository(mock(JdbcTemplate.class),new ObjectMapper());
        var q=repo.query(new Scope("org-a","user-a"),new Filter(LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),"foreign-shop","","PHP","day"));
        assertTrue(q.where().contains("organization_id=?"));assertFalse(q.where().contains("user_id=?"));assertEquals("org-a",q.args().getFirst());
        assertFalse(q.where().contains("foreign-shop"));
    }
    @Test void incompleteBillsCannotAppearMatchedEvenWhenAmountsHappenToAgree(){
        var row=new HashMap<String,Object>(Map.of("status","PARTIAL","difference",BigDecimal.ZERO));
        FinanceRepository.reconcile(row);assertEquals("PARTIAL",row.get("result"));assertNull(row.get("difference"));
        row=new HashMap<>(Map.of("status","IMPORTED","api_status","PARTIAL","difference",BigDecimal.ZERO));
        FinanceRepository.reconcile(row);assertEquals("PARTIAL",row.get("result"));
        row=new HashMap<>(Map.of("status","SETTLED","difference",BigDecimal.ZERO));
        FinanceRepository.reconcile(row);assertEquals("MATCHED",row.get("result"));
    }
    @Test void unexpectedEnvelopesNeverSilentlyAdvanceCheckpoint(){
        assertThrows(BusinessException.class,()->FinanceMarketplace.requiredRows(Map.of("message","different schema"),"transactions"));
        assertThrows(BusinessException.class,()->FinanceMarketplace.requiredRows(Map.of("transactions",List.of("bad")),"transactions"));
        assertEquals(List.of(),FinanceMarketplace.requiredRows(Map.of("total_count",0),"transactions"));
        assertEquals(List.of(),FinanceMarketplace.requiredRows(Map.of(),"return_orders"));
    }
    MarketplaceAccess.Connection shopeeConnection(){
        var shop=new Shop("org",MarketplacePlatform.SHOPEE,"123","","","LOCAL","test","PH","[]");
        return new MarketplaceAccess.Connection(shop,new MarketplaceAccess.TokenSet("token","",Instant.now(),Instant.now(),List.of()));
    }
    Map<String,Object> shopeeCheckpoint(){
        return Map.of("from",Instant.parse("2026-10-13T16:00:00Z").getEpochSecond(),
                "to",Instant.parse("2026-10-20T16:00:00Z").getEpochSecond(),"cursor","previous");
    }
    @Test void shopeeIncomeAcceptsDocumentedAndLiveEnvelopesAndPreservesPagination(){
        var client=mock(ShopeeOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),client,mock(TikTokShopOfficialClient.class),repo);
        var row=Map.of("order_sn","income1","actual_payout_time",Instant.parse("2026-10-19T12:00:00Z").getEpochSecond(),"currency","PHP","released_amount","123.45");
        var page=Map.of("list",List.of(row),"next_page",Map.of("cursor","next","page_size",5));
        when(client.shopGet(eq("/api/v2/payment/get_escrow_detail"),any(),anyMap()))
                .thenReturn(Map.of("response",Map.of("order_income",Map.of("escrow_amount","123.45","commission_fee","5"))));
        for(var envelope:List.<Map<String,Object>>of(Map.of("income_detail_list",page),Map.of("response",Map.of("income_detail_list",page)),Map.of("response",page))){
            clearInvocations(repo);
            when(client.shopGet(eq("/api/v2/payment/get_income_detail"),any(),anyMap())).thenReturn(envelope);
            var step=reader.sho(new Scope("org","u"),shopeeConnection(),"SETTLEMENT","Asia/Manila",shopeeCheckpoint());
            assertFalse(step.done());assertEquals("next",step.checkpoint().get("cursor"));
            var saved=org.mockito.ArgumentCaptor.forClass(Entry.class);verify(repo,times(3)).save(any(),saved.capture());
            assertEquals(List.of("BILL","SETTLEMENT","BILL"),saved.getAllValues().stream().map(Entry::kind).toList());
            assertEquals(new BigDecimal("123.45"),saved.getAllValues().get(1).amount());
            assertEquals(LocalDate.of(2026,10,19),saved.getAllValues().get(1).date());
            assertEquals("SETTLED",saved.getAllValues().getLast().status());
        }
    }
    @Test void shopeeSuccessfulEmptyResponseAdvancesWithoutInventingRecords(){
        var client=mock(ShopeeOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),client,mock(TikTokShopOfficialClient.class),repo);
        // Observed live response for a historical range before the shop had income.
        when(client.shopGet(anyString(),any(),anyMap())).thenReturn(Map.of("error","","message","","response",Map.of()));
        var step=reader.sho(new Scope("org","u"),shopeeConnection(),"SETTLEMENT","Asia/Manila",shopeeCheckpoint());
        assertTrue(step.done());assertEquals("",step.checkpoint().get("cursor"));verifyNoInteractions(repo);
        verify(client,times(1)).shopGet(eq("/api/v2/payment/get_income_detail"),any(),anyMap());
    }
    @Test void shopeeIncomeMalformedPagesDoNotAdvanceOrPretendToBeEmpty(){
        var client=mock(ShopeeOfficialClient.class);var repo=mock(FinanceRepository.class);
        var reader=new FinanceMarketplace(mock(FinanceShopAuthorization.class),client,mock(TikTokShopOfficialClient.class),repo);
        for(var envelope:List.<Map<String,Object>>of(Map.of(),Map.of("response","invalid"),Map.of("response",Map.of("unexpected",List.of())),
                Map.of("response",Map.of("income_detail_list","invalid")),Map.of("response",Map.of("list",List.of("invalid"))),
                Map.of("response",Map.of("list",List.of(),"next_page","invalid")),
                Map.of("response",Map.of("list",List.of(),"next_page",Map.of("cursor","previous"))))){
            when(client.shopGet(anyString(),any(),anyMap())).thenReturn(envelope);
            assertThrows(BusinessException.class,()->reader.sho(new Scope("org","u"),shopeeConnection(),"SETTLEMENT","Asia/Manila",shopeeCheckpoint()));
        }
        verifyNoInteractions(repo);
    }
    @Test void initialSettlementShowsRecentWeekBeforeResumingExactHistoricalCursor(){
        ZoneId zone=ZoneId.of("Asia/Manila");
        long from=LocalDate.of(2026,7,24).atStartOfDay(zone).toEpochSecond();
        long until=LocalDateTime.of(2026,10,21,9,0).atZone(zone).toEpochSecond();
        var old=new LinkedHashMap<String,Object>(Map.of("from",from,"to",from+7*86400L,"until",until,
                "coverage","2026-07-24","run","historical-run","cursor","old-page","transactionCursor","old-bill-page",
                "transactionCount",20,"bills",List.of(Map.of("id","bill1"))));
        var recent=FinanceSync.recentSettlementFirst(old,true,"SETTLEMENT",zone);
        assertEquals(LocalDate.of(2026,9,22).atStartOfDay(zone).toEpochSecond(),recent.get("from"));
        assertEquals(number(recent.get("from"))+604800,recent.get("to"));assertEquals("old-page",old.get("cursor"));
        assertFalse(recent.containsKey("cursor"));assertFalse(recent.containsKey("bills"));
        assertSame(recent,FinanceSync.recentSettlementFirst(recent,true,"SETTLEMENT",zone));
        var page=new LinkedHashMap<>(recent);page.put("cursor","recent-page-2");
        var progress=FinanceSync.nextWindow(recent,new FinanceMarketplace.Step(page,false));
        assertFalse(progress.done());assertEquals("recent-page-2",progress.checkpoint().get("cursor"));
        var resume=FinanceSync.nextWindow(page,new FinanceMarketplace.Step(page,true));
        while(resume.checkpoint().containsKey("backfillResume")){
            var window=resume.checkpoint();
            assertTrue(number(window.get("to"))-number(window.get("from"))<=604800);
            assertFalse(window.containsKey("cursor"));
            resume=FinanceSync.nextWindow(window,new FinanceMarketplace.Step(window,true));
        }
        assertFalse(resume.done());assertEquals("old-page",resume.checkpoint().get("cursor"));
        assertEquals("old-bill-page",resume.checkpoint().get("transactionCursor"));
        assertEquals(20,resume.checkpoint().get("transactionCount"));
        assertEquals(old.get("bills"),resume.checkpoint().get("bills"));
        assertEquals(recent.get("from"),resume.checkpoint().get("until"));
        assertSame(resume.checkpoint(),FinanceSync.recentSettlementFirst(resume.checkpoint(),true,"SETTLEMENT",zone));
        long seconds=until-number(recent.get("from"));var cp=resume.checkpoint();boolean complete=false;
        for(int windows=0;windows<20;windows++){
            seconds+=number(cp.get("to"))-number(cp.get("from"));
            var next=FinanceSync.nextWindow(cp,new FinanceMarketplace.Step(cp,true));
            if(next.done()){complete=true;break;}
            assertEquals(cp.get("to"),next.checkpoint().get("from"));
            assertTrue(Boolean.TRUE.equals(next.checkpoint().get("recentFirst")));
            cp=next.checkpoint();
        }
        assertTrue(complete);assertEquals(until-from,seconds);
    }
    @Test void existingSevenDayBackfillUpgradesToMonthWithoutLosingCurrentBill(){
        var zone=ZoneId.of("Asia/Manila");long from=LocalDate.of(2026,7,24).atStartOfDay(zone).toEpochSecond();
        long until=LocalDate.of(2026,10,15).atStartOfDay(zone).toEpochSecond();
        var old=Map.<String,Object>of("from",from,"to",from+604800,"until",until,"recentFirst",true,"cursor","keep-history","transactionCursor","keep-bill");
        var next=FinanceSync.recentSettlementFirst(old,true,"SETTLEMENT",zone);
        assertEquals(30,next.get("recentWindowDays"));
        assertEquals("keep-history",map(next.get("backfillResume")).get("cursor"));
        assertEquals("keep-bill",map(next.get("backfillResume")).get("transactionCursor"));
        assertSame(next,FinanceSync.recentSettlementFirst(next,true,"SETTLEMENT",zone));
    }
    @Test void recentPriorityDoesNotDisturbIncrementalSyncOrOtherChannels(){
        var cp=Map.<String,Object>of("from",1L,"to",2L,"until",3L);
        var zone=ZoneId.of("Asia/Manila");
        assertSame(cp,FinanceSync.recentSettlementFirst(cp,false,"SETTLEMENT",zone));
        for(String channel:List.of("REFUND","PENDING"))
            assertSame(cp,FinanceSync.recentSettlementFirst(cp,true,channel,zone));
        var last=Map.<String,Object>of("from",1L,"to",3L,"until",3L);
        assertSame(last,FinanceSync.recentSettlementFirst(last,true,"SETTLEMENT",zone));
        assertTrue(FinanceSync.nextWindow(last,new FinanceMarketplace.Step(last,true)).done());
    }
    @Test void historicalOrdersPrioritizeMonthAndResumeEveryOlderPage(){
        var zone=ZoneId.of("Asia/Manila");long until=LocalDateTime.of(2026,10,21,12,0).atZone(zone).toEpochSecond();
        long from=LocalDate.of(2026,7,24).atStartOfDay(zone).toEpochSecond();
        var old=Map.<String,Object>of("from",from,"to",from+604800,"until",until,"cursor","keep","coverage","2026-07-24");
        var prioritized=FinanceSync.recentSettlementFirst(old,true,"ORDER",zone);
        assertEquals(LocalDate.of(2026,9,22).atStartOfDay(zone).toEpochSecond(),prioritized.get("from"));
        var next=FinanceSync.nextWindow(prioritized,new FinanceMarketplace.Step(prioritized,true));
        while(next.checkpoint().containsKey("backfillResume"))next=FinanceSync.nextWindow(next.checkpoint(),new FinanceMarketplace.Step(next.checkpoint(),true));
        assertEquals("keep",next.checkpoint().get("cursor"));assertEquals(prioritized.get("from"),next.checkpoint().get("until"));
    }
    @Test void mysqlTimestampVariantsUseUtc(){
        var day=LocalDateTime.of(2026,10,20,10,0);
        assertEquals(day.toInstant(ZoneOffset.UTC),instant(day));
        assertEquals(day.toInstant(ZoneOffset.UTC),instant(java.sql.Timestamp.from(day.toInstant(ZoneOffset.UTC))));
    }
    @Test void referenceConversionNeverChangesNativeReconciliation(){
        var repo=new FinanceRepository(mock(JdbcTemplate.class),new ObjectMapper());var fx=new FinanceFx(repo,new ObjectMapper());
        var row=new HashMap<String,Object>(Map.of("currency","PHP","amount",new BigDecimal("100"),"detail_amount",new BigDecimal("80"),"difference",new BigDecimal("20")));
        fx.enrich(List.of(row),new FinanceFx.Quote("1",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125"))));
        assertEquals(new BigDecimal("100"),row.get("amount"));assertEquals(new BigDecimal("20"),row.get("difference"));
        assertEquals(new BigDecimal("12.500000"),row.get("cny_amount"));assertEquals(new BigDecimal("2.500000"),row.get("cny_difference"));
    }
    @Test void excelMoneyIsNumericButLongIdentifiersRemainText()throws Exception{
        byte[] bytes=FinanceWorkbook.xlsx(Map.of("明细",List.of(List.of("记录编号","原币金额","参考汇率"),List.of("1234567890123456789","-123.45","0.125"))));
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry part;String xml="";
            while((part=zip.getNextEntry())!=null)if(part.getName().equals("xl/worksheets/sheet1.xml"))xml=new String(zip.readAllBytes());
            assertTrue(xml.contains("<v>-123.45</v>"));assertTrue(xml.contains("<v>0.125</v>"));
            assertTrue(xml.contains("<t xml:space=\"preserve\">1234567890123456789</t>"));assertFalse(xml.contains("<v>1234567890123456789"));
        }
    }
    @Test void importRejectsAllRowsBeforeWritingAnyIfOneRowIsInvalid(){
        var service=mock(FinanceService.class);var repo=spy(new FinanceRepository(mock(JdbcTemplate.class),new ObjectMapper()));var importer=new FinanceImport(service,repo);
        var scope=new Scope("org","user");
        when(service.ownedShop(scope,"shop")).thenReturn(Map.of("platform","SHOPEE"));
        when(service.shopScope(scope,"shop")).thenReturn(scope);
        byte[] csv="id,date,currency,amount\nvalid,2026-10-20,PHP,100\ninvalid,not-date,PHP,99".getBytes();
        assertThrows(BusinessException.class,()->importer.commit(scope,"shop","BILL",Map.of("external_id",0,"date",1,"currency",2,"amount",3),csv,"bill.csv"));
        verify(repo,never()).save(any(),any());
    }
    @Test void batchNumbersRemainExactTextAndRejectUnboundedSearch(){
        assertEquals(List.of("585757874943591564","00123456789012345678"),FinanceRecordSearch.numbers("585757874943591564\n00123456789012345678，585757874943591564"));
        assertEquals(List.of(),FinanceRecordSearch.numbers(null));
        assertThrows(BusinessException.class,()->FinanceRecordSearch.numbers(String.join(",",java.util.stream.IntStream.range(0,101).mapToObj(String::valueOf).toList())));
        var q=FinanceRecordSearch.apply(new FinanceRepository.Query("r.organization_id=?",List.of("org")),"r","x' OR 1=1 --",false);
        assertFalse(q.where().contains("x'"));assertEquals("org",q.args().getFirst());
    }
    @Test void cnyExportUsesPinnedSnapshotAndKeepsNativeAmounts(){
        var repo=mock(FinanceRepository.class);when(repo.jdbc()).thenReturn(mock(JdbcTemplate.class));var policy=mock(OrganizationAccessPolicy.class);var fx=spy(new FinanceFx(repo,new ObjectMapper()));
        var principal=user("heyi");when(policy.isPlatformAdmin(principal)).thenReturn(true);
        var quote=new FinanceFx.Quote("fixed",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125")));
        doReturn(quote).when(fx).quote("fixed");
        var service=classProxy(new FinanceService(repo,policy,mock(FinanceShopAuthorization.class),fx));
        var controller=new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var item=new HashMap<String,Object>(Map.of("external_id","00123456789012345678","currency","PHP","amount",new BigDecimal("100"),"product_skus","SKU-A\n（SKU-B）*2"));
        when(repo.details(any(),any(),any(),eq(1),eq(100),nullable(String.class))).thenReturn(Map.of("total",1,"items",List.of(item)));
        when(repo.reconciliation(any(),any(),eq(100001),nullable(String.class))).thenReturn(List.of());
        var response=controller.export(Map.of("start","2026-10-01","end","2026-10-20","displayCurrency","CNY","fxSnapshot","fixed"),principal);
        var table=FinanceWorkbook.read(response.getBody(),"export.xlsx");
        assertEquals("100",table.get(1).get(table.getFirst().indexOf("原币金额")));
        assertEquals("12.500000",table.get(1).get(table.getFirst().indexOf("折算人民币")));
        assertEquals("00123456789012345678",table.get(1).get(table.getFirst().indexOf("记录编号")));
        assertEquals("SKU-A\n（SKU-B）*2",table.get(1).get(table.getFirst().indexOf("商品 SKU")));
        verify(fx).quote("fixed");
    }
    @Test void importedComparisonBillsNeverDoubleCountOfficialStatementCharts(){
        var db=mock(JdbcTemplate.class);var repo=new FinanceRepository(db,new ObjectMapper());
        repo.grouped(new Scope("org","u"),new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,20),"","","","day"),"kind");
        verify(db).queryForList(contains("AND NOT(kind='BILL' AND source='IMPORT')"),any(Object[].class));
    }
    @Test void orderCostHttpEndpointWorksThroughSpringClassProxies()throws Exception{
        var db=mock(JdbcTemplate.class);
        var repository=classProxy(new FinanceRepository(db,new ObjectMapper()));
        var service=classProxy(new FinanceService(repository,mock(OrganizationAccessPolicy.class),
                mock(FinanceShopAuthorization.class),mock(FinanceFx.class)));
        var workspace=classProxy(new FinanceCostWorkspace(repository,service));
        assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(workspace));
        var principal=member("org","viewer","finance:view");
        var mvc=MockMvcBuilders.standaloneSetup(new FinanceCostController(workspace,service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver(){
                    public boolean supportsParameter(MethodParameter p){return p.getParameterType()==PlatformPrincipal.class;}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory b){return principal;}
                }).build();
        mvc.perform(get("/api/v1/finance/workspace/costs/orders")
                .param("start","2026-10-01").param("end","2026-10-23").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.productCosts.currency").value("CNY"))
                .andExpect(jsonPath("$.data.productCosts.scope").value("SETTLED_ORDER"));
        verify(db).queryForList(startsWith("SELECT x.shop_id,"),
                eq("org"),eq(java.sql.Date.valueOf("2026-10-01")),eq(java.sql.Date.valueOf("2026-10-23")),eq("org"));
        verify(db,never()).queryForObject(startsWith("SELECT COUNT(*) FROM ("),eq(Long.class),any(Object[].class));
        clearInvocations(db);
        mvc.perform(get("/api/v1/finance/workspace/costs/orders")
                .param("kind","BILL").param("recordId","bill-record"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productCosts.scope").value("BILL"));
        verify(db).queryForList(startsWith("SELECT x.shop_id,"),eq("org"),eq("bill-record"));
        mvc.perform(get("/api/v1/finance/workspace/costs/orders").param("kind","INVALID"))
                .andExpect(status().isBadRequest());
    }
    @Test void springClassProxiesRetainDependenciesForWorkersAndController()throws Exception{
        var db=mock(JdbcTemplate.class);
        var source=mock(javax.sql.DataSource.class);when(source.getConnection()).thenReturn(mock(java.sql.Connection.class));when(db.getDataSource()).thenReturn(source);
        var repository=classProxy(new FinanceRepository(db,new ObjectMapper()));
        assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(repository));
        assertSame(db,repository.jdbc());
        var policy=mock(OrganizationAccessPolicy.class);var fx=mock(FinanceFx.class);var shops=mock(FinanceShopAuthorization.class);
        var service=classProxy(new FinanceService(repository,policy,shops,fx));
        assertSame(fx,service.exchangeRates());assertSame(shops,service.authorization());
        var worker=new FinanceSync(repository,mock(FinanceMarketplace.class));
        var ads=new FinanceAds(repository,service,mock(MarketplaceHttp.class),mock(ShopCredentialCipher.class));
        try{
            assertDoesNotThrow(worker::tick);assertDoesNotThrow(ads::tick);
            assertDoesNotThrow(()->service.shops(new Scope("org","u")));
            assertDoesNotThrow(()->service.syncStates(new Scope("org","u")));
            assertDoesNotThrow(()->service.dashboard(new Scope("org","u"),
                    new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,20),"","","","day"),false,null));
        }finally{worker.stop();ads.stop();}
    }
}
