package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded, restartable date-only repair using allowlisted metadata fetched from the official API. */
public final class FinanceSettlementDateRepair {
    static void persist(Path path,String json)throws IOException{
        Path tmp=Path.of(path+".tmp");Files.writeString(tmp,json);
        Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),output=root.resolve("backfill");Files.createDirectories(output);
        var ds=new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
        ds.setMaximumPoolSize(3);
        try{
            var repo=new FinanceRepository(new JdbcTemplate(ds),new ObjectMapper());repo.jdbc().setQueryTimeout(15);
            var metadata=rows(repo.read("{\"items\":"+Files.readString(root.resolve("statement-completion-metadata.json"))+"}").get("items"));
            var byShop=new LinkedHashMap<String,List<Map<String,Object>>>();
            for(var bill:metadata){
                if(!FinanceSettlementDates.completed(bill))throw new IllegalStateException("Unconfirmed statement in repair input");
                byShop.computeIfAbsent(text(bill.get("shop_id")),x->new ArrayList<>()).add(bill);
            }
            var groups=new ArrayList<>(byShop.entrySet());
            String priority=System.getenv().getOrDefault("DATE_REPAIR_PRIORITY_SHOP","");
            groups.sort(Comparator.comparingInt(g->g.getKey().equals(priority)?0:1));
            try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
                var futures=new ArrayList<java.util.concurrent.Future<?>>();
                for(var group:groups)futures.add(workers.submit(()->{
                    try{repairShop(repo,output,group);}catch(Exception error){throw new IllegalStateException("Date repair failed for "+group.getKey(),error);}
                }));
                Exception failed=null;
                for(var future:futures)try{future.get();}catch(java.util.concurrent.ExecutionException error){if(failed==null)failed=error;else failed.addSuppressed(error);}
                if(failed!=null)throw failed;
            }
            persist(output.resolve("complete.json"),repo.write(Map.of("shops",byShop.size(),"completedAt",java.time.Instant.now().toString())));
        }finally{ds.close();}
    }
    static void repairShop(FinanceRepository repo,Path output,Map.Entry<String,List<Map<String,Object>>> group)throws Exception{
                String shop=group.getKey();Path checkpoint=output.resolve(shop+".json");
                var progress=Files.exists(checkpoint)?new LinkedHashMap<>(repo.read(Files.readString(checkpoint))):new LinkedHashMap<String,Object>();
                if(Boolean.TRUE.equals(progress.get("complete")))return;
                long changed=number(progress.get("changed")),records=number(progress.get("records")),bills=number(progress.get("bills"));
                for(int i=(int)number(progress.get("billIndex"));i<group.getValue().size();i++){
                    var bill=group.getValue().get(i);var scope=companyScope(text(bill.get("organization_id")));
                    String cursor=text(progress.get("cursor")),statement=text(bill.get("id"));
                    for(;;){
                        var rows=repo.jdbc().queryForList("""
                            SELECT id,kind,status,record_date,
                              JSON_EXTRACT(raw_json,'$._finance_statement_time') statement_time,
                              JSON_EXTRACT(raw_json,'$._finance_payment_time') payment_time,
                              JSON_EXTRACT(raw_json,'$._finance_payment_status') payment_status,
                              JSON_EXTRACT(raw_json,'$._finance_payment_id') payment_id,
                              JSON_EXTRACT(raw_json,'$._finance_date_source') date_source,
                              JSON_EXTRACT(raw_json,'$._finance_date_version') date_version,
                              JSON_EXTRACT(raw_json,'$._finance_timezone') timezone
                            FROM finance_records FORCE INDEX(idx_finance_cost_statement)
                            WHERE organization_id=? AND shop_id=? AND kind IN ('BILL','SETTLEMENT','FEE')
                              AND statement_id=? AND platform='TIKTOK_SHOP' AND source='API' AND id>?
                            ORDER BY id LIMIT 200
                            """,scope.organizationId(),shop,statement,cursor);
                        if(rows.isEmpty())break;
                        Path archive=output.resolve(shop+"-"+statement+"-"+rows.getFirst().get("id")+".json.gz");
                        if(!Files.exists(archive)){
                            Path tmp=Path.of(archive+".tmp");
                            try(var out=new GZIPOutputStream(Files.newOutputStream(tmp))){out.write(repo.write(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                            Files.move(tmp,archive,StandardCopyOption.ATOMIC_MOVE);
                        }
                        var ids=rows.stream().map(r->text(r.get("id"))).toList();
                        changed+=FinanceSettlementBackfill.retry(()->repo.repairSettlementDates(scope,shop,bill,ids));
                        records+=rows.stream().filter(r->"SETTLEMENT".equals(r.get("kind"))).count();
                        bills+=rows.stream().filter(r->"BILL".equals(r.get("kind"))).count();
                        cursor=text(rows.getLast().get("id"));progress.put("cursor",cursor);progress.put("billIndex",i);
                        progress.put("records",records);progress.put("bills",bills);progress.put("changed",changed);
                        persist(checkpoint,repo.write(progress));
                    }
                    progress.put("billIndex",i+1);progress.remove("cursor");persist(checkpoint,repo.write(progress));
                }
                // Check all active records, including statements omitted by an incomplete input capture.
                long unresolved=repo.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND shop_id=?
                      AND kind IN ('BILL','SETTLEMENT') AND source='API' AND platform='TIKTOK_SHOP'
                      AND status<>'DATE_PENDING' AND COALESCE(JSON_EXTRACT(raw_json,'$._finance_date_version'),0)<>2
                    """,Long.class,group.getValue().getFirst().get("organization_id"),shop);
                if(unresolved>0)throw new IllegalStateException("Official date metadata missing for "+unresolved+" records in shop "+shop);
                progress.put("complete",true);persist(checkpoint,repo.write(progress));
                System.out.println("Completed shop "+shop+": "+records+" settlements, "+bills+" bills, "+changed+" dated records repaired");
    }

}
