package com.yingdao.aicommerce.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Repository
public class FinanceRepository {
    private final JdbcTemplate db; private final ObjectMapper json;
    public FinanceRepository(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public JdbcTemplate jdbc(){return db;}
    <T>T transaction(java.util.function.Supplier<T> action){
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
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
    private void saveEntry(Scope s,Entry e){
        db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,
            statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,raw_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE order_id=VALUES(order_id),statement_id=VALUES(statement_id),
            category=VALUES(category),status=VALUES(status),record_date=VALUES(record_date),currency=VALUES(currency),
            amount=VALUES(amount),revenue=VALUES(revenue),impressions=VALUES(impressions),clicks=VALUES(clicks),
            conversions=VALUES(conversions),raw_json=VALUES(raw_json),updated_at=UTC_TIMESTAMP(6)
            """,id(s,e),s.organizationId(),s.userId(),e.shopId(),e.platform(),e.kind(),e.externalId(),e.orderId(),
                e.statementId(),e.category(),e.status(),Date.valueOf(e.date()),e.currency(),e.amount(),e.revenue(),
                e.impressions(),e.clicks(),e.conversions(),e.source(),write(e.raw()));
        FinanceOrderCosts.enqueue(this,s,e);
        new FinanceOrderCharges(this).orderArrived(s,e);
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
              COUNT(*) records,COUNT(amount) known_amounts,SUM(amount) amount,
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
              COUNT(*) records,COUNT(r.amount) known_amounts,SUM(r.amount) amount,SUM(r.revenue) revenue,
              SUM(r.impressions) impressions,SUM(r.clicks) clicks,SUM(r.conversions) conversions
            FROM finance_marketing_orders m STRAIGHT_JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)
              ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id
            WHERE
            """+markedQuery.where()+" AND r.kind IN ('ORDER','REFUND','SETTLEMENT','PENDING','FEE','COST') GROUP BY r.shop_id,r.record_date,r.kind,r.currency,CASE WHEN r.kind='FEE' THEN r.category ELSE '' END,CASE WHEN r.kind='ORDER' THEN r.status ELSE '' END",markedQuery.args().toArray());
        var lookup=new HashMap<List<Object>,Map<String,Object>>();removed.forEach(row->lookup.put(bucketKey(row),row));
        for(var row:buckets){var excluded=lookup.get(bucketKey(row));if(excluded==null)continue;
            for(String field:List.of("records","known_amounts","amount","revenue","impressions","clicks","conversions")){
                var value=decimal(row.get(field));var less=decimal(excluded.get(field));if(value!=null&&less!=null)row.put(field,value.subtract(less));
            }
            if(number(row.get("known_amounts"))==0)row.put("amount",null);
        }
        return buckets.stream().filter(row->number(row.get("records"))>0).toList();
    }
    static List<Object> bucketKey(Map<String,Object> row){return java.util.stream.Stream.of("shop_id","record_date","kind","currency","category","status").map(row::get).toList();}
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size){
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
        return Map.of("items",data,"total",total==null?0:total,"page",safePage,"pageSize",safeSize);
    }
    public Map<String,Object> detail(Scope s,String id){
        var rows=db.queryForList("SELECT * FROM finance_records WHERE organization_id=? AND id=? AND "+visible("finance_records"),s.organizationId(),id);
        if(rows.isEmpty())throw bad("记录不存在");var row=rows.getFirst();
        row.remove("organization_id");row.remove("user_id");
        // Return only selected business fields; raw provider responses may contain buyer PII.
        enrich(row);new FinanceProductSkus(this).attach(s,List.of(row));new FinanceCostReport(this).attach(s,List.of(row),true);return row;
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
            SELECT b.id,b.shop_id,b.platform,b.external_id,b.order_id,b.statement_id,b.record_date,b.currency,b.amount,b.source,b.status,b.updated_at,b.raw_json,
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
        result.forEach(row->{reconcile(row);row.put("kind","BILL");enrich(row);});
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
