package com.yingdao.aicommerce.finance;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
public class FinanceReportBasisCursorQa extends FinanceConfigurationIntegrationTest {
    void repairCursorTraversesRealIndexOrderWithRepeatedAndBlankOrderIds(){
        String shop=connect(owner,false);var expected=new HashSet<String>();var args=new ArrayList<Object[]>();
        for(int n=0;n<523;n++){
            String id=hash("page-"+n),order=n<3?"":"order-"+(n%31);
            boolean repaired=n%13==0;
            if(!repaired)expected.add(id);
            args.add(new Object[]{id,owner.organizationId(),owner.userId(),shop,"tx-"+n,order,
                repaired?"2026-10-31":"2026-11-01",repaired?"{\"_finance_date_version\":4,\"_finance_date_basis\":\"LOCAL_BALANCE\"}":"{}"});
        }
        db.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
              statement_id,record_date,currency,source,raw_json)
            VALUES(?,?,?,?,'TIKTOK_SHOP','FEE',?,?,'cursor-bill',?,'MYR','API',?)
            """,args);
        var actual=new HashSet<String>();String orderCursor="",idCursor="";int pages=0;
        for(;;){
            var batch=FinanceReportBasisRepair.nextBatch(db,owner.organizationId(),shop,"FEE","cursor-bill",
                orderCursor,idCursor,LocalDate.of(2026,10,31));
            if(batch.isEmpty())break;
            assertTrue(batch.size()<=200);pages++;
            for(var row:batch)assertTrue(actual.add(text(row.get("id"))),"a transaction may not repeat between pages");
            var last=batch.getLast();orderCursor=text(last.get("order_id"));idCursor=text(last.get("id"));
        }
        assertEquals(3,pages);assertEquals(expected,actual);
        var plan=db.queryForList("EXPLAIN "+FinanceReportBasisRepair.BATCH_SQL,owner.organizationId(),shop,
            "FEE","cursor-bill","order-1","order-1","000","2026-10-31");
        assertTrue(plan.stream().noneMatch(row->text(row.get("Extra")).toLowerCase().contains("filesort")));
    }
}
