package com.yingdao.aicommerce.finance;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opentest4j.AssertionFailedError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable comparison of existing count indexes; deliberately adds no DDL or application routing. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceRecordCountCoveringIndexIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final String WIDE="FORCE INDEX (idx_finance_company_kind_date)",NARROW="FORCE INDEX (idx_finance_page)";
    static final String A="rci-shop-a",B="rci-shop-b",U="rci-shop-u",D="rci-shop-disabled",R="rci-shop-removed",F="rci-shop-foreign";
    static final String GA="rci-group-a",GB="rci-group-b";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Filter MONTH=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day","");
    long ownedEventStart;

    /** Literal IDs catch authorization/filter drift; both real counts share a physical RO/RR snapshot. */
    @Test void realDetailsRetainLiteralVisibilitySearchReportingAndCompatibilityOracles() {
        try {
            markOwnedEvents();
            shops();smallLedger();
            var cases=cases();
            repo.readSnapshot(180,()->{
                var witness=new Witness(dataSource);
                var probes=probes(witness);
                int executed=0,inferred=0;
                for(var c:cases) {
                    for(int page:List.of(1,2,100)) {
                        var observations=new EnumMap<Mode,Observation>(Mode.class);
                        for(var mode:Mode.values()) {
                            var probe=probes.get(mode);probe.jdbc().clear();
                            var actual=read(probe.repo(),c,page,5);
                            oracle(c,page,5,actual);
                            int count=probe.jdbc().counts.size();
                            assertTrue(count<=1,"Unexpected details count multiplicity: "+c.name());
                            if(page==100)assertEquals(1,count,"An empty later page must force a real COUNT, including a zero result");
                            if(count==0)inferred++;else executed++;
                            observations.put(mode,new Observation(actual,List.copyOf(probe.jdbc().counts),List.copyOf(probe.jdbc().pages)));
                        }
                        var old=observations.get(Mode.CURRENT_WIDE);
                        for(var mode:List.of(Mode.FORCED_NARROW,Mode.NATURAL)) {
                            var candidate=observations.get(mode);
                            assertEquals(old.page(),candidate.page(),c.name()+": complete returned page differs");
                            assertEquals(old.pages(),candidate.pages(),c.name()+": page SQL or bindings changed");
                            assertCountShape(old.counts(),candidate.counts(),mode);
                        }
                        emit("semantic",Map.of("case",c.name(),"page",page,"literalTotal",c.total(),"pageIds",itemIds(old.page()),
                            "countsActuallyExecuted",old.counts().size(),"terminalInference",old.counts().isEmpty(),
                            "typedHintEligible",!old.counts().isEmpty()&&old.counts().getFirst().original().contains(WIDE)));
                    }
                }
                witness.verify();
                emit("semanticSummary",Map.of("cases",cases.size(),"modes",3,"pagesPerCase",3,"actualCountStatements",executed,
                    "inferredCalls",inferred,"snapshot",witness.receipt()));
                var control=cases.getFirst();
                var real=read(probes.get(Mode.CURRENT_WIDE).repo(),control,1,5);
                var wrongTotal=new LinkedHashMap<>(real);wrongTotal.put("total",number(real.get("total"))+1);
                assertThrows(AssertionFailedError.class,()->oracle(control,1,5,wrongTotal));
                var wrongPage=new LinkedHashMap<>(real);wrongPage.put("items",rows(real.get("items")).subList(1,5));
                assertThrows(AssertionFailedError.class,()->oracle(control,1,5,wrongPage));
                emit("oracleNegativeControl",Map.of("wrongTotalDetected",true,"wrongPageDetected",true,"injectedAuthoritativeTotal",false));
                return null;
            });
            // This control intentionally demonstrates why reusing one repo's snapshot cache would hide the candidate.
            var cachedJdbc=new CountJdbc(db,Mode.CURRENT_WIDE);var cachedRepo=new FinanceRepository(cachedJdbc,new ObjectMapper());
            cachedRepo.readSnapshot(60,()->{
                cachedJdbc.witness=new Witness(dataSource);
                var c=cases.getFirst();var first=read(cachedRepo,c,100,5);
                assertEquals(1,cachedJdbc.counts.size());
                cachedJdbc.mode=Mode.FORCED_NARROW;
                var second=read(cachedRepo,c,100,5);
                assertEquals(first,second);assertEquals(1,cachedJdbc.counts.size(),"The same-repo cache must hide the second COUNT in this negative control");
                assertTrue(cachedJdbc.counts.getFirst().emitted().contains(WIDE));
                emit("cacheNegativeControl",Map.of("currentCountExecuted",1,"candidateCountsExecuted",0,"cacheWouldHideCandidate",true,
                    "snapshot",cachedJdbc.witness.receipt()));return null;
            });
        } finally {cleanup();}
    }

    /** No latency/covering threshold: a negative or inconclusive index hypothesis is a valid measurement. */
    @Test void boundedTypedFeeAndAdvertisingCountsMeasureExistingWideNarrowAndNaturalPaths() {
        try {
            markOwnedEvents();
            shops();long seed=System.nanoTime();
            for(int first=1;first<=100_000;first+=500) {
                var batch=new ArrayList<Object[]>();
                for(int n=first;n<first+500;n++) {
                    int k=n%10;String shop=switch(k){case 0,1,9->A;case 2->B;case 3->U;case 4->"UNASSIGNED";case 5->D;case 6->R;case 7->"rci-missing";default->F;};
                    String category=switch(k){case 0,6,7,8,9->"affiliate_ads_commission_amount";case 2->"tap_shop_ads_commission";
                        case 3->"fee.affiliate_ads_commission_amount";case 4->"fee.tap_shop_ads_commission";default->"PLATFORM_FEE";};
                    batch.add(ledger("rci-h-"+String.format(Locale.ROOT,"%06d",n),k==8?"other-org":"org",k==1?"colleague":"u",shop,
                        k==4?"SHOPEE":"TIKTOK_SHOP","FEE",category,k==9?"DATE_PENDING":"SETTLED","2026-10-10",k==4?"USD":"PHP",
                        k==2||k==4?"IMPORT":"API",null,null,null,"{\"padding\":\""+"p".repeat(480)+"\"}"));
                }
                insert(batch);
            }
            assertEquals(100_000,db.queryForObject("SELECT COUNT(*) FROM finance_records",Long.class));
            db.execute("ANALYZE TABLE finance_records,finance_connections");
            var indexes=db.queryForList("""
                SELECT INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,INDEX_TYPE,IS_VISIBLE FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='finance_records'
                  AND INDEX_NAME IN ('idx_finance_company_kind_date','idx_finance_page') ORDER BY INDEX_NAME,SEQ_IN_INDEX
                """);
            var wide=indexes.stream().filter(r->"idx_finance_company_kind_date".equals(r.get("INDEX_NAME"))).map(r->text(r.get("COLUMN_NAME"))).toList();
            var narrow=indexes.stream().filter(r->"idx_finance_page".equals(r.get("INDEX_NAME"))).map(r->text(r.get("COLUMN_NAME"))).toList();
            assertEquals(16,wide.size());assertEquals(11,narrow.size());assertEquals(wide.subList(0,11),narrow);
            emit("profile",Map.of("actualRecords",100_000,"maximumInsertBatch",500,"rawJsonCharacters",494,"seedMs",millis(seed),
                "serverVersion",Objects.requireNonNull(db.queryForObject("SELECT VERSION()",String.class)),
                "innodbBufferPoolBytes",Objects.requireNonNull(db.queryForObject("SELECT @@innodb_buffer_pool_size",Long.class)),
                "tableMetadata",db.queryForMap("SELECT TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='finance_records'"),
                "runtimeIndexes",indexes,"newIndexOrDdl",false));
            repo.readSnapshot(180,()->{
                var witness=new Witness(dataSource);var probes=probes(witness);
                for(boolean ads:List.of(false,true)) {
                    var c=new Case(ads?"heavy-ad-only-fee":"heavy-ordinary-fee",owner,MONTH,"FEE","",false,ads,ads?40_000:60_000,List.of());
                    var old=probes.get(Mode.CURRENT_WIDE);old.jdbc().clear();
                    var reference=read(old.repo(),c,1,50);heavyOracle(c,reference);
                    assertEquals(1,old.jdbc().counts.size());
                    var referenceQuery=old.jdbc().counts.getFirst();
                    assertTrue(referenceQuery.original().contains(WIDE));
                    var originalPages=List.copyOf(old.jdbc().pages);
                    for(var mode:Mode.values()) {
                        var probe=probes.get(mode);probe.jdbc().clear();
                        var page=read(probe.repo(),c,1,50);
                        assertEquals(reference,page);assertEquals(originalPages,probe.jdbc().pages);
                        assertEquals(1,probe.jdbc().counts.size(),"Each measured route must execute a real COUNT");
                        assertCountShape(List.of(referenceQuery),probe.jdbc().counts,mode);
                        explain(c.name()+"-"+mode,probe.jdbc().counts.getFirst(),witness);
                    }
                    for(var mode:Mode.values())timed(probes.get(mode),c,reference,"warmup",0);
                    for(int round=1;round<=2;round++) {
                        var modes=round==1?List.of(Mode.CURRENT_WIDE,Mode.FORCED_NARROW,Mode.NATURAL):List.of(Mode.NATURAL,Mode.FORCED_NARROW,Mode.CURRENT_WIDE);
                        for(var mode:modes)timed(probes.get(mode),c,reference,"reverse-paired-hot",round);
                    }
                    emit("equivalence",Map.of("case",c.name(),"literalTotal",c.total(),"literalFirst50Hash",idHash(reference),
                        "originalCountSql",referenceQuery.original(),"originalBindings",bindingReceipt(referenceQuery.args()),
                        "originalBindingTypes",referenceQuery.args().stream().map(a->a.getClass().getSimpleName()).toList(),
                        "completePagesEqual",true,"snapshot",witness.receipt()));
                }
                witness.verify();return null;
            });
        } finally {cleanup();}
    }

    private EnumMap<Mode,Probe> probes(Witness witness) {
        var out=new EnumMap<Mode,Probe>(Mode.class);
        for(var mode:Mode.values()){var jdbc=new CountJdbc(db,mode);jdbc.witness=witness;out.put(mode,new Probe(jdbc,new FinanceRepository(jdbc,new ObjectMapper())));}
        assertSame(out.get(Mode.CURRENT_WIDE).jdbc().getDataSource(),out.get(Mode.FORCED_NARROW).jdbc().getDataSource());return out;
    }
    private Map<String,Object> read(FinanceRepository selected,Case c,int page,int size) {
        return c.ads()?selected.advertisingFeeDetails(c.scope(),c.filter(),page,size,c.numbers()):selected.details(c.scope(),c.filter(),c.kind(),page,size,c.numbers(),c.allOrders());
    }
    private void oracle(Case c,int page,int size,Map<String,Object> actual) {
        assertEquals(c.total(),number(actual.get("total")),c.name()+": independent literal total");
        int offset=(page-1)*size;
        assertEquals(c.ids().subList(Math.min(offset,c.ids().size()),Math.min(offset+size,c.ids().size())),itemIds(actual),c.name()+": independent ordered literal IDs");
        assertEquals(page,number(actual.get("page")));assertEquals(size,number(actual.get("pageSize")));
    }
    private void heavyOracle(Case c,Map<String,Object> actual) {
        assertEquals(c.total(),number(actual.get("total")));
        // Explicit residues are independent of the seeding switch and of repository predicates.
        var expected=new ArrayList<String>();int[] residues=c.ads()?new int[]{0,2,3,4}:new int[]{0,1,2,3,4,5};
        for(int n=1;expected.size()<50;n++)for(int residue:residues)if(n%10==residue){expected.add("rci-h-"+String.format(Locale.ROOT,"%06d",n));break;}
        assertEquals(expected,itemIds(actual));
    }
    private void assertCountShape(List<Count> original,List<Count> candidate,Mode mode) {
        assertEquals(original.size(),candidate.size());
        for(int n=0;n<original.size();n++) {
            var old=original.get(n);var now=candidate.get(n);assertEquals(old.original(),now.original());assertEquals(old.args(),now.args());
            String expected=mode==Mode.CURRENT_WIDE?old.original():old.original().replace(WIDE,mode==Mode.FORCED_NARROW?NARROW:"");
            assertEquals(expected,now.emitted(),"Only the exact existing typed count hint may change");
            if(old.original().contains(WIDE)&&mode!=Mode.CURRENT_WIDE)assertNotEquals(old.emitted(),now.emitted());
        }
    }
    private void timed(Probe probe,Case c,Map<String,Object> expected,String pair,int round) {
        probe.jdbc().clear();long start=System.nanoTime();var page=read(probe.repo(),c,1,50);double elapsed=millis(start);
        assertEquals(expected,page);assertEquals(1,probe.jdbc().counts.size());
        var count=probe.jdbc().counts.getFirst();assertEquals(c.total(),count.total());
        emit("hot",Map.of("case",c.name(),"pair",pair,"round",round,"mode",probe.jdbc().mode,"countMs",count.millis(),
            "wholeDetailsMs",elapsed,"actualCountStatements",1,"total",count.total(),"pageIdHash",idHash(page)));
    }
    @SuppressWarnings("unchecked") private void explain(String label,Count q,Witness witness) {
        witness.verify();var json=JSON.readValue(db.queryForObject("EXPLAIN FORMAT=JSON "+q.emitted(),String.class,q.args().toArray()),Map.class);
        var actual=db.queryForList("EXPLAIN ANALYZE "+q.emitted(),q.args().toArray());
        String tree=actual.stream().flatMap(r->r.values().stream()).map(String::valueOf).reduce("",(a,b)->a+b+"\n");witness.verify();
        emit("plan",Map.of("label",label,"sql",q.emitted(),"bindings",bindingReceipt(q.args()),"tables",tables(json),
            "sortFlags",flags(json,"using_filesort"),"temporaryFlags",flags(json,"using_temporary_table"),
            "explainJson",json,"explainAnalyze",tree,"statementRowsExamined","UNAVAILABLE_NOT_REQUESTED"));
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
        db.update("INSERT INTO finance_groups(id,organization_id,name) VALUES(?,'org','count A'),(?,'org','count B')",GA,GB);
        shop(A,"org","u","TIKTOK_SHOP","PHP",GA,"ACTIVE",true);shop(B,"org","colleague","SHOPEE","USD",GB,"ACTIVE",true);
        shop(U,"org","u","TIKTOK_SHOP","PHP",null,"ACTIVE",true);shop(D,"org","u","TIKTOK_SHOP","PHP",GA,"ACTIVE",false);
        shop(R,"org","u","TIKTOK_SHOP","PHP",GA,"REMOVED",true);shop(F,"other-org","other","TIKTOK_SHOP","PHP",null,"ACTIVE",true);
    }
    private void shop(String id,String org,String user,String platform,String currency,String group,String status,boolean enabled) {
        db.update("""
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
        for(int n:List.of(15,17,18,19,20))db.update("""
            INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)
            VALUES('org',?,?,?,'2026-10-01','USER_CONFIRMED_MONTH','count fixture','u')
            """,A,"order-rc-"+n,n==20?"wrong-statement":"statement-rc-"+n);
    }
    private Object[] ledger(String id,String org,String user,String shop,String platform,String kind,String category,String status,String day,String currency,String source,String external,String order,String statement,String raw) {
        return new Object[]{id,org,user,shop,platform,kind,external==null?"external-"+id:external,order==null?"order-"+id:order,
            statement==null?"statement-"+id:statement,category,status,java.sql.Date.valueOf(day),currency,source,raw};
    }
    private void insert(List<Object[]> values) {
        assertTrue(values.size()<=500);
        db.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,
              record_date,currency,amount,revenue,impressions,clicks,conversions,source,raw_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,2,3,4,5,?,?)
            """,values);
    }
    private void markOwnedEvents(){ownedEventStart=Objects.requireNonNull(db.queryForObject("SELECT COALESCE(MAX(event_id),0) FROM finance_query_events",Long.class));}
    private void cleanup() {
        int deleted=db.update("DELETE FROM finance_records WHERE id LIKE 'rci-h-%' OR id LIKE 'rc-%'");
        db.update("DELETE FROM finance_reporting_months WHERE shop_id IN (?,?)",A,B);
        for(String shop:List.of(A,B,U,D,R,F))db.update("DELETE FROM finance_connections WHERE id=?",shop);
        for(String group:List.of(GA,GB))db.update("DELETE FROM finance_groups WHERE id=?",group);
        // This declared experiment is the only writer in its runner-owned schema. The fence also owns
        // compact group/connection trigger events whose payload intentionally contains no object ID.
        int events=db.update("DELETE FROM finance_query_events WHERE event_id>?",ownedEventStart);
        long remaining=number(db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE id LIKE 'rci-h-%' OR id LIKE 'rc-%'",Long.class));
        assertEquals(0,remaining);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_connections WHERE id IN (?,?,?,?,?,?)",Integer.class,A,B,U,D,R,F));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_groups WHERE id IN (?,?)",Integer.class,GA,GB));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_reporting_months WHERE shop_id IN (?,?)",Integer.class,A,B));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_query_events WHERE event_id>?",Integer.class,ownedEventStart));
        int parts=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='finance_records' AND INDEX_NAME IN ('idx_finance_company_kind_date','idx_finance_page')",Integer.class));
        assertEquals(27,parts);assertEquals(0,dataSource.getHikariPoolMXBean().getActiveConnections());
        emit("cleanup",Map.of("deletedOwnedLedgerRows",deleted,"ownedLedgerRowsRemaining",remaining,"deletedOwnedTriggerEvents",events,
            "ownedEventFence",ownedEventStart,"existingIndexPartsAfter",parts,"activePooledConnections",0));
    }
    private static List<Map<String,String>> bindingReceipt(List<Object> values) {
        return values.stream().map(v->Map.of("jdbcType",v.getClass().getName(),"value",v instanceof java.sql.Date day?day.toLocalDate().toString():String.valueOf(v))).toList();
    }
    private static List<String> ids(int...numbers){return Arrays.stream(numbers).mapToObj(n->"rc-"+String.format(Locale.ROOT,"%02d",n)).toList();}
    private static List<String> itemIds(Map<String,Object> page){return rows(page.get("items")).stream().map(r->text(r.get("id"))).toList();}
    private static String idHash(Map<String,Object> page){return hash(String.join("\n",itemIds(page)));}
    private static double millis(long start){return (System.nanoTime()-start)/1_000_000.0;}
    private static void emit(String event,Object data){System.out.println("RECORD_COUNT_INDEX "+JSON.writeValueAsString(Map.of("event",event,"data",data)));}
    private static List<Map<String,Object>> tables(Object v) {
        var out=new ArrayList<Map<String,Object>>();
        if(v instanceof Map<?,?> raw){var m=map(raw);if(m.containsKey("table_name"))out.add(m);for(var child:raw.values())out.addAll(tables(child));}
        else if(v instanceof List<?> list)for(var child:list)out.addAll(tables(child));return out;
    }
    private static List<Object> flags(Object v,String name) {
        var out=new ArrayList<Object>();if(v instanceof Map<?,?> raw){if(raw.containsKey(name))out.add(raw.get(name));for(var child:raw.values())out.addAll(flags(child,name));}
        else if(v instanceof List<?> list)for(var child:list)out.addAll(flags(child,name));return out;
    }
    enum Mode {CURRENT_WIDE,FORCED_NARROW,NATURAL}
    record Case(String name,Scope scope,Filter filter,String kind,String numbers,boolean allOrders,boolean ads,long total,List<String> ids){}
    record Count(String original,String emitted,List<Object> args,long total,double millis){}
    record Page(String sql,List<Object> args){}
    record Probe(CountJdbc jdbc,FinanceRepository repo){}
    record Observation(Map<String,Object> page,List<Count> counts,List<Page> pages){}
    static final class CountJdbc extends JdbcTemplate {
        Mode mode;Witness witness;final List<Count> counts=new ArrayList<>();final List<Page> pages=new ArrayList<>();
        CountJdbc(JdbcTemplate original,Mode mode){super(Objects.requireNonNull(original.getDataSource()));this.mode=mode;setQueryTimeout(60);}
        void clear(){counts.clear();pages.clear();}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args) {
            boolean count=(sql.startsWith("SELECT COUNT(*)")||sql.startsWith("SELECT (SELECT COUNT(*)"))&&sql.contains("finance_records");
            if(!count)return super.queryForObject(sql,type,args);
            witness.verify();String emitted=sql.replace(WIDE,mode==Mode.CURRENT_WIDE?WIDE:mode==Mode.FORCED_NARROW?NARROW:"");
            long start=System.nanoTime();T value=super.queryForObject(emitted,type,args);double elapsed=millis(start);witness.verify();
            counts.add(new Count(sql,emitted,List.copyOf(Arrays.asList(args.clone())),number(value),elapsed));return value;
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
            try(var statement=connection.createStatement();var rs=statement.executeQuery("SELECT CONNECTION_ID()")){assertTrue(rs.next());connectionId=rs.getLong(1);}
            catch(SQLException e){throw new AssertionError(e);}verify();
        }
        void verify() {
            checks++;assertSame(holder,TransactionSynchronizationManager.getResource(source));assertSame(connection,holder.getConnection());
            assertSame(physical,physical(connection));assertTrue(holder.hasTimeout());assertEquals(deadline,holder.getDeadline().getTime());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            try{assertTrue(connection.isReadOnly());assertFalse(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());}
            catch(SQLException e){throw new AssertionError(e);}
        }
        private static Object physical(Connection c){try{return c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);}catch(SQLException e){throw new AssertionError(e);}}
        Map<String,Object> receipt(){verify();return Map.of("connectionId",connectionId,"holderIdentity",System.identityHashCode(holder),"physicalIdentity",System.identityHashCode(physical),
            "deadlineMillis",deadline,"readOnlyRepeatableRead",true,"checks",checks,"sharedDataSource",true);}
    }
}
