package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Queues the user's supplied workbook through the production uploader; the app worker processes it asynchronously. */
public final class FinanceLabelUpload {
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!args[0].matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Organization, source path and filename required");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("DB_URL"));ds.setUsername(System.getenv("DB_USERNAME"));ds.setPassword(System.getenv("DB_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);
            var repo=new FinanceRepository(new org.springframework.jdbc.core.JdbcTemplate(ds),new tools.jackson.databind.ObjectMapper());
            var scope=companyScope(args[0]);
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections WHERE organization_id=? AND status<>'REMOVED'",Long.class,args[0])==0)
                throw new IllegalStateException("No active company shops");
            var labels=new FinanceLabelCosts(repo,null,"/data/automation-attachments/finance-label-imports");
            try(var input=Files.newInputStream(Path.of(args[1]))){
                System.out.println("Queued authorized workbook: "+labels.upload(scope,"SYSTEM_USER_REQUESTED_IMPORT",args[2],input));
            }finally{labels.stop();}
        }
    }
}
