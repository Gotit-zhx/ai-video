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
    @Test void ordinaryAdminAndPermissionCodesCannotUnlockFinance(){
        assertThrows(BusinessException.class,()->scope(user("other"),false));
        assertThrows(BusinessException.class,()->scope(null,false));
        assertEquals("user",scope(user("heyi"),true).userId());
    }
    @Test void everyEndpointRejectsOrdinaryAdminBeforeBusinessLogic() throws Exception {
        var policy=mock(OrganizationAccessPolicy.class);var repo=mock(FinanceRepository.class);
        var service=new FinanceService(repo,policy,mock(FinanceShopAuthorization.class),mock(FinanceFx.class));
        var controller=new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var principal=user("other");
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
        verify(repo,times(3)).save(any(),any());
        when(tk.apiGet(anyString(),anyString(),anyString(),anyMap())).thenReturn(Map.of("data",Map.of("currency","PHP","total_settlement_amount","30","transactions",List.of())));
        assertTrue(reader.tik(new Scope("org","u"),connection,"SETTLEMENT","Asia/Manila",step.checkpoint()).done());
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
        assertTrue(q.where().contains("organization_id=? AND user_id=?"));assertEquals(List.of("org-a","user-a"),q.args().subList(0,2));
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
            var saved=org.mockito.ArgumentCaptor.forClass(Entry.class);verify(repo,times(4)).save(any(),saved.capture());
            assertEquals(List.of("BILL","SETTLEMENT","FEE","BILL"),saved.getAllValues().stream().map(Entry::kind).toList());
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
        byte[] csv="id,date,currency,amount\nvalid,2026-10-20,PHP,100\ninvalid,not-date,PHP,99".getBytes();
        assertThrows(BusinessException.class,()->importer.commit(scope,"shop","BILL",Map.of("external_id",0,"date",1,"currency",2,"amount",3),csv,"bill.csv"));
        verify(repo,never()).save(any(),any());
    }
    @Test void cnyExportUsesPinnedSnapshotAndKeepsNativeAmounts(){
        var repo=mock(FinanceRepository.class);when(repo.jdbc()).thenReturn(mock(JdbcTemplate.class));var policy=mock(OrganizationAccessPolicy.class);var fx=spy(new FinanceFx(repo,new ObjectMapper()));
        var principal=user("heyi");when(policy.isPlatformAdmin(principal)).thenReturn(true);
        var quote=new FinanceFx.Quote("fixed",Instant.EPOCH,Map.of("PHP",new BigDecimal("0.125")));
        doReturn(quote).when(fx).quote("fixed");
        var service=classProxy(new FinanceService(repo,policy,mock(FinanceShopAuthorization.class),fx));
        var controller=new FinanceController(service,repo,mock(FinanceImport.class),mock(FinanceAds.class));
        var item=new HashMap<String,Object>(Map.of("external_id","00123456789012345678","currency","PHP","amount",new BigDecimal("100")));
        when(repo.details(any(),any(),any(),eq(1),eq(100))).thenReturn(Map.of("total",1,"items",List.of(item)));
        when(repo.reconciliation(any(),any(),eq(100001))).thenReturn(List.of());
        var response=controller.export(Map.of("start","2026-10-01","end","2026-10-20","displayCurrency","CNY","fxSnapshot","fixed"),principal);
        var table=FinanceWorkbook.read(response.getBody(),"export.xlsx");
        assertEquals("100",table.get(1).get(table.getFirst().indexOf("原币金额")));
        assertEquals("12.500000",table.get(1).get(table.getFirst().indexOf("折算人民币")));
        assertEquals("00123456789012345678",table.get(1).get(table.getFirst().indexOf("记录编号")));
        verify(fx).quote("fixed");
    }
    @Test void importedComparisonBillsNeverDoubleCountOfficialStatementCharts(){
        var db=mock(JdbcTemplate.class);var repo=new FinanceRepository(db,new ObjectMapper());
        repo.grouped(new Scope("org","u"),new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,20),"","","","day"),"kind");
        verify(db).queryForList(contains("AND NOT(kind='BILL' AND source='IMPORT')"),any(Object[].class));
    }
    @Test void springClassProxiesRetainDependenciesForWorkersAndController(){
        var db=mock(JdbcTemplate.class);
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
