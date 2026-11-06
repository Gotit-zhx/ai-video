package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One bounded scan queues only GIFT orders; the normal cost worker owns recalculation. */
public final class FinanceGiftCostRepair {
    static String cursorFor(Map<String,Object> resume,String shop){
        String previous=text(resume.get("shop"));
        if(!previous.isBlank()&&(shop.compareTo(previous)<0
            ||shop.equals(previous)&&Boolean.TRUE.equals(resume.get("shopComplete"))))return null;
        return shop.equals(previous)?text(resume.get("cursorId")):"";
    }
    static boolean currentCost(FinanceRepository repo,Map<String,Object> row){
        return FinanceOrderCosts.sourceHash(repo,repo.read(text(row.get("raw_json"))),
            text(row.get("source_status"))).equals(text(row.get("source_hash")));
    }
    interface Pause {void sleep(long millis)throws InterruptedException;}
    static <T>T retry(java.util.function.Supplier<T> action,Pause pause)throws InterruptedException{
        for(int attempt=0;;attempt++){
            try{return action.get();}
            catch(org.springframework.dao.TransientDataAccessException|org.springframework.transaction.TransactionTimedOutException failure){
                if(attempt>=4)throw failure;
                pause.sleep(Math.min(2000,250L<<attempt));
            }
        }
    }
    static void verifyCost(Map<String,Object> cost){
        for(var line:rows(cost.get("lines"))){
            if(!FinanceOrderCosts.giftSku(line.get("sellerSku")))continue;
            if(Boolean.TRUE.equals(line.get("excluded"))){
                if(decimal(line.get("amountCny"))==null||decimal(line.get("amountCny")).signum()!=0)
                    throw new IllegalStateException("Excluded GIFT has a nonzero cost");
                continue;
            }
            if(!Boolean.TRUE.equals(line.get("fixedGift"))||!text(line.get("issue")).isBlank()
                ||decimal(line.get("amountCny"))==null||decimal(line.get("amountCny")).signum()!=0
                ||decimal(line.get("unitPriceCny"))==null||decimal(line.get("unitPriceCny")).signum()!=0)
                throw new IllegalStateException("GIFT cost is not confirmed zero");
        }
        if(!"EXCLUDED".equals(text(cost.get("status")))&&rows(cost.get("lines")).stream()
            .noneMatch(l->FinanceOrderCosts.giftSku(l.get("sellerSku"))))
            throw new IllegalStateException("GIFT source has no derived GIFT line");
    }
    static void awaitCosts(FinanceRepository repo,Map<String,String> expected)throws Exception{
        if(expected.isEmpty())return;
        String slots=String.join(",",Collections.nCopies(expected.size(),"?"));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        for(;;){
            var costs=repo.jdbc().queryForList("""
                SELECT o.id order_record_id,o.status source_status,o.raw_json,c.source_hash,c.data_json
                FROM finance_records o LEFT JOIN finance_order_costs c ON c.order_record_id=o.id
                WHERE o.id IN (
                """+slots+")",
                expected.keySet().toArray());
            boolean current=costs.size()==expected.size();
            // Normal synchronization can refresh an order while its cost job runs.
            // Compare against source and cost from the same read, not an obsolete page hash.
            for(var row:costs)current&=currentCost(repo,row);
            if(current){
                for(var row:costs)if(FinanceOrderCosts.hasGift(repo.read(text(row.get("raw_json")))))
                    verifyCost(repo.read(text(row.get("data_json"))));
                return;
            }
            if(System.nanoTime()>=deadline)throw new IllegalStateException("GIFT cost queue has not caught up; retain cursor");
            Thread.sleep(1000);
        }
    }
    static void save(FinanceRepository repo,Path file,Map<String,Object> receipt)throws Exception{
        receipt.put("updatedAt",Instant.now().toString());
        Path tmp=Path.of(file+".tmp");Files.writeString(tmp,repo.write(receipt));
        Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args)throws Exception{
        ((ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(ch.qos.logback.classic.Level.OFF);
        var db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        db.setQueryTimeout(15);
        var repo=new FinanceRepository(db,new ObjectMapper());Path file=Path.of(args[0]);
        var receipt=new LinkedHashMap<String,Object>();
        if(Files.exists(file)){
            receipt.putAll(repo.read(Files.readString(file)));
            if(!Boolean.TRUE.equals(receipt.get("stopped"))||number(receipt.get("verificationVersion"))!=2)
                throw new IllegalStateException("Only an explicitly stopped version-2 scan can resume");
            receipt.remove("stopped");receipt.remove("errorClass");
            receipt.put("resumedAt",Instant.now().toString());receipt.put("resumeCount",number(receipt.get("resumeCount"))+1);
        }else receipt.put("startedAt",Instant.now().toString());
        receipt.put("verificationVersion",2);
        receipt.put("complete",false);receipt.put("sourceRecordsWritten",false);receipt.put("syncCheckpointsWritten",false);
        long scanned=number(receipt.get("ordersScanned")),gifts=number(receipt.get("giftOrders")),queued=number(receipt.get("ordersQueued"));
        int completed=(int)number(receipt.get("shopsCompleted"));
        var resume=new LinkedHashMap<>(receipt);
        try{
            var shops=db.queryForList("SELECT organization_id,id FROM finance_connections WHERE status<>'REMOVED' AND platform IN ('SHOPEE','TIKTOK_SHOP') ORDER BY id");
            receipt.put("shops",shops.size());
            for(var shop:shops){
                String org=text(shop.get("organization_id")),shopId=text(shop.get("id"));
                String idCursor=cursorFor(resume,shopId);if(idCursor==null)continue;
                receipt.put("shop",shopId);receipt.put("cursorId",idCursor);receipt.put("shopComplete",false);save(repo,file,receipt);
                for(;;){
                    var page=db.queryForList("""
                        SELECT o.id,o.order_id,o.status,c.source_hash,
                          CASE WHEN EXISTS(SELECT 1 FROM JSON_TABLE(
                            CASE o.platform WHEN 'TIKTOK_SHOP' THEN o.raw_json->'$.line_items' ELSE o.raw_json->'$.item_list' END,
                            '$[*]' COLUMNS(seller_sku VARCHAR(255) PATH '$.seller_sku',
                              model_sku VARCHAR(255) PATH '$.model_sku',item_sku VARCHAR(255) PATH '$.item_sku')) sk
                            WHERE UPPER(TRIM(CASE o.platform WHEN 'TIKTOK_SHOP' THEN sk.seller_sku
                              ELSE COALESCE(NULLIF(TRIM(sk.model_sku),''),sk.item_sku) END))='GIFT')
                          THEN o.raw_json ELSE NULL END gift_raw
                        FROM (SELECT id,order_id,status,platform,raw_json FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                          WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND source='API'
                            AND id>?
                          ORDER BY id LIMIT 500) o
                        LEFT JOIN finance_order_costs c ON c.order_record_id=o.id ORDER BY o.id
                        """,org,shopId,idCursor);
                    if(page.isEmpty())break;
                    var jobs=new ArrayList<Object[]>();var expected=new LinkedHashMap<String,String>();long pageGifts=0;
                    for(var row:page){
                        if(row.get("gift_raw")==null)continue;
                        var raw=repo.read(text(row.get("gift_raw")));
                        if(!FinanceOrderCosts.hasGift(raw))throw new IllegalStateException("SQL/Java GIFT identity mismatch");
                        pageGifts++;
                        String sourceHash=FinanceOrderCosts.sourceHash(repo,raw,text(row.get("status")));
                        expected.put(text(row.get("id")),sourceHash);
                        if(sourceHash.equals(text(row.get("source_hash"))))continue;
                        jobs.add(new Object[]{row.get("id"),org,shopId});
                    }
                    if(!jobs.isEmpty())retry(()->repo.transaction(()->{
                        if(!repo.writableShop(org,shopId))throw new IllegalStateException("Shop was removed during scan");
                        new FinanceOrderCosts(repo).insertRows(
                            "INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES ",
                            "(?,?,?,0)"," ON DUPLICATE KEY UPDATE priority=0",jobs);
                        return null;
                    }),Thread::sleep);
                    awaitCosts(repo,expected);
                    scanned+=page.size();gifts+=pageGifts;queued+=jobs.size();idCursor=text(page.getLast().get("id"));
                    receipt.put("shop",shopId);receipt.put("ordersScanned",scanned);receipt.put("giftOrders",gifts);
                    receipt.put("ordersQueued",queued);receipt.put("shopsCompleted",completed);
                    receipt.put("giftOrdersVerified",gifts);receipt.put("cursorId",idCursor);
                    save(repo,file,receipt);
                }
                completed++;receipt.put("shopsCompleted",completed);receipt.put("shopComplete",true);save(repo,file,receipt);
            }
            receipt.put("complete",true);save(repo,file,receipt);
        }catch(Throwable failure){
            receipt.put("stopped",true);receipt.put("errorClass",failure.getClass().getSimpleName());save(repo,file,receipt);throw failure;
        }
    }
}
