package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceWorkspaceCurrencyCountIntegrationTest {
    private static final LocalDate START=LocalDate.of(2026,10,1),END=LocalDate.of(2026,10,31),DAY=LocalDate.of(2026,10,3);
    private static final String TARGET_PREFIX="currency-count-target-";
    private final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    private FinanceQueryProjections projections;private FinanceWorkspaceQueryService queries;private String shop,otherShop;

    @BeforeAll void schema()throws Exception{fixture.migrate();}
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){
        fixture.setup();
        shop=fixture.connect(fixture.owner,false);otherShop=fixture.connect(fixture.other,false);
        var spend=new FinanceAdSpendService(fixture.repo,fixture.service);
        projections=new FinanceQueryProjections(fixture.repo,fixture.service,spend);
        queries=new FinanceWorkspaceQueryService(fixture.repo,fixture.service,spend,projections,mock(FinanceAds.class),null);
    }

    Stream<CurrencyCase> currencies(){return Stream.of(
        new CurrencyCase("lowercase SQL equivalent","usd",true),
        new CurrencyCase("accented SQL equivalent","ÜSD",true),
        new CurrencyCase("NO PAD trailing space control","USD ",false),
        new CurrencyCase("ordinary nonmatching control","PHP",false));}

    @ParameterizedTest(name="{0}")
    @MethodSource("currencies")
    void rebuiltReadyProjectionUsesTheNativeCurrencyComparisonForAuthoritativeTotals(CurrencyCase currencyCase){
        seed(fixture.owner,shop,TARGET_PREFIX,70,"USD");
        seed(fixture.owner,shop,"currency-count-multicurrency-",11,"PHP");
        seed(fixture.other,otherShop,"currency-count-other-company-",13,"USD");
        Filter neutral=filter(""),usd=filter("USD");
        build(fixture.owner,neutral);
        String oldRevision=text(projections.version(fixture.owner,neutral).get("revision"));

        int changed=fixture.db.update("UPDATE finance_records SET currency=? WHERE organization_id=? AND external_id LIKE ?",
            currencyCase.stored(),fixture.owner.organizationId(),TARGET_PREFIX+"%");
        assertEquals(70,changed);
        int drained=drain();assertTrue(drained>0,"The representation change must enter the real projection rebuild pipeline");
        assertEquals("PENDING",projections.version(fixture.owner,neutral).get("status"));
        buildPending();

        var version=projections.version(fixture.owner,neutral);
        assertEquals("READY",version.get("status"));assertNotEquals(oldRevision,version.get("revision"));
        var source=fixture.db.queryForMap("""
            SELECT MIN(currency) currency,MIN(HEX(currency)) currency_hex,COUNT(*) records,
              SUM(currency <=> CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci) sql_equal_usd
            FROM finance_records
            WHERE organization_id=? AND external_id LIKE ?
            ""","USD",fixture.owner.organizationId(),TARGET_PREFIX+"%");
        long sourceMatches=number(source.get("sql_equal_usd"));
        assertEquals(currencyCase.matches()?70:0,sourceMatches,"The actual source column decides SQL equivalence");
        long nativeCount=nativeCount(usd);
        assertEquals(currencyCase.matches()?70:0,nativeCount);
        System.out.println("FINANCE_WORKSPACE_CURRENCY_COUNT_READY "+fixture.repo.write(Map.of(
            "case",currencyCase.label(),"stored",currencyCase.stored(),"source",source,
            "drained",drained,"status",version.get("status"),"revision",version.get("revision"),"nativeCount",nativeCount)));

        for(int page=1;page<=2;page++){
            var selected=selection(page);var expected=nativeWorkspace(usd,selected);
            var actual=queries.query(fixture.owner,fixture.owner,usd,selected);
            assertEquals(fixture.repo.write(stable(expected)),fixture.repo.write(stable(actual)),"complete page "+page);
            var records=map(actual.get("records"));assertEquals(nativeCount,number(records.get("total")));
            assertEquals(currencyCase.matches()?(page==1?50:20):0,rows(records.get("items")).size());
            assertEquals("READY",map(actual.get("readModel")).get("status"));
            assertEquals(version.get("revision"),map(actual.get("readModel")).get("revision"));
        }
    }

    private void seed(Scope scope,String selectedShop,String prefix,int count,String currency){
        for(int i=0;i<count;i++)fixture.repo.save(scope,new Entry(selectedShop,"SHOPEE","FEE",prefix+i,"","","pay_per_sale","SETTLED",DAY,currency,
            decimal("12.123456"),null,null,null,null,"API",Map.of()));
    }
    private void build(Scope scope,Filter filter){projections.request(scope,filter);drain();buildPending();assertTrue(projections.find(scope,filter).isPresent());}
    private int drain(){int total=0;for(int i=0;i<20;i++){int count=projections.drainEvents();total+=count;if(count==0)return total;}fail("Projection events did not drain inside the bounded test loop");return total;}
    private void buildPending(){for(int i=0;i<20&&projections.buildNext();i++){} }
    private long nativeCount(Filter filter){
        var query=fixture.repo.query(fixture.owner,filter);var args=new ArrayList<>(query.args());args.add("FEE");
        return number(fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+query.where()+" AND kind=?",Long.class,args.toArray()));
    }
    private Map<String,Object> nativeWorkspace(Filter filter,FinanceWorkspaceQueryService.Selection selected){
        Filter neutral=filter("");
        var facts=fixture.service.buildNativeDashboardFacts(fixture.owner,neutral,false);
        var result=new LinkedHashMap<>(fixture.service.dashboardFromFacts(fixture.owner,filter,false,"",false,facts));
        var records=new LinkedHashMap<>(fixture.repo.details(fixture.owner,filter,"FEE",selected.page(),50,"",false));
        records.put("dateBasis",FinanceCalendar.contract());records.put("fieldSettings",new FinanceFields(fixture.repo).settings(fixture.owner,"FEE"));
        result.put("records",records);return result;
    }
    private static Map<String,Object> stable(Map<String,Object> value){var copy=new LinkedHashMap<>(value);copy.remove("updatedAt");copy.remove("readModel");return copy;}
    private static Filter filter(String currency){return new Filter(START,END,"","",currency,"day");}
    private static FinanceWorkspaceQueryService.Selection selection(int page){return new FinanceWorkspaceQueryService.Selection("fees","FEE",false,false,"",page,1,"","","");}
    private record CurrencyCase(String label,String stored,boolean matches){@Override public String toString(){return label;}}
}
