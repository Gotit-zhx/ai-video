package com.yingdao.aicommerce.finance;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production source-page count and its access plan against disposable MySQL. */
class FinanceLabelSourceCountPathIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final Filter ALL=new Filter(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"","","","day","");

    @Test void unfilteredProductionCountUsesExistingCoveringFileEventIndex() {
        String file=addImport("wide-owner-file","org","READY","2026-10-03 10:00:00");
        addRows(file,"org",1,1200,n->"wide-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,8,10),true);
        db.execute("ANALYZE TABLE finance_label_invoice_rows");

        var traced=new TracedJdbc(db);var labels=labels(traced);
        var page=labels.sourcePage(owner,ALL,Map.of());
        assertEquals(1200,number(page.get("total")));
        assertEquals(50,rows(page.get("rows")).size());
        var count=traced.onlyCount();
        assertEquals("uk_label_file_event",count.key(),"The emitted production COUNT must use the narrower existing index");
        assertTrue(count.usingIndex(),"The unfiltered COUNT must stay covered by the selected secondary index");
        assertEquals(List.of(file),count.args());
        System.out.println("labelSourceCountPlan key="+count.key()+" usingIndex="+count.usingIndex()+" rows="+page.get("total"));

        for(String numbers:List.of("","  \t ")){
            traced.clear();
            assertEquals(1200,number(labels.sourcePage(owner,ALL,Map.of("numbers",numbers)).get("total")));
            assertEquals("uk_label_file_event",traced.onlyCount().key());
        }
        traced.clear();
        assertEquals(1200,number(labels.sourcePage(owner,ALL,Map.of("matchStatus","BILLING_FAILED")).get("total")));
        assertEquals("uk_label_file_event",traced.onlyCount().key(),"The retired status normalizes before count-path selection");

        traced.clear();
        assertEquals(1,number(labels.sourcePage(owner,ALL,Map.of("numbers","wide-1199")).get("total")));
        var searched=traced.onlyCount();
        assertFalse(searched.sql().contains("FORCE INDEX(uk_label_file_event)"),"A real number predicate must keep the existing filtered COUNT");
        assertEquals(List.of(file,"wide-1199"),searched.args());
    }

    @Test void exactPaginationAndFileAuthorizationRemainAcrossSources() {
        String older=addImport("owner-older","org","READY","2026-10-01 10:00:00");
        String middle=addImport("owner-middle","org","PREPARED","2026-10-02 10:00:00");
        String newer=addImport("owner-newer","org","READY","2026-10-03 10:00:00");
        String foreign=addImport("foreign-file","other-org","READY","2026-10-04 10:00:00");
        String removed=addImport("removed-file","org","REMOVED","2026-10-05 10:00:00");
        String expired=addImport("expired-file","org","EXPIRED","2026-10-06 10:00:00");
        addRows(older,"org",1,10,n->"old-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,7,1),false);
        addRows(middle,"org",1,30,n->"middle-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,8,1),false);
        addRows(newer,"org",1,30,n->"new-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,9,1),false);
        addRows(foreign,"other-org",1,3,n->"foreign-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,9,1),false);
        addRows(removed,"org",1,3,n->"removed-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,9,1),false);
        addRows(expired,"org",1,3,n->"expired-"+n,n->null,n->"UNMATCHED",LocalDate.of(2026,9,1),false);

        var labels=labels(new TracedJdbc(db));
        var first=labels.sourcePage(owner,ALL,Map.of());
        assertEquals(70,number(first.get("total")));
        assertEquals(50,rows(first.get("rows")).size());
        assertEquals("new-0",rows(first.get("rows")).getFirst().get("order_id"));
        assertEquals("middle-19",rows(first.get("rows")).getLast().get("order_id"));
        var second=labels.sourcePage(owner,ALL,Map.of("page","2"));
        assertEquals(70,number(second.get("total")));
        assertEquals(20,rows(second.get("rows")).size());
        assertEquals("middle-20",rows(second.get("rows")).getFirst().get("order_id"));
        assertEquals("old-9",rows(second.get("rows")).getLast().get("order_id"));
        var one=labels.sourcePage(owner,ALL,Map.of("fileId",middle));
        assertEquals(30,number(one.get("total")));
        assertEquals("middle-0",rows(one.get("rows")).getFirst().get("order_id"));
        assertEquals(0,number(labels.sourcePage(owner,ALL,Map.of("fileId",foreign)).get("total")));
        assertEquals(0,number(labels.sourcePage(owner,ALL,Map.of("fileId",removed)).get("total")));
        assertEquals(0,number(labels.sourcePage(owner,ALL,Map.of("fileId",expired)).get("total")));
    }

    @Test void filteredCountsKeepPredicatesBindingsAndSettlementPeriodMeaning() {
        String groupA=service.saveGroup(owner,null,"A"),groupB=service.saveGroup(owner,null,"B");
        String shopA=groupedShop(owner,"count-a",groupA),shopB=groupedShop(owner,"count-b",groupB);
        String ungrouped=groupedShop(owner,"count-ungrouped",null),removed=groupedShop(owner,"count-removed",groupA);
        db.update("UPDATE finance_connections SET platform='TIKTOK_SHOP' WHERE id=?",shopB);
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        String file=addImport("filter-owner-file","org","READY","2026-10-03 10:00:00");
        addRow(file,"org",1,"order-a",shopA,"MATCHED",LocalDate.of(2026,10,7),false);
        addRow(file,"org",2,"order-review",shopB,"REVIEW",LocalDate.of(2026,10,7),false);
        addRow(file,"org",3,"order-ungrouped",ungrouped,"MATCHED",LocalDate.of(2026,10,7),false);
        addRow(file,"org",4,"order-unbound",null,"UNMATCHED",LocalDate.of(2026,10,7),false);
        addRow(file,"org",5,"order-removed",removed,"MATCHED",LocalDate.of(2026,10,7),false);
        addRow(file,"org",6,"order-conflict",shopA,"MATCHED",LocalDate.of(2026,10,7),false);
        db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES('org','active')");
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','active',?,'order-conflict',7,1,1)",shopA);
        assertEquals(4,new HashSet<>(List.of(shopA,shopB,ungrouped,removed)).size());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_label_invoice_rows WHERE matched_shop_id=?",Integer.class,shopA));

        var traced=new TracedJdbc(db);var labels=labels(traced);
        assertFiltered(labels,traced,ALL,Map.of(),5);
        assertFiltered(labels,traced,new Filter(ALL.start(),ALL.end(),shopA,"","","day",""),Map.of(),2);
        assertFiltered(labels,traced,new Filter(ALL.start(),ALL.end(),"","SHOPEE","","day",""),Map.of(),3);
        assertFiltered(labels,traced,new Filter(ALL.start(),ALL.end(),"","TIKTOK_SHOP","","day",""),Map.of(),1);
        assertFiltered(labels,traced,new Filter(ALL.start(),ALL.end(),"","","","day",groupA),Map.of(),2);
        assertFiltered(labels,traced,new Filter(ALL.start(),ALL.end(),"","","","day","UNGROUPED"),Map.of(),2);
        assertFiltered(labels,traced,ALL,Map.of("matchStatus","MATCHED"),3);
        assertFiltered(labels,traced,ALL,Map.of("matchStatus","REVIEW"),1);
        assertFiltered(labels,traced,ALL,Map.of("matchStatus","UNMATCHED"),1);
        assertFiltered(labels,traced,ALL,Map.of("matchStatus","CONFLICT"),1);
        traced.clear();
        assertEquals(1,number(labels.sourcePage(owner,ALL,Map.of("numbers","order-review")).get("total")));
        var searched=traced.onlyCount();
        assertFalse(searched.sql().contains("FORCE INDEX(uk_label_file_event)"));
        assertEquals(List.of(file,removed,"order-review"),searched.args(),"Removed-shop authorization precedes number bindings");
        assertFiltered(labels,traced,ALL,Map.of("numbers"," \t "),5);
        assertFiltered(labels,traced,ALL,Map.of("matchStatus","BILLING_FAILED"),5);

        db.update("UPDATE finance_label_order_totals SET pending_count=0 WHERE order_id='order-conflict'");
        db.update("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,status,record_date,currency,amount,source,raw_json) VALUES('settled-label','org','u',?,'SHOPEE','SETTLEMENT','settled-label','order-conflict','SETTLED','2026-09-12','PHP',10,'API','{}')",shopA);
        var september=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day","");
        var august=new Filter(LocalDate.of(2026,8,1),LocalDate.of(2026,8,31),"","","","day","");
        var septemberWorkspace=labels.workspace(owner,september,Map.of());
        assertEquals(5,number(septemberWorkspace.get("total")),"Source rows do not use the report date filter");
        assertEquals(1,number(map(septemberWorkspace.get("period")).get("orders")));
        assertEquals(0,new java.math.BigDecimal("7").compareTo(decimal(map(septemberWorkspace.get("period")).get("amount_cny"))));
        var augustWorkspace=labels.workspace(owner,august,Map.of());
        assertEquals(5,number(augustWorkspace.get("total")));
        assertEquals(0,number(map(augustWorkspace.get("period")).get("orders")),"Period follows first settlement, not invoice transaction_date");
    }

    private FinanceLabelCosts labels(TracedJdbc jdbc){return new FinanceLabelCosts(new FinanceRepository(jdbc,new ObjectMapper()),null,Path.of("target","label-count-path").toString());}
    private void assertFiltered(FinanceLabelCosts labels,TracedJdbc traced,Filter selected,Map<String,String> query,long expected){
        traced.clear();
        long actual=number(labels.sourcePage(owner,selected,query).get("total"));
        var count=traced.onlyCount();
        assertEquals(expected,actual,()->count.sql()+" args="+count.args());
        assertFalse(count.sql().contains("FORCE INDEX(uk_label_file_event)"),"Filtered selections retain the prior COUNT SQL");
    }
    private String addImport(String id,String organization,String status,String created){
        db.update("""
            INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,created_by,created_at)
            VALUES(?,?,?,?,'',0,?,'u',?)
            """,id,organization,id+".xlsx",hash("content-"+id),status,created);
        return id;
    }
    private void addRows(String file,String organization,int first,int count,java.util.function.IntFunction<String> order,
                         java.util.function.IntFunction<String> shop,java.util.function.IntFunction<String> status,
                         LocalDate date,boolean wide){
        var values=new ArrayList<Object[]>();
        for(int n=0;n<count;n++)values.add(row(file,organization,first+n,order.apply(n),shop.apply(n),status.apply(n),date,wide));
        db.batchUpdate("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,
              order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,values);
    }
    private void addRow(String file,String organization,int sourceRow,String order,String shop,String status,LocalDate date,boolean wide){
        db.update("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,
              order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,row(file,organization,sourceRow,order,shop,status,date,wide));
    }
    private Object[] row(String file,String organization,int sourceRow,String order,String shop,String status,LocalDate date,boolean wide){
        String suffix=file+"-"+sourceRow;
        return new Object[]{file,sourceRow,organization,hash("event-"+suffix),hash("row-"+suffix),hash("bucket-"+suffix),order,
            wide?"shop-"+"x".repeat(180):"",wide?"customer-"+"c".repeat(100):"customer",
            wide?"warehouse-"+"w".repeat(95):"warehouse",wide?"document-"+"d".repeat(170):"document",
            date,java.math.BigDecimal.ONE,shop,status,wide?"note-"+"n".repeat(220):""};
    }

    record CountQuery(String sql,List<Object> args,String key,boolean usingIndex) {}
    static final class TracedJdbc extends JdbcTemplate {
        final List<CountQuery> counts=new ArrayList<>();
        TracedJdbc(JdbcTemplate delegate){super(Objects.requireNonNull(delegate.getDataSource()));}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){
            if(sql.startsWith("SELECT COUNT(*) FROM finance_label_invoice_rows r")){
                String json=super.queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class,args);
                var table=table(new ObjectMapper().readValue(json,Map.class));
                counts.add(new CountQuery(sql,List.copyOf(Arrays.asList(args.clone())),text(table.get("key")),Boolean.TRUE.equals(table.get("using_index"))));
            }
            return super.queryForObject(sql,type,args);
        }
        void clear(){counts.clear();}
        CountQuery onlyCount(){assertEquals(1,counts.size(),"Expected one authorized source COUNT");return counts.getFirst();}
        private static Map<String,Object> table(Object value){
            if(value instanceof Map<?,?> raw){
                var valueMap=map(raw);
                if("r".equals(valueMap.get("table_name")))return valueMap;
                for(Object child:valueMap.values()){var found=table(child);if(!found.isEmpty())return found;}
            }else if(value instanceof List<?> list)for(Object child:list){var found=table(child);if(!found.isEmpty())return found;}
            return Map.of();
        }
    }
}
