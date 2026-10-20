package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Explicit financial amounts: unknown amounts remain null, never synthetic zeroes. */
public final class FinanceData {
    private FinanceData() {}
    public static final Set<String> KINDS=Set.of("ORDER","REFUND","SETTLEMENT","BILL","FEE","AD","AD_GMV","COST","PENDING");
    public record Scope(String organizationId,String userId) {}
    public record Entry(String shopId,String platform,String kind,String externalId,String orderId,
                        String statementId,String category,String status,LocalDate date,String currency,
                        BigDecimal amount,BigDecimal revenue,Long impressions,Long clicks,BigDecimal conversions,
                        String source,Map<String,Object> raw) {}
    public record Filter(LocalDate start,LocalDate end,String shopId,String platform,String currency,String granularity) {
        public Filter {
            if(start==null||end==null||start.isAfter(end)||start.plusYears(10).isBefore(end))
                throw bad("请选择有效日期范围，最多 10 年");
            shopId=shopId==null?"":shopId; platform=platform==null?"":platform; currency=currency==null?"":currency;
            granularity=granularity==null?"day":granularity;
            if(!Set.of("day","week","month","quarter","halfyear","year").contains(granularity))throw bad("统计周期不正确");
            if(!platform.isEmpty()&&!Set.of("SHOPEE","TIKTOK_SHOP").contains(platform))throw bad("平台不正确");
            if(!currency.isEmpty()&&!currency.matches("[A-Z]{3}"))throw bad("币种必须为三位代码");
        }
    }
    public static Scope scope(PlatformPrincipal p,boolean platformAdmin) {
        if(p==null)throw new BusinessException(HttpStatus.UNAUTHORIZED,"请先登录");
        if(!platformAdmin)throw new BusinessException(HttpStatus.FORBIDDEN,"财务 BI 仅限超级管理员使用");
        return new Scope(p.organizationId(),p.userId());
    }
    public static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,message);}
    public static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static String id(Scope s,Entry e){return hash(String.join("\0",s.organizationId,s.userId,e.shopId,e.kind,e.externalId,e.source));}
    @SuppressWarnings("unchecked")
    public static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:Map.of();}
    public static List<Map<String,Object>> rows(Object v){
        return v instanceof List<?> list?list.stream().filter(Map.class::isInstance).map(FinanceData::map).toList():List.of();
    }
    public static Object at(Map<String,Object> v,String path){
        Object cur=v;for(String key:path.split("\\.")){cur=map(cur).get(key);if(cur==null)return null;}return cur;
    }
    public static String text(Object v){return v==null?"":String.valueOf(v);}
    public static String first(Map<String,Object> m,String...keys){
        for(String k:keys){String s=text(at(m,k));if(!s.isBlank())return s;}return "";
    }
    public static BigDecimal decimal(Object v){
        if(v==null||text(v).isBlank())return null;
        if(v instanceof Map<?,?>)v=map(v).get("amount");
        try{return new BigDecimal(text(v));}catch(Exception e){return null;}
    }
    public static BigDecimal amount(Map<String,Object> m,String...keys){
        for(String k:keys){BigDecimal n=decimal(at(m,k));if(n!=null)return n;}return null;
    }
    public static long number(Object v){try{return Long.parseLong(text(v));}catch(Exception e){return 0;}}
    public static Instant instant(Object value){
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant();
        if(value instanceof LocalDateTime date)return date.toInstant(ZoneOffset.UTC);
        if(value instanceof Instant instant)return instant;
        throw bad("同步时间格式不正确");
    }
    public static LocalDate date(Object v,String zone){
        String t=text(v);if(t.isBlank())throw bad("平台记录缺少业务日期");
        try{if(t.matches("\\d{10,13}")){long n=Long.parseLong(t);return Instant.ofEpochSecond(n>100000000000L?n/1000:n).atZone(ZoneId.of(zone)).toLocalDate();}
            return LocalDate.parse(t.substring(0,10));}catch(Exception e){throw bad("业务日期格式不正确");}
    }
    public static String bucket(LocalDate d,String g){
        return switch(g) {
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case "month" -> d.toString().substring(0,7);
            case "quarter" -> d.getYear()+" Q"+((d.getMonthValue()-1)/3+1);
            case "halfyear" -> d.getYear()+" H"+((d.getMonthValue()-1)/6+1);
            case "year" -> String.valueOf(d.getYear());
            default -> d.toString();
        };
    }
    public static String currencyFor(String country){return switch(text(country).toUpperCase(Locale.ROOT)){
        case "PH"->"PHP";case "TH"->"THB";case "VN"->"VND";case "MY"->"MYR";case "SG"->"SGD";case "ID"->"IDR";
        case "US"->"USD";case "GB","UK"->"GBP";case "TW"->"TWD";default->"";};}
    public static String timezoneFor(String country){return switch(text(country).toUpperCase(Locale.ROOT)){
        case "TH"->"Asia/Bangkok";case "VN"->"Asia/Ho_Chi_Minh";case "ID"->"Asia/Jakarta";case "PH"->"Asia/Manila";
        case "SG"->"Asia/Singapore";case "MY"->"Asia/Kuala_Lumpur";case "US"->"America/Los_Angeles";case "GB","UK"->"Europe/London";default->"Asia/Shanghai";};}
    public static void validate(Entry e){
        if(!KINDS.contains(e.kind)||e.externalId.isBlank()||e.externalId.length()>200||e.date==null
                ||!e.currency.matches("[A-Z]{3}"))throw bad("财务记录缺少有效类型、唯一编号、日期或币种");
        if(e.amount!=null&&(e.amount.precision()-e.amount.scale()>18||e.amount.scale()>6))throw bad("金额超出支持精度");
    }
}
