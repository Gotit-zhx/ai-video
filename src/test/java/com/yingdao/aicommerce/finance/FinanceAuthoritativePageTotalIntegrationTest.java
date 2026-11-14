package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only a clean projection read in the same MVCC snapshot can supply a page count. */
class FinanceAuthoritativePageTotalIntegrationTest extends FinanceConfigurationIntegrationTest {
    @Test void fullPageUsesAuthoritativeTotalForOnePageOnly(){
        seed("FEE",4);seed("BILL",2);
        var measured=new FinancePageReadIntegrationTest.MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        reader.readSnapshot(()->reader.withAuthoritativePageTotal(4,()->{
            var fees=reader.details(owner,filter,"FEE",1,2);
            assertEquals(4L,number(fees.get("total")));
            assertEquals(List.of("FEE-0","FEE-1"),ids(fees));
            assertEquals(0,measured.ledgerCounts,"Full page must use its verified projection count");
            assertEquals(2L,number(reader.reconciliationPage(owner,filter,1,2).get("total")),"Supplied total belongs to one page only");
            assertEquals(1,measured.ledgerCounts);
            return null;
        }));
    }

    @Test void rejectsTotalsContradictingObservedPageBounds(){
        seed("FEE",3);
        var absent=new Filter(filter.start(),filter.end(),"absent","","PHP","day");
        assertThrows(IllegalStateException.class,()->repo.readSnapshot(()->repo.withAuthoritativePageTotal(1,
                ()->repo.details(owner,filter,"FEE",1,2))));
        assertThrows(IllegalStateException.class,()->repo.readSnapshot(()->repo.withAuthoritativePageTotal(4,
                ()->repo.details(owner,filter,"FEE",2,2))));
        assertThrows(IllegalStateException.class,()->repo.readSnapshot(()->repo.withAuthoritativePageTotal(1,
                ()->repo.details(owner,absent,"FEE",1,2))));
        assertThrows(IllegalStateException.class,()->repo.readSnapshot(()->repo.withAuthoritativePageTotal(5,
                ()->repo.details(owner,filter,"FEE",3,2))));
    }

    @Test void acceptsExactTerminalAndOutOfRangePageTotals(){
        seed("FEE",3);
        var measured=new FinancePageReadIntegrationTest.MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        assertEquals(3L,number(reader.readSnapshot(()->reader.withAuthoritativePageTotal(3,
                ()->reader.details(owner,filter,"FEE",2,2))).get("total")));
        assertEquals(3L,number(reader.readSnapshot(()->reader.withAuthoritativePageTotal(3,
                ()->reader.details(owner,filter,"FEE",3,2))).get("total")));
        assertEquals(0,measured.ledgerCounts);
    }

    @Test void nestedScopesRestoreOuterTotalAndClearAfterException(){
        seed("FEE",4);seed("BILL",2);
        var measured=new FinancePageReadIntegrationTest.MeasuredJdbc(db);
        var reader=new FinanceRepository(measured,new ObjectMapper());
        reader.readSnapshot(()->reader.withAuthoritativePageTotal(4,()->{
            assertEquals(2L,number(reader.withAuthoritativePageTotal(2,
                    ()->reader.reconciliationPage(owner,filter,1,2)).get("total")));
            assertThrows(IllegalArgumentException.class,()->reader.withAuthoritativePageTotal(999,
                    ()->{throw new IllegalArgumentException("abort nested action");}));
            assertEquals(4L,number(reader.details(owner,filter,"FEE",1,2).get("total")));
            assertEquals(0,measured.ledgerCounts);
            return null;
        }));
        assertThrows(IllegalArgumentException.class,()->reader.readSnapshot(()->reader.withAuthoritativePageTotal(999,
                ()->{throw new IllegalArgumentException("abort outer action");})));
        assertEquals(4L,number(reader.details(owner,filter,"FEE",1,2).get("total")));
        assertEquals(1,measured.ledgerCounts,"An exception must not leave an authoritative total for a later request");
    }

    @Test void rejectsMissingReadSnapshotAndNegativeTotalsBeforeRunningAction(){
        var ran=new AtomicBoolean();
        assertThrows(IllegalStateException.class,()->repo.withAuthoritativePageTotal(0,()->{ran.set(true);return null;}));
        assertFalse(ran.get());
        assertThrows(IllegalArgumentException.class,()->repo.readSnapshot(()->repo.withAuthoritativePageTotal(-1,
                ()->{ran.set(true);return null;})));
        assertFalse(ran.get());
    }

    @Test void rejectsReadSnapshotJoiningWritableOrReadCommittedTransactions(){
        assertThrows(IllegalStateException.class,()->repo.transaction(()->repo.readSnapshot(
                ()->repo.withAuthoritativePageTotal(0,()->null))));
        var readCommitted=new org.springframework.transaction.support.TransactionTemplate(tx);
        readCommitted.setReadOnly(true);
        readCommitted.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(IllegalStateException.class,()->readCommitted.execute(status->repo.readSnapshot(
                ()->repo.withAuthoritativePageTotal(0,()->null))));
    }

    private void seed(String kind,int count){
        String shop=connect(owner,false);var values=new ArrayList<Object[]>();
        for(int n=0;n<count;n++)values.add(new Object[]{kind+"-"+n,owner.organizationId(),owner.userId(),shop,kind,kind+"-"+n,filter.start()});
        db.batchUpdate("INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,record_date,status,currency,amount,source,raw_json) "
                +"VALUES(?,?,?,?,'SHOPEE',?, ?,?,'SETTLED','PHP',1,'API','{}')",values);
    }
    private static List<String> ids(Map<String,Object> page){return rows(page.get("items")).stream().map(r->text(r.get("id"))).toList();}
}
