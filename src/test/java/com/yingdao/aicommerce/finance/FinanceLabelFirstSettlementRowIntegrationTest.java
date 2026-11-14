package com.yingdao.aicommerce.finance;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production label-period aggregate against disposable MySQL. */
class FinanceLabelFirstSettlementRowIntegrationTest extends FinanceConfigurationIntegrationTest {
    static final LocalDate OCTOBER_1=LocalDate.of(2026,10,1),OCTOBER_31=LocalDate.of(2026,10,31);
    String groupA,groupB,shopA,shopB,removed,foreign;
    TracedJdbc traced;FinanceRepository tracedRepo;FinanceLabelCosts labels;

    @Test void offRouteNeverNeedsFactTables() {
        fixture();
        var result=labels.workspace(owner,filter(OCTOBER_1,OCTOBER_31,"","","",""),Map.of());
        assertNotNull(result.get("period"));
        assertNotNull(result.get("files"));
        assertFalse(traced.sql.stream().anyMatch(sql->sql.contains("finance_first_settlement_")),
            "The compatible three-argument constructor must execute OFF without the prototype schema");
    }

    @Test void productionPeriodSqlSelectsOneIndexedFirstSettlementRow() {
        fixture();

        var page=labels.workspace(owner,filter(OCTOBER_1,OCTOBER_31,"","","",""),Map.of());

        assertPeriod(page,11,"59.234566",3,14);
        var query=traced.onlyPeriod();
        assertTrue(query.sql().contains("JOIN LATERAL"),"Each published label order must select one settlement row before report filters");
        assertFalse(query.sql().contains("finance_records earlier"),"The correlated anti-earlier lookup must be removed from this aggregate");
        assertEquals(1,occurrences(query.sql(),"finance_records"),"The aggregate must have one settlement-record source");
        assertTrue(query.plan().contains("idx_finance_first_settlement"),"The first-row selection must use its ordered covering index");
        assertTrue(query.plan().contains("r0"));
        assertFalse(query.plan().contains("earlier"));
    }

    @Test void firstSettlementGuardsAndFiltersMatchTheIndependentOriginalQuery() {
        fixture();
        var cases=List.of(
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","","",""),11,"59.234566",3,14),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","","PHP",""),9,"39.234566",3,12),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","","USD",""),2,"20.000000",0,2),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","TIKTOK_SHOP","",""),2,"9.000000",0,2),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","SHOPEE","",""),9,"50.234566",3,12),
            new Expected(filter(OCTOBER_1,OCTOBER_31,shopA,"","",""),10,"51.234566",3,13),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","","",groupA),10,"51.234566",3,13),
            new Expected(filter(OCTOBER_1,OCTOBER_31,"","","",groupB),1,"8.000000",0,1),
            new Expected(filter(OCTOBER_1,LocalDate.of(2026,10,15),"","","",""),10,"55.234566",3,13)
        );

        tracedRepo.readSnapshot(()->{
            for(var expected:cases){
                var page=labels.workspace(owner,expected.filter(),Map.of());
                assertPeriod(page,expected.orders(),expected.amount(),expected.conflicts(),expected.shipments());
                assertPeriodEquals(originalPeriod(expected.filter()),map(page.get("period")));
            }
            return null;
        });
    }

    @Test void realWorkspacePageKeepsSourceFilesAndOrganizationIsolation() {
        fixture();

        var page=labels.workspace(owner,filter(OCTOBER_1,OCTOBER_31,"","","",""),Map.of());

        assertEquals(1,number(page.get("total")),"Invoice source rows do not inherit the settlement period");
        assertEquals("source-order",rows(page.get("rows")).getFirst().get("order_id"));
        assertEquals(List.of("owner.xlsx"),rows(page.get("files")).stream().map(row->text(row.get("source_name"))).toList());
        assertEquals(false,page.get("processing"));
        assertPeriod(page,11,"59.234566",3,14);
        var otherPage=labels.workspace(other,filter(OCTOBER_1,OCTOBER_31,"","","",""),Map.of());
        assertEquals(1,number(otherPage.get("total")));
        assertEquals("foreign-source",rows(otherPage.get("rows")).getFirst().get("order_id"));
        assertPeriod(otherPage,1,"16.000000",0,1);
    }

    void fixture(){
        groupA=service.saveGroup(owner,null,"首笔结算 A");groupB=service.saveGroup(owner,null,"首笔结算 B");
        shopA=groupedShop(owner,"first-row-a",groupA);shopB=groupedShop(owner,"first-row-b",groupB);
        removed=groupedShop(owner,"first-row-removed",groupA);
        String foreignGroup=service.saveGroup(other,null,"他司首笔结算");foreign=groupedShop(other,"first-row-foreign",foreignGroup);
        db.update("UPDATE finance_connections SET status='REMOVED' WHERE id=?",removed);
        db.update("INSERT INTO finance_label_state(organization_id,active_generation) VALUES('org','active'),('other-org','foreign-active')");

        total("org","active",shopA,"same-day","1.234567",2,3);
        settlement("same-day-a",owner,shopA,"same-day",LocalDate.of(2026,10,2),"SHOPEE","结算","SETTLED","API","PHP","same-day");
        settlement("same-day-z",owner,shopA,"same-day",LocalDate.of(2026,10,2),"SHOPEE","结算","SETTLED","API","PHP","same-day");
        total("org","active",shopA,"zero","0",0,1);settled("zero",shopA,LocalDate.of(2026,10,3));
        total("org","active",shopA,"negative","-2.000001",1,2);settled("negative",shopA,LocalDate.of(2026,10,4));

        total("org","active",shopA,"tiktok-order","5",0,1);
        settlement("tiktok-invalid",owner,shopA,"tiktok-order",LocalDate.of(2026,9,30),"TIKTOK_SHOP","FEE","SETTLED","API","PHP","invalid");
        settlement("tiktok-valid",owner,shopA,"tiktok-order",LocalDate.of(2026,10,5),"TIKTOK_SHOP","ORDER","SETTLED","API","PHP","valid");
        total("org","active",shopA,"non-api","6",0,1);
        settlement("non-api-invalid",owner,shopA,"non-api",LocalDate.of(2026,9,29),"SHOPEE","结算","SETTLED","IMPORT","PHP","invalid");
        settled("non-api",shopA,LocalDate.of(2026,10,6));
        total("org","active",shopA,"unsettled","7",0,1);
        settlement("unsettled-invalid",owner,shopA,"unsettled",LocalDate.of(2026,9,28),"SHOPEE","结算","PENDING","API","PHP","invalid");
        settled("unsettled",shopA,LocalDate.of(2026,10,7));

        total("org","active",shopB,"other-group","8",0,1);settled("other-group",shopB,LocalDate.of(2026,10,8));
        total("org","active",shopA,"currency-after","9",0,1);
        settlement("currency-first",owner,shopA,"currency-after",LocalDate.of(2026,10,9),"SHOPEE","结算","SETTLED","API","USD","first");
        settlement("currency-later",owner,shopA,"currency-after",LocalDate.of(2026,10,10),"SHOPEE","结算","SETTLED","API","PHP","later");
        total("org","active",shopA,"platform-after","10",0,1);
        settlement("platform-first",owner,shopA,"platform-after",LocalDate.of(2026,10,11),"SHOPEE","结算","SETTLED","API","PHP","first");
        settlement("platform-later",owner,shopA,"platform-after",LocalDate.of(2026,10,12),"TIKTOK_SHOP","ORDER","SETTLED","API","PHP","later");
        total("org","active",shopA,"same-id-currency","11",0,1);
        settlement("same-id-a",owner,shopA,"same-id-currency",LocalDate.of(2026,10,13),"SHOPEE","结算","SETTLED","API","USD","first");
        settlement("same-id-z",owner,shopA,"same-id-currency",LocalDate.of(2026,10,13),"SHOPEE","结算","SETTLED","API","PHP","later");

        total("org","active",shopA,"outside","12",0,1);
        settlement("outside-first",owner,shopA,"outside",LocalDate.of(2026,9,20),"SHOPEE","结算","SETTLED","API","PHP","first");
        settlement("outside-later",owner,shopA,"outside",LocalDate.of(2026,10,14),"SHOPEE","结算","SETTLED","API","PHP","later");
        total("org","active",shopA,"marketing","13",0,1);settled("marketing",shopA,LocalDate.of(2026,10,14));
        db.update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES('org',?,'marketing',1,1)",shopA);
        total("org","active",shopA,"missing",14,0,1);
        total("org","active",removed,"removed",15,0,1);settled("removed",removed,LocalDate.of(2026,10,14));
        total("org","retired",shopA,"retired",17,0,1);settled("retired",shopA,LocalDate.of(2026,10,14));

        total("org","active",shopA,"corrected",4,0,1);
        db.update("INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by) VALUES('org',?,'corrected','corrected-bill','2026-10-01','USER_CONFIRMED_MONTH','test','u')",shopA);
        settlement("corrected-row",owner,shopA,"corrected",OCTOBER_1,"TIKTOK_SHOP","ORDER","SETTLED","API","PHP","corrected-bill",
            Map.of("_finance_reporting_month","2026-10","_finance_original_settlement_date","2026-09-30"));

        total("other-org","foreign-active",foreign,"foreign",16,0,1);
        settlement("foreign-row",other,foreign,"foreign",LocalDate.of(2026,10,15),"SHOPEE","结算","SETTLED","API","PHP","foreign");
        source("owner-file","org",shopA,"source-order","owner.xlsx",LocalDate.of(2026,8,1));
        source("foreign-file","other-org",foreign,"foreign-source","foreign.xlsx",LocalDate.of(2026,8,1));

        traced=new TracedJdbc(db);tracedRepo=new FinanceRepository(traced,new ObjectMapper());
        labels=new FinanceLabelCosts(tracedRepo,null,Path.of("target","label-first-row").toString());
    }

    Filter filter(LocalDate start,LocalDate end,String shop,String platform,String currency,String group){
        return new Filter(start,end,shop,platform,currency,"day",group);
    }
    void total(String org,String generation,String shop,String order,Object amount,int pending,int shipments){
        db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES(?,?,?,?,?,?,?)",
            org,generation,shop,order,decimal(amount),pending,shipments);
    }
    void settled(String order,String shop,LocalDate day){
        settlement("settled-"+order,owner,shop,order,day,"SHOPEE","结算","SETTLED","API","PHP",order);
    }
    void settlement(String id,Scope scope,String shop,String order,LocalDate day,String platform,String category,String status,String source,String currency,String statement){
        settlement(id,scope,shop,order,day,platform,category,status,source,currency,statement,Map.of());
    }
    void settlement(String id,Scope scope,String shop,String order,LocalDate day,String platform,String category,String status,String source,String currency,String statement,Map<String,Object> raw){
        db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,source,raw_json)
            VALUES(?,?,?,?,?,'SETTLEMENT',?,?,?,?,?,?,?,?,?,?)
            """,id,scope.organizationId(),scope.userId(),shop,platform,id,order,statement,category,status,day,currency,decimal("1"),source,repo.write(raw));
    }
    void source(String id,String org,String shop,String order,String name,LocalDate day){
        db.update("""
            INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,row_count,matched_count,amount_cny,created_by)
            VALUES(?,?,?,?,'',0,'READY',1,1,2,'u')
            """,id,org,name,String.format("%064d",id.hashCode()).replace('-','0'));
        db.update("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_checked)
            VALUES(?,1,?,?,?,?,?,'shop','customer','warehouse','document',?,2,?,'MATCHED',TRUE)
            """,id,org,"event-"+id,"row-"+id,"bucket-"+id,order,day,shop);
    }
    Map<String,Object> originalPeriod(Filter filter){
        var q=tracedRepo.query(owner,filter,"r");
        return traced.queryForMap("""
            SELECT COUNT(*) orders,COALESCE(SUM(lc.amount_cny),0) amount_cny,
              COALESCE(SUM(lc.pending_count),0) conflicts,COALESCE(SUM(lc.shipment_count),0) shipments
            FROM finance_label_state ls JOIN finance_label_order_totals lc
              ON lc.organization_id=ls.organization_id AND lc.generation=ls.active_generation
            JOIN finance_records r FORCE INDEX(idx_finance_settled_order)
              ON r.organization_id=lc.organization_id AND r.shop_id=lc.shop_id AND r.order_id=lc.order_id
            WHERE
            """+FinanceCostReport.firstSettlement("r",true)+" AND "+q.where(),q.args().toArray());
    }
    void assertPeriod(Object page,long orders,String amount,long conflicts,long shipments){
        var period=map(map(page).get("period"));
        assertEquals(orders,number(period.get("orders")));
        assertEquals(0,decimal(amount).compareTo(decimal(period.get("amount_cny"))));
        assertEquals(conflicts,number(period.get("conflicts")));
        assertEquals(shipments,number(period.get("shipments")));
    }
    void assertPeriodEquals(Map<String,Object> expected,Map<String,Object> actual){
        assertEquals(number(expected.get("orders")),number(actual.get("orders")));
        assertEquals(0,decimal(expected.get("amount_cny")).compareTo(decimal(actual.get("amount_cny"))));
        assertEquals(number(expected.get("conflicts")),number(actual.get("conflicts")));
        assertEquals(number(expected.get("shipments")),number(actual.get("shipments")));
    }
    static int occurrences(String text,String needle){
        int count=0,offset=0;while((offset=text.indexOf(needle,offset))>=0){count++;offset+=needle.length();}return count;
    }

    record Expected(Filter filter,long orders,String amount,long conflicts,long shipments) {}
    record PeriodQuery(String sql,List<Object> args,String plan) {}
    static final class TracedJdbc extends JdbcTemplate {
        final List<PeriodQuery> periods=new ArrayList<>();
        final List<String> sql=new ArrayList<>();
        TracedJdbc(JdbcTemplate delegate){super(Objects.requireNonNull(delegate.getDataSource()));}
        @Override public List<Map<String,Object>> queryForList(String statement,Object...args){
            sql.add(statement);return super.queryForList(statement,args);
        }
        @Override public <T> T queryForObject(String statement,Class<T> type,Object...args){
            sql.add(statement);return super.queryForObject(statement,type,args);
        }
        @Override public Map<String,Object> queryForMap(String sql,Object...args){
            this.sql.add(sql);
            if(sql.contains("SUM(lc.amount_cny)")&&sql.contains("finance_label_order_totals")){
                String plan=super.queryForObject("EXPLAIN FORMAT=JSON "+sql,String.class,args);
                periods.add(new PeriodQuery(sql,List.copyOf(Arrays.asList(args.clone())),plan));
            }
            return super.queryForMap(sql,args);
        }
        PeriodQuery onlyPeriod(){assertEquals(1,periods.size(),"Expected one production label-period query");return periods.getFirst();}
    }
}
