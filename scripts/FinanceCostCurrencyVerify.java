package com.yingdao.aicommerce.finance;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only validation of the real dashboard contract and its lazy cost categories. */
public final class FinanceCostCurrencyVerify {
    static void equal(Object a,Object b,String name){
        if(a==null||b==null||decimal(a).compareTo(decimal(b))!=0)throw new IllegalStateException(name+" mismatch");
    }
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();var result=new LinkedHashMap<String,Object>();
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setReadOnly(true);
            var repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(30);
            var scope=companyScope("6a61f788-6685-4b64-82b1-eec97ed8cf90");
            var filter=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"fecf7801-0929-4076-bbaa-ee42f4dce87b","","PHP","day");
            String snapshot=repo.jdbc().queryForObject("SELECT id FROM finance_fx_snapshots ORDER BY rate_time DESC LIMIT 1",String.class);
            var fx=new FinanceFx(repo,json);var quote=fx.quote(snapshot);var service=new FinanceService(repo,null,null,fx);
            var cny=service.buildDashboard(scope,filter,true,snapshot,false);
            var nativeView=service.buildDashboard(scope,filter,false,snapshot,false);
            var cnyProfit=map(cny.get("profit"));var nativeProfit=map(nativeView.get("profit"));
            equal(cnyProfit.get("costCnyRate"),"1","CNY display rate");
            equal(nativeProfit.get("costCnyRate"),quote.rates().get("PHP"),"Original display rate");
            if(!snapshot.equals(map(nativeProfit.get("fx")).get("id")))throw new IllegalStateException("Mixed FX snapshots");
            equal(cnyProfit.get("productCost"),"74331.810998","Underlying product cost");
            equal(cnyProfit.get("returnedProductCost"),"340.684917","Underlying received inventory credit");
            for(String field:List.of("productCost","returnedProductCost","logisticsLabelCost"))
                equal(nativeProfit.get(field),decimal(cnyProfit.get(field)).divide(quote.rates().get("PHP"),6,RoundingMode.HALF_UP),"Converted "+field);
            var categories=new ArrayList<Map<String,Object>>();
            for(String kind:List.of("SAMPLE_SETTLED","REFUND_SETTLED")){
                var t=map(new FinanceCostReport(repo).summary(scope,filter,kind).get("total"));
                BigDecimal amount=decimal(t.get("amount_cny"));
                if(amount==null)throw new IllegalStateException("Category costs incomplete");
                categories.add(Map.of("kind",kind,"CNY",amount,"PHP",amount.divide(decimal(nativeProfit.get("costCnyRate")),6,RoundingMode.HALF_UP)));
            }
            result.put("categories",categories);result.put("rateCnyPerPhp",nativeProfit.get("costCnyRate"));
            result.put("returnsCny",cnyProfit.get("returnedProductCost"));result.put("returnsPhp",nativeProfit.get("returnedProductCost"));
            result.put("fxSnapshot",snapshot);result.put("verifiedAt",Instant.now().toString());
            Files.writeString(Path.of(args[1]),repo.write(result));System.out.println(repo.write(result));
        }
    }
}
