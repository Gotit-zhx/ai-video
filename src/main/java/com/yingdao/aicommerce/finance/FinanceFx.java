package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Display conversions never rewrite a platform amount or its native reconciliation result. */
@Service
public class FinanceFx {
    static final String PROVIDER="ECB via Frankfurter";
    static final String ATTRIBUTION="https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html";
    static final String BASIS="每笔金额按自身业务日不晚于该日的最近公布历史参考率换算；不是银行实际结汇金额";
    private final FinanceRepository repo;
    private final FinanceFxHistory history;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public FinanceFx(FinanceRepository repo,ObjectMapper json){
        this.repo=repo;this.history=new FinanceFxHistory(repo,json,uri->{
            try{
                var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200||response.body().length()>8*1024*1024)throw bad("历史汇率服务暂时不可用");
                return response.body();
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw bad("历史汇率请求已中断");}
            catch(java.io.IOException e){throw bad("历史汇率服务暂时不可用");}
        });
    }
    public record Day(LocalDate rateDate,Map<String,BigDecimal> rates){
        public Day{
            Objects.requireNonNull(rateDate);var canonical=new TreeMap<String,BigDecimal>();
            rates.forEach((currency,rate)->{
                if(rate==null||rate.signum()<=0)throw new IllegalArgumentException("Invalid historical rate");
                canonical.put(currency,rate.stripTrailingZeros());
            });
            rates=Map.copyOf(canonical);
        }
    }
    public static final class Quote{
        private final String id;private final Instant time;private final Map<String,BigDecimal> rates;
        private final boolean historical;private final NavigableMap<LocalDate,Day> days=new TreeMap<>();
        private final NavigableSet<LocalDate> requested=new TreeSet<>(),missing=new TreeSet<>(),attempted=new TreeSet<>();
        private boolean missingBusinessDate;
        private Consumer<Collection<LocalDate>> loader;
        /** Preserve existing synthetic/fixed quote construction. */
        public Quote(String id,Instant time,Map<String,BigDecimal> rates){this.id=id;this.time=time;this.rates=Map.copyOf(rates);historical=false;}
        Quote(String id,Instant time,Map<String,BigDecimal> rates,Map<LocalDate,Day> days){
            this.id=id;this.time=time;this.rates=Map.copyOf(rates);historical=true;addDays(days);
        }
        void loader(Consumer<Collection<LocalDate>> loader){this.loader=loader;}
        synchronized void addDays(Map<LocalDate,Day> incoming){
            incoming.forEach((date,day)->{
                if(day.rateDate().isAfter(date))throw new IllegalArgumentException("Future reference rate");
                Day before=days.get(date);
                if(before!=null&&!before.equals(day))throw new IllegalStateException("Frozen business-date rate changed");
                days.putIfAbsent(date,day);missing.remove(date);
            });
        }
        public String id(){return id;}
        public Instant time(){return time;}
        public Map<String,BigDecimal> rates(){return rates;}
        public boolean historical(){return historical;}
        public void preload(Collection<LocalDate> dates){
            var wanted=new TreeSet<LocalDate>();dates.stream().filter(Objects::nonNull).forEach(wanted::add);
            synchronized(this){requested.addAll(wanted);wanted.removeAll(days.keySet());wanted.removeAll(attempted);attempted.addAll(wanted);}
            if(loader!=null&&!wanted.isEmpty())loader.accept(wanted);
        }
        public BigDecimal rate(String currency,LocalDate date){
            if("CNY".equals(currency))return BigDecimal.ONE;
            BigDecimal value;
            synchronized(this){
                if(!historical)value=rates.get(currency);
                else{
                    if(date==null){missingBusinessDate=true;return null;}
                    requested.add(date);Day day=days.get(date);value=day==null?null:day.rates().get(currency);
                    if(value==null||value.signum()<=0)missing.add(date);
                }
            }
            return value!=null&&value.signum()>0?value:null;
        }
        public BigDecimal convert(Object amount,String currency,LocalDate date){
            BigDecimal value=decimal(amount);if(value==null)return null;
            if(value.signum()==0)return BigDecimal.ZERO.setScale(6);
            BigDecimal rate=rate(currency,date);return rate==null?null:value.multiply(rate).setScale(6,RoundingMode.HALF_UP);
        }
        public BigDecimal fromCny(Object amount,String targetCurrency,LocalDate date){
            BigDecimal value=decimal(amount);if(value==null)return null;
            if(value.signum()==0)return BigDecimal.ZERO.setScale(6);
            BigDecimal rate=rate(targetCurrency,date);return rate==null?null:value.divide(rate,6,RoundingMode.HALF_UP);
        }
        public BigDecimal convert(Object amount,String currency){
            if(historical)throw bad("历史金额换算必须提供业务日期");
            if(decimal(amount)==null)return null;
            if(decimal(amount).signum()==0)return BigDecimal.ZERO.setScale(6);
            if(rate(currency,null)==null)throw bad("缺少 "+currency+" 对人民币的汇率，未进行部分合计");
            return convert(amount,currency,null);
        }
        public synchronized Map<String,Object> metadata(){
            if(!historical)return Map.of("id",id,"time",time.toString(),"provider","ExchangeRate-API",
                "attributionUrl","https://www.exchangerate-api.com","basis","固定参考汇率，仅用于兼容已有快照");
            var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("mode","HISTORICAL");out.put("time",time.toString());
            out.put("provider",PROVIDER);out.put("attributionUrl",ATTRIBUTION);out.put("basis",BASIS);out.put("datePolicy","BUSINESS_DATE_PREVIOUS_PUBLICATION");
            var absent=new TreeSet<>(requested);absent.removeAll(days.keySet());absent.addAll(missing);
            out.put("coverageStart",requested.isEmpty()?null:requested.first().toString());out.put("coverageEnd",requested.isEmpty()?null:requested.last().toString());
            out.put("rateDates",days.values().stream().map(Day::rateDate).distinct().sorted().map(LocalDate::toString).toList());
            out.put("missingDates",absent.stream().map(LocalDate::toString).toList());out.put("status",absent.isEmpty()&&!missingBusinessDate?"READY":"PARTIAL");
            out.put("error",missingBusinessDate?"部分记录真实业务日期尚未核实，未使用人工归属月锚点或当前行情替代":absent.isEmpty()?null:"部分业务日期或币种缺少历史参考汇率，完整换算合计留空");return out;
        }
        public synchronized Map<String,Object> metadata(LocalDate date){
            var out=new LinkedHashMap<String,Object>();
            if(!historical)out.putAll(metadata());
            else{
                out.put("id",id);out.put("mode","HISTORICAL");out.put("time",time.toString());out.put("provider",PROVIDER);
                out.put("attributionUrl",ATTRIBUTION);out.put("basis",BASIS);out.put("datePolicy","BUSINESS_DATE_PREVIOUS_PUBLICATION");
                out.put("status","READY");out.put("error",null);
            }
            out.put("businessDate",date==null?null:date.toString());
            Day day=date==null?null:days.get(date);out.put("rateDate",day==null?null:day.rateDate().toString());
            if(historical&&(day==null||missing.contains(date))){
                out.put("status","MISSING");out.put("error",date==null?"真实业务日期尚未核实，未使用人工归属月锚点或当前行情替代":"该业务日期缺少历史参考汇率");
            }
            return out;
        }
        public synchronized List<Map<String,Object>> evidence(){
            var result=new ArrayList<Map<String,Object>>();
            days.forEach((business,day)->day.rates().forEach((currency,rate)->result.add(Map.of("businessDate",business.toString(),
                "rateDate",day.rateDate().toString(),"currency",currency,"rate",rate,"provider",PROVIDER))));
            return result;
        }
    }
    public Quote historical(String snapshotId,LocalDate start,LocalDate end){
        if(start==null||end==null||start.isAfter(end)||start.plusYears(10).isBefore(end))throw bad("历史汇率日期范围不正确");
        var dates=new ArrayList<LocalDate>();for(var day=start;!day.isAfter(end);day=day.plusDays(1))dates.add(day);
        return historical(snapshotId,dates);
    }
    public Quote historical(String snapshotId,Collection<LocalDate> dates){return history.quote(snapshotId,dates);}
    public Map<String,Object> market(){return history.market();}
    @PreDestroy void close(){history.close();}
    /** Legacy reads never insert new formats into finance_fx_snapshots. */
    public Quote quote(String snapshotId){
        if(snapshotId==null||snapshotId.isBlank())return historical(null,List.of(LocalDate.now(ZoneOffset.UTC)));
        if(snapshotId.matches("[a-f0-9]{32}"))return historical(snapshotId,List.of());
        var rows=repo.jdbc().queryForList("SELECT * FROM finance_fx_snapshots WHERE id=?",snapshotId);
        if(rows.isEmpty())throw bad("汇率快照不存在，请刷新看板");
        var row=rows.getFirst();var rates=new TreeMap<String,BigDecimal>();
        repo.read(text(row.get("rates_json"))).forEach((k,v)->rates.put(k,decimal(v)));
        return new Quote(text(row.get("id")),instant(row.get("rate_time")),rates);
    }
    public static LocalDate businessDate(Map<String,Object> row){
        if(row.containsKey("fx_date")&&text(row.get("fx_date")).isBlank())return null;
        String date=text(row.get("fx_date"));
        if(date.isBlank()&&Set.of("ORDER","PENDING","REFUND").contains(text(row.get("kind"))))date=text(row.get("source_record_date"));
        if(date.isBlank()&&Set.of("SETTLEMENT","FEE","BILL").contains(text(row.get("kind")))&&!text(row.get("reporting_month")).isBlank())return null;
        if(date.isBlank())date=text(row.get("record_date"));
        try{return date.isBlank()?null:LocalDate.parse(date.substring(0,10));}catch(RuntimeException e){return null;}
    }
    public static List<Map<String,Object>> groups(List<Map<String,Object>> rows,Quote quote,String...dimensions){
        quote.preload(rows.stream().map(FinanceFx::businessDate).filter(Objects::nonNull).toList());
        Map<List<Object>,Map<String,Object>> result=new LinkedHashMap<>();
        for(var row:rows){
            List<Object> key=Arrays.stream(dimensions).map(row::get).toList();
            var target=result.computeIfAbsent(key,k->{var m=new LinkedHashMap<String,Object>();for(String d:dimensions)m.put(d,row.get(d));m.put("currency","CNY");return m;});
            BigDecimal nativeAmount=decimal(row.get("amount")),nativeRevenue=decimal(row.get("revenue"));
            boolean missingAmount=nativeAmount!=null&&nativeAmount.signum()!=0&&quote.rate(text(row.get("currency")),businessDate(row))==null;
            boolean missingRevenue=nativeRevenue!=null&&nativeRevenue.signum()!=0&&quote.rate(text(row.get("currency")),businessDate(row))==null;
            for(String field:List.of("amount","revenue","records","known_amounts","known_revenues","known_impressions","known_clicks","known_conversions","impressions","clicks","conversions")){
                BigDecimal value=Set.of("amount","revenue").contains(field)?quote.convert(row.get(field),text(row.get("currency")),businessDate(row)):decimal(row.get(field));
                if(field.equals("known_amounts")&&missingAmount||field.equals("known_revenues")&&missingRevenue)value=BigDecimal.ZERO;
                if(value!=null)target.merge(field,value,(a,b)->((BigDecimal)a).add((BigDecimal)b));else target.putIfAbsent(field,null);
            }
            if(missingAmount)target.put("_fx_missing_amount",true);if(missingRevenue)target.put("_fx_missing_revenue",true);
        }
        for(var target:result.values())for(String field:List.of("amount","revenue")){
            target.put("known_"+field,target.get(field));target.put("partial_"+field,target.get(field));
            if(Boolean.TRUE.equals(target.remove("_fx_missing_"+field))){target.put(field,null);target.put("fx_complete",false);}
        }
        result.values().forEach(target->target.putIfAbsent("fx_complete",true));
        return new ArrayList<>(result.values());
    }
    public void enrich(List<Map<String,Object>> rows,Quote quote){
        var dates=new ArrayList<LocalDate>();
        rows.forEach(row->{dates.add(businessDate(row));FinanceData.rows(row.get("_fx_detail_daily")).forEach(part->dates.add(businessDate(part)));});
        quote.preload(dates);
        for(var row:rows){
            String currency=text(row.get("currency"));LocalDate day=businessDate(row);
            row.put("cny_amount",quote.convert(row.get("amount"),currency,day));row.put("cny_rate",quote.rate(currency,day));row.put("fx",quote.metadata(day));
            for(String field:List.of("revenue","difference","detail_amount","settled_net_amount","goods_before_discount_amount"))
                if(row.containsKey(field))row.put("cny_"+field,quote.convert(row.get(field),currency,day));
            if(row.containsKey("_fx_detail_daily")){
                BigDecimal total=BigDecimal.ZERO;boolean complete=true;var parts=FinanceData.rows(row.remove("_fx_detail_daily"));
                for(var part:parts){BigDecimal value=quote.convert(part.get("amount"),text(part.get("currency")),businessDate(part));
                    if(value==null||number(part.get("records"))!=number(part.get("known_amounts")))complete=false;else total=total.add(value);}
                row.put("cny_detail_amount",parts.isEmpty()||!complete?null:total);
                BigDecimal bill=decimal(row.get("cny_settled_net_amount"));
                row.put("cny_difference",row.get("difference")==null||bill==null||parts.isEmpty()||!complete?null:bill.subtract(total));
                row.put("cny_reconciliation_basis","账单与每笔结算交易分别按自身业务日换算后比较，可能包含汇率日期影响；原币对账状态保持不变");
            }
        }
    }
}
