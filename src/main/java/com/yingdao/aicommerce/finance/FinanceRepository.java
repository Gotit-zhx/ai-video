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
    public FinanceRepository(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public JdbcTemplate jdbc(){return db;}
    <T>T transaction(java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setTimeout(15);
        return tx.execute(status->action.get());
    }
    <T>T readSnapshot(java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(15);
        return tx.execute(status->action.get());
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
        transaction(()->{rejectDeletedWrite(s,e.shopId());saveEntry(s,e);return null;});
    }
    void saveEntry(Scope s,Entry e){
        boolean settlement=e.source().equals("API")&&e.kind().equals("SETTLEMENT")
                &&FinanceSettlementBreakdown.supported(e.platform(),e.raw());
        var breakdown=settlement?FinanceSettlementBreakdown.parse(e.platform(),e.raw(),e.amount()):null;
        var revenue=breakdown==null?e.revenue():breakdown.goods();
        if(e.kind().equals("BILL"))revenue=billGoods(s,e.shopId(),e.externalId(),e.currency(),e.status());
        db.update(UPSERT,entryArgs(s,e,revenue));
        if(breakdown!=null)replaceFees(s,e,breakdown);
        FinanceOrderCosts.enqueue(this,s,e);
        new FinanceOrderCharges(this).orderArrived(s,e);
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
            if("TIKTOK_SHOP".equals(row.get("platform")))day=number(raw.get("_finance_statement_time"))>0
                    ?FinanceMarketplace.tiktokSettlementDay(raw.get("_finance_statement_time"))
                    :tiktokBillDay(scope,shop,text(row.get("statement_id")));
            var e=new Entry(shop,text(row.get("platform")),"SETTLEMENT",text(row.get("external_id")),text(row.get("order_id")),
                    text(row.get("statement_id")),text(row.get("category")),text(row.get("status")),day,
                    text(row.get("currency")),decimal(row.get("amount")),null,null,null,null,"API",raw);
            var result=FinanceSettlementBreakdown.parse(e.platform(),raw,e.amount());
            db.update("UPDATE finance_records SET revenue=?,record_date=?,updated_at=updated_at WHERE id=?",result.goods(),Date.valueOf(day),record);
            replaceFees(new Scope(scope.organizationId(),text(row.get("user_id"))),e,result);
            return true;
        });
    }
    java.time.LocalDate tiktokBillDay(Scope scope,String shop,String statement){
        var found=db.queryForList("""
            SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_statement_time')),
              JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time'))) statement_time,
              JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$._finance_legacy_statement_date')) statement_date
            FROM finance_records FORCE INDEX(idx_finance_cost_statement)
            WHERE organization_id=? AND shop_id=? AND kind='BILL' AND statement_id=? AND source='API' LIMIT 1
            """,scope.organizationId(),shop,statement);
        if(!found.isEmpty()&&number(found.getFirst().get("statement_time"))<=0&&!text(found.getFirst().get("statement_date")).isBlank())
            return java.time.LocalDate.parse(text(found.getFirst().get("statement_date"))).minusDays(1);
        if(found.isEmpty()||number(found.getFirst().get("statement_time"))<=0)throw bad("历史 TikTok 结算缺少原始账单时间，未猜测结算日期");
        return FinanceMarketplace.tiktokSettlementDay(found.getFirst().get("statement_time"));
    }
    private void replaceFees(Scope s,Entry e,FinanceSettlementBreakdown.Result breakdown){
        // Replace this transaction's generated fee rows atomically. Imports and other transactions stay intact.
        String prefix=e.externalId().replace("=","==").replace("%","=%").replace("_","=_")+":%";
        db.update("""
            DELETE FROM finance_records WHERE organization_id=? AND shop_id=? AND kind='FEE'
              AND order_id=? AND statement_id=? AND source='API' AND external_id LIKE ? ESCAPE '='
            """,s.organizationId(),e.shopId(),e.orderId(),e.statementId(),prefix);
        var batch=new ArrayList<Object[]>();
        for(var fee:breakdown.fees()){
            var raw=new LinkedHashMap<String,Object>();
            raw.put("field",fee.field());raw.put("source_amount",fee.sourceAmount());raw.put("amount",fee.amount());
            raw.put("fee_group",fee.group());raw.put("basis",fee.note());raw.put("settlement_external_id",e.externalId());
            raw.put("breakdown_version",1);
            batch.add(entryArgs(s,new Entry(e.shopId(),e.platform(),"FEE",e.externalId()+":d:"+hash(fee.field()).substring(0,24),
                    e.orderId(),e.statementId(),fee.label(),"SETTLED",e.date(),e.currency(),fee.amount(),null,null,null,null,"API",raw),null));
        }
        if(!batch.isEmpty())db.batchUpdate(UPSERT,batch);
    }
    public record Query(String where,List<Object> args) {}
    public Query query(Scope s,Filter f) {
        return query(s,f,"");
    }
    Query query(Scope s,Filter f,String alias) {
        String p=alias.isEmpty()?"":alias+".";
        String w=" "+p+"organization_id=? AND "+p+"record_date>=? AND "+p+"record_date<=?";
        List<Object> a=new ArrayList<>(List.of(s.organizationId(),Date.valueOf(f.start()),Date.valueOf(f.end())));
        for(var pair:List.of(new String[]{"shop_id",f.shopId()},new String[]{"platform",f.platform()},new String[]{"currency",f.currency()})){
            if(!pair[1].isEmpty()){w+=" AND "+p+pair[0]+"=?";a.add(pair[1]);}
        }
        String record=alias.isEmpty()?"finance_records":alias;
        // The organization is already fixed above. Materialize the small authorized-shop
        // set once instead of running a correlated visibility lookup for every ledger row.
        w+=" AND ("+record+".shop_id='UNASSIGNED' OR "+record+".shop_id IN"
                +" (SELECT fc.id FROM finance_connections fc WHERE fc.organization_id=? AND fc.status<>'REMOVED'))";
        a.add(s.organizationId());
        if(!f.groupId().isBlank()){
            String group=record+".shop_id IN (SELECT fg.id FROM finance_connections fg WHERE fg.organization_id=? AND fg.status<>'REMOVED' AND ";
            a.add(s.organizationId());
            if("UNGROUPED".equals(f.groupId()))w+=" AND ("+record+".shop_id='UNASSIGNED' OR "+group+"fg.group_id IS NULL))";
            else {w+=" AND "+group+"fg.group_id=?)";a.add(f.groupId());}
        }
        return new Query(w,a);
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
        Query q=query(s,f);
        var buckets=db.queryForList("""
            SELECT shop_id,record_date,kind,currency,
              CASE WHEN kind='FEE' THEN category ELSE '' END category,
              CASE WHEN kind='ORDER' THEN status ELSE '' END status,
              COUNT(*) records,COUNT(amount) known_amounts,COUNT(revenue) known_revenues,SUM(amount) amount,
              SUM(revenue) revenue,SUM(impressions) impressions,SUM(clicks) clicks,SUM(conversions) conversions
            FROM finance_records FORCE INDEX (idx_finance_company_dashboard) WHERE
            """+q.where+" AND NOT(kind='BILL' AND source='IMPORT')"
                +" GROUP BY shop_id,record_date,kind,currency,"
                +"CASE WHEN kind='FEE' THEN category ELSE '' END,CASE WHEN kind='ORDER' THEN status ELSE '' END",q.args.toArray());
        // Keep the covering scan. Only tagged order identities need the secondary lookup.
        var markedQuery=query(s,f,"r");
        var removed=db.queryForList("""
            SELECT r.shop_id,r.record_date,r.kind,r.currency,
              CASE WHEN r.kind='FEE' THEN r.category ELSE '' END category,
              CASE WHEN r.kind='ORDER' THEN r.status ELSE '' END status,
              COUNT(*) records,COUNT(r.amount) known_amounts,COUNT(r.revenue) known_revenues,SUM(r.amount) amount,SUM(r.revenue) revenue,
              SUM(r.impressions) impressions,SUM(r.clicks) clicks,SUM(r.conversions) conversions
            FROM finance_marketing_orders m STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)
              ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id
            WHERE
            """+markedQuery.where()+" AND r.kind IN ('ORDER','REFUND','SETTLEMENT','PENDING','FEE','COST') GROUP BY r.shop_id,r.record_date,r.kind,r.currency,CASE WHEN r.kind='FEE' THEN r.category ELSE '' END,CASE WHEN r.kind='ORDER' THEN r.status ELSE '' END",markedQuery.args().toArray());
        var lookup=new HashMap<List<Object>,Map<String,Object>>();removed.forEach(row->lookup.put(bucketKey(row),row));
        for(var row:buckets){var excluded=lookup.get(bucketKey(row));if(excluded==null)continue;
            for(String field:List.of("records","known_amounts","known_revenues","amount","revenue","impressions","clicks","conversions")){
                var value=decimal(row.get(field));var less=decimal(excluded.get(field));if(value!=null&&less!=null)row.put(field,value.subtract(less));
            }
            if(number(row.get("known_amounts"))==0)row.put("amount",null);
        }
        return buckets.stream().filter(row->number(row.get("records"))>0).toList();
    }
    static List<Object> bucketKey(Map<String,Object> row){return java.util.stream.Stream.of("shop_id","record_date","kind","currency","category","status").map(row::get).toList();}
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size){
        if(text(kind).isBlank())return reportDetails(s,f,page,size);
        if(Set.of("ORDER","REFUND").contains(text(kind)))return settledOrderDetails(s,f,kind,page,size);
        Query q=query(s,f);String where=q.where;List<Object>a=new ArrayList<>(q.args);
        if(kind!=null&&!kind.isBlank()){if(!KINDS.contains(kind))throw bad("数据类型不正确");where+=" AND kind=?";a.add(kind);}
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        // MySQL can otherwise choose a user-prefixed skip scan for ORDER counts,
        // even when the company/type/date index is available.
        String source="finance_records FORCE INDEX ("+(kind!=null&&!kind.isBlank()?"idx_finance_company_kind_date":"idx_finance_company_dashboard")+")";
        Long total=db.queryForObject("SELECT COUNT(*) FROM "+source+" WHERE "+where,Long.class,a.toArray());
        a.add(safeSize);a.add((long)(safePage-1)*safeSize);
        List<Map<String,Object>> data=db.queryForList("SELECT id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,updated_at,raw_json"
                +" FROM "+(kind!=null&&!kind.isBlank()?source:"finance_records")+" WHERE "+where+" ORDER BY record_date DESC,id LIMIT ? OFFSET ?",a.toArray());
        data.forEach(this::enrich);
        new FinanceProductSkus(this).attach(s,data);
        new FinanceCostReport(this).attach(s,data,false);
        presentSettlementAmounts(data);
        return Map.of("items",data,"total",total==null?0:total,"page",safePage,"pageSize",safeSize);
    }
    public Map<String,Object> detail(Scope s,String id){
        var rows=db.queryForList("SELECT * FROM finance_records WHERE organization_id=? AND id=? AND "+visible("finance_records"),s.organizationId(),id);
        if(rows.isEmpty())throw bad("记录不存在");var row=rows.getFirst();
        row.remove("organization_id");row.remove("user_id");
        if(Set.of("ORDER","REFUND").contains(text(row.get("kind")))){
            var day=db.queryForObject("SELECT MIN(record_date) FROM finance_records r WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND "
                    +FinanceCostReport.firstSettlementIdentity("r",true),Date.class,s.organizationId(),row.get("shop_id"),row.get("order_id"));
            row.put("source_record_date",row.get("record_date"));row.put("first_settlement_date",day);row.put("record_date",day);
        }
        // Return only selected business fields; raw provider responses may contain buyer PII.
        enrich(row);new FinanceProductSkus(this).attach(s,List.of(row));new FinanceCostReport(this).attach(s,List.of(row),true);presentSettlementAmounts(List.of(row));return row;
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
            SELECT o.shop_id,r.record_date,o.kind,o.currency,'' category,
              CASE WHEN o.kind='ORDER' THEN o.status ELSE '' END status,
              COUNT(*) records,COUNT(o.amount) known_amounts,COUNT(o.revenue) known_revenues,
              SUM(o.amount) amount,SUM(o.revenue) revenue,SUM(o.impressions) impressions,
              SUM(o.clicks) clicks,SUM(o.conversions) conversions
            """+settledOrderRelation(s,f,kind,true)
                +" GROUP BY o.shop_id,r.record_date,o.kind,o.currency,CASE WHEN o.kind='ORDER' THEN o.status ELSE '' END",q.args().toArray());
    }
    Map<String,Object> settledOrderDetails(Scope s,Filter f,String kind,int page,int size){
        var q=query(s,f,"r");String relation=settledOrderRelation(s,f,kind,false);
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        long total=number(db.queryForObject("SELECT COUNT(*) "+relation,Long.class,q.args().toArray()));
        var args=new ArrayList<>(q.args());args.add(safeSize);args.add((safePage-1L)*safeSize);
        var data=db.queryForList("""
            SELECT o.id,o.shop_id,o.platform,o.kind,o.external_id,o.order_id,o.statement_id,o.category,o.status,
              p.record_date,p.record_date first_settlement_date,o.record_date source_record_date,
              o.currency,o.amount,o.revenue,o.impressions,o.clicks,o.conversions,o.source,o.updated_at,o.raw_json
            FROM (SELECT o.id,r.record_date
            """+relation+" ORDER BY r.record_date DESC,o.id LIMIT ? OFFSET ?) p"
                +" JOIN finance_records o ON o.id=p.id ORDER BY p.record_date DESC,p.id",args.toArray());
        data.forEach(this::enrich);new FinanceProductSkus(this).attach(s,data);new FinanceCostReport(this).attach(s,data,false);
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    Map<String,Object> reportDetails(Scope s,Filter f,int page,int size){
        Query q=query(s,f,"r");var args=new ArrayList<Object>(q.args());
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        String other=" FROM finance_records r FORCE INDEX(idx_finance_company_dashboard) WHERE "+q.where()+" AND r.kind NOT IN ('ORDER','REFUND')";
        String count="SELECT (SELECT COUNT(*)"+other+")";
        long candidates=(long)safePage*safeSize;
        String union="(SELECT r.id,r.record_date report_date"+other+" ORDER BY r.record_date DESC,r.id LIMIT "+candidates+")";
        for(String kind:List.of("ORDER","REFUND")){
            String relation=settledOrderRelation(s,f,kind,false);
            count+="+(SELECT COUNT(*) "+relation+")";
            union+=" UNION ALL (SELECT o.id,r.record_date report_date "+relation+" ORDER BY r.record_date DESC,o.id LIMIT "+candidates+")";
            args.addAll(q.args());
        }
        long total=number(db.queryForObject(count,Long.class,args.toArray()));
        args.add(safeSize);args.add((safePage-1L)*safeSize);
        var data=db.queryForList("""
            SELECT r.id,r.shop_id,r.platform,r.kind,r.external_id,r.order_id,r.statement_id,r.category,r.status,
              p.report_date record_date,r.record_date source_record_date,
              CASE WHEN r.kind IN ('ORDER','REFUND') THEN p.report_date END first_settlement_date,
              r.currency,r.amount,r.revenue,r.impressions,r.clicks,r.conversions,r.source,r.updated_at,r.raw_json
            FROM (
            """+union+" ORDER BY report_date DESC,id LIMIT ? OFFSET ?) p JOIN finance_records r ON r.id=p.id ORDER BY p.report_date DESC,p.id",args.toArray());
        data.forEach(this::enrich);new FinanceProductSkus(this).attach(s,data);new FinanceCostReport(this).attach(s,data,false);
        presentSettlementAmounts(data);
        return Map.of("items",data,"total",total,"page",safePage,"pageSize",safeSize);
    }
    /** Presentation changes never overwrite the stored platform amount used by reconciliation. */
    void presentSettlementAmounts(List<Map<String,Object>> rows){
        for(var row:rows)if(Set.of("SETTLEMENT","BILL").contains(text(row.get("kind")))){
            row.put("settled_net_amount",row.get("amount"));
            Object goods=row.get("goods_before_discount_amount");
            if(!row.containsKey("goods_before_discount_amount"))goods=row.get("revenue");
            row.put("amount",goods);
            row.put("settlement_display_basis","优惠前商品金额；优惠、退款冲减、平台费用及补贴在平台费用中分别列明");
            row.put("settlement_amount_note",goods==null?"平台优惠前商品金额待补齐，不用实付或净结算代替":"平台优惠前商品金额；实际净结算另列");
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
    public Map<String,Object> reconciliationPage(Scope s,Filter f,int page,int size){
        Query q=query(s,f);int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        Long total=db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+q.where+" AND kind='BILL'",
                Long.class,q.args.toArray());
        return Map.of("items",reconciliation(s,f,safeSize,(long)(safePage-1)*safeSize),
                "total",total==null?0:total,"page",safePage,"pageSize",safeSize);
    }
    public List<Map<String,Object>> hourlyOrders(Scope s,Filter f){
        var q=query(s,f);
        return db.queryForList("SELECT shop_id,currency,FLOOR(CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED)/3600) utc_hour,"
                +"SUM(amount) amount,COUNT(amount) paid_count FROM finance_records WHERE "+q.where
                +" AND "+FinanceOrderCharges.normalOrder("finance_records")+" AND kind='ORDER' AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) REGEXP '^[0-9]{10}$'"
                +" GROUP BY shop_id,currency,utc_hour ORDER BY utc_hour",q.args.toArray());
    }
    public List<Map<String,Object>> recentOrders(Scope s,Filter f){
        var q=query(s,f);
        var result=db.queryForList("SELECT id,shop_id,platform,kind,external_id,status,record_date,currency,amount,source,raw_json FROM finance_records WHERE "+q.where
                +" AND "+FinanceOrderCharges.normalOrder("finance_records")+" AND kind='ORDER' ORDER BY CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED) DESC,id LIMIT 8",q.args.toArray());
        result.forEach(this::enrich);new FinanceCostReport(this).attach(s,result,false);return result;
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit){
        return reconciliation(s,f,Math.min(100001,Math.max(1,limit)),0);
    }
    private List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit,long offset){
        Query q=query(s,f);
        // Select the visible bill page first, then use indexed lookups for that bill's
        // complete transactions. Transaction dates must NOT inherit the bill date filter.
        String sql="""
            SELECT b.id,b.shop_id,b.platform,b.external_id,b.order_id,b.statement_id,b.record_date,b.currency,b.amount,COALESCE(b.revenue,api_bill.revenue) revenue,b.source,b.status,b.updated_at,b.raw_json,
            api_bill.status api_status,
            CASE WHEN x.n>0 AND x.known=x.n THEN x.amount END detail_amount,
            CASE WHEN x.n>0 AND x.known=x.n THEN x.n END detail_count,
            CASE WHEN x.n>0 AND x.known=x.n THEN b.amount-x.amount END difference
            FROM (
              SELECT * FROM finance_records WHERE kind='BILL' AND %s
              ORDER BY record_date DESC,id LIMIT ? OFFSET ?
            ) b
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
}
