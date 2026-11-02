package com.yingdao.aicommerce.finance;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
/** Read-only production reconciliation against the user's workbook, without seller login. */
public final class FinanceExcelBasisVerify {
 static void require(boolean b,String text){if(!b)throw new IllegalStateException(text);}
 static void equal(Object a,Object b,String label){require(a!=null&&b!=null&&decimal(a).compareTo(decimal(b))==0,label+": "+a+" != "+b);}
 public static void main(String[] args)throws Exception{
  var json=new ObjectMapper();var spec=map(json.readValue(Files.readString(Path.of(args[0])),Map.class));
  var scope=companyScope(text(spec.get("organization")));var report=new LinkedHashMap<String,Object>();
  try(var ds=new com.zaxxer.hikari.HikariDataSource()){
   ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));ds.setMaximumPoolSize(2);ds.setReadOnly(true);
   var repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(30);
   int dailyChecks=0;
   for(var entry:map(spec.get("shops")).entrySet()){
    String shop=entry.getKey();var expected=map(entry.getValue());
    var rows=repo.jdbc().queryForList("""
      SELECT record_date,SUM(revenue) income,SUM(amount) net,COUNT(*) records
      FROM finance_records FORCE INDEX(idx_finance_company_dashboard)
      WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API'
        AND record_date>='2026-09-01' AND record_date<'2026-10-01' GROUP BY record_date
      """,scope.organizationId(),shop);
    require(rows.size()==expected.size(),"Daily period count differs");BigDecimal income=BigDecimal.ZERO,net=BigDecimal.ZERO;
    for(var row:rows){var e=map(expected.get(text(row.get("record_date"))));equal(row.get("income"),e.get("income"),"Daily sales income");equal(row.get("net"),e.get("net"),"Daily net settlement");income=income.add(decimal(row.get("income")));net=net.add(decimal(row.get("net")));dailyChecks++;}
    report.put(shop,Map.of("salesIncome",income,"netSettlement",net,"platformDeductions",income.subtract(net)));
   }
   String shop=text(spec.get("target"));
   var orders=repo.jdbc().queryForList("SELECT order_id,SUM(revenue) income,SUM(amount) net FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API' AND category='ORDER' AND record_date>='2026-09-01' AND record_date<'2026-10-01' GROUP BY order_id",scope.organizationId(),shop);
   var expected=map(spec.get("orders"));require(orders.size()==expected.size(),"Workbook order identity count differs");
   for(var order:orders){var e=map(expected.get(text(order.get("order_id"))));equal(order.get("income"),e.get("income"),"Order income");equal(order.get("net"),e.get("net"),"Order net");}
   report.put("dailyChecks",dailyChecks);report.put("exactOrderAmounts",orders.size());
   var filter=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),shop,"","PHP","day");
   var buckets=repo.dashboardBuckets(scope,filter);report.put("operatingBuckets",buckets);
   var summary=new FinanceCostReport(repo).summary(scope,filter,"ORDER");report.put("operatingCosts",summary);
   var totals=new ArrayList<Map<String,Object>>();
   for(var row:buckets){String kind=text(row.get("kind"));var t=totals.stream().filter(x->kind.equals(x.get("kind"))).findFirst().orElseGet(()->{var x=new LinkedHashMap<String,Object>();x.put("kind",kind);totals.add(x);return x;});for(String k:List.of("amount","revenue","records","known_amounts","known_revenues")){if(row.get(k)!=null)t.put(k,Optional.ofNullable(decimal(t.get(k))).orElse(BigDecimal.ZERO).add(decimal(row.get(k))));}}
   var quote=new FinanceFx.Quote("workbook-2026-09",Instant.parse("2026-09-30T00:00:00Z"),Map.of("PHP",BigDecimal.ONE.divide(decimal("9.4833"),16,RoundingMode.HALF_UP)));
   report.put("profitWithWorkbookFxAndConfiguredCosts",FinanceProfitReport.calculate(totals,summary,"PHP",quote));
   var fee=repo.jdbc().queryForObject("SELECT SUM(amount) FROM finance_records FORCE INDEX(idx_finance_company_dashboard) WHERE organization_id=? AND shop_id=? AND kind='FEE' AND source='API' AND record_date>='2026-09-01' AND record_date<'2026-10-01'",BigDecimal.class,scope.organizationId(),shop);
   equal(fee,"381225.18","Total deductions include adjustments once");
   var sample=rows(repo.details(scope,filter,"SETTLEMENT",1,50).get("items"));
   require(!sample.isEmpty(),"Settlement page empty");
   for(var r:sample)equal(r.get("amount"),r.get("revenue"),"Page uses sales income");
   var field=new FinanceFields(repo).selected(scope,"SETTLEMENT",true,false);
   require(field.stream().anyMatch(f->f.id().equals("amount")&&f.label().equals("销售收入（原币）")),"Settlement export income header mismatch");
   require(field.stream().anyMatch(f->f.id().equals("record_date")&&f.label().equals("结算账单日期")),"Settlement export date header mismatch");
   // Use exact first-settlement dates for an August and a September order, including narrowed periods.
   var candidates=repo.jdbc().queryForList("""
     SELECT r.order_id,MIN(r.record_date) day FROM finance_records r
     WHERE r.organization_id=? AND r.shop_id=? AND r.kind='SETTLEMENT' AND r.source='API'
       AND r.category='ORDER' AND r.order_id<>'' AND
     """+FinanceOrderCharges.normalOrder("r")+" GROUP BY r.order_id HAVING day IN ('2026-09-01','2026-10-01') ORDER BY day,r.order_id",scope.organizationId(),shop);
   var controls=new LinkedHashMap<String,String>();for(var r:candidates)controls.putIfAbsent(text(r.get("day")),text(r.get("order_id")));
   require(controls.size()==2,"Missing real cross-month controls");
   for(var c:controls.entrySet()){
    var start=LocalDate.parse(c.getKey());var f=new Filter(start,start.plusDays(14),shop,"","PHP","day");
    for(String source:List.of("ORDER","BILL","RECONCILIATION")){
     var page=new FinanceCostReport(repo).orders(scope,f,source,"",1,"ALL",String.join("\n",controls.values()));
     require(number(page.get("total"))==1&&text(rows(page.get("items")).getFirst().get("order_id")).equals(c.getValue()),"Partial-month cost page mismatch");
     byte[] file=new FinanceCostExport(repo).export(scope,f,source,"","ALL",String.join("\n",controls.values()));
     require(FinanceSettlementExportVerify.exportedOrders(file).equals(Set.of(c.getValue())),"Cost export period mismatch");
    }
   }
   report.put("costPageAndExportChecks",6);report.put("verifiedAt",Instant.now().toString());
   Files.writeString(Path.of(args[1]),json.writeValueAsString(report));
   System.out.println(json.writeValueAsString(Map.of("dailyChecks",dailyChecks,"exactOrderAmounts",orders.size(),"target",report.get(shop),"costPageAndExportChecks",6)));
  }
 }
}
