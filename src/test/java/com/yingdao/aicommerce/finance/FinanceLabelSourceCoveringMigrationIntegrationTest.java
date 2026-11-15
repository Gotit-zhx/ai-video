package com.yingdao.aicommerce.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated V161 -> V162 upgrade and unchanged production source-page proof. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceLabelSourceCoveringMigrationIntegrationTest {
    static final String TABLE="finance_label_invoice_rows",INDEX="idx_label_import_shop_status";
    static final Filter ALL=new Filter(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"","","","day","");
    static final Scope HEAVY_SCOPE=new Scope("mig-heavy-org","u");
    static final String SHOP_A="mig-shop-a",SHOP_B="mig-shop-b",SHOP_U="mig-shop-u",SHOP_REMOVED="mig-shop-removed",SHOP_FOREIGN="mig-shop-foreign";
    static final String GROUP_A="mig-group-a",GROUP_B="mig-group-b";
    static final String HEAVY_A="mig-heavy-a",HEAVY_B="mig-heavy-b",HEAVY_U="mig-heavy-u",HEAVY_REMOVED="mig-heavy-removed",HEAVY_EMPTY="mig-heavy-empty";
    static final String HEAVY_GROUP="mig-heavy-group",EMPTY_GROUP="mig-empty-group";
    static final String NEW="mig-sem-new",MIDDLE="mig-sem-middle",OLD="mig-sem-old";
    static final List<String> HEAVY=List.of("mig-heavy-new","mig-heavy-mid","mig-heavy-old");
    static final ObjectMapper JSON=new ObjectMapper();
    static final int HEAVY_ROWS_PER_FILE=10_000,WRITE_ROWS=120;
    @TempDir Path temporary;

    @Test void upgradesTheRealV161SchemaWithoutChangingSourcePagesAndNaturallyCoversConditionalCounts()throws Exception{
        var f=new FinanceConfigurationIntegrationTest();
        try{
            f.migrateThrough(157);
            f.migration("V159__finance_first_settlement_order.sql");
            f.migration("V160__finance_projection_record_identity_events.sql");
            f.migration("V161__finance_projection_currency_byte_events.sql");
            f.setup();
            assertTrue(indexDefinition(f,INDEX).isEmpty(),"The isolated physical baseline must be V161 without the target index");

            seedSemantic(f);seedHeavy(f);
            f.db.execute("ANALYZE TABLE "+TABLE);
            var preWrite=writeProbe(f,"mig-probe-pre",false);
            f.db.execute("ANALYZE TABLE "+TABLE);

            var cases=semanticCases();var beforePages=new LinkedHashMap<String,Observation>();
            var nativeLabels=labels(f.db);
            for(var c:cases)beforePages.put(c.name(),observe(nativeLabels,c));

            var heavySelected=selected(HEAVY_SCOPE,"","",HEAVY_GROUP);
            var emptySelected=selected(HEAVY_SCOPE,"","",EMPTY_GROUP);
            var matched=Map.of("matchStatus","MATCHED");
            var heavyBeforeJdbc=new TracedJdbc(f.db);var heavyBefore=labels(heavyBeforeJdbc).sourcePage(HEAVY_SCOPE,heavySelected,matched);
            assertHeavyOracle(heavyBefore);assertEquals(HEAVY.size(),heavyBeforeJdbc.counts.size());
            var zeroBeforeJdbc=new TracedJdbc(f.db);var zeroBefore=labels(zeroBeforeJdbc).sourcePage(HEAVY_SCOPE,emptySelected,matched);
            assertEquals(0,number(zeroBefore.get("total")));assertTrue(rows(zeroBefore.get("rows")).isEmpty());assertEquals(HEAVY.size(),zeroBeforeJdbc.counts.size());
            var oldPlan=plan(f,heavyBeforeJdbc.counts.getFirst());
            assertNotEquals(INDEX,text(oldPlan.table().get("key")));
            assertFalse(Boolean.TRUE.equals(oldPlan.table().get("using_index")),"The V161 conditional count must retain the known missing-cover baseline");

            var columnsBefore=columns(f);
            var otherIndexesBefore=otherIndexes(f);
            var triggersBefore=triggers(f);
            var sourceBefore=sourceState(f);
            var tableBefore=tableMetadata(f);
            Path migration=Path.of("src/main/resources/db/migration/V162__finance_label_source_count_covering.sql");
            assertTrue(Files.exists(migration));Files.copy(migration,temporary.resolve(migration.getFileName()));
            var flyway=Flyway.configure().dataSource(f.dataSource).locations("filesystem:"+temporary)
                .baselineVersion("161").baselineDescription("isolated fixture baseline 161").load();
            flyway.baseline();var historyPrefix=history(f);
            assertEquals(1,historyPrefix.size());assertEquals("161",text(historyPrefix.getFirst().get("version")));
            assertEquals(1,flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals("162",flyway.info().current().getVersion().getVersion());
            assertEquals(historyPrefix,f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history WHERE version<>'162' ORDER BY installed_rank"));
            assertEquals(0,flyway.migrate().migrationsExecuted);

            var target=indexDefinition(f,INDEX);assertIndex(target);
            assertEquals(columnsBefore,columns(f));assertEquals(otherIndexesBefore,otherIndexes(f));assertEquals(triggersBefore,triggers(f));assertEquals(sourceBefore,sourceState(f));
            f.db.execute("ANALYZE TABLE "+TABLE);var tableAfter=tableMetadata(f);

            var semanticSummary=new ArrayList<Map<String,Object>>();
            for(var c:cases){
                var after=observe(nativeLabels,c);
                var before=beforePages.get(c.name());
                assertEquals(before.page(),after.page(),c.name()+": complete source page changed across V162");
                assertEquals(before.sourceHash(),after.sourceHash(),c.name()+": ordered IDs changed across V162");
                semanticSummary.add(Map.of("case",c.name(),"total",c.total(),"pageRows",rows(after.page().get("rows")).size(),"sourceIdHash",after.sourceHash()));
            }

            var heavyAfterJdbc=new TracedJdbc(f.db);var heavyAfter=labels(heavyAfterJdbc).sourcePage(HEAVY_SCOPE,heavySelected,matched);
            assertEquals(heavyBefore,heavyAfter);assertHeavyOracle(heavyAfter);assertNaturalTarget(f,heavyAfterJdbc.counts);
            var zeroAfterJdbc=new TracedJdbc(f.db);var zeroAfter=labels(zeroAfterJdbc).sourcePage(HEAVY_SCOPE,emptySelected,matched);
            assertEquals(zeroBefore,zeroAfter);assertNaturalTarget(f,zeroAfterJdbc.counts);
            var nonzeroPlan=plan(f,heavyAfterJdbc.counts.getFirst());
            var zeroPlan=plan(f,zeroAfterJdbc.counts.getFirst());
            assertPlanUsesTarget(nonzeroPlan);assertPlanUsesTarget(zeroPlan);

            var postWrite=writeProbe(f,"mig-probe-post",true);
            assertEquals(sourceBefore,sourceState(f),"Owned write probes must be cleaned without changing the migration sentinel");

            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("baseline","isolated fixture physical V161");receipt.put("historyPrefix",historyPrefix);receipt.put("migrationExecuted",1);
            receipt.put("index",target);receipt.put("tableMetadataBefore",tableBefore);receipt.put("tableMetadataAfter",tableAfter);
            receipt.put("tableMetadataDelta",delta(tableBefore,tableAfter));receipt.put("sourceStateHash",hash(JSON.writeValueAsString(sourceBefore)));
            receipt.put("semanticCases",semanticSummary);receipt.put("heavy",Map.of("files",HEAVY.size(),"rowsPerFile",HEAVY_ROWS_PER_FILE,
                "nonzeroTotal",number(heavyAfter.get("total")),"zeroTotal",number(zeroAfter.get("total")),"orderedIds",sourceIds(heavyAfter)));
            receipt.put("plans",Map.of("v161Before",oldPlan.receipt(),"v162Nonzero",nonzeroPlan.receipt(),"v162Zero",zeroPlan.receipt()));
            receipt.put("statementCounts",Map.of("nonzero",heavyAfterJdbc.counts.stream().map(CountQuery::receipt).toList(),"zero",zeroAfterJdbc.counts.stream().map(CountQuery::receipt).toList()));
            receipt.put("writes",Map.of("preIndex",preWrite.receipt(),"postIndex",postWrite.receipt(),"orderBias","pre-index first; post-index second; single samples only"));
            System.out.println("FINANCE_LABEL_SOURCE_COVERING_MIGRATION "+JSON.writeValueAsString(receipt));
        }finally{f.closeDataSource();}
    }

    private static void assertIndex(List<Map<String,Object>> rows){
        assertEquals(3,rows.size());assertEquals(List.of("import_id","matched_shop_id","match_status"),rows.stream().map(r->text(r.get("COLUMN_NAME"))).toList());
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);assertEquals(i+1,number(row.get("SEQ_IN_INDEX")));assertEquals(1,number(row.get("NON_UNIQUE")));
            assertNull(row.get("SUB_PART"));assertEquals("BTREE",text(row.get("INDEX_TYPE")));assertEquals("YES",text(row.get("IS_VISIBLE")));
        }
    }
    private static void assertNaturalTarget(FinanceConfigurationIntegrationTest f,List<CountQuery> counts){
        assertEquals(HEAVY.size(),counts.size());
        for(var count:counts){var table=jsonTable(f,count);assertEquals(INDEX,text(table.get("key")));assertEquals(Boolean.TRUE,table.get("using_index"));}
    }
    private static void assertPlanUsesTarget(Plan plan){assertEquals(INDEX,text(plan.table().get("key")));assertEquals(Boolean.TRUE,plan.table().get("using_index"));assertTrue(plan.analyze().contains(INDEX),plan.analyze());}

    private static List<OracleCase> semanticCases(){
        var all=concat(ids(NEW,1,2,3,4,6,7,8,9,10),range(MIDDLE,1,60),ids(OLD,1,2));
        var a=concat(ids(NEW,1,6,7,8,10),range(MIDDLE,1,60));
        var matched=concat(ids(NEW,1,3,6,9,10),range(MIDDLE,1,60));
        var c=new ArrayList<OracleCase>();
        c.add(oracle("all-file-authorized-legacy-and-foreign-row-org",ALL,Map.of(),71,all));
        c.add(oracle("all-page-two-cross-file-offset",ALL,Map.of("page","2"),71,all));
        c.add(oracle("all-page-three-empty-offset",ALL,Map.of("page","3"),71,all));
        c.add(oracle("group-a",selected(companyScope("org"),"","",GROUP_A),Map.of(),65,a));
        c.add(oracle("group-a-page-two",selected(companyScope("org"),"","",GROUP_A),Map.of("page","2"),65,a));
        c.add(oracle("group-b",selected(companyScope("org"),"","",GROUP_B),Map.of(),2,ids(NEW,2,OLD,1)));
        c.add(oracle("explicit-shop",selected(companyScope("org"),SHOP_A,"",""),Map.of(),65,a));
        c.add(oracle("platform-shopee",selected(companyScope("org"),"","SHOPEE",""),Map.of(),66,concat(ids(NEW,1,3,6,7,8,10),range(MIDDLE,1,60))));
        c.add(oracle("platform-tiktok",selected(companyScope("org"),"","TIKTOK_SHOP",""),Map.of(),2,ids(NEW,2,OLD,1)));
        c.add(oracle("ungrouped-includes-null",selected(companyScope("org"),"","","UNGROUPED"),Map.of(),3,ids(NEW,3,4,OLD,2)));
        c.add(oracle("ungrouped-platform-excludes-null",selected(companyScope("org"),"","SHOPEE","UNGROUPED"),Map.of(),1,ids(NEW,3)));
        c.add(oracle("removed-shop-selection",selected(companyScope("org"),SHOP_REMOVED,"",""),Map.of(),0,List.of()));
        c.add(oracle("unknown-group-selection",selected(companyScope("org"),"","","missing-group"),Map.of(),0,List.of()));
        c.add(oracle("unknown-shop-selection",selected(companyScope("org"),"missing-shop","",""),Map.of(),0,List.of()));
        c.add(oracle("status-matched",ALL,Map.of("matchStatus","MATCHED"),65,matched));
        c.add(oracle("status-review",ALL,Map.of("matchStatus","REVIEW"),3,ids(NEW,2,7,OLD,1)));
        c.add(oracle("status-unmatched",ALL,Map.of("matchStatus","UNMATCHED"),2,ids(NEW,4,OLD,2)));
        c.add(oracle("status-conflict-original-fallback-active-generation",ALL,Map.of("matchStatus","CONFLICT"),1,ids(NEW,6)));
        c.add(oracle("retired-status-normalizes-all",ALL,Map.of("matchStatus","BILLING_FAILED"),71,all));
        c.add(oracle("blank-numbers-normalize-all",ALL,Map.of("numbers"," \t \n"),71,all));
        c.add(oracle("exact-number-delimiters-deduplicate",ALL,Map.of("numbers","alpha-a,alpha-a；middle-042\nold-null"),3,ids(NEW,1,MIDDLE,42,OLD,2)));
        c.add(oracle("number-and-group-fallback",selected(companyScope("org"),"","",GROUP_A),Map.of("numbers","alpha-a;middle-042;old-null"),2,ids(NEW,1,MIDDLE,42)));
        c.add(oracle("number-and-status-fallback",ALL,Map.of("matchStatus","REVIEW","numbers","alpha-b old-review alpha-a"),2,ids(NEW,2,OLD,1)));
        c.add(oracle("number-and-conflict-fallback",ALL,Map.of("matchStatus","CONFLICT","numbers","alpha-conflict alpha-inactive-conflict"),1,ids(NEW,6)));
        c.add(oracle("number-is-exact-not-like",ALL,Map.of("numbers","alpha-%"),0,List.of()));
        c.add(oracle("prepared-file-authorized",ALL,Map.of("fileId",MIDDLE),60,range(MIDDLE,1,60)));
        c.add(oracle("remove-queued-file-authorized",ALL,Map.of("fileId",OLD),2,ids(OLD,1,2)));
        c.add(oracle("single-new-file",ALL,Map.of("fileId",NEW),9,ids(NEW,1,2,3,4,6,7,8,9,10)));
        for(String file:List.of("mig-sem-foreign","mig-sem-removed","mig-sem-expired","mig-sem-missing"))c.add(oracle("unauthorized-file-"+file,ALL,Map.of("fileId",file),0,List.of()));
        c.add(oracle("source-does-not-filter-invoice-date-or-currency",new Filter(LocalDate.of(2030,1,1),LocalDate.of(2030,1,31),"","","USD","day",""),Map.of(),71,all));
        c.add(oracle("group-and-status",selected(companyScope("org"),"","",GROUP_A),Map.of("matchStatus","MATCHED"),63,concat(ids(NEW,1,6,10),range(MIDDLE,1,60))));
        return c;
    }
    private static OracleCase oracle(String name,Filter filter,Map<String,String> query,long total,List<String> ids){assertEquals(total,ids.size());return new OracleCase(name,filter,query,total,ids);}
    private static Observation observe(FinanceLabelCosts labels,OracleCase c){
        var page=labels.sourcePage(companyScope("org"),c.filter(),c.query());assertEquals(c.total(),number(page.get("total")),c.name());
        int offset=(Integer.parseInt(c.query().getOrDefault("page","1"))-1)*50;
        var expected=c.ids().subList(Math.min(offset,c.ids().size()),Math.min(offset+50,c.ids().size()));
        assertEquals(expected,sourceIds(page),c.name()+": independent literal ordered IDs");return new Observation(page,hash(String.join("\n",sourceIds(page))));
    }

    private static void seedSemantic(FinanceConfigurationIntegrationTest f){
        addGroup(f,"org",GROUP_A,"migration A");addGroup(f,"org",GROUP_B,"migration B");
        addShop(f,"org",SHOP_A,"SHOPEE",GROUP_A,"ACTIVE");addShop(f,"org",SHOP_B,"TIKTOK_SHOP",GROUP_B,"ACTIVE");
        addShop(f,"org",SHOP_U,"SHOPEE",null,"ACTIVE");addShop(f,"org",SHOP_REMOVED,"SHOPEE",GROUP_A,"REMOVED");addShop(f,"other-org",SHOP_FOREIGN,"SHOPEE",null,"ACTIVE");
        addImport(f,NEW,"org","READY","2026-10-03 10:00:00");addImport(f,MIDDLE,"org","PREPARED","2026-10-02 10:00:00");addImport(f,OLD,"org","REMOVE_QUEUED","2026-10-01 10:00:00");
        addImport(f,"mig-sem-foreign","other-org","READY","2026-10-06 10:00:00");addImport(f,"mig-sem-removed","org","REMOVED","2026-10-05 10:00:00");addImport(f,"mig-sem-expired","org","EXPIRED","2026-10-04 10:00:00");
        addRows(f,List.of(
            row(NEW,1,"org","alpha-a",SHOP_A,"MATCHED",false),row(NEW,2,"org","alpha-b",SHOP_B,"REVIEW",false),
            row(NEW,3,"org","alpha-u",SHOP_U,"MATCHED",false),row(NEW,4,"org","alpha-null",null,"UNMATCHED",false),
            row(NEW,5,"org","alpha-removed",SHOP_REMOVED,"MATCHED",false),row(NEW,6,"org","alpha-conflict",SHOP_A,"MATCHED",false),
            row(NEW,7,"legacy-row-org","alpha-legacy",SHOP_A,"REVIEW",false),row(NEW,8,"org","alpha-retired",SHOP_A,"BILLING_FAILED",false),
            row(NEW,9,"other-org","alpha-foreign-shop",SHOP_FOREIGN,"MATCHED",false),row(NEW,10,"org","alpha-inactive-conflict",SHOP_A,"MATCHED",false),
            row(OLD,1,"org","old-review",SHOP_B,"REVIEW",false),row(OLD,2,"org","old-null",null,"UNMATCHED",false),row(OLD,3,"org","old-removed",SHOP_REMOVED,"MATCHED",false),
            row("mig-sem-foreign",1,"org","foreign-file-row",SHOP_A,"MATCHED",false),row("mig-sem-removed",1,"org","removed-file-row",SHOP_A,"MATCHED",false),row("mig-sem-expired",1,"org","expired-file-row",SHOP_A,"MATCHED",false)));
        var middle=new ArrayList<Object[]>();for(int n=1;n<=60;n++)middle.add(row(MIDDLE,n,"org","middle-"+String.format(Locale.ROOT,"%03d",n),SHOP_A,"MATCHED",false));addRows(f,middle);
        f.db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES('org','mig-active')");
        f.db.update("""
            INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count)
            VALUES('org','mig-active',?,'alpha-conflict',1,1,1),('org','mig-inactive',?,'alpha-inactive-conflict',1,1,1)
            """,SHOP_A,SHOP_A);
    }
    private static void seedHeavy(FinanceConfigurationIntegrationTest f){
        addGroup(f,HEAVY_SCOPE.organizationId(),HEAVY_GROUP,"heavy");addGroup(f,HEAVY_SCOPE.organizationId(),EMPTY_GROUP,"empty");
        addShop(f,HEAVY_SCOPE.organizationId(),HEAVY_A,"SHOPEE",HEAVY_GROUP,"ACTIVE");addShop(f,HEAVY_SCOPE.organizationId(),HEAVY_B,"TIKTOK_SHOP",null,"ACTIVE");
        addShop(f,HEAVY_SCOPE.organizationId(),HEAVY_U,"SHOPEE",null,"ACTIVE");addShop(f,HEAVY_SCOPE.organizationId(),HEAVY_REMOVED,"SHOPEE",HEAVY_GROUP,"REMOVED");addShop(f,HEAVY_SCOPE.organizationId(),HEAVY_EMPTY,"SHOPEE",EMPTY_GROUP,"ACTIVE");
        long started=System.nanoTime();
        for(int fileIndex=0;fileIndex<HEAVY.size();fileIndex++){
            String file=HEAVY.get(fileIndex);addImport(f,file,HEAVY_SCOPE.organizationId(),"READY","2026-09-0"+(3-fileIndex)+" 10:00:00");
            for(int first=1;first<=HEAVY_ROWS_PER_FILE;first+=500){
                var batch=new ArrayList<Object[]>();
                for(int n=first;n<first+500;n++){
                    int kind=n%10;String shop=switch(kind){case 0,1,6,8->HEAVY_A;case 2,7->HEAVY_B;case 3,9->HEAVY_U;case 5->HEAVY_REMOVED;default->null;};
                    String status=switch(kind){case 1,7->"REVIEW";case 4->"UNMATCHED";case 9->"BILLING_FAILED";default->"MATCHED";};
                    batch.add(row(file,n,HEAVY_SCOPE.organizationId(),"heavy-"+fileIndex+"-"+n,shop,status,true));
                }
                addRows(f,batch);
            }
        }
        assertEquals((long)HEAVY.size()*HEAVY_ROWS_PER_FILE,f.db.queryForObject("SELECT COUNT(*) FROM "+TABLE+" WHERE import_id IN (?,?,?)",Long.class,HEAVY.toArray()));
        System.out.println("FINANCE_LABEL_SOURCE_COVERING_SEED "+JSON.writeValueAsString(Map.of("files",HEAVY.size(),"rowsPerFile",HEAVY_ROWS_PER_FILE,"elapsedMs",millis(started))));
    }

    private static WriteObservation writeProbe(FinanceConfigurationIntegrationTest f,String file,boolean indexed)throws Exception{
        addImport(f,file,HEAVY_SCOPE.organizationId(),"READY",indexed?"2026-08-02 10:00:00":"2026-08-01 10:00:00");
        var before=tableMetadata(f);long insertStarted=System.nanoTime(),insertEnded,updateStarted,updateEnded;int updated;
        try{
            try(var connection=f.dataSource.getConnection()){
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("""
                    INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,
                      shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)){
                    for(int n=1;n<=WRITE_ROWS;n++){Object[] row=row(file,n,HEAVY_SCOPE.organizationId(),"probe-"+n,HEAVY_A,"MATCHED",true);for(int i=0;i<row.length;i++)statement.setObject(i+1,row[i]);statement.addBatch();}
                    statement.executeBatch();connection.commit();insertEnded=System.nanoTime();
                }catch(Exception failure){connection.rollback();throw failure;}
                updateStarted=System.nanoTime();
                try(var statement=connection.prepareStatement("UPDATE finance_label_invoice_rows SET matched_shop_id=?,match_status=? WHERE import_id=?")){
                    statement.setString(1,HEAVY_B);statement.setString(2,"REVIEW");statement.setString(3,file);updated=statement.executeUpdate();connection.commit();updateEnded=System.nanoTime();
                }catch(Exception failure){connection.rollback();throw failure;}
            }
            assertEquals(WRITE_ROWS,f.db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows WHERE import_id=?",Integer.class,file));assertEquals(WRITE_ROWS,updated);
            assertEquals(WRITE_ROWS,f.db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows WHERE import_id=? AND matched_shop_id=? AND match_status='REVIEW'",Integer.class,file,HEAVY_B));
            assertEquals(0,new BigDecimal(String.valueOf(WRITE_ROWS)).compareTo(f.db.queryForObject("SELECT SUM(amount_cny) FROM finance_label_invoice_rows WHERE import_id=?",BigDecimal.class,file)));
            var after=tableMetadata(f);return new WriteObservation(indexed,WRITE_ROWS,updated,(insertEnded-insertStarted)/1_000_000.0,(updateEnded-updateStarted)/1_000_000.0,before,after,delta(before,after));
        }finally{f.db.update("DELETE FROM finance_label_invoice_rows WHERE import_id=?",file);f.db.update("DELETE FROM finance_label_imports WHERE id=?",file);}
    }

    private static FinanceLabelCosts labels(JdbcTemplate jdbc){return new FinanceLabelCosts(new FinanceRepository(jdbc,new ObjectMapper()),null,Path.of("target","label-source-covering-migration").toString());}
    private static void assertHeavyOracle(Map<String,Object> page){
        assertEquals(9_000,number(page.get("total")));
        var expected=ids(HEAVY.getFirst(),6,8,10,16,18,20,26,28,30,36,38,40,46,48,50,56,58,60,66,68,70,76,78,80,86,88,90,96,98,100,106,108,110,116,118,120,126,128,130,136,138,140,146,148,150,156,158,160,166,168);
        assertEquals(expected,sourceIds(page));
    }
    private static Filter selected(Scope scope,String shop,String platform,String group){return new Filter(ALL.start(),ALL.end(),shop,platform,"","day",group);}
    private static List<String> sourceIds(Map<String,Object> page){return rows(page.get("rows")).stream().map(r->text(r.get("import_id"))+"/"+number(r.get("source_row"))).toList();}
    private static List<String> ids(String initial,Object...sources){String file=initial;var out=new ArrayList<String>();for(Object source:sources){if(source instanceof String s)file=s;else out.add(file+"/"+source);}return out;}
    private static List<String> range(String file,int first,int last){var out=new ArrayList<String>();for(int n=first;n<=last;n++)out.add(file+"/"+n);return out;}
    @SafeVarargs private static List<String> concat(List<String>...parts){return Arrays.stream(parts).flatMap(List::stream).toList();}

    private static void addGroup(FinanceConfigurationIntegrationTest f,String org,String id,String name){f.db.update("INSERT INTO finance_groups(id,organization_id,name) VALUES(?,?,?)",id,org,name);}
    private static void addShop(FinanceConfigurationIntegrationTest f,String org,String id,String platform,String group,String status){f.db.update("""
        INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,currency,timezone,
          token_group_id,access_token_encrypted,scopes_json,group_id,status)
        VALUES(?,?,'u',?,?,'migration','PHP','UTC',?,'','[]',?,?)
        """,id,org,platform,id,id,group,status);}
    private static void addImport(FinanceConfigurationIntegrationTest f,String id,String org,String status,String created){f.db.update("""
        INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,created_by,created_at)
        VALUES(?,?,?,?,'',0,?,'u',?)
        """,id,org,id+".xlsx",hash(id),status,created);}
    private static Object[] row(String file,int source,String org,String order,String shop,String status,boolean wide){String suffix=file+"/"+source;return new Object[]{file,source,org,hash("event/"+suffix),hash("row/"+suffix),hash("bucket/"+suffix),order,
        wide?"s".repeat(160):"",wide?"c".repeat(96):"customer",wide?"w".repeat(96):"warehouse",wide?"d".repeat(160):"document",LocalDate.of(2020,1,1),BigDecimal.ONE,shop,status,wide?"n".repeat(160):""};}
    private static void addRows(FinanceConfigurationIntegrationTest f,List<Object[]> rows){f.db.batchUpdate("""
        INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,
          shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,rows);}

    private static List<Map<String,Object>> indexDefinition(FinanceConfigurationIntegrationTest f,String index){return f.db.queryForList("""
        SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE,IS_VISIBLE
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX
        """,TABLE,index);}
    private static List<Map<String,Object>> otherIndexes(FinanceConfigurationIntegrationTest f){return f.db.queryForList("""
        SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE,IS_VISIBLE
        FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME<>? ORDER BY INDEX_NAME,SEQ_IN_INDEX
        """,TABLE,INDEX);}
    private static List<Map<String,Object>> columns(FinanceConfigurationIntegrationTest f){return f.db.queryForList("""
        SELECT COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME
        FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION
        """,TABLE);}
    private static List<Map<String,Object>> triggers(FinanceConfigurationIntegrationTest f){return f.db.queryForList("""
        SELECT TRIGGER_NAME,ACTION_TIMING,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_STATEMENT,ACTION_ORIENTATION,ACTION_CONDITION
        FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME
        """);}
    private static Map<String,Object> tableMetadata(FinanceConfigurationIntegrationTest f){return f.db.queryForMap("SELECT TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",TABLE);}
    private static Map<String,Object> delta(Map<String,Object> before,Map<String,Object> after){return Map.of("tableRowsEstimate",number(after.get("TABLE_ROWS"))-number(before.get("TABLE_ROWS")),"dataBytes",number(after.get("DATA_LENGTH"))-number(before.get("DATA_LENGTH")),"indexBytes",number(after.get("INDEX_LENGTH"))-number(before.get("INDEX_LENGTH")));}
    private static Map<String,Object> sourceState(FinanceConfigurationIntegrationTest f){
        var result=new LinkedHashMap<String,Object>();
        result.put("sentinels",f.db.queryForList("""
            SELECT import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,matched_shop_id,match_status,amount_cny
            FROM finance_label_invoice_rows WHERE (import_id=? AND source_row IN (1,4,7,9)) OR (import_id=? AND source_row IN (1,10000)) ORDER BY import_id,source_row
            """,NEW,HEAVY.getFirst()));
        result.put("totals",f.db.queryForList("""
            SELECT import_id,match_status,COUNT(*) records,SUM(amount_cny) amount FROM finance_label_invoice_rows
            WHERE import_id IN (?,?,?,?,?,?) GROUP BY import_id,match_status ORDER BY import_id,match_status
            """,NEW,MIDDLE,OLD,HEAVY.get(0),HEAVY.get(1),HEAVY.get(2)));
        return result;
    }
    private static List<Map<String,Object>> history(FinanceConfigurationIntegrationTest f){return f.db.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");}

    @SuppressWarnings("unchecked") private static Plan plan(FinanceConfigurationIntegrationTest f,CountQuery count){
        var bounded=new JdbcTemplate(f.dataSource);bounded.setQueryTimeout(30);
        String json=bounded.queryForObject("EXPLAIN FORMAT=JSON "+count.sql(),String.class,count.args().toArray());var tree=JSON.readValue(json,Map.class);
        var actual=bounded.queryForList("EXPLAIN ANALYZE "+count.sql(),count.args().toArray());String analyze=actual.stream().flatMap(row->row.values().stream()).map(String::valueOf).reduce("",(a,b)->a+b+"\n");
        return new Plan(tree,table(tree),analyze);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> jsonTable(FinanceConfigurationIntegrationTest f,CountQuery count){String json=f.db.queryForObject("EXPLAIN FORMAT=JSON "+count.sql(),String.class,count.args().toArray());return table(JSON.readValue(json,Map.class));}
    private static Map<String,Object> table(Object value){if(value instanceof Map<?,?> raw){var map=map(raw);if("r".equals(map.get("table_name")))return map;for(Object child:map.values()){var found=table(child);if(!found.isEmpty())return found;}}else if(value instanceof List<?> list)for(Object child:list){var found=table(child);if(!found.isEmpty())return found;}return Map.of();}
    private static double millis(long started){return (System.nanoTime()-started)/1_000_000.0;}

    private record OracleCase(String name,Filter filter,Map<String,String> query,long total,List<String> ids){}
    private record Observation(Map<String,Object> page,String sourceHash){}
    private record CountQuery(String sql,List<Object> args,long result,double millis){Map<String,Object> receipt(){return Map.of("sql",sql,"args",args,"result",result,"millis",millis);}}
    private record Plan(Map<String,Object> json,Map<String,Object> table,String analyze){Map<String,Object> receipt(){return Map.of("json",json,"table",table,"analyze",analyze);}}
    private record WriteObservation(boolean indexed,int inserted,int updated,double insertCommitMillis,double updateCommitMillis,Map<String,Object> metadataBefore,Map<String,Object> metadataAfter,Map<String,Object> metadataDelta){Map<String,Object> receipt(){return Map.of("indexed",indexed,"inserted",inserted,"updated",updated,"insertCommitMillis",insertCommitMillis,"updateCommitMillis",updateCommitMillis,"metadataBefore",metadataBefore,"metadataAfter",metadataAfter,"metadataDelta",metadataDelta);}}
    private static final class TracedJdbc extends JdbcTemplate{
        final List<CountQuery> counts=new ArrayList<>();TracedJdbc(JdbcTemplate delegate){super(Objects.requireNonNull(delegate.getDataSource()));setQueryTimeout(30);}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){long started=System.nanoTime();T result=super.queryForObject(sql,type,args);if(sql.startsWith("SELECT COUNT(*) FROM finance_label_invoice_rows r"))counts.add(new CountQuery(sql,List.copyOf(Arrays.asList(args.clone())),number(result),millis(started)));return result;}
    }
}
