package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the disposable FINANCE_QA_JDBC runner may execute these plan and visibility checks. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceVisibilityAccessPathIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();
    final Scope colleague=new Scope("org","colleague");
    final Filter month=new Filter(LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),"","","","day");

    @BeforeAll void schema()throws Exception{fixture.migrate();}
    @AfterAll void close(){fixture.closeDataSource();}
    @BeforeEach void setup(){fixture.setup();}

    @Test void typedCountLooksUpEachVisibleShopByPrimaryKey(){
        var connections=new ArrayList<Object[]>();
        var records=new ArrayList<Object[]>();
        for(int shop=0;shop<96;shop++){
            String id="plan-shop-"+shop;
            connections.add(connection(id,"org","u",null,"ACTIVE"));
            for(int record=0;record<12;record++)records.add(record("plan-record-"+shop+"-"+record,"org","u",id,
                    "TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,15),"{}"));
        }
        insertConnections(connections);insertRecords(records);
        fixture.db.execute("ANALYZE TABLE finance_connections,finance_records");
        var filter=new Filter(month.start(),month.end(),"","TIKTOK_SHOP","PHP","day");
        var query=fixture.repo.query(fixture.owner,filter,"",true,true);

        var plan=fixture.db.queryForList("EXPLAIN SELECT COUNT(*) FROM finance_records FORCE INDEX(idx_finance_company_kind_date) WHERE "
                +query.where()+" AND kind='FEE'",query.args().toArray());
        var outer=plan.stream().filter(row->"finance_records".equals(text(row.get("table")))).findFirst().orElseThrow();
        var visibility=plan.stream().filter(row->"fc".equals(text(row.get("table")))).findFirst().orElseThrow();

        assertEquals("idx_finance_company_kind_date",text(outer.get("key")));
        assertEquals("PRIMARY",text(visibility.get("key")),plan.toString());
        assertEquals("eq_ref",text(visibility.get("type")),plan.toString());
        assertTrue(number(visibility.get("rows"))<=1,"Visibility lookup must estimate at most one shop row: "+plan);
        assertEquals(1152L,fixture.db.queryForObject("SELECT COUNT(*) FROM finance_records FORCE INDEX(idx_finance_company_kind_date) WHERE "
                +query.where()+" AND kind='FEE'",Long.class,query.args().toArray()));
    }

    @Test void visibilityAndEveryExistingFilterBoundaryKeepTheirExactRows(){
        fixture.db.update("INSERT INTO finance_groups(id,organization_id,name) VALUES('group-a','org','Group A'),('group-b','org','Group B'),('foreign-group','other-org','Foreign')");
        insertConnections(List.of(
                connection("active-a","org","u","group-a","ACTIVE"),
                connection("colleague-a","org","colleague","group-a","ACTIVE"),
                connection("active-b","org","u","group-b","ACTIVE"),
                connection("ungrouped","org","u",null,"ACTIVE"),
                connection("removed-a","org","u","group-a","REMOVED"),
                connection("foreign-active","other-org","other","foreign-group","ACTIVE")));
        insertRecords(List.of(
                record("active","org","u","active-a","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("colleague","org","colleague","colleague-a","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("other-group","org","u","active-b","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("ungrouped-row","org","u","ungrouped","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("removed","org","u","removed-a","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("unassigned","org","u","UNASSIGNED","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("cross-company-shop","org","u","foreign-active","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("date-pending","org","u","active-a","TIKTOK_SHOP","FEE","DATE_PENDING","PHP",LocalDate.of(2026,10,10),"{}"),
                record("other-platform","org","u","active-a","SHOPEE","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("other-currency","org","u","active-a","TIKTOK_SHOP","FEE","SETTLED","USD",LocalDate.of(2026,10,10),"{}"),
                record("corrected","org","u","active-a","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{\"_finance_reporting_month\":\"2026-10\"}"),
                record("foreign-own","other-org","other","foreign-active","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}"),
                record("foreign-on-owner-shop","other-org","other","active-a","TIKTOK_SHOP","FEE","SETTLED","PHP",LocalDate.of(2026,10,10),"{}")));
        fixture.db.update("""
            INSERT INTO finance_reporting_months(organization_id,shop_id,order_id,statement_id,reporting_month,source,reason,confirmed_by)
            VALUES('org','active-a','order-corrected','statement-corrected','2026-10-01','USER_CONFIRMED_MONTH','test boundary','u')
            """);

        var all=List.of("active","colleague","corrected","other-currency","other-group","other-platform","unassigned","ungrouped-row");
        assertEquals(all,ids(fixture.owner,month,""));
        assertEquals(all,ids(fixture.owner,month,"r"));
        assertEquals(all,ids(colleague,month,"r"),"Finance visibility remains company-scoped rather than credential-owner scoped");
        assertEquals(List.of("foreign-own"),ids(fixture.other,month,"r"));

        var typed=new Filter(month.start(),month.end(),"","TIKTOK_SHOP","PHP","day");
        assertEquals(List.of("active","colleague","corrected","other-group","unassigned","ungrouped-row"),ids(fixture.owner,typed,"r"));
        assertEquals(List.of("active","corrected"),ids(fixture.owner,new Filter(month.start(),month.end(),"active-a","TIKTOK_SHOP","PHP","day"),"r"));
        assertEquals(List.of("active","colleague","corrected"),ids(fixture.owner,new Filter(month.start(),month.end(),"","TIKTOK_SHOP","PHP","day","group-a"),"r"));
        assertEquals(List.of("other-group"),ids(fixture.owner,new Filter(month.start(),month.end(),"","TIKTOK_SHOP","PHP","day","group-b"),"r"));
        assertEquals(List.of("unassigned","ungrouped-row"),ids(fixture.owner,new Filter(month.start(),month.end(),"","TIKTOK_SHOP","PHP","day","UNGROUPED"),"r"));
        assertEquals(List.of("active","colleague","other-group","unassigned","ungrouped-row"),ids(fixture.owner,
                new Filter(month.start(),LocalDate.of(2026,10,21),"","TIKTOK_SHOP","PHP","day"),"r"),
                "A partial month must keep excluding explicitly month-corrected rows");
    }

    private List<String> ids(Scope scope,Filter filter,String alias){
        var query=fixture.repo.query(scope,filter,alias,true,true);
        String record=alias.isEmpty()?"finance_records":alias;
        String source=alias.isEmpty()?"finance_records":"finance_records "+alias;
        return fixture.db.queryForList("SELECT "+record+".id FROM "+source+" WHERE "+query.where()+" ORDER BY "+record+".id",
                String.class,query.args().toArray());
    }

    private void insertConnections(List<Object[]> rows){fixture.db.batchUpdate("""
        INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,status,enabled,currency,timezone,
          token_group_id,access_token_encrypted,scopes_json,group_id)
        VALUES(?,?,?,'TIKTOK_SHOP',?,?,?,TRUE,'PHP','Asia/Manila',?,'test','[]',?)
        """,rows);}
    private Object[] connection(String id,String organization,String user,String group,String status){
        return new Object[]{id,organization,user,"external-"+id,"Shop "+id,status,"token-"+id,group};
    }
    private void insertRecords(List<Object[]> rows){fixture.db.batchUpdate("""
        INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,
          record_date,currency,amount,source,raw_json)
        VALUES(?,?,?,?,?,?,?,CONCAT('order-',?),CONCAT('statement-',?),'PLATFORM_FEE',?,?,?,1,'API',?)
        """,rows);}
    private Object[] record(String id,String organization,String user,String shop,String platform,String kind,String status,String currency,LocalDate day,String raw){
        return new Object[]{id,organization,user,shop,platform,kind,id,id,id,status,Date.valueOf(day),currency,raw};
    }
}
