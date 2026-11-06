package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only checks using the production repository and export code, without a seller login. */
public final class FinanceReportingMonthVerify {
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();Path root=Path.of(args[0]);
        var spec=map(json.readValue(Files.readString(root.resolve("approved-months.json")),Map.class));
        var scope=companyScope(text(spec.get("organization")));String shop=text(spec.get("shop"));
        var orders=((List<?>)spec.get("orders")).stream().map(FinanceData::text).toList();
        var controls=((List<?>)spec.get("septemberControls")).stream().map(FinanceData::text).toList();
        String numbers=String.join("\n",orders);
        var ds=new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));ds.setMaximumPoolSize(2);
        try{
            var repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(30);
            var sep=new Filter(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"),shop,"","","day");
            var oct=new Filter(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),shop,"","","day");
            var result=new LinkedHashMap<String,Object>();
            for(String kind:List.of("SETTLEMENT","ORDER","FEE")){
                require(number(repo.details(scope,sep,kind,1,100,numbers).get("total"))==0,kind+" still enters September");
                var detail=repo.details(scope,oct,kind,1,100,numbers);
                require(number(detail.get("total"))>0,kind+" absent in October");
                if(!kind.equals("FEE"))require(number(detail.get("total"))==27,kind+" target count differs");
                for(var row:rows(detail.get("items"))){
                    require("2026-10".equals(row.get("reporting_month")),"Missing month provenance");
                    require("2026-09-30".equals(row.get("platform_settlement_date")),"Missing original date");
                    require("2026-10（按月校正）".equals(FinanceFields.cell(row,FinanceFields.catalog(kind).stream().filter(f->f.id().equals("record_date")).findFirst().orElseThrow())),"Export shows an invented day");
                }
                result.put(kind,Map.of("septemberTargets",0,"octoberTargets",detail.get("total")));
            }
            require(number(repo.details(scope,sep,"SETTLEMENT",1,100,String.join("\n",controls)).get("total"))==15,"First batch no longer in September");
            var costs=new FinanceCostReport(repo);
            require(number(costs.orders(scope,sep,"ORDER","",1,"ALL",numbers).get("total"))==0,"Costs still enter September");
            var octCosts=costs.orders(scope,oct,"ORDER","",1,"ALL",numbers);
            require(number(octCosts.get("total"))>0,"October costs absent");
            for(var row:rows(octCosts.get("items")))require("2026-10".equals(row.get("reporting_month")),"Cost drilldown lacks month provenance");
            var export=new FinanceCostExport(repo).export(scope,oct,"ORDER","","ALL",numbers);
            require(export.length>1000,"Cost workbook empty");
            Files.write(root.resolve("verified-october-order-costs.xlsx"),export);
            var service=new FinanceService(repo,null,null,null);
            long started=System.nanoTime();var dashboard=service.buildDashboard(scope,oct,false,"",false);
            result.put("octoberOverviewMs",(System.nanoTime()-started)/1_000_000);
            require("month".equals(dashboard.get("trendGranularity")),"Exact daily trend manufactured from month-only evidence");
            require(!rows(dashboard.get("reportingMonths")).isEmpty(),"Missing month notice");
            var partial=new Filter(oct.start(),LocalDate.parse("2026-10-28"),shop,"","","day");
            started=System.nanoTime();var partialDashboard=service.buildDashboard(scope,partial,false,"",false);
            result.put("partialOverviewMs",(System.nanoTime()-started)/1_000_000);
            require(number(repo.details(scope,partial,"SETTLEMENT",1,100,numbers).get("total"))==0,"Partial month pretends to know completion day");
            require(rows(partialDashboard.get("reportingMonths")).stream().noneMatch(r->Boolean.TRUE.equals(r.get("included"))),"Partial month notice incorrect");
            result.put("septemberControls",15);result.put("octoberCostOrders",octCosts.get("total"));
            result.put("costExportBytes",export.length);result.put("partialMonthHonest",true);
            Files.writeString(root.resolve("repository-verification.json"),json.writeValueAsString(result));
            System.out.println(json.writeValueAsString(result));
        }finally{ds.close();}
    }
}
