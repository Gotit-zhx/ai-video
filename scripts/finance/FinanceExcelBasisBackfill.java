package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/**
 * Explicit release maintenance. Fetch one indexed page of transactions and its
 * fees together, then commit their revenue, date and fees in the same bounded
 * transaction. Immutable rollback archives precede writes; checkpoints follow commits.
 */
public final class FinanceExcelBasisBackfill {
    static FinanceRepository repo;
    static Path output;
    static final int PAGE=50;
    static <T>T transaction(java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(repo.jdbc().getDataSource())));
        tx.setTimeout(30);
        return tx.execute(status->action.get());
    }
    static <T>T retry(java.util.function.Supplier<T> action)throws InterruptedException{
        return FinanceSettlementBackfill.retry(()->{
            try{return action.get();}
            catch(org.springframework.jdbc.UncategorizedSQLException error){
                // Connector/J can wrap SQLTimeoutException in BatchUpdateException without SQLState.
                // Spring then cannot classify it. The enclosing page transaction has rolled back.
                if(!Thread.currentThread().isInterrupted()){
                    for(Throwable cause=error;cause!=null;cause=cause.getCause())
                        if(cause instanceof java.sql.SQLTimeoutException)
                            throw new org.springframework.dao.QueryTimeoutException("Maintenance batch timed out",error);
                }
                throw error;
            }
        });
    }
    static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    static void archive(Path file,Object value){
        if(Files.exists(file))return;
        try{
            Path tmp=Path.of(file+".tmp");
            try(var stream=new GZIPOutputStream(Files.newOutputStream(tmp))){
                stream.write(repo.write(value).getBytes(StandardCharsets.UTF_8));
            }
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException e){throw new IllegalStateException("Rollback archive failed",e);}
    }
    static void checkpoint(Path file,Map<String,Object> progress)throws IOException{
        progress.put("updatedAt",Instant.now().toString());
        Path tmp=Path.of(file+".tmp");Files.writeString(tmp,repo.write(progress));
        Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    record Page(String last,int records,int removed,int written){}
    static Page settlements(Scope scope,String shop,String zone,String cursor){
        return transaction(()->{
            if(!repo.writableShop(scope.organizationId(),shop))throw bad("Shop removed during maintenance");
            var records=repo.jdbc().queryForList("""
                SELECT * FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API' AND status<>'DATE_PENDING' AND id>?
                ORDER BY id LIMIT ? FOR UPDATE
                """,scope.organizationId(),shop,cursor,PAGE);
            if(records.isEmpty())return new Page(cursor,0,0,0);
            var parents=new HashSet<String>();var orders=new LinkedHashSet<String>();
            for(var r:records){parents.add(text(r.get("external_id")));orders.add(text(r.get("order_id")));}
            var feeArgs=new ArrayList<Object>(List.of(scope.organizationId(),shop));feeArgs.addAll(orders);
            var candidates=repo.jdbc().queryForList("""
                SELECT * FROM finance_records FORCE INDEX(idx_finance_settled_order)
                WHERE organization_id=? AND shop_id=? AND kind='FEE' AND source='API' AND order_id IN (
                """+placeholders(orders.size())+") FOR UPDATE",feeArgs.toArray());
            var existing=new LinkedHashMap<String,Map<String,Object>>();
            for(var fee:candidates){
                var raw=repo.read(text(fee.get("raw_json")));
                if(parents.contains(text(raw.get("settlement_external_id"))))existing.put(text(fee.get("id")),fee);
            }
            var beforeFees=new ArrayList<>(existing.values());
            var beforeFeeIds=new HashSet<>(existing.keySet());
            var updates=new ArrayList<Object[]>();var writes=new ArrayList<Object[]>();
            for(var row:records){
                String platform=text(row.get("platform"));var raw=repo.read(text(row.get("raw_json")));
                if(!FinanceSettlementBreakdown.supported(platform,raw))throw bad("Unsupported source row in maintenance");
                var day=LocalDate.parse(text(row.get("record_date")));
                if(platform.equals("TIKTOK_SHOP")){
                    raw=new LinkedHashMap<>(raw);
                    raw.putAll(FinanceSettlementDates.metadata(raw,zone));day=FinanceSettlementDates.day(raw,zone);
                }
                raw.put("_finance_income_version",2);
                var e=new Entry(shop,platform,"SETTLEMENT",text(row.get("external_id")),text(row.get("order_id")),
                        text(row.get("statement_id")),text(row.get("category")),text(row.get("status")),day,
                        text(row.get("currency")),decimal(row.get("amount")),null,null,null,null,"API",raw);
                e=new FinanceReportingMonths(repo).apply(scope,e);
                var breakdown=FinanceSettlementBreakdown.parse(platform,e.raw(),e.amount());
                if(breakdown.goods()==null)throw bad("Missing sales revenue; never substitute zero");
                if(e.amount()==null||breakdown.goods().subtract(breakdown.deductions()).compareTo(e.amount())!=0)
                    throw bad("Income/fee/net invariant failed");
                updates.add(new Object[]{breakdown.goods(),Date.valueOf(e.date()),repo.write(e.raw()),row.get("id")});
                var owner=new Scope(scope.organizationId(),text(row.get("user_id")));
                for(var args:repo.derivedFees(owner,e,breakdown)){
                    var old=existing.remove(text(args[0]));
                    if(old==null||!repo.sameFee(old,args))writes.add(args);
                }
            }
            String first=text(records.getFirst().get("id"));
            archive(output.resolve(shop+"-settlements-"+first+".json.gz"),
                    Map.of("records",records,"fees",beforeFees,"insertedFeeIds",writes.stream()
                            .map(a->text(a[0])).filter(id->!beforeFeeIds.contains(id)).toList()));
            repo.jdbc().batchUpdate("UPDATE finance_records SET revenue=?,record_date=?,raw_json=?,updated_at=updated_at WHERE id=?",updates);
            if(!existing.isEmpty())repo.jdbc().update("DELETE FROM finance_records WHERE id IN ("+placeholders(existing.size())+")",
                    existing.keySet().toArray());
            if(!writes.isEmpty())repo.jdbc().batchUpdate(FinanceRepository.UPSERT,writes);
            return new Page(text(records.getLast().get("id")),records.size(),existing.size(),writes.size());
        });
    }
    static Page bills(Scope scope,String shop,String zone,String cursor){
        return transaction(()->{
            if(!repo.writableShop(scope.organizationId(),shop))throw bad("Shop removed during maintenance");
            var records=repo.jdbc().queryForList("""
                SELECT * FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                WHERE organization_id=? AND shop_id=? AND kind='BILL' AND source='API' AND status<>'DATE_PENDING' AND id>?
                ORDER BY id LIMIT ? FOR UPDATE
                """,scope.organizationId(),shop,cursor,PAGE);
            if(records.isEmpty())return new Page(cursor,0,0,0);
            var statements=records.stream().map(r->text(r.get("external_id"))).distinct().toList();
            var args=new ArrayList<Object>(List.of(scope.organizationId(),shop));args.addAll(statements);
            var totals=repo.jdbc().queryForList("""
                SELECT statement_id,currency,CASE WHEN COUNT(revenue)=COUNT(*) THEN SUM(revenue) END income
                FROM finance_records FORCE INDEX(idx_finance_cost_statement)
                WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API' AND statement_id IN (
                """+placeholders(statements.size())+") GROUP BY statement_id,currency",args.toArray());
            var byStatement=new HashMap<String,Object>();
            totals.forEach(r->byStatement.put(text(r.get("statement_id"))+"\0"+text(r.get("currency")),r.get("income")));
            var updates=new ArrayList<Object[]>();
            for(var row:records){
                var raw=new LinkedHashMap<>(repo.read(text(row.get("raw_json"))));
                var day=LocalDate.parse(text(row.get("record_date")));
                if("TIKTOK_SHOP".equals(row.get("platform"))){
                    raw.putAll(FinanceSettlementDates.metadata(raw,zone));day=FinanceSettlementDates.day(raw,zone);
                }
                raw.put("_finance_income_version",2);
                Object income="PARTIAL".equals(row.get("status"))?null:
                        byStatement.get(text(row.get("external_id"))+"\0"+text(row.get("currency")));
                updates.add(new Object[]{income,Date.valueOf(day),repo.write(raw),row.get("id")});
            }
            archive(output.resolve(shop+"-bills-"+records.getFirst().get("id")+".json.gz"),records);
            repo.jdbc().batchUpdate("UPDATE finance_records SET revenue=?,record_date=?,raw_json=?,updated_at=updated_at WHERE id=?",updates);
            return new Page(text(records.getLast().get("id")),records.size(),0,updates.size());
        });
    }
    static void rebuild(Map<String,Object> shop)throws Exception{
        String id=text(shop.get("id")),zone=text(shop.get("timezone"));
        var scope=new Scope(text(shop.get("organization_id")),text(shop.get("user_id")));
        Path file=output.resolve(id+".json");
        var state=Files.exists(file)?new LinkedHashMap<>(repo.read(Files.readString(file))):new LinkedHashMap<String,Object>();
        if(Boolean.TRUE.equals(state.get("complete")))return;
        for(boolean bill:List.of(false,true)){
            String key=bill?"billCursor":"cursor",countKey=bill?"bills":"records";
            for(;;){
                String cursor=text(state.get(key));
                Page p=retry(()->bill?bills(scope,id,zone,cursor):settlements(scope,id,zone,cursor));
                if(p.records()==0)break;
                state.put(key,p.last());state.put(countKey,number(state.get(countKey))+p.records());
                state.put("removedFees",number(state.get("removedFees"))+p.removed());
                state.put("writtenFees",number(state.get("writtenFees"))+(bill?0:p.written()));
                checkpoint(file,state);
            }
        }
        state.put("complete",true);checkpoint(file,state);
        System.out.println("Completed "+id+": "+state.get("records")+" transactions, "+state.get("bills")+" bills");
    }
    public static void main(String[] args)throws Exception{
        output=Path.of(args[0]);Files.createDirectories(output);Files.deleteIfExists(output.resolve("complete.json"));
        int parallelism=Integer.parseInt(System.getenv().getOrDefault("BACKFILL_WORKERS","2"));
        if(parallelism<1||parallelism>4)throw new IllegalArgumentException("Maintenance workers must be between one and four");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
            ds.setMaximumPoolSize(parallelism+1);ds.setMinimumIdle(1);ds.setConnectionTimeout(15000);
            repo=new FinanceRepository(new JdbcTemplate(ds),new ObjectMapper());repo.jdbc().setQueryTimeout(20);
            var shops=repo.jdbc().queryForList("SELECT organization_id,user_id,id,timezone FROM finance_connections WHERE status<>'REMOVED' ORDER BY id");
            var priority=Arrays.asList(System.getenv().getOrDefault("BACKFILL_FIRST_SHOPS","").split(","));
            shops.sort(Comparator.comparingInt(shop->{int rank=priority.indexOf(text(shop.get("id")));return rank<0?Integer.MAX_VALUE:rank;}));
            try(var workers=Executors.newFixedThreadPool(parallelism)){
                var tasks=new ArrayList<Future<?>>();
                for(var shop:shops)tasks.add(workers.submit(()->{try{rebuild(shop);}catch(Exception e){throw new IllegalStateException("Basis backfill failed for "+shop.get("id"),e);}}));
                for(var task:tasks)task.get();
            }
            checkpoint(output.resolve("complete.json"),new LinkedHashMap<>(Map.of("shops",shops.size())));
        }
    }
}
