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
    public Map<String,Object> read(String text){try{return map(json.readValue(text,Map.class));}catch(Exception e){throw bad("财务数据格式不正确");}}
    public String write(Object value){return json.writeValueAsString(value);}
    public void save(Scope s,Entry e){
        validate(e);
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
    }
    public record Query(String where,List<Object> args) {}
    public Query query(Scope s,Filter f) {
        return query(s,f,"");
    }
    Query query(Scope s,Filter f,String alias) {
        String p=alias.isEmpty()?"":alias+".";
        String w=" "+p+"organization_id=? AND "+p+"user_id=? AND "+p+"record_date>=? AND "+p+"record_date<=?";
        List<Object> a=new ArrayList<>(List.of(s.organizationId(),s.userId(),Date.valueOf(f.start()),Date.valueOf(f.end())));
        for(var pair:List.of(new String[]{"shop_id",f.shopId()},new String[]{"platform",f.platform()},new String[]{"currency",f.currency()})){
            if(!pair[1].isEmpty()){w+=" AND "+p+pair[0]+"=?";a.add(pair[1]);}
        }
        w+=" AND "+visible(alias.isEmpty()?"finance_records":alias);
        return new Query(w,a);
    }
    static String visible(String alias){
        return "("+alias+".shop_id='UNASSIGNED' OR EXISTS (SELECT 1 FROM finance_connections fc WHERE fc.id="+alias
                +".shop_id AND fc.organization_id="+alias+".organization_id AND fc.user_id="+alias+".user_id AND fc.status<>'REMOVED'))";
    }
    public List<Map<String,Object>> grouped(Scope s,Filter f,String dimension) {
        if(!Set.of("kind","record_date,kind","shop_id,kind","category,kind","status,kind","currency,kind").contains(dimension))throw bad("统计维度不正确");
        Query q=query(s,f);
        String kind=dimension.equals("category,kind")?" AND kind='FEE'":dimension.equals("status,kind")?" AND kind='ORDER'":"";
        return db.queryForList("SELECT "+dimension+",currency,COUNT(*) records,COUNT(amount) known_amounts,SUM(amount) amount,"
                +"SUM(revenue) revenue,SUM(impressions) impressions,SUM(clicks) clicks,SUM(conversions) conversions"
                +" FROM finance_records WHERE "+q.where+kind+" AND NOT(kind='BILL' AND source='IMPORT') GROUP BY "+dimension+",currency",q.args.toArray());
    }
    public Map<String,Object> details(Scope s,Filter f,String kind,int page,int size){
        Query q=query(s,f);String where=q.where;List<Object>a=new ArrayList<>(q.args);
        if(kind!=null&&!kind.isBlank()){if(!KINDS.contains(kind))throw bad("数据类型不正确");where+=" AND kind=?";a.add(kind);}
        int safeSize=Math.min(100,Math.max(1,size)),safePage=Math.max(1,page);
        Long total=db.queryForObject("SELECT COUNT(*) FROM finance_records WHERE "+where,Long.class,a.toArray());
        a.add(safeSize);a.add((long)(safePage-1)*safeSize);
        List<Map<String,Object>> data=db.queryForList("SELECT id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,revenue,impressions,clicks,conversions,source,updated_at,raw_json"
                +" FROM finance_records WHERE "+where+" ORDER BY record_date DESC,id LIMIT ? OFFSET ?",a.toArray());
        data.forEach(this::enrich);
        return Map.of("items",data,"total",total==null?0:total,"page",safePage,"pageSize",safeSize);
    }
    public Map<String,Object> detail(Scope s,String id){
        var rows=db.queryForList("SELECT * FROM finance_records WHERE organization_id=? AND user_id=? AND id=? AND "+visible("finance_records"),s.organizationId(),s.userId(),id);
        if(rows.isEmpty())throw bad("记录不存在");var row=rows.getFirst();
        row.remove("organization_id");row.remove("user_id");
        // Return only selected business fields; raw provider responses may contain buyer PII.
        enrich(row);return row;
    }
    void enrich(Map<String,Object> row){
        Object raw=row.remove("raw_json");
        FinanceFields.enrich(row,raw==null?Map.of():read(raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):text(raw)));
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f){
        return reconciliation(s,f,1000);
    }
    public List<Map<String,Object>> hourlyOrders(Scope s,Filter f){
        var q=query(s,f);
        return db.queryForList("SELECT shop_id,currency,FLOOR(CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED)/3600) utc_hour,"
                +"SUM(amount) amount,COUNT(amount) paid_count FROM finance_records WHERE "+q.where
                +" AND kind='ORDER' AND JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) REGEXP '^[0-9]{10}$'"
                +" GROUP BY shop_id,currency,utc_hour ORDER BY utc_hour",q.args.toArray());
    }
    public List<Map<String,Object>> recentOrders(Scope s,Filter f){
        var q=query(s,f);
        var result=db.queryForList("SELECT id,shop_id,platform,kind,external_id,status,record_date,currency,amount,source,raw_json FROM finance_records WHERE "+q.where
                +" AND kind='ORDER' ORDER BY CAST(JSON_UNQUOTE(JSON_EXTRACT(raw_json,'$.create_time')) AS UNSIGNED) DESC,id LIMIT 8",q.args.toArray());
        result.forEach(this::enrich);return result;
    }
    public List<Map<String,Object>> reconciliation(Scope s,Filter f,int limit){
        Query q=query(s,f,"b");
        // API bills are compared with API transaction sums; imported bills use the same reference IDs.
        String sql="""
            SELECT b.id,b.shop_id,b.platform,b.external_id,b.order_id,b.statement_id,b.record_date,b.currency,b.amount,b.source,b.status,b.updated_at,b.raw_json,
            api_bill.status api_status,
            x.amount detail_amount,x.n detail_count,b.amount-x.amount difference
            FROM finance_records b LEFT JOIN (
              SELECT organization_id,user_id,shop_id,statement_id,currency,SUM(amount) amount,
                     COUNT(*) n,COUNT(amount) known FROM finance_records WHERE kind='SETTLEMENT' AND source='API'
              AND organization_id=? AND user_id=? GROUP BY organization_id,user_id,shop_id,statement_id,currency
            ) x ON x.organization_id=b.organization_id AND x.user_id=b.user_id AND x.shop_id=b.shop_id
              AND x.statement_id=b.external_id AND x.currency=b.currency AND x.known=x.n
            LEFT JOIN finance_records api_bill ON api_bill.organization_id=b.organization_id AND api_bill.user_id=b.user_id
              AND api_bill.shop_id=b.shop_id AND api_bill.external_id=b.external_id AND api_bill.currency=b.currency
              AND api_bill.kind='BILL' AND api_bill.source='API'
            WHERE b.kind='BILL' AND
            """;
        List<Object>a=new ArrayList<>(List.of(s.organizationId(),s.userId()));a.addAll(q.args);
        a.add(Math.min(100001,Math.max(1,limit)));
        var result=db.queryForList(sql+q.where+" ORDER BY b.record_date DESC,b.id LIMIT ?",a.toArray());
        result.forEach(row->{reconcile(row);row.put("kind","BILL");enrich(row);});
        return result;
    }
    static void reconcile(Map<String,Object> row){
        boolean partial="PARTIAL".equals(row.get("status"))||"PARTIAL".equals(row.get("api_status"));
        if(partial)row.put("difference",null);
        var d=decimal(row.get("difference"));
        row.put("result",partial?"PARTIAL":d==null?"MISSING":d.signum()==0?"MATCHED":"DIFFERENCE");
    }
}
