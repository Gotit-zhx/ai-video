package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only page/filter checks against the explicitly selected company. No financial writes. */
public final class FinanceLabelPageProbe {
    public static void main(String[] args)throws Exception{
        if(args.length<1||!args[0].matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Organization required");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("DB_URL"));ds.setUsername(System.getenv("DB_USERNAME"));ds.setPassword(System.getenv("DB_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);
            var db=new org.springframework.jdbc.core.JdbcTemplate(ds);db.setQueryTimeout(20);
            var repo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
            var labels=new FinanceLabelCosts(repo,null,"/data/automation-attachments/finance-label-imports");
            var scope=companyScope(args[0]);var filter=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day");
            try{
                String file=db.queryForObject("SELECT id FROM finance_label_imports WHERE organization_id=? AND status='READY' ORDER BY created_at DESC,id LIMIT 1",String.class,args[0]);
                var cases=new LinkedHashMap<String,Map<String,String>>();
                cases.put("all",Map.of("detailsOnly","true"));
                cases.put("file",Map.of("detailsOnly","true","fileId",file));
                if(args.length==1||!args[1].equals("baseline")){
                    cases.put("billingFailed",Map.of("detailsOnly","true","fileId",file,"matchStatus","BILLING_FAILED"));
                    cases.put("unmatched",Map.of("detailsOnly","true","fileId",file,"matchStatus","UNMATCHED"));
                    cases.put("matched",Map.of("detailsOnly","true","fileId",file,"matchStatus","MATCHED"));
                    cases.put("page2",Map.of("detailsOnly","true","fileId",file,"page","2"));
                }
                for(var entry:cases.entrySet()){
                    long start=System.nanoTime();var result=labels.workspace(scope,filter,entry.getValue());var records=rows(result.get("rows"));
                    if(records.size()>50)throw new AssertionError("Unbounded page");
                    if(entry.getValue().containsKey("fileId")&&records.stream().anyMatch(r->!file.equals(r.get("import_id"))))throw new AssertionError("Wrong source");
                    switch(entry.getKey()){
                        case "billingFailed" -> {if(records.stream().anyMatch(r->"计算成功".equals(r.get("billing_status"))))throw new AssertionError("Wrong billing filter");}
                        case "unmatched" -> {if(records.stream().anyMatch(r->!"UNMATCHED".equals(r.get("match_status"))))throw new AssertionError("Wrong unmatched filter");}
                        case "matched" -> {if(records.stream().anyMatch(r->!"MATCHED".equals(r.get("match_status"))))throw new AssertionError("Wrong matched filter");}
                    }
                    System.out.printf(Locale.ROOT,"%s: %.3fs, sourceRows=%s, pageRows=%d, includesPeriod=%s%n",
                        entry.getKey(),(System.nanoTime()-start)/1e9,result.get("total"),records.size(),result.containsKey("period"));
                }
                System.out.println("PASS read-only file switching, bounded pages and selected status.");
            }finally{labels.stop();}
        }
    }
}
