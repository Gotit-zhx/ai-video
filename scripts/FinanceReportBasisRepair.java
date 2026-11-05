package com.yingdao.aicommerce.finance;

import java.nio.file.*;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Bounded date-only migration. One small replaced receipt; no raw-row archives or application logs. */
public final class FinanceReportBasisRepair {
    static final Set<String> META=Set.of("_finance_statement_time","_finance_payment_time","_finance_payment_status",
        "_finance_payment_id","_finance_date_version","_finance_timezone","_finance_export_statement_date",
        "_finance_date_source","_finance_date_basis","_finance_seller_type","_finance_reporting_month",
        "_finance_original_settlement_date","_finance_reporting_source","_finance_legacy_reporting_month",
        "_finance_legacy_original_settlement_date","_finance_legacy_reporting_source");
    static Object invariant(FinanceRepository repo,Map<String,Object> original){
        var row=new LinkedHashMap<>(original);row.remove("record_date");
        var raw=new LinkedHashMap<>(repo.read(text(row.remove("raw_json"))));
        META.forEach(raw::remove);row.put("raw",raw);
        return FinanceOrderCosts.canonical(row);
    }
    static void persist(FinanceRepository repo,Path file,Map<String,Object> progress)throws Exception{
        Path next=Path.of(file+".tmp");Files.writeString(next,repo.write(progress));
        Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static final String BATCH_SQL="""
            SELECT id,order_id FROM finance_records FORCE INDEX(idx_finance_cost_statement)
            WHERE organization_id=? AND shop_id=? AND kind=? AND statement_id=? AND source='API'
              AND (order_id>? OR (order_id=? AND id>?))
              AND (record_date<>? OR COALESCE(JSON_UNQUOTE(raw_json->'$._finance_date_version'),'')<>'4'
                OR COALESCE(JSON_UNQUOTE(raw_json->'$._finance_date_basis'),'')<>'LOCAL_BALANCE')
            ORDER BY order_id,id LIMIT 200
            """;
    static List<Map<String,Object>> nextBatch(JdbcTemplate db,String org,String shop,String kind,String statement,
                                             String orderCursor,String idCursor,LocalDate day){
        // idx_finance_cost_statement ends in (order_id, primary id), not id alone.
        // Traverse that order so a large bill is not repeatedly scanned/filesorted.
        return db.queryForList(BATCH_SQL,org,shop,kind,statement,orderCursor,orderCursor,idCursor,day.toString());
    }
    public static void main(String[] args)throws Exception{
        ((ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger("ROOT").setLevel(ch.qos.logback.classic.Level.OFF);
        String url=System.getenv("DB_URL");
        if(url.matches("(?i).*rewriteBatchedStatements=.*"))url=url.replaceAll("(?i)rewriteBatchedStatements=[^&]*","rewriteBatchedStatements=true");
        else url+=(url.contains("?")?"&":"?")+"rewriteBatchedStatements=true";
        var ds=new DriverManagerDataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var db=new JdbcTemplate(ds);db.setQueryTimeout(15);
        var repo=new FinanceRepository(db,new ObjectMapper());Path receipt=Path.of(args[0]);
        var progress=new LinkedHashMap<String,Object>();progress.put("startedAt",Instant.now().toString());
        progress.put("complete",false);progress.put("dateVersion",FinanceSettlementDates.VERSION);
        progress.put("sourceFieldsAndAmountsUnchanged",true);progress.put("syncCheckpointsWritten",false);
        long totalChanged=0,totalRows=0,totalBills=0;int shopsDone=0;
        try{
            var shops=db.queryForList("""
                SELECT organization_id,id,timezone FROM finance_connections
                WHERE platform='TIKTOK_SHOP' AND seller_type='LOCAL' AND status<>'REMOVED'
                ORDER BY (id='cacf3754-9038-4143-88d0-7205c8f452b6') DESC,id
                """);
            progress.put("localShops",shops.size());
            for(boolean recent:List.of(true,false)){
            for(var shopRow:shops){
                String shop=text(shopRow.get("id")),org=text(shopRow.get("organization_id")),zone=text(shopRow.get("timezone"));
                Scope scope=companyScope(org);
                var bills=db.queryForList("""
                    SELECT id,external_id,JSON_OBJECT('id',external_id,
                      'statement_time',COALESCE(raw_json->'$._finance_statement_time',raw_json->'$.statement_time'),
                      'payment_time',COALESCE(raw_json->'$._finance_payment_time',raw_json->'$.payment_time'),
                      'payment_status',COALESCE(raw_json->'$._finance_payment_status',raw_json->'$.payment_status'),
                      'payment_id',COALESCE(raw_json->'$._finance_payment_id',raw_json->'$.payment_id')) metadata
                    FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                    WHERE organization_id=? AND shop_id=? AND kind='BILL' AND source='API' AND status='SETTLED'
                    """+(recent?" AND record_date>='2026-09-29'":" AND record_date<'2026-09-29'")+
                    " ORDER BY record_date DESC,id",org,shop);
                for(var billRow:bills){
                    var bill=repo.read(text(billRow.get("metadata")));String statement=text(billRow.get("external_id"));
                    if(!FinanceSettlementDates.completed(bill))throw new IllegalStateException("Completed bill lacks authoritative metadata");
                    LocalDate day=FinanceSettlementDates.day(bill,zone,"LOCAL");
                    // BILL goes last. Its metadata is never treated as proof that all detail rows are repaired.
                    for(String kind:List.of("SETTLEMENT","FEE","BILL")){
                        String cursor="",orderCursor="";
                        for(;;){
                            var batch=nextBatch(db,org,shop,kind,statement,orderCursor,cursor,day);
                            var ids=batch.stream().map(row->text(row.get("id"))).toList();
                            if(ids.isEmpty())break;
                            boolean verifyIdempotence=totalRows==0;
                            int changed=repo.transaction(()->{
                                // A concurrent sync may not replace the source bill while this batch is derived.
                                var current=db.queryForMap("SELECT raw_json FROM finance_records WHERE id=? FOR SHARE",billRow.get("id"));
                                var currentRaw=repo.read(text(current.get("raw_json")));
                                for(String f:List.of("statement_time","payment_time","payment_status")){
                                    Object now=currentRaw.containsKey("_finance_"+f)?currentRaw.get("_finance_"+f):currentRaw.get(f);
                                    if(!text(now).equals(text(bill.get(f))))throw new IllegalStateException("Bill source changed; stop for a fresh bounded pass");
                                }
                                String read="SELECT * FROM finance_records WHERE id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id";
                                var before=db.queryForList(read+" FOR UPDATE",ids.toArray());
                                String hashBefore=hash(repo.write(before.stream().map(r->invariant(repo,r)).toList()));
                                int count=repo.repairSettlementDates(scope,shop,bill,ids);
                                var after=db.queryForList(read,ids.toArray());
                                if(!hashBefore.equals(hash(repo.write(after.stream().map(r->invariant(repo,r)).toList()))))
                                    throw new IllegalStateException("Business-field invariant failed; transaction rolled back");
                                if(after.stream().anyMatch(r->!day.toString().equals(text(r.get("record_date")))))
                                    throw new IllegalStateException("Date projection failed; transaction rolled back");
                                if(verifyIdempotence&&repo.repairSettlementDates(scope,shop,bill,ids)!=0)
                                    throw new IllegalStateException("Date repair is not idempotent; transaction rolled back");
                                return count;
                            });
                            totalChanged+=changed;totalRows+=ids.size();cursor=ids.getLast();
                            orderCursor=text(batch.getLast().get("order_id"));
                        }
                    }
                    totalBills++;
                    progress.put("shop",shop);progress.put("bills",totalBills);progress.put("rowsChecked",totalRows);progress.put("rowsChanged",totalChanged);
                    progress.put("phase",recent?"recent":"history");progress.put("shopsCompleted",shopsDone);
                    progress.put("updatedAt",Instant.now().toString());persist(repo,receipt,progress);
                }
                if(recent)shopsDone++;
                progress.put("shopsCompleted",shopsDone);persist(repo,receipt,progress);
            }
            if(recent){progress.put("recentShopsComplete",true);persist(repo,receipt,progress);}
            }
            progress.put("complete",true);progress.put("completedAt",Instant.now().toString());persist(repo,receipt,progress);
        }catch(Throwable failure){
            progress.put("stopped",true);progress.put("errorClass",failure.getClass().getSimpleName());
            progress.put("updatedAt",Instant.now().toString());persist(repo,receipt,progress);throw failure;
        }
    }
}
