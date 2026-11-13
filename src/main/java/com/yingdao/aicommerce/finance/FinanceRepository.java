package com.yingdao.aicommerce.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Repository
public class FinanceRepository {
    static final String UPSERT="""
        INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
        statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,raw_json)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),statement_id=VALUES(statement_id),
        category=VALUES(category),status=VALUES(status),record_date=VALUES(record_date),currency=VALUES(currency),
        amount=VALUES(amount),revenue=VALUES(revenue),impressions=VALUES(impressions),clicks=VALUES(clicks),
        conversions=VALUES(conversions),raw_json=VALUES(raw_json),updated_at=UTC_TIMESTAMP(6)
        """;
    private final JdbcTemplate db; private final ObjectMapper json;
    private final ThreadLocal<Map<Query,Long>> pageCounts=new ThreadLocal<>();
    private final ThreadLocal<Long> authoritativePageTotal=new ThreadLocal<>();
    private record ProjectionWindow(Filter semanticMonth,java.time.LocalDate day) {}
    private final ThreadLocal<ProjectionWindow> projectionWindow=new ThreadLocal<>();
    public FinanceRepository(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public JdbcTemplate jdbc(){return db;}
    <T>T transaction(java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setTimeout(15);
        return tx.execute(status->action.get());
    }
    <T>T readSnapshot(java.util.function.Supplier<T> action){
        return readSnapshot(15,action);
    }
    <T>T readSnapshot(int seconds,java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(seconds);
        return tx.execute(status->{
            boolean owner=pageCounts.get()==null;
            if(owner)pageCounts.set(new HashMap<>());
            try{return action.get();}finally{if(owner)pageCounts.remove();}
        });
    }
    /** A clean projection may supply one exact count only in the same read snapshot. */
    <T>T withAuthoritativePageTotal(long total,java.util.function.Supplier<T> action){
        if(total<0)throw new IllegalArgumentException("Page total must be nonnegative");
        if(pageCounts.get()==null
                ||!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ))
            throw new IllegalStateException("An authoritative page total requires a read-only repeatable-read snapshot");
        Long previous=authoritativePageTotal.get();authoritativePageTotal.set(total);
        try{return action.get();}
        finally{if(previous==null)authoritativePageTotal.remove();else authoritativePageTotal.set(previous);}
    }
    /** Exact totals are reusable only inside this repeatable-read snapshot. */
    private long pageTotal(String sql,List<Object> args,int page,int size,int returned){
        var key=new Query(sql,List.copyOf(args));var counts=pageCounts.get();
        Long authoritative=authoritativePageTotal.get();
        if(authoritative!=null){
            // Consume before validation so a failed page cannot leak its total to another read.
            authoritativePageTotal.remove();long offset=(page-1L)*size;
            boolean mismatch=returned>0&&offset+returned>authoritative
                    ||returned<size&&(returned>0||page==1)&&offset+returned!=authoritative
                    ||returned==0&&page>1&&offset<authoritative;
            if(mismatch)throw new IllegalStateException("Authoritative page total does not match the observed page");
            return authoritative;
        }
        if(counts!=null&&counts.containsKey(key))return counts.get(key);
        // An empty later page proves only that its offset is beyond the end.
        long total=returned<size&&(returned>0||page==1)?(page-1L)*size+returned
                :number(db.queryForObject(sql,Long.class,args.toArray()));
        if(counts!=null)counts.put(key,total);
        return total;
    }
    /** Lock before a financial write so removal waits for committed work and blocks late responses. */
    boolean writableShop(String organization,String shop){
        return !db.queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED' FOR SHARE",
                organization,shop).isEmpty();
    }
    void requireShopWrite(Scope scope,String shop){
        if(!writableShop(scope.organizationId(),shop))throw bad("财务店铺已移除或正在删除，请刷新页面");
    }
    private void rejectDeletedWrite(Scope scope,String shop){
        if("UNASSIGNED".equals(shop))return;
        var connection=db.queryForList("SELECT status FROM finance_connections WHERE organization_id=? AND id=? FOR SHARE",scope.organizationId(),shop);
        if(!connection.isEmpty()){
            if("REMOVED".equals(connection.getFirst().get("status")))throw bad("财务店铺已移除，忽略迟到的同步数据");
        }else if(!db.queryForList("SELECT shop_id FROM finance_shop_deletions WHERE organization_id=? AND shop_id=? FOR SHARE",
                scope.organizationId(),shop).isEmpty())throw bad("财务店铺已删除，忽略迟到的同步数据");
    }
    public Map<String,Object> read(String text){try{return map(json.readValue(text,Map.class));}catch(Exception e){throw bad("财务数据格式不正确");}}
    public String write(Object value){return json.writeValueAsString(value);}
    public void save(Scope s,Entry e){
        validate(e);
        // Retry only this idempotent database write, after its whole transaction
        // has rolled back. A caller-owned transaction must be retried by its owner.
        boolean nested=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        for(int attempt=0;;attempt++){
            try{transaction(()->{rejectDeletedWrite(s,e.shopId());saveEntry(s,e);return null;});return;}
            catch(org.springframework.dao.PessimisticLockingFailureException failure){
                if(nested||attempt>=2||Thread.currentThread().isInterrupted())throw failure;
                try{Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(25,76)*(attempt+1));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure;}
            }
        }
    }
    void saveEntry(Scope s,Entry e){
        e=new FinanceReportingMonths(this).apply(s,e);
        boolean settlement=e.source().equals("API")&&e.kind().equals("SETTLEMENT")
                &&FinanceSettlementBreakdown.supported(e.platform(),e.raw());
        var breakdown=settlement?FinanceSettlementBreakdown.parse(e.platform(),e.raw(),e.amount()):null;
        var revenue=breakdown==null?e.revenue():breakdown.goods();
        if(e.kind().equals("BILL"))revenue=billGoods(s,e.shopId(),e.externalId(),e.currency(),e.status());
        db.update(UPSERT,entryArgs(s,e,revenue));
        if(breakdown!=null)replaceFees(s,e,breakdown);
        FinanceOrderCosts.enqueue(this,s,e);
        new FinanceOrderCharges(this).orderArrived(s,e);
        FinanceLabelCosts.orderArrived(this,s,e);
    }
    Object[] entryArgs(Scope s,Entry e,java.math.BigDecimal revenue){
        return new Object[]{id(s,e),s.organizationId(),s.userId(),e.shopId(),e.platform(),e.kind(),e.externalId(),e.orderId(),
                e.statementId(),e.category(),e.status(),Date.valueOf(e.date()),e.currency(),e.amount(),revenue,
                e.impressions(),e.clicks(),e.conversions(),e.source(),write(e.raw())};
    }
    java.math.BigDecimal billGoods(Scope s,String shop,String statement,String currency,String status){
        if(status.equals("PARTIAL"))return null;
        return db.queryForObject("""
            SELECT CASE WHEN COUNT(*)>0 AND COUNT(revenue)=COUNT(*) THEN SUM(revenue) END
            FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT'
              AND source='API' AND statement_id=? AND currency=?
            """,java.math.BigDecimal.class,s.organizationId(),shop,statement,currency);
    }
    /** Resumable historical derivation. Lock the latest row; never replay stale raw data over a live sync. */
    int rebuildSettlements(Scope scope,String shop,List<String> records){
        if(records.size()>20)throw new IllegalArgumentException("Settlement maintenance batch is limited to 20 rows");
        return transaction(()->{
            int derived=0;
            for(String record:records)if(rebuildSettlement(scope,shop,record))derived++;
            return derived;
        });
    }
    boolean rebuildSettlement(Scope scope,String shop,String record){
        return transaction(()->{
            if(!writableShop(scope.organizationId(),shop))return false;
            var found=db.queryForList("SELECT * FROM finance_records WHERE organization_id=? AND shop_id=? AND id=? AND kind='SETTLEMENT' AND source='API' FOR UPDATE",
                    scope.organizationId(),shop,record);
            if(found.isEmpty())return false;var row=found.getFirst();var raw=read(text(row.get("raw_json")));
            if(!FinanceSettlementBreakdown.supported(text(row.get("platform")),raw))return false;
            var day=java.time.LocalDate.parse(text(row.get("record_date")));
            if("TIKTOK_SHOP".equals(row.get("platform"))){
                String zone=shopTimezone(scope,shop),sellerType=shopSellerType(scope,shop);
                if(!FinanceSettlementDates.completed(raw)){
                    var bills=db.queryForList("SELECT raw_json FROM finance_records WHERE organization_id=? AND shop_id=?"
                            +" AND kind='BILL' AND statement_id=? AND source='API' LIMIT 1",
                            scope.organizationId(),shop,text(row.get("statement_id")));
                    if(bills.isEmpty())throw bad("结算元数据尚未补齐，不能重新归属账单日期");
                    var merged=new LinkedHashMap<>(raw);
                    merged.putAll(FinanceSettlementDates.metadata(read(text(bills.getFirst().get("raw_json"))),zone,sellerType));
                    raw=merged;
                }
                raw=new LinkedHashMap<>(raw);raw.putAll(FinanceSettlementDates.metadata(raw,zone,sellerType));
                day=FinanceSettlementDates.day(raw,zone);
            }
            var e=new Entry(shop,text(row.get("platform")),"SETTLEMENT",text(row.get("external_id")),text(row.get("order_id")),
                    text(row.get("statement_id")),text(row.get("category")),text(row.get("status")),day,
                    text(row.get("currency")),decimal(row.get("amount")),null,null,null,null,"API",raw);
            e=new FinanceReportingMonths(this).apply(scope,e);
            var result=FinanceSettlementBreakdown.parse(e.platform(),raw,e.amount());
            db.update("UPDATE finance_records SET revenue=?,record_date=?,raw_json=?,updated_at=updated_at WHERE id=?",result.goods(),Date.valueOf(e.date()),write(e.raw()),record);
            replaceFees(new Scope(scope.organizationId(),text(row.get("user_id"))),e,result);
            return true;
        });
    }
    java.time.LocalDate tiktokBillDay(Scope scope,String shop,String statement){
        var found=db.queryForList("""
            SELECT raw_json
            FROM finance_records FORCE INDEX(idx_finance_cost_statement)
            WHERE organization_id=? AND shop_id=? AND kind='BILL' AND statement_id=? AND source='API' LIMIT 1
            """,scope.organizationId(),shop,statement);
        if(found.isEmpty())throw bad("历史 TikTok 结算缺少原始账单，未猜测结算日期");
        return FinanceSettlementDates.day(read(text(found.getFirst().get("raw_json"))),shopTimezone(scope,shop),shopSellerType(scope,shop));
    }
    String shopTimezone(Scope scope,String shop){
        return db.queryForObject("SELECT timezone FROM finance_connections WHERE organization_id=? AND id=?",String.class,scope.organizationId(),shop);
    }
    String shopSellerType(Scope scope,String shop){
        return db.queryForObject("SELECT seller_type FROM finance_connections WHERE organization_id=? AND id=?",String.class,scope.organizationId(),shop);
    }
    /** Date-only repair from verified official statement metadata. No amount, price or fee derivation. */
    int repairSettlementDates(Scope scope,String shop,Map<String,Object> bill,List<String> ids){
        if(ids.isEmpty())return 0;
        if(ids.size()>200)throw new IllegalArgumentException("Date repair is limited to 200 records");
        return transaction(()->{
            requireShopWrite(scope,shop);
            String zone=shopTimezone(scope,shop),statement=text(bill.get("id")),sellerType=shopSellerType(scope,shop);
            var day=FinanceSettlementDates.day(bill,zone,sellerType);
            var metadata=FinanceSettlementDates.metadata(bill,zone,sellerType);
            var args=new ArrayList<Object>(List.of(scope.organizationId(),shop,statement));args.addAll(ids);
            var records=db.queryForList("""
                SELECT id,kind,status,record_date,raw_json,order_id,currency FROM finance_records
                WHERE organization_id=? AND shop_id=? AND statement_id=? AND platform='TIKTOK_SHOP'
                  AND source='API' AND kind IN ('BILL','SETTLEMENT','FEE') AND id IN (
                """+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id FOR UPDATE",args.toArray());
            var changes=new ArrayList<Object[]>();
            for(var row:records){
                var raw=read(text(row.get("raw_json")));
                if(number(raw.get("_finance_statement_time"))>0&&number(raw.get("_finance_statement_time"))!=number(bill.get("statement_time")))
                    throw bad("原始账单时间与平台核对结果不一致，已停止本批日期修复");
                String status=FinanceSettlementDates.PENDING.equals(row.get("status"))?"SETTLED":text(row.get("status"));
                var rowMetadata=metadata;
                var merged=new LinkedHashMap<>(raw);merged.putAll(rowMetadata);
                // Version 3 supersedes the legacy month correction and retains its provenance.
                var projected=new FinanceReportingMonths(this).apply(scope,new Entry(shop,"TIKTOK_SHOP",text(row.get("kind")),
                        "",text(row.get("order_id")),statement,"",status,day,text(row.get("currency")),null,null,null,null,null,"API",merged));
                if(projected.date().toString().equals(text(row.get("record_date")))&&status.equals(row.get("status"))
                        &&sameJsonValue(raw,projected.raw()))continue;
                changes.add(new Object[]{Date.valueOf(projected.date()),status,write(projected.raw()),row.get("id")});
            }
            if(!changes.isEmpty())db.batchUpdate("""
                UPDATE finance_records SET record_date=?,status=?,raw_json=?,
                  updated_at=updated_at WHERE id=?
                """,changes);
            return changes.size();
        });
    }
    private void replaceFees(Scope s,Entry e,FinanceSettlementBreakdown.Result breakdown){
        // Reconcile this transaction's generated rows. Repeated sync must not delete and reinsert
        // identical fees across every index; imports and unrelated transactions remain untouched.
        String prefix=e.externalId().replace("=","==").replace("%","=%").replace("_","=_")+":%";
        var existing=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:db.queryForList("""
            SELECT * FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='FEE'
              AND order_id=? AND statement_id=? AND source='API' AND external_id LIKE ? ESCAPE '='
              FOR UPDATE
            """,s.organizationId(),e.shopId(),e.orderId(),e.statementId(),prefix))existing.put(text(row.get("id")),row);
        var batch=new ArrayList<Object[]>();
        for(var args:derivedFees(s,e,breakdown)){
            var old=existing.remove(text(args[0]));
            if(old==null||!sameFee(old,args))batch.add(args);
        }
        if(!existing.isEmpty()){
            var args=new ArrayList<Object>(List.of(s.organizationId(),e.shopId()));args.addAll(existing.keySet());
            db.update("DELETE FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='FEE' AND source='API' AND id IN ("
                    +String.join(",",Collections.nCopies(existing.size(),"?"))+")",args.toArray());
        }
        if(!batch.isEmpty())db.batchUpdate(UPSERT,batch);
    }
    /** Shared derivation for live writes and bounded historical maintenance. */
    List<Object[]> derivedFees(Scope s,Entry e,FinanceSettlementBreakdown.Result breakdown){
        var result=new ArrayList<Object[]>();
        for(var fee:breakdown.fees()){
            var raw=new LinkedHashMap<String,Object>();
            raw.put("field",fee.field());raw.put("source_amount",fee.sourceAmount());raw.put("amount",fee.amount());
            raw.put("fee_group",fee.group());raw.put("basis",fee.note());raw.put("settlement_external_id",e.externalId());
            raw.put("breakdown_version",1);
            for(String key:FinanceReportingMonths.RAW_FIELDS)if(e.raw().containsKey(key))raw.put(key,e.raw().get(key));
            for(String key:List.of("_finance_date_version","_finance_export_statement_date","_finance_date_source",
                    "_finance_date_basis","_finance_seller_type","_finance_timezone",
                    "_finance_statement_time","_finance_payment_time","_finance_payment_status","_finance_payment_id"))
                if(e.raw().containsKey(key))raw.put(key,e.raw().get(key));
            var args=entryArgs(s,new Entry(e.shopId(),e.platform(),"FEE",e.externalId()+":d:"+hash(fee.field()).substring(0,24),
                    e.orderId(),e.statementId(),fee.label(),e.status(),e.date(),e.currency(),fee.amount(),null,null,null,null,"API",raw),null);
            result.add(args);
        }
        return result;
    }
    boolean sameFee(Map<String,Object> row,Object[] values){
        var fields=List.of("id","organization_id","user_id","shop_id","platform","kind","external_id","order_id",
                "statement_id","category","status","record_date","currency","amount","revenue","impressions","clicks","conversions","source");
        for(int i=0;i<fields.size();i++)if(!sameJsonValue(row.get(fields.get(i)),values[i]))return false;
        return sameJsonValue(read(text(row.get("raw_json"))),read(text(values[19])));
    }
    private static boolean sameJsonValue(Object a,Object b){
        if(a instanceof Number&&b instanceof Number)return decimal(a).compareTo(decimal(b))==0;
        if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y){
            if(!x.keySet().equals(y.keySet()))return false;
            return x.keySet().stream().allMatch(k->sameJsonValue(x.get(k),y.get(k)));
        }
        return Objects.equals(a,b);
    }
    public record Query(String where,List<Object> args) {}
    <T>T inProjectionWindow(Filter semanticMonth,java.time.LocalDate scanDay,java.util.function.Supplier<T> action){
        Objects.requireNonNull(semanticMonth);Objects.requireNonNull(scanDay);Objects.requireNonNull(action);
        if(scanDay.isBefore(semanticMonth.start())||scanDay.isAfter(semanticMonth.end()))
            throw new IllegalArgumentException("Projection day is outside its semantic period");
        var previous=projectionWindow.get();projectionWindow.set(new ProjectionWindow(semanticMonth,scanDay));
        try{return action.get();}
        finally{if(previous==null)projectionWindow.remove();else projectionWindow.set(previous);}
    }
    public Query query(Scope s,Filter f) {
        return query(s,f,"");
    }
    Query query(Scope s,Filter f,String alias) {
        return query(s,f,alias,true);
    }
    Query query(Scope s,Filter f,String alias,boolean reporting) {
        return query(s,f,alias,reporting,true);
    }
    Query query(Scope s,Filter f,String alias,boolean reporting,boolean dates) {
        String p=alias.isEmpty()?"":alias+".";
        String w=" "+p+"organization_id=?";
        List<Object> a=new ArrayList<>(List.of(s.organizationId()));
        if(dates){w+=" AND "+p+"record_date>=? AND "+p+"record_date<=?";a.add(Date.valueOf(f.start()));a.add(Date.valueOf(f.end()));}
        w+=" AND "+p+"status<>'DATE_PENDING'";
        for(var pair:List.of(new String[]{"shop_id",f.shopId()},new String[]{"platform",f.platform()},new String[]{"currency",f.currency()})){
            if(!pair[1].isEmpty()){w+=" AND "+p+pair[0]+"=?";a.add(pair[1]);}
        }
        String record=alias.isEmpty()?"finance_records":alias;
        // Resolve each ledger shop by its globally unique primary key, then verify company visibility.
        w+=" AND ("+record+".shop_id='UNASSIGNED' OR EXISTS"
                +" (SELECT 1 FROM finance_connections fc FORCE INDEX(PRIMARY) WHERE fc.id="+record
                +".shop_id AND fc.organization_id=? AND fc.status<>'REMOVED'))";
        a.add(s.organizationId());
        if(!f.groupId().isBlank()){
            String group=record+".shop_id IN (SELECT fg.id FROM finance_connections fg WHERE fg.organization_id=? AND fg.status<>'REMOVED' AND ";
            a.add(s.organizationId());
            if("UNGROUPED".equals(f.groupId()))w+=" AND ("+record+".shop_id='UNASSIGNED' OR "+group+"fg.group_id IS NULL))";
            else {w+=" AND "+group+"fg.group_id=?)";a.add(f.groupId());}
        }
        var window=projectionWindow.get();
        if(dates&&window!=null&&window.semanticMonth().equals(f)){
            w+=" AND "+p+"record_date>=? AND "+p+"record_date<=?";
            a.add(Date.valueOf(window.day()));a.add(Date.valueOf(window.day()));
        }
        return reporting?FinanceReportingMonths.constrain(s,new Query(w,a),f,alias):new Query(w,a);
    }
    static String visible(String alias){
        return "("+alias+".shop_id='UNASSIGNED' OR EXISTS (SELECT 1 FROM finance_connections fc WHERE fc.id="+alias
                +".shop_id AND fc.organization_id="+alias+".organization_id AND fc.status<>'REMOVED'))";
    }
    public List<Map<String,Object>> grouped(Scope s,Filter f,String dimension) {
        if(!Set.of("kind","record_date,kind","shop_id,kind","category,kind","status,kind","currency,kind").contains(dimension))throw bad("统计维度不正确");
        Query q=query(s,f);
        String kind=dimension.equals("category,kind")?" AND kind='FEE'":dimension.equals("status,kind")?" AND kind='ORDER'":"";
        return db.queryForList("SELECT "+dimension+",currency,COUNT(*) records,COUNT(amount) known_amounts,SUM(amount) amount,"
                +"SUM(revenue) revenue,SUM(impressions) impressions,SUM(clicks) clicks,SUM(conversions) conversions"
                +" FROM finance_records WHERE "+q.where+kind+" AND "+FinanceOrderCharges.normalOrder("finance_records")+" AND NOT(kind='BILL' AND source='IMPORT') GROUP BY "+dimension+",currency",q.args.toArray());
    }
    /** One covering range scan; only fee categories and order statuses need extra dimensions. */
    public List<Map<String,Object>> dashboardBuckets(Scope s,Filter f){
        return dashboardBuckets(s,f,true);
    }
    /** Historical dashboards obtain orders/refunds by settlement date in separate indexed reads. */
    List<Map<String,Object>> dashboardBuckets(Scope s,Filter f,boolean includeCreatedOrders){
        return dashboardBuckets(s,f,includeCreatedOrders,null);
    }
    List<Map<String,Object>> dashboardBuckets(Scope s,Filter f,boolean includeCreatedOrders,List<Map<String,Object>> advertising){
        // Keep every partition and the marketing exclusion in the same MVCC snapshot.
        return readSnapshot(180,()->readDashboardBuckets(s,f,includeCreatedOrders,advertising));
    }
    private List<Map<String,Object>> readDashboardBuckets(Scope s,Filter f,boolean includeCreatedOrders,List<Map<String,Object>> advertising){
        Query q=query(s,f);
        var buckets=dailyAggregate(f,q,"finance_records.record_date",range->db.queryForList("""
            SELECT shop_id,record_date,kind,currency,
              CASE WHEN kind='FEE' THEN category ELSE '' END category,
              CASE WHEN kind='ORDER' THEN status ELSE '' END status,
              COUNT(*) records,COUNT(amount) known_amounts,COUNT(revenue) known_revenues,
              COUNT(impressions) known_impressions,COUNT(clicks) known_clicks,COUNT(conversions) known_conversions,SUM(amount) amount,
              SUM(revenue) revenue,SUM(impressions) impressions,SUM(clicks) clicks,SUM(conversions) conversions
            FROM finance_records FORCE INDEX (
            """+(f.shopId().isBlank()?"idx_finance_company_dashboard":"idx_finance_shop_dashboard")+") WHERE "
                +range.where+" AND NOT(kind='BILL' AND source='IMPORT')"
                +(includeCreatedOrders?"":" AND kind NOT IN ('ORDER','REFUND')")
                +" GROUP BY shop_id,record_date,kind,currency,"
                +"CASE WHEN kind='FEE' THEN category ELSE '' END,CASE WHEN kind='ORDER' THEN status ELSE '' END",range.args.toArray()));
        // Advertising shows every posted fee, including tagged orders, without changing operating-profit exclusions.
        if(advertising!=null)buckets.stream().filter(FinanceAdvertisingReport::includes)
                .forEach(row->advertising.add(new LinkedHashMap<>(row)));
        // Keep the covering scan. Only tagged order identities need the secondary lookup.
        var markedQuery=query(s,f,"r");
        var removed=db.queryForList("""
            SELECT r.shop_id,r.record_date,r.kind,r.currency,
              CASE WHEN r.kind='FEE' THEN r.category ELSE '' END category,
              CASE WHEN r.kind='ORDER' THEN r.status ELSE '' END status,
              COUNT(*) records,COUNT(r.amount) known_amounts,COUNT(r.revenue) known_revenues,
              COUNT(r.impressions) known_impressions,COUNT(r.clicks) known_clicks,COUNT(r.conversions) known_conversions,SUM(r.amount) amount,SUM(r.revenue) revenue,
              SUM(r.impressions) impressions,SUM(r.clicks) clicks,SUM(r.conversions) conversions
            FROM finance_marketing_orders m STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)
              ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id
            WHERE
            """+markedQuery.where()+" AND r.kind IN ('ORDER','REFUND','SETTLEMENT','PENDING','FEE','COST')"
                +(includeCreatedOrders?"":" AND r.kind NOT IN ('ORDER','REFUND')")
                +" GROUP BY r.shop_id,r.record_date,r.kind,r.currency,CASE WHEN r.kind='FEE' THEN r.category ELSE '' END,CASE WHEN r.kind='ORDER' THEN r.status ELSE '' END",markedQuery.args().toArray());
        var lookup=new HashMap<List<Object>,Map<String,Object>>();removed.forEach(row->lookup.put(bucketKey(row),row));
        for(var row:buckets){var excluded=lookup.get(bucketKey(row));if(excluded==null)continue;
            for(String field:List.of("records","known_amounts","known_revenues","known_impressions","known_clicks","known_conversions","amount","revenue","impressions","clicks","conversions")){
                var value=decimal(row.get(field));var less=decimal(excluded.get(field));if(value!=null&&less!=null)row.put(field,value.subtract(less));
            }
            if(number(row.get("known_amounts"))==0)row.put("amount",null);
        }
        var corrections=FinanceFxDates.ledger(this,s,f);
        if(advertising!=null&&!corrections.isEmpty()){
            var revised=FinanceFxDates.split(advertising,corrections.stream().filter(FinanceAdvertisingReport::includes).toList(),
                FinanceFxDates.LEDGER_KEYS,FinanceFxDates.LEDGER_SUMS,"records","fx_date");
            advertising.clear();advertising.addAll(revised);
        }
        return FinanceFxDates.split(buckets.stream().filter(row->number(row.get("records"))>0).toList(),
            corrections.stream().filter(row->number(row.get("fx_operating"))==1).toList(),
            FinanceFxDates.LEDGER_KEYS,FinanceFxDates.LEDGER_SUMS,"records","fx_date");
    }
    /** Daily groups are disjoint across windows. Bound the MySQL aggregate's working
     * set without increasing global memory or spilling a whole month to disk.
     * The original query remains intact: monthly provenance uses the requested
     * period, not the internal scan window. Callers must own one read snapshot. */
    List<Map<String,Object>> dailyAggregate(Filter filter,Query query,String dateColumn,
            java.util.function.Function<Query,List<Map<String,Object>>> read){
        return aggregateWindows(filter,query,dateColumn,7,read);
    }
    List<Map<String,Object>> aggregateWindows(Filter filter,Query query,String dateColumn,int days,
            java.util.function.Function<Query,List<Map<String,Object>>> read){
        if(days<1||days>7)throw new IllegalArgumentException("Invalid aggregation window");
        if(!filter.start().plusDays(days-1).isBefore(filter.end()))return read.apply(query);
        var result=new ArrayList<Map<String,Object>>();
        for(var start=filter.start();!start.isAfter(filter.end());start=start.plusDays(days)){
            var end=start.plusDays(days-1);if(end.isAfter(filter.end()))end=filter.end();
            var args=new ArrayList<>(query.args());args.add(Date.valueOf(start));args.add(Date.valueOf(end));
            result.addAll(read.apply(new Query(query.where()+" AND "+dateColumn+">=? AND "+dateColumn+"<=?",args)));
        }
        return result;
    }
    static List<Object> bucketKey(Map<String,Object> row){return java.util.stream.Stream.of("shop_id","record_date","kind","currency","category","status").map(row::get).toList();}
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size){return details(s,f,kind,page,size,"");}
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size,String numbers){
        return details(s,f,kind,page,size,numbers,false);
    }
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size,String numbers,boolean allOrders){
        return details(s,f,kind,page,size,numbers,allOrders,false);
    }
    public Map<String,Object> advertisingFeeDetails(Scope s,Filter f,int page,int size,String numbers){
        return details(s,f,"FEE",page,size,numbers,false,true);
    }
    private Map<String,Object> details(Scope s,Filter f,String kind,int page,int size,String numbers,boolean allOrders,boolean advertisingOnly){
        // Preserve the existing request timeout while making identities and totals consistent.
        return readSnapshot(org.springframework.transaction.TransactionDefinition.TIMEOUT_DEFAULT,
                ()->loadDetails(s,f,kind,page,size,numbers,allOrders,advertisingOnly));
    }
    private Map<String,Object> loadDetails(Scope s,Filter f,String kind,int page,int size,String numbers,boolean allOrders,boolean advertisingOnly){
        allOrders=allOrders&&"ORDER".equals(kind);
        if(text(kind).isBlank())return reportDetails(s,f,page,size,numbers);
        if(!allOrders&&Set.of("ORDER","REFUND").contains(text(kind)))return settledOrderDetails(s,f,kind,page,size,numbers);
        boolean lookup=allOrders&&!FinanceRecordSearch.numbers(numbers).isEmpty();
        Query base=query(s,f,"",!allOrders,!lookup);
        Query q=lookup?FinanceRecordSearch.orders(base,"finance_records.order_id",numbers)
                :FinanceRecordSearch.apply(base,"finance_records",numbers,true);
        String where=q.where;List<Object>a=new ArrayList<>(q.args);
        if(kind!=null&&!kind.isBlank()){if(!KINDS.contains(kind))throw bad("数据类型不正确");where+=" AND kind=?";a.add(kind);}
        if(advertisingOnly)where+=" AND "+FinanceAdvertisingReport.ledgerPredicate("finance_records");
        if(allOrders)where+=" AND source='API'";
        if(lookup){
            // Bound cross-month lookup by the small company shop set and the existing
            // (company, shop, kind, order) index; never scan the historical ledger.
            var shops=db.queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND status<>'REMOVED'",String.class,s.organizationId());
            if(shops.isEmpty())where+=" AND 1=0";
            else{where+=" AND shop_id IN ("+String.join(",",Collections.nCopies(shops.size(),"?"))+")";a.addAll(shops);}
        }
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        // MySQL can otherwise choose a user-prefixed skip scan for ORDER counts,
        // even when the company/type/date index is available.
        String source="finance_records FORCE INDEX ("+(lookup?"idx_finance_settled_order":kind!=null&&!kind.isBlank()?"idx_finance_company_kind_date":"idx_finance_company_dashboard")+")";
        String countSql="SELECT COUNT(*) FROM "+source+" WHERE "+where;
        var countArgs=List.copyOf(a);
        // The 16-part aggregate index loses its PK sort/covering flags in MySQL
        // 8.4; the narrower page index retains the complete (date DESC,id) order.
        String pageSource=lookup?source:"finance_records FORCE INDEX(idx_finance_page)";
        a.add(safeSize);a.add((long)(safePage-1)*safeSize);
        // Select page identities through the covering index before reading large
        // provider payloads. Every filter and the stable page order stay inside.
        List<Map<String,Object>> data=db.queryForList("""
                SELECT detail.id,detail.shop_id,detail.platform,detail.kind,detail.external_id,detail.order_id,
                  detail.statement_id,detail.category,detail.status,detail.record_date,detail.currency,
                  detail.amount,detail.revenue,detail.impressions,detail.clicks,detail.conversions,
                  detail.source,detail.updated_at,detail.raw_json
                """+(allOrders?",detail.record_date source_record_date,(SELECT MIN(r.record_date) FROM finance_records r FORCE INDEX(idx_finance_settled_order)"
                +" WHERE r.organization_id=detail.organization_id AND r.shop_id=detail.shop_id AND r.order_id=detail.order_id AND "
                +FinanceCostReport.firstSettlementIdentity("r",true)+") first_settlement_date,'CREATED' order_date_basis\n":"")+"""
                FROM (SELECT id,record_date FROM
                """+pageSource+" WHERE "+where+" ORDER BY record_date DESC,id LIMIT ? OFFSET ?) page"
                +" JOIN finance_records detail ON detail.id=page.id ORDER BY page.record_date DESC,page.id",a.toArray());
        long total=pageTotal(countSql,countArgs,safePage,safeSize,data.size());
        data.forEach(this::enrich);if(!allOrders)new FinanceReportingMonths(this).attach(s,data);
        new FinanceProductSkus(this).attach(s,data);
        new FinanceCostReport(this).attach(s,data,false);
        presentSettlementAmounts(data);
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    public Map<String,Object> detail(Scope s,String id){
        return detail(s,id,false);
    }
    public Map<String,Object> detail(Scope s,String id,boolean allOrders){
        var rows=db.queryForList("SELECT * FROM finance_records WHERE organization_id=? AND id=? AND "+visible("finance_records"),s.organizationId(),id);
        if(rows.isEmpty())throw bad("记录不存在");var row=rows.getFirst();
        row.remove("organization_id");row.remove("user_id");
        if(Set.of("ORDER","REFUND").contains(text(row.get("kind")))){
            var day=db.queryForObject("SELECT MIN(record_date) FROM finance_records r WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND "
                    +FinanceCostReport.firstSettlementIdentity("r",true),Date.class,s.organizationId(),row.get("shop_id"),row.get("order_id"));
            row.put("source_record_date",row.get("record_date"));row.put("first_settlement_date",day);
            if(allOrders&&"ORDER".equals(row.get("kind")))row.put("order_date_basis","CREATED");
            else row.put("record_date",day);
        }
        // Return only selected business fields; raw provider responses may contain buyer PII.
        enrich(row);if(!"CREATED".equals(row.get("order_date_basis")))new FinanceReportingMonths(this).attach(s,List.of(row));new FinanceProductSkus(this).attach(s,List.of(row));new FinanceCostReport(this).attach(s,List.of(row),true);presentSettlementAmounts(List.of(row));return row;
    }
    /** Reporting follows the first actual settlement, without placing an order-created date condition on o. */
    String settledOrderRelation(Scope s,Filter f,String kind,boolean normal){
        Query q=query(s,f,"r");
        String tables=kind.equals("REFUND")
                ?"finance_records o FORCE INDEX(idx_finance_company_kind_date) STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_settled_order)"
                :"finance_records r FORCE INDEX(idx_finance_company_kind_date) STRAIGHT_JOIN finance_records o FORCE INDEX(idx_finance_settled_order)";
        return " FROM "+tables+"""
              ON o.organization_id=r.organization_id AND o.shop_id=r.shop_id AND o.order_id=r.order_id
              AND o.kind='%s' AND o.source='API'
            WHERE %s AND %s
            """.formatted(kind,FinanceCostReport.firstSettlementIdentity("r",true),q.where())
                +(normal?" AND "+FinanceOrderCharges.normalOrder("r"):"");
    }
    List<Map<String,Object>> settledOrderBuckets(Scope s,Filter f,String kind){
        var q=query(s,f,"r");
        return db.queryForList("""
            SELECT o.shop_id,r.record_date,o.record_date fx_date,o.kind,o.currency,'' category,
              CASE WHEN o.kind='ORDER' THEN o.status ELSE '' END status,
              COUNT(*) records,COUNT(o.amount) known_amounts,COUNT(o.revenue) known_revenues,
              SUM(o.amount) amount,SUM(o.revenue) revenue,SUM(o.impressions) impressions,
              SUM(o.clicks) clicks,SUM(o.conversions) conversions
            """+settledOrderRelation(s,f,kind,true)
                +" GROUP BY o.shop_id,r.record_date,o.record_date,o.kind,o.currency,CASE WHEN o.kind='ORDER' THEN o.status ELSE '' END",q.args().toArray());
    }
    Map<String,Object> settledOrderDetails(Scope s,Filter f,String kind,int page,int size,String numbers){
        var q=FinanceRecordSearch.apply(query(s,f,"r"),"o",numbers,false);
        String relation=settledOrderRelation(s,f,kind,false)+q.where().substring(query(s,f,"r").where().length());
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        var args=new ArrayList<>(q.args());args.add(safeSize);args.add((safePage-1L)*safeSize);
        var data=db.queryForList("""
            SELECT o.id,o.shop_id,o.platform,o.kind,o.external_id,o.order_id,o.statement_id,o.category,o.status,
              p.record_date,p.record_date first_settlement_date,o.record_date source_record_date,
              o.currency,o.amount,o.revenue,o.impressions,o.clicks,o.conversions,o.source,o.updated_at,o.raw_json
            FROM (SELECT o.id,r.record_date
            """+relation+" ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?) p"
                +" JOIN finance_records o ON o.id=p.id ORDER BY p.record_date DESC,p.id",args.toArray());
        long total=pageTotal("SELECT COUNT(*) "+relation,q.args(),safePage,safeSize,data.size());
        data.forEach(this::enrich);new FinanceReportingMonths(this).attach(s,data);new FinanceProductSkus(this).attach(s,data);new FinanceCostReport(this).attach(s,data,false);
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    Map<String,Object> reportDetails(Scope s,Filter f,int page,int size,String numbers){
        Query q=FinanceRecordSearch.apply(query(s,f,"r"),"r",numbers,true);var args=new ArrayList<Object>(q.args());
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        String other=" FROM finance_records r FORCE INDEX(idx_finance_company_dashboard) WHERE "+q.where()+" AND r.kind NOT IN ('ORDER','REFUND')";
        String count="SELECT (SELECT COUNT(*)"+other+")";
        long candidates=(long)safePage*safeSize;
        String union="(SELECT r.id,r.record_date report_date"+other+" ORDER BY r.record_date DESC,r.id LIMIT "+candidates+")";
        for(String kind:List.of("ORDER","REFUND")){
            var selected=FinanceRecordSearch.apply(query(s,f,"r"),"o",numbers,false);
            String relation=settledOrderRelation(s,f,kind,false)+selected.where().substring(query(s,f,"r").where().length());
            count+="+(SELECT COUNT(*) "+relation+")";
            union+=" UNION ALL (SELECT o.id,r.record_date report_date "+relation+" ORDER BY r.record_date DESC,o.id LIMIT "+candidates+")";
            args.addAll(selected.args());
        }
        var countArgs=List.copyOf(args);
        args.add(safeSize);args.add((safePage-1L)*safeSize);
        var data=db.queryForList("""
            SELECT r.id,r.shop_id,r.platform,r.kind,r.external_id,r.order_id,r.statement_id,r.category,r.status,
              p.report_date record_date,r.record_date source_record_date,
              CASE WHEN r.kind IN ('ORDER','REFUND') THEN p.report_date END first_settlement_date,
              r.currency,r.amount,r.revenue,r.impressions,r.clicks,r.conversions,r.source,r.updated_at,r.raw_json
            FROM (
            """+union+" ORDER BY report_date DESC,id LIMIT ? OFFSET ?) p JOIN finance_records r ON r.id=p.id ORDER BY p.report_date DESC,p.id",args.toArray());
        long total=pageTotal(count,countArgs,safePage,safeSize,data.size());
        data.forEach(this::enrich);new FinanceReportingMonths(this).attach(s,data);new FinanceProductSkus(this).attach(s,data);new FinanceCostReport(this).attach(s,data,false);
        presentSettlementAmounts(data);
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    /** Presentation changes never overwrite the stored platform amount used by reconciliation. */
    void presentSettlementAmounts(List<Map<String,Object>> rows){
        for(var row:rows)if(Set.of("SETTLEMENT","BILL").contains(text(row.get("kind")))){
            row.put("settled_net_amount",row.get("amount"));
            Object goods=row.get("revenue");
            row.put("amount",goods);
            row.put("settlement_display_basis","销售收入：商家优惠和退款已体现在收入中；平台费用、物流及调整另列");
            row.put("settlement_amount_note",goods==null?"平台销售收入待补齐，不用订单实付或净结算代替":"平台销售收入；实际净结算另列");
        }
    }
    void enrich(Map<String,Object> row){
        Object raw=row.remove("raw_json");
        FinanceFields.enrich(row,raw==null?Map.of():read(raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):text(raw)));
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f){
        return reconciliation(s,f,1000);
    }
    public List<String> billCurrencies(Scope s,Filter f){
        Query q=query(s,f);
        return db.queryForList("SELECT DISTINCT currency FROM finance_records WHERE "+q.where
                +" AND kind='BILL' ORDER BY currency",String.class,q.args.toArray());
    }
    public Map<String,Object> reconciliationPage(Scope s,Filter f,int page,int size){return reconciliationPage(s,f,page,size,"");}
    public Map<String,Object> reconciliationPage(Scope s,Filter f,int page,int size,String numbers){
        return readSnapshot(org.springframework.transaction.TransactionDefinition.TIMEOUT_DEFAULT,
                ()->loadReconciliationPage(s,f,page,size,numbers));
    }
    private Map<String,Object> loadReconciliationPage(Scope s,Filter f,int page,int size,String numbers){
        Query q=FinanceRecordSearch.apply(query(s,f),"finance_records",numbers,true);int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        var data=reconciliation(s,f,safeSize,(long)(safePage-1)*safeSize,numbers);
        long total=pageTotal("SELECT COUNT(*) FROM finance_records FORCE INDEX(idx_finance_company_kind_date) WHERE "+q.where+" AND kind='BILL'",
                q.args,safePage,safeSize,data.size());
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    public List<Map<String,Object>> hourlyOrders(Scope s,Filter f){
        var q=query(s,f);
        return db.queryForList("SELECT shop_id,record_date fx_date,currency,FLOOR(CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED)/3600) utc_hour,"
                +"SUM(amount) amount,COUNT(amount) paid_count FROM finance_records WHERE "+q.where
                +" AND "+FinanceOrderCharges.normalOrder("finance_records")+" AND kind='ORDER' AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) REGEXP '^[0-9]{10}$'"
                +" GROUP BY shop_id,record_date,currency,utc_hour ORDER BY utc_hour",q.args.toArray());
    }
    public List<Map<String,Object>> recentOrders(Scope s,Filter f){
        var q=query(s,f);
        var result=db.queryForList("SELECT id,shop_id,platform,kind,external_id,status,record_date,currency,amount,source,raw_json FROM finance_records WHERE "+q.where
                +" AND "+FinanceOrderCharges.normalOrder("finance_records")+" AND kind='ORDER' ORDER BY CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED) DESC,id LIMIT 8",q.args.toArray());
        result.forEach(this::enrich);new FinanceCostReport(this).attach(s,result,false);return result;
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit){
        return reconciliation(s,f,limit,"");
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit,String numbers){
        return reconciliation(s,f,Math.min(100001,Math.max(1,limit)),0,numbers);
    }
    private List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit,long offset,String numbers){
        Query q=FinanceRecordSearch.apply(query(s,f),"finance_records",numbers,true);
        // Select the visible bill page first, then use indexed lookups for that bill's
        // complete transactions. Transaction dates must NOT inherit the bill date filter.
        String sql="""
            SELECT b.id,b.shop_id,b.platform,b.external_id,b.order_id,b.statement_id,b.record_date,b.currency,b.amount,COALESCE(b.revenue,api_bill.revenue) revenue,b.source,b.status,b.updated_at,b.raw_json,
            api_bill.status api_status,
            CASE WHEN x.n>0 AND x.known=x.n THEN x.amount END detail_amount,
            CASE WHEN x.n>0 AND x.known=x.n THEN x.n END detail_count,
            CASE WHEN x.n>0 AND x.known=x.n THEN b.amount-x.amount END difference
            FROM (
              SELECT id,record_date FROM finance_records FORCE INDEX(idx_finance_page) WHERE kind='BILL' AND %s
              ORDER BY record_date DESC,id LIMIT ? OFFSET ?
            ) page
            JOIN finance_records b ON b.id=page.id
            LEFT JOIN LATERAL (
              SELECT SUM(t.amount) amount,COUNT(*) n,COUNT(t.amount) known
              FROM finance_records t
              WHERE t.organization_id=b.organization_id AND t.user_id=b.user_id AND t.shop_id=b.shop_id
                AND t.statement_id=b.external_id AND t.currency=b.currency AND t.kind='SETTLEMENT' AND t.source='API'
            ) x ON TRUE
            LEFT JOIN finance_records api_bill ON api_bill.organization_id=b.organization_id AND api_bill.user_id=b.user_id
              AND api_bill.shop_id=b.shop_id AND api_bill.external_id=b.external_id AND api_bill.currency=b.currency
              AND api_bill.kind='BILL' AND api_bill.source='API'
            ORDER BY b.record_date DESC,b.id
            """;
        List<Object>a=new ArrayList<>(q.args);a.add(limit);a.add(offset);
        var result=db.queryForList(sql.formatted(q.where),a.toArray());
        result.forEach(row->{reconcile(row);row.put("kind","BILL");enrich(row);
            row.put("settled_net_amount",row.get("amount"));row.put("goods_before_discount_amount",row.get("revenue"));});
        new FinanceProductSkus(this).attach(s,result);
        new FinanceCostReport(this).attach(s,result,false);
        return result;
    }
    static void reconcile(Map<String,Object> row){
        boolean partial="PARTIAL".equals(row.get("status"))||"PARTIAL".equals(row.get("api_status"));
        if(partial)row.put("difference",null);
        var d=decimal(row.get("difference"));
        row.put("result",partial?"PARTIAL":d==null?"MISSING":d.signum()==0?"MATCHED":"DIFFERENCE");
    }
    /** Kept out of native reconciliation; CNY readers need each underlying transaction's own date. */
    void reconciliationFxDays(Scope scope,List<Map<String,Object>> bills){
        for(int start=0;start<bills.size();start+=200){
            var batch=bills.subList(start,Math.min(start+200,bills.size()));
            var conditions=new ArrayList<String>();var args=new ArrayList<Object>();args.add(scope.organizationId());
            var wanted=new HashMap<List<String>,List<Map<String,Object>>>();
            for(var bill:batch){
                String shop=text(bill.get("shop_id")),statement=text(bill.get("external_id")),currency=text(bill.get("currency"));
                conditions.add("(shop_id=? AND statement_id=? AND currency=?)");Collections.addAll(args,shop,statement,currency);
                wanted.put(List.of(shop,statement,currency),new ArrayList<>());
            }
            if(conditions.isEmpty())continue;
            String fxDate="COALESCE("+FinanceFxDates.trustedDate("finance_records")+",CASE WHEN JSON_EXTRACT(raw_json,'$._finance_reporting_month') IS NULL THEN record_date END)";
            for(var part:db.queryForList("SELECT shop_id,statement_id,currency,"+fxDate+" fx_date,SUM(amount) amount,COUNT(*) records,COUNT(amount) known_amounts"
                +" FROM finance_records WHERE organization_id=? AND kind='SETTLEMENT' AND source='API' AND ("+String.join(" OR ",conditions)+")"
                +" GROUP BY shop_id,statement_id,currency,fx_date",args.toArray())){
                var target=wanted.get(List.of(text(part.get("shop_id")),text(part.get("statement_id")),text(part.get("currency"))));
                if(target!=null)target.add(part);
            }
            for(var bill:batch)bill.put("_fx_detail_daily",wanted.get(List.of(text(bill.get("shop_id")),text(bill.get("external_id")),text(bill.get("currency")))));
        }
    }
}
