package com.yingdao.aicommerce.finance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** One bounded page or one return document per lease; remote I/O is outside database transactions. */
@Component
public class FinanceQianyiWorker {
    final FinanceQianyi integration;final FinanceRepository repo;
    static final int CONCURRENCY=3;
    final ScheduledExecutorService worker=Executors.newScheduledThreadPool(CONCURRENCY,r->{var t=new Thread(r,"finance-qianyi");t.setDaemon(true);return t;});
    public FinanceQianyiWorker(FinanceQianyi integration){this.integration=integration;this.repo=integration.repo;}
    @PostConstruct public void start(){
        // Independent shops can wait on HTTP concurrently; the client still
        // applies one shared request-rate limit and leases serialize each shop.
        for(int i=0;i<CONCURRENCY;i++)worker.scheduleWithFixedDelay(()->{
            try{com.yingdao.aicommerce.ops.MaintenanceGate.runIfOpen(this::tick);}catch(RuntimeException ignored){/* Durable per-connection state is retried on the next tick. */}
        },40+i,1,TimeUnit.SECONDS);
    }
    @PreDestroy public void stop(){worker.shutdownNow();}
    public void tick(){
        var found=repo.jdbc().queryForList("""
            SELECT q.* FROM finance_qianyi_connections q JOIN finance_connections c ON c.organization_id=q.organization_id AND c.id=q.shop_id
            WHERE q.enabled=TRUE AND c.status<>'REMOVED' AND (q.lease_until IS NULL OR q.lease_until<UTC_TIMESTAMP(6))
              AND q.next_at<=UTC_TIMESTAMP(6)
            ORDER BY q.next_at,q.shop_id LIMIT 1
            """);
        if(found.isEmpty())return;
        var config=found.getFirst();String org=text(config.get("organization_id")),shop=text(config.get("shop_id")),lease=UUID.randomUUID().toString();
        if(repo.jdbc().update("""
            UPDATE finance_qianyi_connections SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
            WHERE organization_id=? AND shop_id=? AND enabled=TRUE AND revision=? AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))
            """,lease,org,shop,config.get("revision"))!=1)return;
        try{
            readReturns(config,lease);
        }catch(RuntimeException failure){
            if(Thread.currentThread().isInterrupted())return;
            String message=failure instanceof com.yingdao.aicommerce.common.exception.BusinessException?text(failure.getMessage()):"千易同步暂未完成，后台将从原进度重试";
            if(message.length()>400)message=message.substring(0,400);
            int seconds=(int)Math.min(3600,30L*(1L<<Math.min(7,number(config.get("failures")))));
            repo.jdbc().update("""
                UPDATE finance_qianyi_connections SET status='ERROR',error_message=?,failures=failures+1,
                  next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND)
                WHERE organization_id=? AND shop_id=? AND lease_token=?
                """,message,seconds,org,shop,lease);
        }finally{
            repo.jdbc().update("UPDATE finance_qianyi_connections SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND shop_id=? AND lease_token=?",org,shop,lease);
        }
    }
    void readReturns(Map<String,Object> config,String lease){
        var checkpoint=config.get("checkpoint_json")==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(repo.read(text(config.get("checkpoint_json"))));
        if(number(config.get("source_version"))<2&&number(checkpoint.get("sourceVersion"))<2)checkpoint.clear();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        checkpoint=initializeCheckpoint(config,checkpoint,today);
        checkpoint=new LinkedHashMap<>(FinanceQianyiReceipt.plan(config,checkpoint,today));
        LocalDate from=LocalDate.parse(text(checkpoint.get("from"))),until=LocalDate.parse(text(checkpoint.get("until")));
        LocalDate to=from.plusDays(6);if(to.isAfter(until))to=until;
        long page=Math.max(1,number(checkpoint.get("page")));
        // Fix the query window before HTTP so a retry cannot advance the watermark.
        repo.jdbc().update("UPDATE finance_qianyi_connections SET checkpoint_json=?,status='SYNCING' WHERE organization_id=? AND shop_id=? AND lease_token=?",
            repo.write(checkpoint),config.get("organization_id"),config.get("shop_id"),lease);
        var parameters=new LinkedHashMap<String,Object>();
        parameters.put("shopIdList",List.of(new java.math.BigInteger(text(config.get("remote_shop_id")))));
        boolean receiptScan=Boolean.TRUE.equals(checkpoint.get("receiptScan"));
        if(receiptScan){
            parameters.put("typeList",List.of("SALES_RETURN"));parameters.put("returnStatus","FINISH");
            parameters.put("receiveTimeFrom",from+" 00:00:00");parameters.put("receiveTimeTo",to+" 23:59:59");
        }else{
            parameters.put("withoutType",true);parameters.put("updateTimeFrom",from.toString());parameters.put("updateTimeTo",to.toString());
        }
        parameters.put("page",page);parameters.put("pageSize",100);
        var result=integration.client.query(integration.credentials(config),"QUERY_RETURN_ORDER_LIST",parameters);
        if(result.items().isEmpty()&&(page-1)*100<result.total())throw bad("千易售后分页不完整，保留当前页重试");
        boolean done=false;
        if(page*100<result.total())checkpoint.put("page",page+1);
        else if(to.isBefore(until)){checkpoint.put("from",to.plusDays(1).toString());checkpoint.put("page",1);}
        else done=true;
        boolean receiptFinished=done&&receiptScan;
        if(done&&checkpoint.get("backfillResume") instanceof Map<?,?>){
            checkpoint=new LinkedHashMap<>(map(checkpoint.get("backfillResume")));
            done=markerOnly(checkpoint);
        }
        if(receiptFinished)checkpoint.put("receiptScanFinished",true);
        integration.acceptPage(config,lease,FinanceQianyiReceipt.enrich(integration,config,result),checkpoint,done);
    }
    /** Completion flags have no page/window to resume. Never discard a real cursor. */
    static boolean markerOnly(Map<String,Object> checkpoint){
        return checkpoint.keySet().stream().allMatch(Set.of("sourceVersion","sourcesChanged")::contains);
    }
    static LinkedHashMap<String,Object> initializeCheckpoint(Map<String,Object> config,Map<String,Object> saved,LocalDate today){
        var checkpoint=new LinkedHashMap<>(saved);
        if(markerOnly(checkpoint)){
            LocalDate from=config.get("last_success_at")==null||number(config.get("source_version"))<2?LocalDate.parse(text(config.get("sync_start")))
                :instant(config.get("last_success_at")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(2);
            checkpoint.put("from",from.toString());checkpoint.put("until",today.toString());checkpoint.put("page",1);
            checkpoint.put("sourceVersion",2);
        }
        return checkpoint;
    }
    void readCosts(Map<String,Object> config,String lease){
        String org=text(config.get("organization_id")),shop=text(config.get("shop_id"));
        var pending=repo.jdbc().queryForList("""
            SELECT * FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=? AND cost_status='PENDING'
              AND next_at<=UTC_TIMESTAMP(6) ORDER BY next_at,return_number LIMIT 1
            """,org,shop);
        if(pending.isEmpty())return;
        var document=pending.getFirst();var source=repo.read(text(document.get("data_json")));
        var matches=repo.jdbc().queryForList("""
            SELECT id,currency FROM finance_records FORCE INDEX(idx_finance_settled_order)
            WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND source='API' AND order_id=? LIMIT 2
            """,org,shop,document.get("order_id"));
        if(matches.size()!=1){
            integration.acceptCosts(config,lease,document,FinanceQianyiEvidence.pending("平台订单尚未同步或无法在本店唯一匹配"),Map.of());return;
        }
        if(document.get("receipt_date")==null){
            integration.acceptCosts(config,lease,document,FinanceQianyiEvidence.pending("千易尚未返回实际收货或完成时间"),matches.getFirst());return;
        }
        if(!Boolean.TRUE.equals(config.get("cost_basis_verified"))){
            integration.acceptCosts(config,lease,document,new FinanceQianyiEvidence.Result("WAIT_COST_BASIS","售后单已同步；首次接入需核验千易库存成本币种和计价口径",List.of()),matches.getFirst());return;
        }
        if(text(source.get("warehouse")).isBlank()){
            integration.acceptCosts(config,lease,document,FinanceQianyiEvidence.pending("千易退货单缺少仓库，未跨仓库取成本"),matches.getFirst());return;
        }
        var logs=new ArrayList<Map<String,Object>>();
        for(String bill:FinanceQianyiEvidence.documents(source)){
            for(int page=1;page<=5;page++){
                var parameters=inventoryParameters(source,bill,page);
                var response=integration.client.query(integration.credentials(config),"QUERY_INVENTORY_LOG_LIST",parameters);
                if(response.items().isEmpty()&&(page-1L)*100<response.total())throw bad("千易库存流水分页不完整，保留该退货单重试");
                logs.addAll(response.items());
                if(page*100L>=response.total())break;
                if(page==5)throw bad("单笔千易退货库存流水超过 500 行，未截断或计入部分成本");
            }
        }
        integration.acceptCosts(config,lease,document,FinanceQianyiEvidence.verify(source,logs),matches.getFirst());
    }
    static Map<String,Object> inventoryParameters(Map<String,Object> source,String bill,int page){
        long created=number(source.get("createTime")),updated=number(source.get("updateTime"));
        if(created<=0||updated<created)throw bad("千易退货单创建或更新时间缺失，未猜测库存流水范围");
        var zone=ZoneId.of("Asia/Shanghai");
        String from=Instant.ofEpochMilli(created).atZone(zone).toLocalDate().minusDays(2)+" 00:00:00";
        String to=Instant.ofEpochMilli(updated).atZone(zone).toLocalDate().plusDays(2)+" 23:59:59";
        var parameters=new LinkedHashMap<String,Object>();
        parameters.put("billNumber",bill);parameters.put("warehouseName",text(source.get("warehouse")));
        parameters.put("typeList",List.of("I_RETURN"));parameters.put("inventoryType","AVAILABLE");
        parameters.put("operateTimeFrom",from);parameters.put("operateTimeTo",to);
        parameters.put("createTimeFrom",from);parameters.put("createTimeTo",to);
        parameters.put("page",page);parameters.put("pageSize",100);
        return parameters;
    }
}
