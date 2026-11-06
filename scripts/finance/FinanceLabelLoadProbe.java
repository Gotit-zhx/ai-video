package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Isolated MySQL load verification. Refuses databases outside the disposable finance_qa namespace. */
public final class FinanceLabelLoadProbe {
    public static void main(String[] args)throws Exception{
        var qa=new FinanceConfigurationIntegrationTest();qa.migrate();
        Path directory=Files.createTempDirectory("label-load-probe-"),source=Path.of(args[0]);
        var scope=new Scope("invoice-load-probe","qa");var labels=new FinanceLabelCosts(qa.repo,null,directory.toString());
        try{
            // Only accelerate fixture creation in this disposable schema. The exact
            // production index used by invoice matching and all invoice indexes stay intact.
            var unused=qa.db.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='finance_records' AND non_unique=1
                  AND index_name<>'idx_finance_settled_order'
                """,String.class);
            if(!unused.isEmpty())qa.db.execute("ALTER TABLE finance_records "+String.join(",",
                unused.stream().map(name->"DROP INDEX `"+name.replace("`","``")+"`").toList()));
            for(int i=0;i<100;i++)qa.db.update("""
                INSERT INTO finance_connections(id,organization_id,user_id,platform,external_shop_id,name,status,currency,timezone,token_group_id,access_token_encrypted,scopes_json)
                VALUES(?,'invoice-load-probe','qa','TIKTOK_SHOP',?,?,'ACTIVE','THB','Asia/Bangkok','qa','unused','[]')
                ""","bench-shop-"+i,"bench-external-"+i,"Bench "+i);
            var batch=new ArrayList<FinanceLabelInvoice.Line>();long seeded=System.nanoTime();
            java.util.function.Consumer<List<FinanceLabelInvoice.Line>> write=lines->{
                var params=new ArrayList<Object>();
                for(var row:lines)if(!row.orderId().isBlank()){
                    String shop="bench-shop-"+Math.floorMod(row.orderId().hashCode(),100);
                    Collections.addAll(params,hash(shop+":"+row.orderId()),shop,row.orderId(),row.orderId());
                }
                if(params.isEmpty())return;
                qa.db.update("""
                    INSERT IGNORE INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,record_date,currency,amount,source,raw_json)
                    VALUES
                    """+String.join(",",Collections.nCopies(params.size()/4,"(?,'invoice-load-probe','qa',?,'TIKTOK_SHOP','ORDER',?,?,'2026-08-20','THB',1,'API','{}')")),params.toArray());
            };
            var parsed=FinanceLabelInvoice.parse(source,directory.resolve("seed-strings"),line->{
                batch.add(line);if(batch.size()==500){write.accept(batch);batch.clear();}
            });
            if(!batch.isEmpty())write.accept(batch);batch.clear();
            System.out.printf(Locale.ROOT,"Seeded %d invoice rows across 100 test shops in %.2fs%n",parsed.rows(),(System.nanoTime()-seeded)/1e9);
            Map<String,Object> uploaded;long started=System.nanoTime();
            try(var input=Files.newInputStream(source)){uploaded=labels.upload(scope,"qa","source.xlsx",input);}
            String id=text(uploaded.get("id"));
            if(!"QUEUED".equals(uploaded.get("status")))throw new AssertionError("Upload must only queue");
            String token=UUID.randomUUID().toString();
            qa.db.update("UPDATE finance_label_worker_lock SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=1",token);
            long parseStart=System.nanoTime();
            labels.parse(qa.db.queryForMap("SELECT * FROM finance_label_imports WHERE id=?",id),token);
            double ingest=(System.nanoTime()-parseStart)/1e9;
            System.out.printf(Locale.ROOT,"Parsed and staged %d source rows in %.2fs; no partial totals published%n",parsed.rows(),ingest);
            long matchStart=System.nanoTime();
            labels.reconcile(scope.organizationId(),qa.db.queryForObject("SELECT requested_revision FROM finance_label_state WHERE organization_id=?",Long.class,scope.organizationId()),token);
            double match=(System.nanoTime()-matchStart)/1e9;
            var result=qa.db.queryForMap("SELECT status,row_count,matched_count,unmatched_count,billing_failed_count,amount_cny FROM finance_label_imports WHERE id=?",id);
            if(!"READY".equals(result.get("status"))||number(result.get("row_count"))!=parsed.rows())throw new AssertionError(result);
            if(decimal(result.get("amount_cny")).compareTo(parsed.amount())!=0)throw new AssertionError("Source amount mismatch");
            var totals=qa.db.queryForMap("SELECT COUNT(*) orders,SUM(amount_cny) amount,SUM(pending_count) pending FROM finance_label_order_totals WHERE organization_id=?",scope.organizationId());
            labels.cleanup(token);
            try(var input=Files.newInputStream(source)){
                if(!Boolean.TRUE.equals(labels.upload(scope,"qa","source-copy.xlsx",input).get("duplicate")))throw new AssertionError("Duplicate file was accepted twice");
            }
            System.out.printf(Locale.ROOT,"PASS import=%s totals=%s stageSeconds=%.2f matchPublishSeconds=%.2f totalSeconds=%.2f heapLimitMB=%d%n",
                result,totals,ingest,match,(System.nanoTime()-started)/1e9,Runtime.getRuntime().maxMemory()/1048576);
        }finally{
            labels.stop();qa.closeDataSource();
            try(var paths=Files.list(directory)){for(var path:paths.toList())Files.deleteIfExists(path);}
            Files.deleteIfExists(directory);
        }
    }
}
