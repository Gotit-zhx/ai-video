package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Page identities, tenant/group filters and exact-number lookup precede payload reads. */
class FinanceRecordPageIntegrationTest extends FinanceDashboardCacheIntegrationTest {
    @Test void coveredPagesKeepExactOrderingCountsAndVisibilityWithLargePayloads(){
        String group=service.saveGroup(owner,null,"page-group"),shop=groupedShop(owner,"page-shop",group);
        String outside=groupedShop(owner,"other-shop",service.saveGroup(owner,null,"other-group"));
        String foreign=connect(other,false);
        var values=new ArrayList<Object[]>();
        for(int n=0;n<123;n++)values.add(new Object[]{String.format("page-%03d",n),owner.organizationId(),owner.userId(),shop,
            "reference-"+n,n<70?"2026-10-21":"2026-10-20","SETTLED","{\"payload\":\""+"x".repeat(8192)+"\"}"});
        values.add(new Object[]{"outside",owner.organizationId(),owner.userId(),outside,"outside","2026-10-21","SETTLED","{}"});
        values.add(new Object[]{"foreign",other.organizationId(),other.userId(),foreign,"foreign","2026-10-21","SETTLED","{}"});
        values.add(new Object[]{"hidden",owner.organizationId(),owner.userId(),shop,"hidden","2026-10-21","DATE_PENDING","{}"});
        db.batchUpdate("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
              record_date,status,currency,amount,source,raw_json)
            VALUES(?,?,?,?,'SHOPEE','FEE',?,'',?,?,'PHP',1,'API',?)
            """,values);
        var filter=new Filter(LocalDate.of(2026,10,20),LocalDate.of(2026,10,21),"","","","day",group);
        for(int page=1;page<=3;page++){
            var result=repo.details(owner,filter,"FEE",page,50);
            assertEquals(123L,number(result.get("total")));
            var ids=rows(result.get("items")).stream().map(r->text(r.get("id"))).toList();
            var expected=new ArrayList<String>();
            for(int n=(page-1)*50;n<Math.min(page*50,123);n++)expected.add(String.format("page-%03d",n));
            assertEquals(expected,ids);
        }
        assertTrue(rows(repo.details(owner,filter,"FEE",4,50).get("items")).isEmpty());
        var exact=repo.details(owner,filter,"FEE",1,50,"reference-80");
        assertEquals(1L,number(exact.get("total")));
        assertEquals("page-080",rows(exact.get("items")).getFirst().get("id"));
        var single=new Filter(filter.start(),filter.end(),shop,"SHOPEE","PHP","day");
        assertEquals(123L,number(repo.details(owner,single,"FEE",1,50).get("total")));
    }
}
