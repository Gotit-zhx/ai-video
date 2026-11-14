package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Throwaway index-decision probe, not a production migration or source-query routing change.
 * Heavy data is seeded only in the declared experiment; the 63 inherited cases keep their normal fixtures.
 */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceLabelSourceCoveringIndexIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final String INDEX="idx_lab_source_cover_proto";
    static final String TABLE="finance_label_invoice_rows";
    static final Filter ALL=new Filter(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"","","","day","");
    static final String SHOP_A="cover-shop-a",SHOP_B="cover-shop-b",SHOP_U="cover-shop-u",SHOP_REMOVED="cover-shop-removed",SHOP_FOREIGN="cover-shop-foreign";
    static final String GROUP_A="cover-group-a",GROUP_B="cover-group-b";
    static final String NEW="cover-sem-new",MIDDLE="cover-sem-middle",OLD="cover-sem-old";
    static final List<String> HEAVY=List.of("cover-heavy-new","cover-heavy-mid","cover-heavy-old");
    static final ObjectMapper JSON=new ObjectMapper();

    /** Catches narrowed file authorization, changed filters, wrong page order, and unsupported candidate routing. */
    @Test void prototypePreservesLiteralSourceOraclesAndUnsupportedSearchFallbacks() {
        var files=List.of(NEW,MIDDLE,OLD,"cover-sem-foreign","cover-sem-removed","cover-sem-expired");
        boolean installed=false;
        try {
            shops();
            addImport(NEW,"org","READY","2026-10-03 10:00:00");
            addImport(MIDDLE,"org","PREPARED","2026-10-02 10:00:00");
            addImport(OLD,"org","REMOVE_QUEUED","2026-10-01 10:00:00");
            addImport(files.get(3),"other-org","READY","2026-10-06 10:00:00");
            addImport(files.get(4),"org","REMOVED","2026-10-05 10:00:00");
            addImport(files.get(5),"org","EXPIRED","2026-10-04 10:00:00");
            addRows(List.of(
                row(NEW,1,"org","alpha-a",SHOP_A,"MATCHED",false),
                row(NEW,2,"org","alpha-b",SHOP_B,"REVIEW",false),
                row(NEW,3,"org","alpha-u",SHOP_U,"MATCHED",false),
                row(NEW,4,"org","alpha-null",null,"UNMATCHED",false),
                row(NEW,5,"org","alpha-removed",SHOP_REMOVED,"MATCHED",false),
                row(NEW,6,"org","alpha-conflict",SHOP_A,"MATCHED",false),
                row(NEW,7,"legacy-row-org","alpha-legacy",SHOP_A,"REVIEW",false),
                row(NEW,8,"org","alpha-retired",SHOP_A,"BILLING_FAILED",false),
                row(NEW,9,"other-org","alpha-foreign-shop",SHOP_FOREIGN,"MATCHED",false),
                row(NEW,10,"org","alpha-inactive-conflict",SHOP_A,"MATCHED",false),
                row(OLD,1,"org","old-review",SHOP_B,"REVIEW",false),
                row(OLD,2,"org","old-null",null,"UNMATCHED",false),
                row(OLD,3,"org","old-removed",SHOP_REMOVED,"MATCHED",false),
                row(files.get(3),1,"org","foreign-file-row",SHOP_A,"MATCHED",false),
                row(files.get(4),1,"org","removed-file-row",SHOP_A,"MATCHED",false),
                row(files.get(5),1,"org","expired-file-row",SHOP_A,"MATCHED",false)));
            var middle=new ArrayList<Object[]>();
            for(int n=1;n<=60;n++)middle.add(row(MIDDLE,n,"org","middle-"+String.format(Locale.ROOT,"%03d",n),SHOP_A,"MATCHED",false));
            addRows(middle);
            db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES('org','cover-active')");
            db.update("""
                INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
                VALUES('org','cover-active',?,'alpha-conflict',1,1,1),
                  ('org','cover-inactive',?,'alpha-inactive-conflict',1,1,1)
                """,SHOP_A,SHOP_A);
            db.execute("ANALYZE TABLE "+TABLE);
            var cases=semanticCases();
            var baseline=new LinkedHashMap<String,Observation>();
            for(var c:cases)baseline.put(c.name(),observe(new ProbeJdbc(db,Mode.UNCHANGED),c));
            install();installed=true;
            for(var c:cases) {
                var jdbc=new ProbeJdbc(db,Mode.FORCE_CANDIDATE);
                var candidate=observe(jdbc,c);
                assertEquals(baseline.get(c.name()).page(),candidate.page(),c.name()+": full source page differs");
                assertEquals(baseline.get(c.name()).sourceHash(),candidate.sourceHash(),c.name()+": source ID hash differs");
                for(var count:jdbc.counts) {
                    if(eligible(count.original()))assertTrue(count.emitted().contains("FORCE INDEX("+INDEX+")"));
                    else assertEquals(count.original(),count.emitted(),"Unsupported predicates retain their original count SQL");
                }
                emit("semantics",Map.of("case",c.name(),"literalTotal",c.total(),"pageRows",rows(candidate.page().get("rows")).size(),
                    "sourceIdHash",candidate.sourceHash(),"countQueries",jdbc.counts.size(),"candidateQueries",jdbc.counts.stream().filter(q->!q.original().equals(q.emitted())).count()));
            }
            var invalid=new ProbeJdbc(db,Mode.FORCE_CANDIDATE);
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->labels(invalid).sourcePage(owner,ALL,Map.of("matchStatus","NOT_A_STATUS")));
            assertTrue(invalid.counts.isEmpty());
            assertThrows(com.yingdao.aicommerce.common.exception.BusinessException.class,
                ()->labels(invalid).sourcePage(owner,ALL,Map.of("numbers","x".repeat(201))));
            assertTrue(invalid.counts.isEmpty());

            // With no removed shop, the genuinely unfiltered production fast path must stay on uk_label_file_event.
            db.update("UPDATE finance_connections SET status='ACTIVE' WHERE id=?",SHOP_REMOVED);
            for(var mode:List.of(Mode.UNCHANGED,Mode.FORCE_CANDIDATE)) {
                var jdbc=new ProbeJdbc(db,mode);
                var page=labels(jdbc).sourcePage(owner,ALL,Map.of("fileId",MIDDLE,"numbers"," \t ","matchStatus","BILLING_FAILED"));
                assertEquals(60,number(page.get("total")));
                assertEquals(1,jdbc.counts.size());
                var q=jdbc.counts.getFirst();var p=plan(q.emitted(),q.args());
                assertEquals(q.original(),q.emitted());
                assertTrue(q.emitted().contains("FORCE INDEX(uk_label_file_event)"));
                assertEquals("uk_label_file_event",text(p.table().get("key")));
                assertEquals(Boolean.TRUE,p.table().get("using_index"));
                emit("unfiltered",Map.of("mode",mode,"key",text(p.table().get("key")),"usingIndex",true,"total",60));
            }
        } finally {
            if(installed)drop();
            cleanup(files);
        }
    }

    /** Catches a candidate that cannot cover the real conditional count or changes the selected source page. */
    @Test void conditionalSourceCountCanUseImportShopStatusCover() {
        boolean installed=false;
        try {
            shops();
            long seedStart=System.nanoTime();
            for(int f=0;f<HEAVY.size();f++) {
                String file=HEAVY.get(f);
                addImport(file,"org","READY","2026-10-0"+(3-f)+" 10:00:00");
                for(int first=1;first<=20_000;first+=500) {
                    var batch=new ArrayList<Object[]>();
                    for(int n=first;n<first+500;n++) {
                        int kind=n%10;
                        String shop=switch(kind){case 0,1,6,8->SHOP_A;case 2,7->SHOP_B;case 3,9->SHOP_U;case 5->SHOP_REMOVED;default->null;};
                        String status=switch(kind){case 1,7->"REVIEW";case 4->"UNMATCHED";case 9->"BILLING_FAILED";default->"MATCHED";};
                        batch.add(row(file,n,kind==8?"legacy-row-org":"org","heavy-"+f+"-"+n,shop,status,true));
                    }
                    addRows(batch);
                }
            }
            assertEquals(60_000,db.queryForObject("SELECT COUNT(*) FROM "+TABLE,Long.class));
            db.execute("ANALYZE TABLE "+TABLE);
            emit("profile",Map.of("files",3,"rowsPerFile",20_000,"actualRows",60_000,"seedMs",millis(seedStart),
                "widePayloadCharacters",672,"serverVersion",Objects.requireNonNull(db.queryForObject("SELECT VERSION()",String.class)),
                "innodbBufferPoolBytes",Objects.requireNonNull(db.queryForObject("SELECT @@innodb_buffer_pool_size",Long.class))));
            var before=metadata();emit("metadataBefore",before);
            var selected=selected("","",GROUP_A);
            var query=Map.of("matchStatus","MATCHED");
            var old=new ProbeJdbc(db,Mode.UNCHANGED);
            var oldPage=labels(old).sourcePage(owner,selected,query);
            assertHeavyOracle(oldPage);
            assertEquals(3,old.counts.size());
            var representative=old.counts.getFirst();
            var oldPlan=explain("baseline-natural-before-index",representative.emitted(),representative.args());
            assertFalse(Boolean.TRUE.equals(oldPlan.table().get("using_index")),"No existing index contains both matched_shop_id and match_status for this count");
            explain("baseline-page-before-index",old.pages.getFirst().sql(),old.pages.getFirst().args());
            install();installed=true;
            var after=metadata();emit("metadataAfter",after);
            emit("metadataDelta",Map.of("dataBytes",number(after.get("DATA_LENGTH"))-number(before.get("DATA_LENGTH")),
                "totalIndexBytes",number(after.get("INDEX_LENGTH"))-number(before.get("INDEX_LENGTH")),"metadataOnly",true));
            var natural=new ProbeJdbc(db,Mode.UNCHANGED);
            var naturalPage=labels(natural).sourcePage(owner,selected,query);
            assertEquals(oldPage,naturalPage);
            explain("unchanged-sql-after-index",natural.counts.getFirst().emitted(),natural.counts.getFirst().args());
            var candidate=new ProbeJdbc(db,Mode.FORCE_CANDIDATE);
            var candidatePage=labels(candidate).sourcePage(owner,selected,query);
            assertHeavyOracle(candidatePage);assertEquals(oldPage,candidatePage);
            assertEquals(sourceHash(oldPage),sourceHash(candidatePage));
            var q=candidate.counts.getFirst();
            var covered=explain("forced-candidate-after-index",q.emitted(),q.args());
            assertEquals(INDEX,text(covered.table().get("key")));
            assertEquals(Boolean.TRUE,covered.table().get("using_index"),"The desired missing-cover RED becomes index-only GREEN");
            assertTrue(covered.tree().contains("Covering index"),covered.tree());
            var primary=new ProbeJdbc(db,Mode.FORCE_OLD_PRIMARY);
            assertEquals(oldPage,labels(primary).sourcePage(owner,selected,query));
            explain("forced-old-primary-after-index",primary.counts.getFirst().emitted(),primary.counts.getFirst().args());
            explain("candidate-page-unchanged-primary",candidate.pages.getFirst().sql(),candidate.pages.getFirst().args());
            assertEquals(old.pages.getFirst(),candidate.pages.getFirst(),"The count-only hook leaves source paging SQL and bindings unchanged");

            // One warm-up per mode, then two paired hot rounds with order reversed in the second round.
            for(var mode:Mode.values())timed(mode,selected,query,oldPage,"warmup",0);
            for(int round=1;round<=2;round++) {
                var modes=round==1?List.of(Mode.UNCHANGED,Mode.FORCE_CANDIDATE):List.of(Mode.FORCE_CANDIDATE,Mode.UNCHANGED);
                for(var mode:modes)timed(mode,selected,query,oldPage,"natural-v-candidate",round);
            }
            for(int round=1;round<=2;round++) {
                var modes=round==1?List.of(Mode.FORCE_OLD_PRIMARY,Mode.FORCE_CANDIDATE):List.of(Mode.FORCE_CANDIDATE,Mode.FORCE_OLD_PRIMARY);
                for(var mode:modes)timed(mode,selected,query,oldPage,"forced-primary-v-candidate",round);
            }
            emit("equivalence",Map.of("literalTotal",18_000,"literalFirst50Rows",50,"sourceIdHash",sourceHash(oldPage),
                "unchangedSql",true,"countArgs",representative.args(),"noRowOrganizationPredicate",!representative.original().contains("r.organization_id=?")));
        } finally {
            if(installed)drop();
            cleanup(HEAVY);
        }
    }

    private List<OracleCase> semanticCases() {
        var all=concat(ids(NEW,1,2,3,4,6,7,8,9,10),range(MIDDLE,1,60),ids(OLD,1,2));
        var a=concat(ids(NEW,1,6,7,8,10),range(MIDDLE,1,60));
        var matched=concat(ids(NEW,1,3,6,9,10),range(MIDDLE,1,60));
        var c=new ArrayList<OracleCase>();
        c.add(oracle("all-file-authorized-legacy-and-foreign-row-org",ALL,Map.of(),71,all));
        c.add(oracle("all-page-two-cross-file-offset",ALL,Map.of("page","2"),71,all));
        c.add(oracle("all-page-three-empty-offset",ALL,Map.of("page","3"),71,all));
        c.add(oracle("group-a",selected("","",GROUP_A),Map.of(),65,a));
        c.add(oracle("group-a-page-two",selected("","",GROUP_A),Map.of("page","2"),65,a));
        c.add(oracle("group-b",selected("","",GROUP_B),Map.of(),2,ids(NEW,2,OLD,1)));
        c.add(oracle("explicit-shop",selected(SHOP_A,"",""),Map.of(),65,a));
        c.add(oracle("platform-shopee",selected("","SHOPEE",""),Map.of(),66,concat(ids(NEW,1,3,6,7,8,10),range(MIDDLE,1,60))));
        c.add(oracle("platform-tiktok",selected("","TIKTOK_SHOP",""),Map.of(),2,ids(NEW,2,OLD,1)));
        c.add(oracle("ungrouped-includes-null",selected("","","UNGROUPED"),Map.of(),3,ids(NEW,3,4,OLD,2)));
        c.add(oracle("ungrouped-platform-excludes-null",selected("","SHOPEE","UNGROUPED"),Map.of(),1,ids(NEW,3)));
        c.add(oracle("removed-shop-selection",selected(SHOP_REMOVED,"",""),Map.of(),0,List.of()));
        c.add(oracle("unknown-group-selection",selected("","","missing-group"),Map.of(),0,List.of()));
        c.add(oracle("unknown-shop-selection",selected("missing-shop","",""),Map.of(),0,List.of()));
        c.add(oracle("status-matched",ALL,Map.of("matchStatus","MATCHED"),65,matched));
        c.add(oracle("status-review",ALL,Map.of("matchStatus","REVIEW"),3,ids(NEW,2,7,OLD,1)));
        c.add(oracle("status-unmatched",ALL,Map.of("matchStatus","UNMATCHED"),2,ids(NEW,4,OLD,2)));
        c.add(oracle("status-conflict-original-fallback-active-generation",ALL,Map.of("matchStatus","CONFLICT"),1,ids(NEW,6)));
        c.add(oracle("retired-status-normalizes-all",ALL,Map.of("matchStatus","BILLING_FAILED"),71,all));
        c.add(oracle("blank-numbers-normalize-all",ALL,Map.of("numbers"," \t \n"),71,all));
        c.add(oracle("exact-number-delimiters-deduplicate",ALL,Map.of("numbers","alpha-a,alpha-a；middle-042\nold-null"),3,ids(NEW,1,MIDDLE,42,OLD,2)));
        c.add(oracle("number-and-group-fallback",selected("","",GROUP_A),Map.of("numbers","alpha-a;middle-042;old-null"),2,ids(NEW,1,MIDDLE,42)));
        c.add(oracle("number-and-status-fallback",ALL,Map.of("matchStatus","REVIEW","numbers","alpha-b old-review alpha-a"),2,ids(NEW,2,OLD,1)));
        c.add(oracle("number-and-conflict-fallback",ALL,Map.of("matchStatus","CONFLICT","numbers","alpha-conflict alpha-inactive-conflict"),1,ids(NEW,6)));
        c.add(oracle("number-is-exact-not-like",ALL,Map.of("numbers","alpha-%"),0,List.of()));
        c.add(oracle("prepared-file-authorized",ALL,Map.of("fileId",MIDDLE),60,range(MIDDLE,1,60)));
        c.add(oracle("remove-queued-file-authorized",ALL,Map.of("fileId",OLD),2,ids(OLD,1,2)));
        c.add(oracle("single-new-file",ALL,Map.of("fileId",NEW),9,ids(NEW,1,2,3,4,6,7,8,9,10)));
        for(String file:List.of("cover-sem-foreign","cover-sem-removed","cover-sem-expired","cover-sem-missing"))
            c.add(oracle("unauthorized-file-"+file,ALL,Map.of("fileId",file),0,List.of()));
        c.add(oracle("source-does-not-filter-invoice-date-or-currency",new Filter(LocalDate.of(2030,1,1),LocalDate.of(2030,1,31),"","","USD","day",""),Map.of(),71,all));
        c.add(oracle("group-and-status",selected("","",GROUP_A),Map.of("matchStatus","MATCHED"),63,concat(ids(NEW,1,6,10),range(MIDDLE,1,60))));
        return c;
    }
    private OracleCase oracle(String name,Filter selected,Map<String,String> query,long total,List<String> sourceIds) {
        assertEquals(total,sourceIds.size(),"Hand-derived literal fixture count for "+name);
        return new OracleCase(name,selected,query,total,sourceIds);
    }
    private Observation observe(ProbeJdbc jdbc,OracleCase c) {
        var page=labels(jdbc).sourcePage(owner,c.selected(),c.query());
        assertEquals(c.total(),number(page.get("total")),c.name());
        int offset=(Integer.parseInt(c.query().getOrDefault("page","1"))-1)*50;
        var expected=c.sourceIds().subList(Math.min(offset,c.sourceIds().size()),Math.min(offset+50,c.sourceIds().size()));
        assertEquals(expected,sourceIds(page),c.name()+": independent literal source-row oracle");
        return new Observation(page,sourceHash(page));
    }
    private void assertHeavyOracle(Map<String,Object> page) {
        assertEquals(18_000,number(page.get("total")),"Exactly residues 0,6,8 of each 20,000-row file match");
        var expected=ids(HEAVY.getFirst(),6,8,10,16,18,20,26,28,30,36,38,40,46,48,50,56,58,60,66,68,70,76,78,80,86,88,90,
            96,98,100,106,108,110,116,118,120,126,128,130,136,138,140,146,148,150,156,158,160,166,168);
        assertEquals(50,expected.size());assertEquals(expected,sourceIds(page),"Literal first fifty source rows in the newest file");
    }
    private void timed(Mode mode,Filter selected,Map<String,String> query,Map<String,Object> expected,String pair,int round) {
        var jdbc=new ProbeJdbc(db,mode);long start=System.nanoTime();var page=labels(jdbc).sourcePage(owner,selected,query);double elapsed=millis(start);
        assertEquals(expected,page);assertEquals(3,jdbc.counts.size());
        emit("hot",Map.of("pair",pair,"round",round,"mode",mode,"wholeSourcePageMs",elapsed,"sumCountMs",jdbc.counts.stream().mapToDouble(CountQuery::millis).sum(),
            "countMs",jdbc.counts.stream().map(CountQuery::millis).toList(),"total",number(page.get("total")),"sourceIdHash",sourceHash(page)));
    }
    private FinanceLabelCosts labels(ProbeJdbc jdbc) {
        return new FinanceLabelCosts(new FinanceRepository(jdbc,new ObjectMapper()),null,Path.of("target","label-source-cover-prototype").toString());
    }
    private Filter selected(String shop,String platform,String group) {
        return new Filter(ALL.start(),ALL.end(),shop,platform,"","day",group);
    }
    private void shops() {
        db.update("INSERT INTO finance_groups(id,organization_id,name) VALUES(?,'org','prototype A'),(?,'org','prototype B')",GROUP_A,GROUP_B);
        addShop(SHOP_A,"org","SHOPEE",GROUP_A,"ACTIVE");addShop(SHOP_B,"org","TIKTOK_SHOP",GROUP_B,"ACTIVE");
        addShop(SHOP_U,"org","SHOPEE",null,"ACTIVE");addShop(SHOP_REMOVED,"org","SHOPEE",GROUP_A,"REMOVED");
        addShop(SHOP_FOREIGN,"other-org","SHOPEE",null,"ACTIVE");
    }
    private void addShop(String id,String organization,String platform,String group,String status) {
        db.update("""
            INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,currency,timezone,
              token_group_id,access_token_encrypted,scopes_json,group_id,status)
            VALUES(?,?,'u',?,?,'prototype','PHP','UTC',?,'','[]',?,?)
            """,id,organization,platform,id,id,group,status);
    }
    private void addImport(String id,String organization,String status,String created) {
        db.update("""
            INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,created_by,created_at)
            VALUES(?,?,?,?,'',0,?,'u',?)
            """,id,organization,id+".xlsx",hash(id),status,created);
    }
    private Object[] row(String file,int source,String organization,String order,String shop,String status,boolean wide) {
        String suffix=file+"/"+source;
        return new Object[]{file,source,organization,hash("event/"+suffix),hash("row/"+suffix),hash("bucket/"+suffix),order,
            wide?"s".repeat(160):"",wide?"c".repeat(96):"customer",wide?"w".repeat(96):"warehouse",wide?"d".repeat(160):"document",
            LocalDate.of(2020,1,1),BigDecimal.ONE,shop,status,wide?"n".repeat(160):""};
    }
    private void addRows(List<Object[]> rows) {
        db.batchUpdate("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,
              shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,rows);
    }
    private void install() {
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?",Integer.class,TABLE,INDEX));
        db.execute("CREATE INDEX "+INDEX+" ON "+TABLE+"(import_id,matched_shop_id,match_status)");
        try {db.execute("ANALYZE TABLE "+TABLE);} catch(RuntimeException failure) {drop();throw failure;}
    }
    private void drop(){db.execute("DROP INDEX "+INDEX+" ON "+TABLE);}
    private void cleanup(List<String> files) {
        for(String file:files){db.update("DELETE FROM "+TABLE+" WHERE import_id=?",file);db.update("DELETE FROM finance_label_imports WHERE id=?",file);}
        db.update("DELETE FROM finance_label_order_totals WHERE generation IN ('cover-active','cover-inactive')");
        db.update("DELETE FROM finance_label_state WHERE organization_id='org' AND active_generation='cover-active'");
        for(String shop:List.of(SHOP_A,SHOP_B,SHOP_U,SHOP_REMOVED,SHOP_FOREIGN))db.update("DELETE FROM finance_connections WHERE id=?",shop);
        for(String group:List.of(GROUP_A,GROUP_B))db.update("DELETE FROM finance_groups WHERE id=?",group);
    }
    private Map<String,Object> metadata() {
        return db.queryForMap("SELECT TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",TABLE);
    }
    private Plan explain(String label,String sql,List<Object> args) {
        var p=plan(sql,args);
        emit("plan",Map.of("label",label,"sql",sql,"args",args,"table",p.table(),"sortFlags",flags(p.json(),"using_filesort"),
            "temporaryFlags",flags(p.json(),"using_temporary_table"),"explainJson",p.json(),"explainAnalyze",p.tree()));
        return p;
    }
    @SuppressWarnings("unchecked") private Plan plan(String sql,List<Object> args) {
        var json=JSON.readValue(db.queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class,args.toArray()),Map.class);
        var actual=db.queryForList("EXPLAIN ANALYZE "+sql,args.toArray());
        String tree=actual.stream().flatMap(row->row.values().stream()).map(String::valueOf).reduce("",(a,b)->a+b+"\n");
        return new Plan(json,table(json),tree);
    }
    private static Map<String,Object> table(Object value) {
        if(value instanceof Map<?,?> raw){var m=map(raw);if("r".equals(m.get("table_name")))return m;for(var child:m.values()){var found=table(child);if(!found.isEmpty())return found;}}
        else if(value instanceof List<?> values)for(var child:values){var found=table(child);if(!found.isEmpty())return found;}
        return Map.of();
    }
    private static List<Object> flags(Object value,String name) {
        var found=new ArrayList<Object>();
        if(value instanceof Map<?,?> raw){if(raw.containsKey(name))found.add(raw.get(name));for(var child:raw.values())found.addAll(flags(child,name));}
        else if(value instanceof List<?> values)for(var child:values)found.addAll(flags(child,name));
        return found;
    }
    private static List<String> sourceIds(Map<String,Object> page) {return rows(page.get("rows")).stream().map(r->text(r.get("import_id"))+"/"+number(r.get("source_row"))).toList();}
    private static String sourceHash(Map<String,Object> page){return hash(String.join("\n",sourceIds(page)));}
    /** Independent source-row oracle, explicitly listing source IDs rather than reimplementing SQL predicates. */
    private static List<String> ids(String initial,Object...sources) {
        String file=initial;var out=new ArrayList<String>();for(Object source:sources){if(source instanceof String s)file=s;else out.add(file+"/"+source);}return out;
    }
    private static List<String> range(String file,int first,int last){var out=new ArrayList<String>();for(int n=first;n<=last;n++)out.add(file+"/"+n);return out;}
    @SafeVarargs private static List<String> concat(List<String>...parts){return Arrays.stream(parts).flatMap(List::stream).toList();}
    private static double millis(long start){return (System.nanoTime()-start)/1_000_000.0;}
    private static void emit(String event,Object data){System.out.println("LABEL_SOURCE_COVER "+JSON.writeValueAsString(Map.of("event",event,"data",data)));}
    private static boolean eligible(String sql){return !sql.contains("FORCE INDEX(")&&!sql.contains("r.order_id")&&!sql.contains("EXISTS(");}
    enum Mode {UNCHANGED,FORCE_CANDIDATE,FORCE_OLD_PRIMARY}
    record OracleCase(String name,Filter selected,Map<String,String> query,long total,List<String> sourceIds){}
    record Observation(Map<String,Object> page,String sourceHash){}
    record CountQuery(String original,String emitted,List<Object> args,double millis){}
    record PageQuery(String sql,List<Object> args){}
    record Plan(Map<String,Object> json,Map<String,Object> table,String tree){}
    static final class ProbeJdbc extends JdbcTemplate {
        final Mode mode;final List<CountQuery> counts=new ArrayList<>();final List<PageQuery> pages=new ArrayList<>();
        ProbeJdbc(JdbcTemplate delegate,Mode mode){super(Objects.requireNonNull(delegate.getDataSource()));this.mode=mode;setQueryTimeout(30);}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args) {
            if(!sql.startsWith("SELECT COUNT(*) FROM finance_label_invoice_rows r"))return super.queryForObject(sql,type,args);
            String emitted=sql;
            if(mode!=Mode.UNCHANGED&&eligible(sql))emitted=sql.replace("finance_label_invoice_rows r WHERE","finance_label_invoice_rows r FORCE INDEX("+(mode==Mode.FORCE_CANDIDATE?INDEX:"PRIMARY")+") WHERE");
            long start=System.nanoTime();T result=super.queryForObject(emitted,type,args);
            counts.add(new CountQuery(sql,emitted,List.copyOf(Arrays.asList(args.clone())),millis(start)));return result;
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args) {
            if(sql.stripLeading().startsWith("SELECT r.import_id,r.source_row"))pages.add(new PageQuery(sql,List.copyOf(Arrays.asList(args.clone()))));
            return super.queryForList(sql,args);
        }
    }
}
