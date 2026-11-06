package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import java.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Explicit release maintenance command; bounded workers/transactions and durable per-shop checkpoints. */
public final class FinanceSettlementBackfill {
    static Path output;static FinanceRepository repo;static ObjectMapper json;
    static int batchSize=5;
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Private checkpoint directory required");
        output=Path.of(args[0]);Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("complete.json"));
        var ds=new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
        int parallelism=Integer.parseInt(System.getenv().getOrDefault("BACKFILL_WORKERS","2"));
        if(parallelism<1||parallelism>4)throw new IllegalArgumentException("Backfill workers must be between one and four");
        batchSize=Integer.parseInt(System.getenv().getOrDefault("BACKFILL_BATCH_SIZE","5"));
        if(batchSize<1||batchSize>20)throw new IllegalArgumentException("Backfill batch must contain one to twenty rows");
        ds.setMaximumPoolSize(parallelism+1);ds.setMinimumIdle(1);ds.setConnectionTimeout(15000);json=new ObjectMapper();
        repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(15);
        var shops=repo.jdbc().queryForList("SELECT organization_id,user_id,id FROM finance_connections WHERE status<>'REMOVED' ORDER BY id");
        try(var workers=Executors.newFixedThreadPool(parallelism)){
            var futures=new ArrayList<Future<?>>();
            for(var shop:shops)futures.add(workers.submit(()->{
                try{rebuild(shop);}catch(Exception error){
                    System.err.println("Shop "+shop.get("id")+" failed: "+error.getClass().getSimpleName());
                    throw new IllegalStateException("Backfill failed for shop "+shop.get("id"),error);
                }
            }));
            Exception failure=null;
            for(var task:futures)try{task.get();}catch(ExecutionException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)throw failure;
            long missing=repo.jdbc().queryForObject("""
                SELECT COUNT(*) FROM finance_records r FORCE INDEX(idx_finance_cost_backfill)
                JOIN finance_connections c ON c.organization_id=r.organization_id AND c.id=r.shop_id
                WHERE c.status<>'REMOVED' AND r.kind='SETTLEMENT' AND r.source='API' AND r.revenue IS NULL
                """,Long.class);
            if(missing>0)throw new IllegalStateException("Settlement amount verification failed: "+missing+" records still missing goods amounts");
            Files.writeString(output.resolve("complete.json"),json.writeValueAsString(Map.of("shops",shops.size(),"completedAt",java.time.Instant.now().toString())));
            System.out.println("All shop derivations completed: "+shops.size());
        }finally{ds.close();}
    }
    static void rebuild(Map<String,Object> shop)throws Exception{
        String id=text(shop.get("id"));var scope=new Scope(text(shop.get("organization_id")),text(shop.get("user_id")));
        Path checkpoint=output.resolve(id+".json");
        var progress=Files.exists(checkpoint)?new LinkedHashMap<>(repo.read(Files.readString(checkpoint))):new LinkedHashMap<String,Object>();
        if(Boolean.TRUE.equals(progress.get("complete")))return;
        // The old connector stored Get Statements.statement_time as record_date even when
        // the transaction endpoint's create_time was zero. Preserve that existing source date
        // before conversion; don't invent a timestamp, and don't subtract again on a retry.
        var legacy=repo.jdbc().queryForList("""
            SELECT id,record_date,raw_json FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
            WHERE organization_id=? AND shop_id=? AND kind='BILL' AND platform='TIKTOK_SHOP' AND source='API'
              AND COALESCE(JSON_EXTRACT(raw_json,'$._finance_statement_time'),JSON_EXTRACT(raw_json,'$.create_time'),0)<=0
              AND JSON_EXTRACT(raw_json,'$._finance_legacy_statement_date') IS NULL
            """,scope.organizationId(),id);
        if(!legacy.isEmpty()){
            Path archive=output.resolve(id+"-original-statement-dates.json");
            if(!Files.exists(archive))Files.writeString(archive,json.writeValueAsString(legacy));
            for(var old:legacy)repo.jdbc().update("""
                UPDATE finance_records SET raw_json=JSON_SET(raw_json,'$._finance_legacy_statement_date',CAST(record_date AS CHAR)),
                  updated_at=updated_at WHERE id=? AND record_date=?
                  AND COALESCE(JSON_EXTRACT(raw_json,'$._finance_statement_time'),JSON_EXTRACT(raw_json,'$.create_time'),0)<=0
                  AND JSON_EXTRACT(raw_json,'$._finance_legacy_statement_date') IS NULL
                """,old.get("id"),old.get("record_date"));
        }
        String cursor=text(progress.get("cursor"));long count=number(progress.get("records")),unsupported=number(progress.get("unsupported"));
        for(;;){
            var rows=repo.jdbc().queryForList("""
                SELECT id,external_id,order_id,statement_id,user_id,revenue,amount,record_date,currency
                FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API' AND id>?
                ORDER BY id LIMIT 100
                """,scope.organizationId(),id,cursor);
            if(rows.isEmpty())break;
            // Archive only changed derived columns and old generated fees, never complete raw order payloads.
            Path archive=output.resolve(id+"-"+text(rows.getFirst().get("id"))+".jsonl.gz");
            if(!Files.exists(archive)){
                Path temporary=Path.of(archive+".tmp");
                try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)),StandardCharsets.UTF_8))){
                    for(var r:rows){
                        String prefix=text(r.get("external_id")).replace("=","==").replace("%","=%").replace("_","=_")+":%";
                        var fees=repo.jdbc().queryForList("""
                            SELECT * FROM finance_records FORCE INDEX(idx_finance_settled_order)
                            WHERE organization_id=? AND shop_id=? AND kind='FEE' AND order_id=? AND statement_id=?
                              AND source='API' AND external_id LIKE ? ESCAPE '='
                            """,scope.organizationId(),id,r.get("order_id"),r.get("statement_id"),prefix);
                        out.write(json.writeValueAsString(Map.of("settlement",r,"fees",fees)));out.newLine();
                    }
                }
                Files.move(temporary,archive,StandardCopyOption.ATOMIC_MOVE);
            }
            // Amortize durable commits without weakening MySQL durability or locking an entire shop.
            for(int offset=0;offset<rows.size();offset+=batchSize){
                var batch=rows.subList(offset,Math.min(offset+batchSize,rows.size())).stream().map(r->text(r.get("id"))).toList();
                int derived=retry(()->repo.rebuildSettlements(scope,id,batch));unsupported+=batch.size()-derived;count+=batch.size();
                cursor=batch.getLast();progress.put("cursor",cursor);progress.put("records",count);progress.put("unsupported",unsupported);
                progress.put("updatedAt",java.time.Instant.now().toString());persist(checkpoint,progress);
            }
            if(count%2000==0)System.out.println("Shop "+id+": "+count+" settlements derived");
        }
        // Bill totals are small indexed aggregations over their own transactions; imported net amounts stay untouched.
        String billCursor=text(progress.get("billCursor"));long bills=number(progress.get("bills"));
        for(;;){
            var rows=repo.jdbc().queryForList("""
                SELECT id,revenue,record_date,platform,external_id,source FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                WHERE organization_id=? AND shop_id=? AND kind='BILL' AND id>? ORDER BY id LIMIT 100
                """,scope.organizationId(),id,billCursor);
            if(rows.isEmpty())break;
            Path billArchive=output.resolve(id+"-bills-"+text(rows.getFirst().get("id"))+".json");
            if(!Files.exists(billArchive))Files.writeString(billArchive,json.writeValueAsString(rows));
            for(int offset=0;offset<rows.size();offset+=batchSize){
                var batch=rows.subList(offset,Math.min(offset+batchSize,rows.size()));
                retry(()->repo.transaction(()->{
                    if(!repo.writableShop(scope.organizationId(),id))return null;
                    for(var row:batch){
                        var found=repo.jdbc().queryForList("SELECT external_id,currency,status,revenue FROM finance_records WHERE id=? FOR UPDATE",row.get("id"));
                        if(!found.isEmpty()){
                            var b=found.getFirst();BigDecimal goods=repo.billGoods(scope,id,text(b.get("external_id")),text(b.get("currency")),text(b.get("status")));
                            if("TIKTOK_SHOP".equals(row.get("platform"))&&"API".equals(row.get("source")))
                                repo.jdbc().update("UPDATE finance_records SET revenue=?,record_date=?,updated_at=updated_at WHERE id=?",goods,
                                        java.sql.Date.valueOf(repo.tiktokBillDay(scope,id,text(b.get("external_id")))),row.get("id"));
                            else repo.jdbc().update("UPDATE finance_records SET revenue=?,updated_at=updated_at WHERE id=?",goods,row.get("id"));
                        }
                    }
                    return null;
                }));
                bills+=batch.size();billCursor=text(batch.getLast().get("id"));progress.put("billCursor",billCursor);progress.put("bills",bills);
                progress.put("updatedAt",java.time.Instant.now().toString());persist(checkpoint,progress);
            }
        }
        progress.put("complete",true);persist(checkpoint,progress);
        System.out.println("Shop "+id+" completed: "+count+" settlements, "+bills+" bills; unsupported "+unsupported);
    }
    /** Each retry starts a fresh transaction; permanent data errors must remain visible. */
    static <T>T retry(java.util.function.Supplier<T> action)throws InterruptedException{
        return retry(action,Thread::sleep);
    }
    interface Pause { void sleep(long millis)throws InterruptedException; }
    static <T>T retry(java.util.function.Supplier<T> action,Pause pause)throws InterruptedException{
        for(int attempt=0;;attempt++){
            try{return action.get();}
            catch(org.springframework.dao.TransientDataAccessException|org.springframework.transaction.TransactionTimedOutException transientFailure){
                if(attempt>=4)throw transientFailure;
                System.err.println("Retrying rolled-back batch after "+transientFailure.getClass().getSimpleName()+" (attempt "+(attempt+2)+"/5)");
                pause.sleep(Math.min(4000,500L<<attempt));
            }
        }
    }
    static void persist(Path path,Map<String,Object> value)throws IOException{
        Path temporary=Path.of(path+".tmp");Files.writeString(temporary,json.writeValueAsString(value));
        Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
