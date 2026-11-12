package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the emitted page query and its EXPLAIN in a disposable MySQL schema. */
class FinancePageReadIntegrationTest extends FinanceRecordPageIntegrationTest {
    @Test void typedPageUsesCoveringOrderedCandidateRead() {
        String shop=connect(owner,false);
        seed(shop,123);
        var measured=new MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        var result=reader.details(owner,filter,"FEE",1,50);
        assertEquals(123L,number(result.get("total")));
        assertEquals("fee-000",rows(result.get("items")).getFirst().get("id"));
        var plan=measured.pagePlan();
        assertFalse(hasTrue(plan,"using_filesort"),"The candidate ID page must stop in index order, not sort the matching ledger");
        assertTrue(hasTrue(plan,"using_index"),"Candidate IDs and filters must be covered without provider payload reads");
        String baseline="SELECT id,record_date FROM finance_records FORCE INDEX(idx_finance_company_kind_date) "
                +"WHERE organization_id=? AND kind='FEE' AND record_date BETWEEN ? AND ? ORDER BY record_date DESC,id LIMIT 50";
        var oldPlan=new ObjectMapper().readValue(db.queryForObject("EXPLAIN FORMAT=JSON "+baseline,String.class,
                owner.organizationId(),filter.start(),filter.end()),Map.class);
        System.out.println("Finance page index comparison: mysql="+db.queryForObject("SELECT VERSION()",String.class)
                +", aggregate16Filesort="+hasTrue(oldPlan,"using_filesort")+", page11Filesort="+hasTrue(plan,"using_filesort")
                +", page11Covering="+hasTrue(plan,"using_index"));
    }

    @Test void repeatableReadSnapshotReusesExactCountAcrossPages() {
        String shop=connect(owner,false);seed(shop,4);
        var measured=new MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        reader.readSnapshot(()->{
            var first=reader.details(owner,filter,"FEE",1,2);
            var second=reader.details(owner,filter,"FEE",2,2);
            assertEquals(List.of("fee-000","fee-001"),ids(first));
            assertEquals(List.of("fee-002","fee-003"),ids(second));
            assertEquals(4L,number(first.get("total")));
            assertEquals(4L,number(second.get("total")));
            return null;
        });
        assertEquals(1,measured.ledgerCounts,"The same predicate in one read snapshot has one exact count");
        seed(shop,1,"later-");
        assertEquals(5L,number(reader.details(owner,filter,"FEE",1,2).get("total")),"A new snapshot must see committed changes");
    }

    @Test void terminalPageDerivesExactTotalButEmptyLaterPageStillCounts() {
        String shop=connect(owner,false);seed(shop,3);
        var measured=new MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        var last=reader.details(owner,filter,"FEE",2,2);
        assertEquals(List.of("fee-002"),ids(last));
        assertEquals(3L,number(last.get("total")));
        assertEquals(0,measured.ledgerCounts,"A nonempty short page proves its exact total");
        var beyond=reader.details(owner,filter,"FEE",3,2);
        assertTrue(ids(beyond).isEmpty());
        assertEquals(3L,number(beyond.get("total")),"Empty out-of-range pages cannot infer offset as total");
        assertEquals(1,measured.ledgerCounts);
    }

    @Test void snapshotCountsRemainSeparatedByTenantShopAndCurrencyAndClearAfterFailure() {
        String shop=connect(owner,false);seed(shop,4);
        var measured=new MeasuredJdbc(db);var reader=new FinanceRepository(measured,new ObjectMapper());
        var absentCurrency=new Filter(filter.start(),filter.end(),"","","USD","day");
        var absentShop=new Filter(filter.start(),filter.end(),"another-shop","","PHP","day");
        assertThrows(IllegalStateException.class,()->reader.readSnapshot(()->{
            assertEquals(4L,number(reader.details(owner,filter,"FEE",1,2).get("total")));
            assertEquals(0L,number(reader.details(owner,absentCurrency,"FEE",1,2).get("total")));
            assertEquals(0L,number(reader.details(owner,absentShop,"FEE",1,2).get("total")));
            assertEquals(0L,number(reader.details(other,filter,"FEE",1,2).get("total")));
            throw new IllegalStateException("abort this read");
        }));
        seed(shop,1,"after-abort-");
        assertEquals(5L,number(reader.details(owner,filter,"FEE",1,2).get("total")));
    }

    @Test void reconciliationReadsOrderedBillIdsBeforePayloads() {
        String shop=connect(owner,false);seed(shop,123);
        db.update("UPDATE finance_records SET kind='BILL' WHERE organization_id=?",owner.organizationId());
        var measured=new MeasuredJdbc(db);var reader=new FinanceRepository(measured,new ObjectMapper());
        var result=reader.reconciliationPage(owner,filter,1,50);
        assertEquals(123L,number(result.get("total")));
        assertEquals("fee-000",rows(result.get("items")).getFirst().get("id"));
        var plan=measured.pagePlan();
        assertFalse(hasTrue(plan,"using_filesort"));
        assertTrue(hasTrue(plan,"using_index"));
    }

    private void seed(String shop,int count){seed(shop,count,"fee-");}
    private void seed(String shop,int count,String prefix){
        var values=new ArrayList<Object[]>();
        for(int n=0;n<count;n++)values.add(new Object[]{prefix+String.format("%03d",n),owner.organizationId(),owner.userId(),shop,prefix+n,filter.start()});
        db.batchUpdate("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,status,currency,amount,source,raw_json) "
                +"VALUES(?,?,?,?,'SHOPEE','FEE',?,?,'SETTLED','PHP',1,'API','{}')",values);
    }
    private static List<String> ids(Map<String,Object> page){return rows(page.get("items")).stream().map(r->text(r.get("id"))).toList();}
    private static boolean hasTrue(Object value,String key){
        if(value instanceof Map<?,?> m)return Boolean.TRUE.equals(m.get(key))||m.values().stream().anyMatch(v->hasTrue(v,key));
        return value instanceof List<?> list&&list.stream().anyMatch(v->hasTrue(v,key));
    }
    private static Map<String,Object> candidatePlan(Object value){
        if(value instanceof Map<?,?> m){
            if("page".equals(m.get("table_name")))return map(map(m.get("materialized_from_subquery")).get("query_block"));
            for(Object child:m.values()){var found=candidatePlan(child);if(!found.isEmpty())return found;}
        }else if(value instanceof List<?> list)for(Object child:list){var found=candidatePlan(child);if(!found.isEmpty())return found;}
        return Map.of();
    }
    static final class MeasuredJdbc extends JdbcTemplate {
        int ledgerCounts;String pageSql;Object[] pageArgs;
        MeasuredJdbc(JdbcTemplate delegate){super(Objects.requireNonNull(delegate.getDataSource()));}
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){
            if(sql.startsWith("SELECT COUNT(*) FROM finance_records"))ledgerCounts++;
            return super.queryForObject(sql,type,args);
        }
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
            if(sql.contains(") page")){pageSql=sql;pageArgs=args.clone();}
            return super.queryForList(sql,args);
        }
        Map<String,Object> pagePlan(){
            assertNotNull(pageSql,"A bounded candidate ID page must be read");
            var plan=new ObjectMapper().readValue(super.queryForObject("EXPLAIN FORMAT=JSON "+pageSql,String.class,pageArgs),Map.class);
            var candidate=candidatePlan(plan);assertFalse(candidate.isEmpty(),"The page must be materialized before payload joins");return candidate;
        }
    }
}
