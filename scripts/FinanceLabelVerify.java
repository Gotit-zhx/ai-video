package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Read-only verification of the authorized company's asynchronous invoice result. */
public final class FinanceLabelVerify {
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!args[0].matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Organization required");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("DB_URL"));ds.setUsername(System.getenv("DB_USERNAME"));ds.setPassword(System.getenv("DB_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);
            var db=new org.springframework.jdbc.core.JdbcTemplate(ds);db.setQueryTimeout(30);
            var repo=new FinanceRepository(db,new tools.jackson.databind.ObjectMapper());
            var scope=companyScope(args[0]);
            var labels=new FinanceLabelCosts(repo,null,"/data/automation-attachments/finance-label-imports");
            try{
                var files=db.queryForList("""
                    SELECT status,row_count,matched_count,unmatched_count,review_count,billing_failed_count,conflict_count,amount_cny,
                      (storage_name='') temporary_file_cleaned,error_message
                    FROM finance_label_imports WHERE organization_id=? AND status<>'REMOVED' ORDER BY created_at DESC LIMIT 5
                    """,args[0]);
                System.out.println("Import status: "+files);
                var total=db.queryForMap("""
                    SELECT COUNT(*) orders,COALESCE(SUM(t.amount_cny),0) amount_cny,COALESCE(SUM(t.pending_count),0) pending
                    FROM finance_label_state s JOIN finance_label_order_totals t
                      ON t.organization_id=s.organization_id AND t.generation=s.active_generation WHERE s.organization_id=?
                    """,args[0]);
                System.out.println("Active order totals: "+total);
                long start=System.nanoTime();
                var workspace=labels.workspace(scope,new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),"","","","day"),Map.of());
                System.out.printf(Locale.ROOT,"Workspace read: %.2fs; sourceRows=%s; firstPageRows=%d; September=%s; processing=%s%n",
                    (System.nanoTime()-start)/1e9,workspace.get("total"),rows(workspace.get("rows")).size(),workspace.get("period"),workspace.get("processing"));
                if(files.isEmpty()||files.stream().anyMatch(f->!"READY".equals(f.get("status"))))
                    throw new IllegalStateException("Import is not fully published");
                var file=files.getFirst();
                if(number(file.get("row_count"))!=280630||decimal(file.get("amount_cny")).compareTo(decimal("504467.50"))!=0)
                    throw new IllegalStateException("Latest authorized workbook control total does not match");
                if(number(file.get("matched_count"))+number(file.get("unmatched_count"))+number(file.get("review_count"))!=280630)
                    throw new IllegalStateException("Source row accounting does not balance");
                System.out.println("PASS source control totals, published generation and workspace read.");
            }finally{labels.stop();}
        }
    }
}
