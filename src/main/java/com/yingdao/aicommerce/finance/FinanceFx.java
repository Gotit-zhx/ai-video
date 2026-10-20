package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Reference-rate reporting only. Never changes platform ledger amounts or native-currency reconciliation. */
@Service
public class FinanceFx {
    private final FinanceRepository repo;private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public FinanceFx(FinanceRepository repo,ObjectMapper json){this.repo=repo;this.json=json;}
    public record Quote(String id,Instant time,Map<String,BigDecimal> rates){
        public BigDecimal convert(Object amount,String currency){
            BigDecimal value=decimal(amount);if(value==null)return null;
            BigDecimal rate=rates.get(currency);
            if(rate==null||rate.signum()<=0)throw bad("缺少 "+currency+" 对人民币的汇率，未进行部分合计");
            return value.multiply(rate).setScale(6,RoundingMode.HALF_UP);
        }
        public Map<String,Object> metadata(){return Map.of("id",id,"time",time.toString(),"provider","ExchangeRate-API",
                "attributionUrl","https://www.exchangerate-api.com","basis","按同一参考汇率折算，不是历史结汇金额");}
    }
    public synchronized Quote quote(String snapshotId){
        if(snapshotId!=null&&!snapshotId.isBlank()){
            var rows=repo.db.queryForList("SELECT * FROM finance_fx_snapshots WHERE id=?",snapshotId);
            if(rows.isEmpty())throw bad("汇率快照不存在，请刷新看板");return read(rows.getFirst());
        }
        var cached=repo.db.queryForList("SELECT * FROM finance_fx_snapshots ORDER BY rate_time DESC LIMIT 1");
        if(!cached.isEmpty()){
            Quote q=read(cached.getFirst());if(q.time.plusSeconds(86400).isAfter(Instant.now()))return q;
        }
        try{
            var request=HttpRequest.newBuilder(URI.create("https://open.er-api.com/v6/latest/CNY")).timeout(Duration.ofSeconds(10)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw bad("汇率服务暂时不可用");
            Map<String,Object> body=map(json.readValue(response.body(),Map.class));
            if(!"success".equals(body.get("result"))||!"CNY".equals(body.get("base_code")))throw bad("汇率响应不正确");
            long timestamp=number(body.get("time_last_update_unix"));Instant time=Instant.ofEpochSecond(timestamp);
            if(time.isBefore(Instant.now().minusSeconds(3*86400))||time.isAfter(Instant.now().plusSeconds(300)))throw bad("参考汇率已过期");
            Map<String,BigDecimal> rates=new TreeMap<>();
            map(body.get("rates")).forEach((k,v)->{BigDecimal n=decimal(v);if(k.matches("[A-Z]{3}")&&n!=null&&n.signum()>0)rates.put(k,BigDecimal.ONE.divide(n,12,RoundingMode.HALF_UP));});
            rates.put("CNY",BigDecimal.ONE);if(rates.size()<20)throw bad("汇率数据不完整");
            String id=Long.toString(timestamp);repo.db.update("INSERT IGNORE INTO finance_fx_snapshots(id,provider,rate_time,rates_json) VALUES(?,?,?,?)",
                    id,"ExchangeRate-API",java.sql.Timestamp.from(time),repo.write(rates));return new Quote(id,time,rates);
        }catch(Exception e){
            if(!cached.isEmpty()){Quote q=read(cached.getFirst());if(q.time.plusSeconds(3*86400).isAfter(Instant.now()))return q;}
            throw bad("人民币换算暂时不可用，请稍后重试或切回原币；原始财务数据不受影响");
        }
    }
    Quote read(Map<String,Object> row){
        Map<String,BigDecimal> rates=new TreeMap<>();repo.read(text(row.get("rates_json"))).forEach((k,v)->rates.put(k,decimal(v)));
        return new Quote(text(row.get("id")),Instant.ofEpochSecond(Long.parseLong(text(row.get("id")))),rates);
    }
    public static List<Map<String,Object>> groups(List<Map<String,Object>> rows,Quote quote,String...dimensions){
        Map<List<Object>,Map<String,Object>> result=new LinkedHashMap<>();
        for(var row:rows){
            List<Object> key=Arrays.stream(dimensions).map(row::get).toList();
            var target=result.computeIfAbsent(key,k->{var m=new LinkedHashMap<String,Object>();for(String d:dimensions)m.put(d,row.get(d));m.put("currency","CNY");return m;});
            for(String field:List.of("amount","revenue","records","known_amounts","impressions","clicks","conversions")){
                BigDecimal value=Set.of("amount","revenue").contains(field)?quote.convert(row.get(field),text(row.get("currency"))):decimal(row.get(field));
                if(value!=null)target.merge(field,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));
                else target.putIfAbsent(field,null);
            }
        }
        return new ArrayList<>(result.values());
    }
    public void enrich(List<Map<String,Object>> rows,Quote quote){
        for(var row:rows){String currency=text(row.get("currency"));row.put("cny_amount",quote.convert(row.get("amount"),currency));row.put("cny_rate",quote.rates.get(currency));
            row.put("fx",quote.metadata());
            if(row.containsKey("difference"))row.put("cny_difference",quote.convert(row.get("difference"),currency));
            if(row.containsKey("detail_amount"))row.put("cny_detail_amount",quote.convert(row.get("detail_amount"),currency));
        }
    }
}
