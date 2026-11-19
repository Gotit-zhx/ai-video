package com.yingdao.aicommerce.finance;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentest4j.AssertionFailedError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only grouped visibility and independent row-order cohorts; no production adoption. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinanceRecordCountVisibilityIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final String PREFIX="SELECT COUNT(*) FROM finance_records FORCE INDEX (idx_finance_company_kind_date) WHERE ";
    static final String ORIGINAL="AND (finance_records.shop_id='UNASSIGNED' OR EXISTS (SELECT 1 FROM finance_connections fc FORCE INDEX(PRIMARY) WHERE fc.id=finance_records.shop_id AND fc.organization_id=? AND fc.status<>'REMOVED'))";
    static final String CANDIDATE="AND (finance_records.shop_id='UNASSIGNED' OR finance_records.shop_id IN (SELECT fc.id FROM finance_connections fc WHERE fc.organization_id=? AND fc.status<>'REMOVED'))";
    static final String A="rci-shop-a",B="rci-shop-b",U="rci-shop-u",D="rci-shop-disabled",D2="rcv-disabled-status",R="rci-shop-removed",F="rci-shop-foreign";
    static final String GA="rci-group-a",GB="rci-group-b";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Filter MONTH=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day","");
    static final int SAMPLES_PER_MODE=30;
    static final long PHASE_NANOS=120_000_000_000L;
    final List<String> ownedIds=new ArrayList<>();
    final Set<String> ownedShops=new LinkedHashSet<>();
    final List<Object[]> ownedMarkers=new ArrayList<>();
    JdbcTemplate experimentDb;
    long ownedEventStart;
    boolean semanticsVerified,collationVerified;
    String fixtureStage="native-matrix";

    @Test @Order(1) void literalNativeSemanticsAndRealWrongResultControlsRunBeforeHeavyProbe() {
        try {
            fixtureStage="native-matrix";beginFixture();shops();smallLedger();
            repo.readSnapshot(180,()->{
                var witness=new Witness(dataSource);var probes=probes(witness);
                compareCases(cases(),probes,witness,"native-matrix");
                var ownerCase=cases().getFirst();var current=probes.get(Mode.CURRENT_EXISTS);
                current.jdbc().clear();
                var foreign=new Case("negative-foreign-count",other,MONTH,"FEE","",false,false,1,ids(10));
                var wrongTotal=read(current.repo(),foreign,100,5);
                assertEquals(1,current.jdbc().counts.size());
                assertEquals(1,number(wrongTotal.get("total")));
                assertThrows(AssertionFailedError.class,()->oracle(ownerCase,100,5,wrongTotal));
                current.jdbc().clear();var wrongPage=read(current.repo(),ownerCase,2,5);
                assertEquals(1,current.jdbc().counts.size());assertEquals(ownerCase.total(),number(wrongPage.get("total")));
                assertThrows(AssertionFailedError.class,()->oracle(ownerCase,1,5,wrongPage));
                emit("realNegativeControls",Map.of("wrongTotalFromRealForeignScopeCount",true,"wrongPageFromRealOwnerPageTwo",true,
                    "eachExecutedCount",1,"noInjectedTotal",true,"mode",Mode.CURRENT_EXISTS,"snapshot",witness.receipt()));
                String actual=current.jdbc().counts.getFirst().original();
                assertNotEquals(actual,rewrite(actual));
                for(String nearMiss:List.of(actual.replace("OR EXISTS (","OR EXISTS  ("),actual.replace("fc.id=finance_records.shop_id","fc.id=r.shop_id"),
                    actual+" "+ORIGINAL,actual.replace("SELECT COUNT(*) FROM","select count(*) from")))
                    assertEquals(nearMiss,rewrite(nearMiss),"Only one exact positively recognized typed clause may change");
                for(var page:current.jdbc().pages)assertEquals(page.sql(),rewrite(page.sql()));
                verifyGroupedGuard(actual,current.jdbc().counts.getFirst().args());
                emit("positiveGuard",Map.of("actualTypedCountRewritten",true,"nearMissesPreserved",4,"pageSqlPreserved",true,"groupedBindingAndMalformedControls",true));
                for(var mode:List.of(Mode.CANDIDATE_IN,Mode.GROUPED_EXISTS)) {
                    var candidate=probes.get(mode);candidate.jdbc().clear();
                    var foreignActual=read(candidate.repo(),foreign,100,5);assertEquals(1,candidate.jdbc().counts.size());assertEquals(1,number(foreignActual.get("total")));
                    assertThrows(AssertionFailedError.class,()->oracle(ownerCase,100,5,foreignActual));
                    candidate.jdbc().clear();var secondPage=read(candidate.repo(),ownerCase,2,5);assertEquals(1,candidate.jdbc().counts.size());assertEquals(ownerCase.total(),number(secondPage.get("total")));
                    assertThrows(AssertionFailedError.class,()->oracle(ownerCase,1,5,secondPage));
                    emit("realNegativeControls",Map.of("mode",mode,"wrongTotalFromRealForeignScopeCount",true,"wrongPageFromRealOwnerPageTwo",true,
                        "eachExecutedCount",1,"noInjectedTotal",true,"snapshot",witness.receipt()));
                }
                return null;
            });
            var cached=new CountJdbc(experimentDb,Mode.CURRENT_EXISTS);var cachedRepo=new FinanceRepository(cached,new ObjectMapper());
            cachedRepo.readSnapshot(60,()->{
                cached.witness=new Witness(dataSource);var selected=cases().getFirst();
                var oldPage=read(cachedRepo,selected,100,5);assertEquals(1,cached.counts.size());
                for(var mode:List.of(Mode.CANDIDATE_IN,Mode.GROUPED_EXISTS)) {
                    cached.mode=mode;assertEquals(oldPage,read(cachedRepo,selected,100,5));assertEquals(1,cached.counts.size());
                    assertFalse(cached.counts.getFirst().rewritten());
                    emit("cacheNegativeControl",Map.of("mode",mode,"currentCountsExecuted",1,"candidateCountsExecuted",0,"cacheWouldHideCandidate",true,
                        "snapshot",cached.witness.receipt()));
                }
                return null;
            });
            semanticsVerified=true;
        } finally {cleanup();}
    }

    @Test @Order(2) void collationEquivalentDisabledAndPermittedMissingShopRowsKeepExactVisibility() {
        assertTrue(semanticsVerified,"Literal native comparison must pass before extra visibility cases");
        try {
            fixtureStage="collation-and-disabled";beginFixture();shops();
            var values=new ArrayList<Object[]>();
            for(int n=33;n<=39;n++) {
                String shop=switch(n){case 33->"RCI-SHOP-A";case 34->"rci-shóp-a";case 35->"unassigned";case 36->"";case 37->"rcv-missing";case 38->D2;default->"RCI-SHOP-FOREIGN";};
                String category=switch(n){case 33->"affiliate_ads_commission_amount";case 34->"tap_shop_ads_commission";case 35->"fees.affiliate_ads_commission_amount";default->"PLATFORM_FEE";};
                values.add(ledger("rc-"+n,"org","u",shop,"TIKTOK_SHOP","FEE",category,"SETTLED","2026-10-10","PHP",n==35?"IMPORT":"API",null,null,null,"{}"));
            }
            insert(values);
            var columns=experimentDb.queryForList("SELECT TABLE_NAME,COLUMN_NAME,IS_NULLABLE,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='finance_records' AND COLUMN_NAME='shop_id') OR (TABLE_NAME='finance_connections' AND COLUMN_NAME IN ('id','group_id')))");
            assertEquals("NO",text(columns.stream().filter(r->"finance_records".equals(r.get("TABLE_NAME"))).findFirst().orElseThrow().get("IS_NULLABLE")));
            emit("permittedSchemaCases",Map.of("columns",columns,"nullLedgerShopInserted",false,"nullLedgerShopUnavailableBySchema",true,
                "emptyAndMissingShopInserted",true,"nullConnectionGroupCovered",true));
            var cases=List.of(
                c("collation-all",owner,MONTH,"FEE","",false,false,4,ids(33,34,35,38)),
                c("collation-company-uppercase",new Scope("ORG","colleague"),MONTH,"FEE","",false,false,4,ids(33,34,35,38)),
                c("collation-case-accent-shop-filter",owner,filter(A,"","","",false),"FEE","",false,false,2,ids(33,34)),
                c("collation-group-membership",owner,filter("","","",GA,false),"FEE","",false,false,3,ids(33,34,38)),
                c("collation-lowercase-unassigned-ungrouped",owner,filter("","","","UNGROUPED",false),"FEE","",false,false,1,ids(35)),
                c("collation-ad-both-commissions-and-import",owner,MONTH,"FEE","",false,true,3,ids(33,34,35)),
                c("empty-shop-is-not-unassigned",owner,MONTH,"FEE","order-rc-36",false,false,0,List.of()),
                c("missing-shop-is-hidden",owner,MONTH,"FEE","order-rc-37",false,false,0,List.of()),
                c("disabled-status-remains-visible",owner,filter(D2,"","","",false),"FEE","",false,false,1,ids(38)),
                c("foreign-collation-shop-remains-hidden",owner,MONTH,"FEE","order-rc-39",false,false,0,List.of()));
            repo.readSnapshot(120,()->{var witness=new Witness(dataSource);compareCases(cases,probes(witness),witness,"collation-and-disabled");return null;});
            collationVerified=true;
        } finally {cleanup();}
    }

    @Test @Order(3) void boundedSkewedProfileReportsThirtyPairedSamplesPerModeWithoutSpeedThreshold() {
        runProfile(Profile.FIXED);
    }
    @Test @Order(4) void independentlyInterleavedIdsReportAllModesWithoutPoolingTheFixedCohort() {
        runProfile(Profile.INTERLEAVED);
    }
    private void runProfile(Profile profile) {
        assertTrue(semanticsVerified&&collationVerified,"Heavy probe is unexecuted unless both semantic stages pass");
        try {
            fixtureStage=profile.label;beginFixture();shops();
            for(int n=0;n<100;n++)shop(tail(n),"org",n%2==0?"u":"colleague","SHOPEE","USD",n%2==0?GB:null,"ACTIVE",true);
            long seed=System.nanoTime();
            for(int first=1;first<=100_000;first+=500) {
                var batch=new ArrayList<Object[]>();
                for(int n=first;n<first+500;n++) {
                    String shop=n<=60_000?A:n<=80_000?tail((n-60_001)%100):n<=85_000?D2:n<=90_000?(n%2==0?"UNASSIGNED":"unassigned"):n<=93_000?R:n<=95_000?"rcv-missing":n<=98_000?F:A;
                    String category=n<=80_000?(n%2==0?"fees.affiliate_ads_commission_amount":"PLATFORM_FEE"):"tap_shop_ads_commission";
                    String logicalId=heavyId(n),physicalId=heavyId(profile==Profile.FIXED?n:permutedSlot(n));
                    batch.add(ledger(physicalId,n>95_000&&n<=98_000?"other-org":"org",n%2==0?"colleague":"u",shop,
                        "TIKTOK_SHOP","FEE",category,n>98_000?"DATE_PENDING":"SETTLED","2026-10-10","PHP",n%3==0?"IMPORT":"API",
                        "external-"+logicalId,"order-"+logicalId,"statement-"+logicalId,
                        "{\"padding\":\""+"p".repeat(480)+"\"}"));
                }
                insert(batch);
            }
            assertEquals(100_000,experimentDb.queryForObject("SELECT COUNT(*) FROM finance_records",Long.class));
            experimentDb.execute("ANALYZE TABLE finance_records,finance_connections");
            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("profile",profile.label);receipt.put("actualRecords",100_000);receipt.put("maximumInsertBatch",500);receipt.put("rawJsonCharacters",494);receipt.put("seedMs",millis(seed));
            receipt.put("serverVersion",experimentDb.queryForObject("SELECT VERSION()",String.class));receipt.put("innodbBufferPoolBytes",experimentDb.queryForObject("SELECT @@innodb_buffer_pool_size",Long.class));
            receipt.put("tableMetadata",experimentDb.queryForList("SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('finance_records','finance_connections')"));
            receipt.put("indexMetadata",experimentDb.queryForList("SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,IS_VISIBLE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('finance_records','finance_connections') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"));
            receipt.put("actualShopSkew",experimentDb.queryForList("SELECT shop_id,COUNT(*) records FROM finance_records GROUP BY shop_id ORDER BY records DESC,shop_id LIMIT 8"));
            receipt.put("newDdl",false);receipt.put("idPermutation",profile==Profile.FIXED?"identity":"1+((logical-1)*7919 mod 100000); business fields retain logical identity");
            emit("profile",receipt);verifyActualIndexOrder(profile);
            repo.readSnapshot(180,()->{
                var witness=new Witness(dataSource);var probes=probes(witness);
                for(boolean ads:List.of(false,true)) {
                    var selected=new Case(profile.label+(ads?"-ad-only-fee":"-ordinary-fee"),owner,MONTH,"FEE","",false,ads,ads?50_000:90_000,List.of());
                    var current=probes.get(Mode.CURRENT_EXISTS);current.jdbc().clear();
                    var reference=read(current.repo(),selected,1,50);heavyOracle(selected,reference,profile);
                    assertEquals(1,current.jdbc().counts.size());var original=current.jdbc().counts.getFirst();var pages=List.copyOf(current.jdbc().pages);
                    for(var mode:Mode.values()) {
                        var p=probes.get(mode);p.jdbc().clear();var actual=read(p.repo(),selected,1,50);
                        assertEquals(reference,actual);assertEquals(pages,p.jdbc().pages);assertEquals(1,p.jdbc().counts.size());
                        assertCountShape(List.of(original),p.jdbc().counts,mode);
                        explain(selected.name()+"-"+mode,p.jdbc().counts.getFirst(),witness);
                    }
                    emit("equivalence",Map.of("profile",profile.label,"case",selected.name(),"literalTotal",selected.total(),"pageRows",50,"pageIdHash",idHash(reference),
                        "originalSql",original.original(),"originalBindings",bindingReceipt(original.args()),"snapshot",witness.receipt()));
                    measuredPhase(probes,selected,reference,witness);
                }
                witness.verify();return null;
            });
        } finally {cleanup();}
    }
    private void verifyActualIndexOrder(Profile profile) {
        String sql="SELECT id,shop_id,LOWER(shop_id) shop_order_key FROM finance_records FORCE INDEX (idx_finance_company_kind_date) WHERE organization_id=? AND kind=? AND record_date>=? AND record_date<=? ORDER BY record_date DESC,id";
        Object[] args={"org","FEE",java.sql.Date.valueOf(MONTH.start()),java.sql.Date.valueOf(MONTH.end())};
        int[] rows={0},run={0},max={0},changes={0};String[] previous={null};var first=new ArrayList<Map<String,Object>>();
        experimentDb.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            String shop=rs.getString("shop_id"),key=rs.getString("shop_order_key");rows[0]++;if(Objects.equals(previous[0],key))run[0]++;else{if(previous[0]!=null)changes[0]++;run[0]=1;previous[0]=key;}
            max[0]=Math.max(max[0],run[0]);if(first.size()<30)first.add(Map.of("id",rs.getString("id"),"shop",shop));
        },args);
        emit("indexRowOrder",Map.of("profile",profile.label,"sql",sql,"bindings",bindingReceipt(Arrays.asList(args)),"actualCompanyIndexRows",rows[0],
            "maximumAdjacentShopRun",max[0],"adjacentShopChanges",changes[0],"firstThirty",first,"asciiFixtureCaseFold",true));
        assertEquals(97_000,rows[0]);
        if(profile==Profile.INTERLEAVED){assertTrue(max[0]<50,"Interleaving fixture must break the dominant shop block");assertTrue(changes[0]>20_000);}
    }
    private static int permutedSlot(int logical){return 1+(int)(((logical-1L)*7919)%100_000);}
    private static String heavyId(int slot){return "rcv-h-"+String.format(Locale.ROOT,"%06d",slot);}

    private void compareCases(List<Case> cases,EnumMap<Mode,Probe> probes,Witness witness,String stage) {
        int executed=0,inferred=0;var rewrites=new EnumMap<Mode,Integer>(Mode.class);var compatibility=new EnumMap<Mode,Integer>(Mode.class);
        for(var mode:Mode.values()){rewrites.put(mode,0);compatibility.put(mode,0);}
        for(var selected:cases)for(int page:List.of(1,2,100)) {
            var observations=new EnumMap<Mode,Observation>(Mode.class);
            for(var mode:Mode.values()) {
                var p=probes.get(mode);p.jdbc().clear();var actual=read(p.repo(),selected,page,5);oracle(selected,page,5,actual);
                assertTrue(p.jdbc().counts.size()<=1);if(page==100)assertEquals(1,p.jdbc().counts.size(),"Empty later page must really count");
                if(p.jdbc().counts.isEmpty())inferred++;else executed++;
                observations.put(mode,new Observation(actual,List.copyOf(p.jdbc().counts),List.copyOf(p.jdbc().pages)));
            }
            var old=observations.get(Mode.CURRENT_EXISTS);var routeReceipt=new LinkedHashMap<String,Object>();
            for(var mode:List.of(Mode.CANDIDATE_IN,Mode.GROUPED_EXISTS)) {
                var candidate=observations.get(mode);assertEquals(old.page(),candidate.page());assertEquals(old.pages(),candidate.pages());
                assertCountShape(old.counts(),candidate.counts(),mode);
                for(var count:candidate.counts())if(count.rewritten())rewrites.merge(mode,1,Integer::sum);else compatibility.merge(mode,1,Integer::sum);
                routeReceipt.put(mode.name(),candidate.counts().stream().anyMatch(Count::rewritten));
            }
            emit("semantic",Map.of("stage",stage,"case",selected.name(),"page",page,"literalTotal",selected.total(),"pageIds",itemIds(old.page()),
                "actualCountStatementsPerMode",old.counts().size(),"inferred",old.counts().isEmpty(),"candidateActuallyRewritten",routeReceipt));
        }
        emit("semanticSummary",Map.of("stage",stage,"cases",cases.size(),"modes",Mode.values().length,"pagesPerCase",3,"realCountStatements",executed,"inferredCalls",inferred,
            "rewrittenCountsByMode",rewrites,"compatibilityCountsByMode",compatibility,"snapshot",witness.receipt()));
    }
    private void measuredPhase(EnumMap<Mode,Probe> probes,Case selected,Map<String,Object> expected,Witness witness) {
        for(var mode:Mode.values())sample(probes.get(mode),selected,expected,"warmup",0);
        long start=System.nanoTime(),deadline=start+PHASE_NANOS;boolean budgetExit=false;
        var samples=new ArrayList<Sample>();int attempted=0;
        try {
            paired:for(int pair=1;pair<=SAMPLES_PER_MODE;pair++) {
                var order=modeOrder(pair);
                emit("pairOrder",Map.of("case",selected.name(),"pair",pair,"order",order));
                for(var mode:order) {
                    if(System.nanoTime()>=deadline){budgetExit=true;break paired;}
                    attempted++;samples.add(sample(probes.get(mode),selected,expected,"measured",pair));
                }
            }
        } finally {
            var byMode=new LinkedHashMap<String,Object>();
            for(var mode:Mode.values()) {
                var completed=samples.stream().filter(s->s.mode()==mode).toList();
                byMode.put(mode.name(),Map.of("completed",completed.size(),"countMs",stats(completed.stream().map(Sample::countMs).toList()),
                    "detailsMs",stats(completed.stream().map(Sample::detailsMs).toList())));
            }
            emit("phaseSummary",Map.of("case",selected.name(),"plannedPerMode",SAMPLES_PER_MODE,"phaseBudgetMs",PHASE_NANOS/1_000_000,
                "elapsedMs",millis(start),"budgetExit",budgetExit,"attemptedSamples",attempted,"completedSamples",samples.size(),"allCompletedSamplesRetained",true,"modes",byMode,
                "snapshot",witness.receipt()));
        }
    }
    private Sample sample(Probe p,Case selected,Map<String,Object> expected,String phase,int pair) {
        p.jdbc().clear();long start=System.nanoTime();
        try {
            var actual=read(p.repo(),selected,1,50);double elapsed=millis(start);
            assertEquals(expected,actual);assertEquals(1,p.jdbc().counts.size());
            var count=p.jdbc().counts.getFirst();assertEquals(selected.total(),count.total());
            assertEquals(p.jdbc().mode!=Mode.CURRENT_EXISTS,count.rewritten());
            emit("sample",Map.of("case",selected.name(),"phase",phase,"pair",pair,"mode",p.jdbc().mode,"status","COMPLETED",
                "countMs",count.millis(),"detailsMs",elapsed,"actualCounts",1,"total",count.total(),"pageIdHash",idHash(actual)));
            return new Sample(p.jdbc().mode,count.millis(),elapsed);
        } catch(RuntimeException|AssertionError failure) {
            emit("failedSample",Map.of("case",selected.name(),"phase",phase,"pair",pair,"mode",p.jdbc().mode,"status","FAILED",
                "elapsedMs",millis(start),"failureClass",failure.getClass().getName(),"successfulCountStatements",p.jdbc().counts.size()));throw failure;
        }
    }
    private static Object stats(List<Double> values) {
        if(values.isEmpty())return "UNAVAILABLE_NO_COMPLETED_SAMPLES";
        var sorted=new ArrayList<>(values);Collections.sort(sorted);int size=sorted.size();
        return Map.of("p50",sorted.get((int)Math.ceil(size*0.50)-1),"p95",sorted.get((int)Math.ceil(size*0.95)-1),"max",sorted.getLast(),"method","nearest-rank");
    }
    private EnumMap<Mode,Probe> probes(Witness witness) {
        var out=new EnumMap<Mode,Probe>(Mode.class);
        for(var mode:Mode.values()){var jdbc=new CountJdbc(experimentDb,mode);jdbc.witness=witness;out.put(mode,new Probe(jdbc,new FinanceRepository(jdbc,new ObjectMapper())));}
        for(var probe:out.values())assertSame(dataSource,probe.jdbc().getDataSource());return out;
    }
    private Map<String,Object> read(FinanceRepository selected,Case c,int page,int size) {
        return c.ads()?selected.advertisingFeeDetails(c.scope(),c.filter(),page,size,c.numbers()):selected.details(c.scope(),c.filter(),c.kind(),page,size,c.numbers(),c.allOrders());
    }
    private void oracle(Case c,int page,int size,Map<String,Object> actual) {
        assertEquals(c.total(),number(actual.get("total")),c.name()+": independent literal total");int offset=(page-1)*size;
        assertEquals(c.ids().subList(Math.min(offset,c.ids().size()),Math.min(offset+size,c.ids().size())),itemIds(actual),c.name()+": ordered literal IDs");
        assertEquals(page,number(actual.get("page")));assertEquals(size,number(actual.get("pageSize")));
    }
    private void heavyOracle(Case c,Map<String,Object> actual,Profile profile) {
        assertEquals(c.total(),number(actual.get("total")));var expected=new ArrayList<String>();
        if(profile==Profile.FIXED)for(int n=1;expected.size()<50;n++){if(!c.ads()||n%2==0)expected.add(heavyId(n));}
        else {
            int[] literal=c.ads()?new int[]{2,4,6,8,10,14,16,17,18,20,22,23,24,26,28,30,32,34,36,38,40,42,44,48,50,51,52,54,56,58,60,62,64,66,68,70,72,76,78,82,84,85,86,88,90,92,94,96,98,100}
                :new int[]{1,2,3,4,5,6,7,8,9,10,11,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,47,48,49,50,51,52,53};
            for(int slot:literal)expected.add(heavyId(slot));
        }
        assertEquals(expected,itemIds(actual));
    }
    private static List<Mode> modeOrder(int pair) {
        var order=new ArrayList<>(List.of(Mode.CURRENT_EXISTS,Mode.CANDIDATE_IN,Mode.GROUPED_EXISTS));
        Collections.rotate(order,-(((pair-1)/2)%3));if(pair%2==0)Collections.reverse(order);return order;
    }
    private void assertCountShape(List<Count> original,List<Count> candidate,Mode mode) {
        assertEquals(original.size(),candidate.size());
        for(int n=0;n<original.size();n++) {
            var old=original.get(n);var now=candidate.get(n);assertEquals(old.original(),now.original());assertEquals(old.args(),now.args());
            var expected=transform(mode,old.original(),old.args());assertEquals(expected.sql(),now.emitted());assertEquals(expected.args(),now.emittedArgs());
            assertEquals(!old.original().equals(now.emitted()),now.rewritten());assertEquals(old.args().size(),now.emittedArgs().size());
            assertEquals(scan(now.emitted(),-1).parameters().size(),now.emittedArgs().size());
            if(mode==Mode.GROUPED_EXISTS&&now.rewritten()) {
                assertEquals(old.args().get(now.visibilityArgumentIndex()),now.emittedArgs().getLast());
                var remaining=new ArrayList<>(now.emittedArgs());remaining.removeLast();
                var originalRemainder=new ArrayList<>(old.args());originalRemainder.remove(now.visibilityArgumentIndex());assertEquals(originalRemainder,remaining);
            } else assertEquals(old.args(),now.emittedArgs());
        }
    }
    @SuppressWarnings("unchecked") private void explain(String label,Count q,Witness witness) {
        var planDb=new JdbcTemplate(dataSource);planDb.setQueryTimeout(10);witness.verify();
        var json=JSON.readValue(planDb.queryForObject("EXPLAIN FORMAT=JSON "+q.emitted(),String.class,q.emittedArgs().toArray()),Map.class);
        var actual=planDb.queryForList("EXPLAIN ANALYZE "+q.emitted(),q.emittedArgs().toArray());
        String tree=actual.stream().flatMap(r->r.values().stream()).map(String::valueOf).reduce("",(a,b)->a+b+"\n");witness.verify();
        var receipt=new LinkedHashMap<String,Object>();receipt.put("label",label);receipt.put("originalSql",q.original());receipt.put("emittedSql",q.emitted());
        receipt.put("originalBindings",bindingReceipt(q.args()));receipt.put("emittedBindings",bindingReceipt(q.emittedArgs()));receipt.put("visibilityArgumentIndex",q.visibilityArgumentIndex());
        receipt.put("tables",tables(json));receipt.put("sortFlags",flags(json,"using_filesort"));receipt.put("temporaryFlags",flags(json,"using_temporary_table"));
        receipt.put("materializedSubqueryFlags",flags(json,"materialized_from_subquery"));receipt.put("explainJson",json);receipt.put("explainAnalyze",tree);receipt.put("statementRowsExamined","UNAVAILABLE_NOT_REQUESTED");
        emit("plan",receipt);
    }
    private List<Case> cases() {
        var fee=ids(1,2,3,4,5,6,12,13,14,21,15,16,17,18,19,20);var ad=ids(1,2,3,4,5,14,15);
        var c=new ArrayList<Case>();
        c.add(c("ordinary-fee-company",owner,MONTH,"FEE","",false,false,16,fee));
        c.add(c("ordinary-fee-colleague",new Scope("org","colleague"),MONTH,"FEE","",false,false,16,fee));
        c.add(c("ad-fee-both-commissions-dotted-trimmed-labels",owner,MONTH,"FEE","",false,true,7,ad));
        c.add(c("group-a-includes-disabled",owner,filter("","","",GA,false),"FEE","",false,false,13,ids(1,2,6,12,13,14,21,15,16,17,18,19,20)));
        c.add(c("group-b-record-platform-differs-connection",owner,filter("","","",GB,false),"FEE","",false,false,1,ids(3)));
        c.add(c("ungrouped-and-unassigned",owner,filter("","","","UNGROUPED",false),"FEE","",false,false,2,ids(4,5)));
        c.add(c("unassigned-explicit",owner,filter("UNASSIGNED","","","",false),"FEE","",false,false,1,ids(5)));
        c.add(c("disabled-shop-is-visible",owner,filter(D,"","","",false),"FEE","",false,false,1,ids(6)));
        c.add(c("removed-shop-hidden",owner,filter(R,"","","",false),"FEE","",false,false,0,List.of()));
        c.add(c("missing-shop-hidden",owner,filter("rci-missing","","","",false),"FEE","",false,false,0,List.of()));
        c.add(c("foreign-shop-hidden",owner,filter(F,"","","",false),"FEE","",false,false,0,List.of()));
        c.add(c("foreign-company-owner",other,MONTH,"FEE","",false,false,1,ids(10)));
        c.add(c("record-platform-shopee",owner,filter("","SHOPEE","","",false),"FEE","",false,false,3,ids(5,12,19)));
        c.add(c("record-currency-usd",owner,filter("","","USD","",false),"FEE","",false,false,2,ids(5,12)));
        c.add(c("record-platform-currency-tiktok-php",owner,filter("","TIKTOK_SHOP","PHP","",false),"FEE","",false,false,13,ids(1,2,3,4,6,13,14,21,15,16,17,18,20)));
        c.add(c("date-pending-excluded",owner,MONTH,"FEE","order-rc-11",false,false,0,List.of()));
        c.add(c("import-fee-retained",owner,MONTH,"FEE","order-rc-13",false,false,1,ids(13)));
        c.add(c("credential-user-not-filtered",owner,MONTH,"FEE","order-rc-02",false,false,1,ids(2)));
        c.add(c("exact-order-number",owner,MONTH,"FEE","order-rc-21,order-rc-21",false,false,1,ids(21)));
        c.add(c("exact-external-number",owner,MONTH,"FEE","external-rc-21",false,false,1,ids(21)));
        c.add(c("exact-statement-number",owner,MONTH,"FEE","statement-rc-21",false,false,1,ids(21)));
        c.add(c("wildcard-is-exact",owner,MONTH,"FEE","order-rc-%",false,false,0,List.of()));
        c.add(c("bill-cross-statement-order-search",owner,MONTH,"BILL","order-rc-23",false,false,1,ids(22)));
        c.add(c("bill-ordinary-api-and-import",owner,MONTH,"BILL","",false,false,2,ids(22,25)));
        c.add(c("cost-ordinary-import",owner,MONTH,"COST","",false,false,1,ids(24)));
        c.add(c("partial-month-qualified-correction-only",owner,filter("","","","",true),"FEE","",false,false,15,ids(1,2,3,4,5,6,12,13,14,21,16,17,18,19,20)));
        c.add(c("partial-month-ad-qualified-correction",owner,filter("","","","",true),"FEE","",false,true,6,ids(1,2,3,4,5,14)));
        c.add(c("marker-only-without-reporting-row-retained",owner,filter("","","","",true),"FEE","order-rc-16",false,false,1,ids(16)));
        c.add(c("reporting-row-without-marker-retained",owner,filter("","","","",true),"FEE","order-rc-17",false,false,1,ids(17)));
        c.add(c("reporting-marker-import-retained",owner,filter("","","","",true),"FEE","order-rc-18",false,false,1,ids(18)));
        c.add(c("reporting-marker-other-platform-retained",owner,filter("","","","",true),"FEE","order-rc-19",false,false,1,ids(19)));
        c.add(c("reporting-marker-mismatched-statement-retained",owner,filter("","","","",true),"FEE","order-rc-20",false,false,1,ids(20)));
        c.add(c("order-all-nonlookup-created-date-api",owner,MONTH,"ORDER","",true,false,1,ids(28)));
        c.add(c("order-all-identifier-cross-date-settled-index",owner,MONTH,"ORDER","order-rc-26",true,false,1,ids(26)));
        c.add(c("settled-order-route-unchanged",owner,MONTH,"ORDER","",false,false,1,ids(26)));
        c.add(c("settled-refund-route-unchanged",owner,MONTH,"REFUND","",false,false,1,ids(30)));
        c.add(c("blank-kind-report-route-unchanged",owner,MONTH,null,"",false,false,23,ids(1,2,3,4,5,6,12,13,14,21,22,23,24,25,26,27,30,15,16,17,18,19,20)));
        c.add(c("blank-kind-cross-statement-search",owner,MONTH,null,"order-rc-23",false,false,2,ids(22,23)));
        return c;
    }
    private Case c(String name,Scope scope,Filter f,String kind,String numbers,boolean allOrders,boolean ads,long total,List<String> ids) {
        assertEquals(total,ids.size(),"Independent fixture literal count: "+name);return new Case(name,scope,f,kind,numbers,allOrders,ads,total,ids);
    }
    private Filter filter(String shop,String platform,String currency,String group,boolean partial) {
        return new Filter(MONTH.start(),partial?LocalDate.of(2026,10,21):MONTH.end(),shop,platform,currency,"day",group);
    }
    private void shops() {
        experimentDb.update("INSERT INTO finance_groups(id,organization_id,name) VALUES(?,'org','count A'),(?,'org','count B')",GA,GB);
        shop(A,"org","u","TIKTOK_SHOP","PHP",GA,"ACTIVE",true);shop(B,"org","colleague","SHOPEE","USD",GB,"ACTIVE",true);
        shop(U,"org","u","TIKTOK_SHOP","PHP",null,"ACTIVE",true);shop(D,"org","u","TIKTOK_SHOP","PHP",GA,"ACTIVE",false);
        shop(R,"org","u","TIKTOK_SHOP","PHP",GA,"REMOVED",true);shop(F,"other-org","other","TIKTOK_SHOP","PHP",null,"ACTIVE",true);
        shop(D2,"org","u","TIKTOK_SHOP","PHP",GA,"DISABLED",false);
    }
    private void shop(String id,String org,String user,String platform,String currency,String group,String status,boolean enabled) {
        ownedShops.add(id);
        experimentDb.update("""
            INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,status,enabled,currency,timezone,
              token_group_id,access_token_encrypted,scopes_json,group_id) VALUES(?,?,?,?,?,'count fixture',?,?,?,'UTC',?,'','[]',?)
            """,id,org,user,platform,id,status,enabled,currency,id,group);
    }
    private void smallLedger() {
        var out=new ArrayList<Object[]>();
        for(int n=1;n<=32;n++) {
            String shop=switch(n){case 3->B;case 4,24->U;case 5->"UNASSIGNED";case 6->D;case 7->R;case 8->"rci-missing";case 9,10->F;default->A;};
            String platform=Set.of(5,12,19).contains(n)?"SHOPEE":"TIKTOK_SHOP";
            String kind=switch(n){case 22,25->"BILL";case 23,27->"SETTLEMENT";case 24->"COST";case 26,28,29->"ORDER";case 30->"REFUND";default->"FEE";};
            String category=switch(n){case 1,15->"affiliate_ads_commission_amount";case 2->"tap_shop_ads_commission";
                case 3->"fees.affiliate_ads_commission_amount";case 4->"达人广告佣金";case 5->"fees.tap_shop_ads_commission";
                case 14->"  AFFILIATE_ADS_COMMISSION_AMOUNT  ";case 23,27->"ORDER";default->"PLATFORM_FEE";};
            String day=n>=15&&n<=20?"2026-10-01":switch(n){case 26->"2026-09-20";case 30->"2026-09-21";case 31->"2026-09-30";case 32->"2026-11-01";default->"2026-10-10";};
            String raw=Set.of(15,16,18,19,20).contains(n)?"{\"_finance_reporting_month\":\"2026-10\",\"_finance_original_settlement_date\":\"2025-01-01\"}":"{}";
            String external=n==22?"cross-bill":null,statement=n==22||n==23?"cross-bill":null,order=n==22?"":n==27||n==30?"order-rc-26":null;
            out.add(ledger("rc-"+String.format(Locale.ROOT,"%02d",n),n==10?"other-org":"org",n==2?"colleague":"u",shop,platform,kind,category,
                n==11?"DATE_PENDING":"SETTLED",day,Set.of(5,12).contains(n)?"USD":"PHP",Set.of(5,13,18,24,25,29).contains(n)?"IMPORT":"API",external,order,statement,raw));
        }
        insert(out);
        for(int n:List.of(15,17,18,19,20)){ownedMarkers.add(new Object[]{A,"order-rc-"+n,n==20?"wrong-statement":"statement-rc-"+n});experimentDb.update("""
            INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)
            VALUES('org',?,?,?,'2026-10-01','USER_CONFIRMED_MONTH','count fixture','u')
            """,A,"order-rc-"+n,n==20?"wrong-statement":"statement-rc-"+n);}
    }
    private Object[] ledger(String id,String org,String user,String shop,String platform,String kind,String category,String status,String day,String currency,String source,String external,String order,String statement,String raw) {
        return new Object[]{id,org,user,shop,platform,kind,external==null?"external-"+id:external,order==null?"order-"+id:order,
            statement==null?"statement-"+id:statement,category,status,java.sql.Date.valueOf(day),currency,source,raw};
    }
    private void insert(List<Object[]> values) {
        assertTrue(values.size()<=500);
        for(var value:values)ownedIds.add(String.valueOf(value[0]));
        experimentDb.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,
              record_date,currency,amount,revenue,impressions,clicks,conversions,source,raw_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,2,3,4,5,?,?)
            """,values);
    }

    private void beginFixture() {
        ownedIds.clear();ownedShops.clear();ownedMarkers.clear();experimentDb=new JdbcTemplate(dataSource);experimentDb.setQueryTimeout(30);
        ownedEventStart=Objects.requireNonNull(experimentDb.queryForObject("SELECT COALESCE(MAX(event_id),0) FROM finance_query_events",Long.class));
    }
    private void cleanup() {
        if(experimentDb==null)return;int deleted=0;
        for(int first=0;first<ownedIds.size();first+=500) {
            var batch=ownedIds.subList(first,Math.min(first+500,ownedIds.size()));
            deleted+=experimentDb.update("DELETE FROM finance_records WHERE id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",batch.toArray());
        }
        for(var marker:ownedMarkers)experimentDb.update("DELETE FROM finance_reporting_months WHERE organization_id='org' AND shop_id=? AND order_id=? AND statement_id=?",marker);
        for(String shop:ownedShops)experimentDb.update("DELETE FROM finance_connections WHERE id=?",shop);
        for(String group:List.of(GA,GB))experimentDb.update("DELETE FROM finance_groups WHERE id=?",group);
        long eventEnd=Objects.requireNonNull(experimentDb.queryForObject("SELECT COALESCE(MAX(event_id),0) FROM finance_query_events",Long.class));
        int events=experimentDb.update("DELETE FROM finance_query_events WHERE event_id>? AND event_id<=?",ownedEventStart,eventEnd);
        long remaining=0;
        for(int first=0;first<ownedIds.size();first+=500){var batch=ownedIds.subList(first,Math.min(first+500,ownedIds.size()));remaining+=number(experimentDb.queryForObject("SELECT COUNT(*) FROM finance_records WHERE id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",Long.class,batch.toArray()));}
        assertEquals(0,remaining);
        int connections=ownedShops.isEmpty()?0:Objects.requireNonNull(experimentDb.queryForObject("SELECT COUNT(*) FROM finance_connections WHERE id IN ("+String.join(",",Collections.nCopies(ownedShops.size(),"?"))+")",Integer.class,ownedShops.toArray()));
        assertEquals(0,connections);assertEquals(0,experimentDb.queryForObject("SELECT COUNT(*) FROM finance_groups WHERE id IN (?,?)",Integer.class,GA,GB));
        assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());
        emit("cleanup",Map.of("stage",fixtureStage,"declaredOwnedIds",ownedIds.size(),"deletedOwnedLedgerRows",deleted,"ownedRowsRemaining",remaining,
            "ownedEventStart",ownedEventStart,"ownedEventEnd",eventEnd,"deletedOwnedEvents",events,"ownedConnectionsRemaining",connections,"activePooledConnections",0));
    }
    private static String tail(int n){return "rcv-tail-"+String.format(Locale.ROOT,"%03d",n);}
    private static String rewrite(String sql) {
        if(!sql.startsWith(PREFIX))return sql;int position=sql.indexOf(ORIGINAL);
        if(position<0||sql.indexOf(ORIGINAL,position+ORIGINAL.length())>=0)return sql;
        return sql.substring(0,position)+CANDIDATE+sql.substring(position+ORIGINAL.length());
    }

    /** Strict native-shape transform; no general SQL rewrite is attempted. */
    private static Transformation transform(Mode mode,String sql,List<Object> args) {
        if(mode==Mode.CURRENT_EXISTS||!sql.startsWith(PREFIX))return new Transformation(sql,List.copyOf(args),-1);
        int position=sql.indexOf(ORIGINAL);
        if(position<0)return new Transformation(sql,List.copyOf(args),-1);
        if(sql.indexOf(ORIGINAL,position+ORIGINAL.length())>=0)throw new IllegalArgumentException("Ambiguous native visibility clauses");
        var shape=scan(sql,position);
        if(!shape.markerAtTopLevel()||shape.parameters().size()!=args.size())throw new IllegalArgumentException("Malformed native placeholder shape");
        var forbidden=Set.of("SELECT","GROUP","HAVING","ORDER","LIMIT","UNION","INTO","FOR","LOCK","PROCEDURE","WINDOW");
        if(shape.topLevelTailWords().stream().anyMatch(forbidden::contains))throw new IllegalArgumentException("Unsupported native count tail");
        int parameter=shape.parameters().indexOf(position+ORIGINAL.indexOf('?'));
        if(parameter<0||parameter>=args.size())throw new IllegalArgumentException("Visibility argument could not be located");
        if(mode==Mode.CANDIDATE_IN)return new Transformation(rewrite(sql),List.copyOf(args),parameter);
        String rowWhere=sql.substring(PREFIX.length(),position)+sql.substring(position+ORIGINAL.length());
        String grouped="SELECT COALESCE(SUM(grouped.records),0) FROM (SELECT finance_records.shop_id,COUNT(*) records FROM finance_records FORCE INDEX (idx_finance_company_kind_date) WHERE "
            +rowWhere+" GROUP BY finance_records.shop_id) grouped WHERE (grouped.shop_id='UNASSIGNED' OR EXISTS (SELECT 1 FROM finance_connections fc FORCE INDEX(PRIMARY) WHERE fc.id=grouped.shop_id AND fc.organization_id=? AND fc.status<>'REMOVED'))";
        var emitted=new ArrayList<>(args);Object visibilityCompany=emitted.remove(parameter);emitted.add(visibilityCompany);
        if(scan(grouped,-1).parameters().size()!=emitted.size())throw new IllegalArgumentException("Grouped emitted placeholders differ");
        return new Transformation(grouped,List.copyOf(emitted),parameter);
    }
    /** Lexer only identifies real placeholders and rejects comments, unterminated quotes and unsupported tails. */
    private static SqlScan scan(String sql,int marker) {
        var parameters=new ArrayList<Integer>();var tailWords=new ArrayList<String>();char quote=0;int depth=0;boolean markerAtTop=false;
        for(int i=0;i<sql.length();i++) {
            char c=sql.charAt(i);
            if(quote!=0) {
                if(c=='\\'){if(++i>=sql.length())throw new IllegalArgumentException("Unterminated SQL escape");}
                else if(c==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote)i++;else quote=0;}
                continue;
            }
            if(i==marker)markerAtTop=depth==0;
            if(c=='\''||c=='"'||c=='`'){quote=c;continue;}
            if(c==';'||c=='#'||c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'||c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-')
                throw new IllegalArgumentException("SQL comments or multiple statements are unsupported");
            if(c=='('){depth++;continue;}if(c==')'){if(--depth<0)throw new IllegalArgumentException("Unbalanced SQL parentheses");continue;}
            if(c=='?'){parameters.add(i);continue;}
            if(depth==0&&i>=PREFIX.length()&&(Character.isLetter(c)||c=='_')) {
                int first=i;while(i+1<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i+1))||sql.charAt(i+1)=='_'))i++;
                tailWords.add(sql.substring(first,i+1).toUpperCase(Locale.ROOT));
            }
        }
        if(quote!=0||depth!=0)throw new IllegalArgumentException("Unterminated SQL quoting or parentheses");
        return new SqlScan(List.copyOf(parameters),markerAtTop,List.copyOf(tailWords));
    }
    private static void verifyGroupedGuard(String actual,List<Object> actualArgs) {
        var grouped=transform(Mode.GROUPED_EXISTS,actual,actualArgs);assertNotEquals(actual,grouped.sql());
        assertTrue(grouped.sql().startsWith("SELECT COALESCE(SUM(grouped.records),0) FROM ("));
        String fixture=PREFIX+" organization_id=? AND shop_id=? "+ORIGINAL
            +" AND shop_id IN (SELECT fg.id FROM finance_connections fg WHERE fg.organization_id=? AND fg.group_id=?) AND kind=?";
        var tags=List.<Object>of("record-org","record-shop","visibility-org","group-org","group-id","FEE");
        var expected=List.<Object>of("record-org","record-shop","group-org","group-id","FEE","visibility-org");
        var transformed=transform(Mode.GROUPED_EXISTS,fixture,tags);assertEquals(2,transformed.visibilityArgumentIndex());assertEquals(expected,transformed.args());
        assertTrue(transformed.sql().contains("AND shop_id IN (SELECT fg.id FROM finance_connections fg WHERE fg.organization_id=? AND fg.group_id=?) AND kind=? GROUP BY"));
        String quoted=PREFIX+" organization_id=? "+ORIGINAL+" AND category='literal?identifier' AND kind=?";
        assertEquals(List.of("record-org","FEE","visibility-org"),transform(Mode.GROUPED_EXISTS,quoted,List.of("record-org","visibility-org","FEE")).args());
        for(var mode:List.of(Mode.CANDIDATE_IN,Mode.GROUPED_EXISTS)) {
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual,actualArgs.subList(0,actualArgs.size()-1)));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual+" "+ORIGINAL,actualArgs));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual+" AND category='unterminated",actualArgs));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual+" GROUP BY shop_id",actualArgs));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual+"; SELECT 1",actualArgs));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual+" /* unsupported */",actualArgs));
            assertThrows(IllegalArgumentException.class,()->transform(mode,actual.replace(ORIGINAL,"("+ORIGINAL+")"),actualArgs));
        }
    }
    private static List<Map<String,String>> bindingReceipt(List<Object> values) {
        return values.stream().map(v->Map.of("jdbcType",v.getClass().getName(),"value",v instanceof java.sql.Date day?day.toLocalDate().toString():String.valueOf(v))).toList();
    }
    private static List<String> ids(int...numbers){return Arrays.stream(numbers).mapToObj(n->"rc-"+String.format(Locale.ROOT,"%02d",n)).toList();}
    private static List<String> itemIds(Map<String,Object> page){return rows(page.get("items")).stream().map(r->text(r.get("id"))).toList();}
    private static String idHash(Map<String,Object> page){return hash(String.join("\n",itemIds(page)));}
    private static double millis(long start){return (System.nanoTime()-start)/1_000_000.0;}
    private static void emit(String event,Object data){System.out.println("RECORD_COUNT_VISIBILITY "+JSON.writeValueAsString(Map.of("event",event,"data",data)));}
    private static List<Map<String,Object>> tables(Object v) {
        var out=new ArrayList<Map<String,Object>>();if(v instanceof Map<?,?> raw){var m=map(raw);if(m.containsKey("table_name"))out.add(m);for(var child:raw.values())out.addAll(tables(child));}
        else if(v instanceof List<?> list)for(var child:list)out.addAll(tables(child));return out;
    }
    private static List<Object> flags(Object v,String name) {
        var out=new ArrayList<Object>();if(v instanceof Map<?,?> raw){if(raw.containsKey(name))out.add(raw.get(name));for(var child:raw.values())out.addAll(flags(child,name));}
        else if(v instanceof List<?> list)for(var child:list)out.addAll(flags(child,name));return out;
    }
    enum Mode {CURRENT_EXISTS,CANDIDATE_IN,GROUPED_EXISTS}
    enum Profile {FIXED("fixed-skewed"),INTERLEAVED("interleaved-id");final String label;Profile(String label){this.label=label;}}
    record Case(String name,Scope scope,Filter filter,String kind,String numbers,boolean allOrders,boolean ads,long total,List<String> ids){}
    record Count(String original,String emitted,List<Object> args,List<Object> emittedArgs,int visibilityArgumentIndex,long total,double millis,boolean rewritten){}
    record Transformation(String sql,List<Object> args,int visibilityArgumentIndex){}
    record SqlScan(List<Integer> parameters,boolean markerAtTopLevel,List<String> topLevelTailWords){}
    record EmittedShape(Mode mode,String sql,List<Object> args){}
    record Page(String sql,List<Object> args){}
    record Probe(CountJdbc jdbc,FinanceRepository repo){}
    record Observation(Map<String,Object> page,List<Count> counts,List<Page> pages){}
    record Sample(Mode mode,double countMs,double detailsMs){}
    static final class CountJdbc extends JdbcTemplate {
        Mode mode;Witness witness;final List<Count> counts=new ArrayList<>();final List<Page> pages=new ArrayList<>();
        final Set<EmittedShape> shapes=new HashSet<>();
        CountJdbc(JdbcTemplate original,Mode mode){super(Objects.requireNonNull(original.getDataSource()));this.mode=mode;setQueryTimeout(10);}
        void clear(){counts.clear();pages.clear();}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args) {
            boolean count=(sql.startsWith("SELECT COUNT(*)")||sql.startsWith("SELECT (SELECT COUNT(*)"))&&sql.contains("finance_records");
            if(!count)return super.queryForObject(sql,type,args);
            witness.verify();var originalArgs=List.copyOf(Arrays.asList(args.clone()));var transformed=transform(mode,sql,originalArgs);String emitted=transformed.sql();
            if(shapes.add(new EmittedShape(mode,sql,originalArgs)))emit("countShape",Map.of("mode",mode,"originalSql",sql,"emittedSql",emitted,
                "originalBindings",bindingReceipt(originalArgs),"emittedBindings",bindingReceipt(transformed.args()),"visibilityArgumentIndex",transformed.visibilityArgumentIndex()));
            long start=System.nanoTime();T value;
            try {value=super.queryForObject(emitted,type,transformed.args().toArray());} catch(RuntimeException failure) {
                emit("countExecutionFailure",Map.of("mode",mode,"rewritten",!sql.equals(emitted),"failureClass",failure.getClass().getName(),"elapsedMs",millis(start)));throw failure;
            }
            double elapsed=millis(start);witness.verify();
            counts.add(new Count(sql,emitted,originalArgs,transformed.args(),transformed.visibilityArgumentIndex(),number(value),elapsed,!sql.equals(emitted)));return value;
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args) {
            if(sql.contains(" LIMIT ? OFFSET ?)")){witness.verify();pages.add(new Page(sql,List.copyOf(Arrays.asList(args.clone()))));}
            return super.queryForList(sql,args);
        }
    }
    static final class Witness {
        final DataSource source;final ConnectionHolder holder;final Connection connection;final Object physical;final long deadline,connectionId;
        int checks;
        Witness(DataSource source) {
            this.source=source;holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);assertNotNull(holder);
            connection=holder.getConnection();physical=physical(connection);assertTrue(holder.hasTimeout());deadline=holder.getDeadline().getTime();
            try(var statement=connection.createStatement()){statement.setQueryTimeout(5);try(var rs=statement.executeQuery("SELECT CONNECTION_ID()")){assertTrue(rs.next());connectionId=rs.getLong(1);}}
            catch(SQLException e){throw new AssertionError(e);}verify();
        }
        void verify() {
            checks++;assertSame(holder,TransactionSynchronizationManager.getResource(source));assertSame(connection,holder.getConnection());assertSame(physical,physical(connection));
            assertTrue(holder.hasTimeout());assertEquals(deadline,holder.getDeadline().getTime());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            try{assertTrue(connection.isReadOnly());assertFalse(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
            catch(SQLException e){throw new AssertionError(e);}
        }
        private static Object physical(Connection c){try{return c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);}catch(SQLException e){throw new AssertionError(e);}}
        Map<String,Object> receipt(){verify();return Map.of("connectionId",connectionId,"holderIdentity",System.identityHashCode(holder),"physicalIdentity",System.identityHashCode(physical),
            "deadlineMillis",deadline,"readOnlyRepeatableRead",true,"checks",checks,"sharedDataSource",true);}
    }
}
