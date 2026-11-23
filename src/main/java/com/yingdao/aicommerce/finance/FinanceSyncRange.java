package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.yingdao.aicommerce.finance.FinanceData.*;

final class FinanceSyncRange {
    static final String MONTH="recentMonths";
    static final String RESUME="monthResume";
    static final String VERIFIED_FROM="priorityFrom";
    static final String VERIFIED_UNTIL="priorityUntil";

    private FinanceSyncRange(){}

    static LocalDate start(LocalDate today){return today.withDayOfMonth(1).minusMonths(1);}
    static String month(LocalDate today){return YearMonth.from(today).toString();}

    static Map<String,Object> prioritize(Map<String,Object> saved,LocalDate today,LocalDate earliest){
        if(month(today).equals(saved.get(MONTH)))return saved;
        LocalDate from=start(today);
        if(earliest.isAfter(from))from=earliest;
        if(from.isAfter(today)||!saved.containsKey("from")||!LocalDate.parse(text(saved.get("from"))).isBefore(from))return saved;
        var next=new LinkedHashMap<String,Object>();
        next.put("from",from.toString());next.put("until",today.toString());next.put(MONTH,month(today));
        next.put(RESUME,new LinkedHashMap<>(saved));
        next.put(VERIFIED_FROM,from.toString());
        if(saved.containsKey("unavailableBefore"))next.put("unavailableBefore",saved.get("unavailableBefore"));
        return next;
    }

    /** Pop the original, untouched page only after the priority range has completed. */
    static boolean resume(Map<String,Object> checkpoint){
        if(!(checkpoint.get(RESUME) instanceof Map<?,?>))return false;
        var saved=new LinkedHashMap<>(map(checkpoint.get(RESUME)));
        Object month=checkpoint.get(MONTH);
        Object unavailable=checkpoint.get("unavailableBefore");
        Object from=checkpoint.get(VERIFIED_FROM),until=checkpoint.get("until");
        boolean changed=Boolean.TRUE.equals(checkpoint.get("sourcesChanged"));
        checkpoint.clear();checkpoint.putAll(saved);checkpoint.put(MONTH,month);
        if(from!=null&&until!=null){checkpoint.put(VERIFIED_FROM,from);checkpoint.put(VERIFIED_UNTIL,until);}
        if(changed)checkpoint.put("sourcesChanged",true);
        if(unavailable!=null)checkpoint.putIfAbsent("unavailableBefore",unavailable);
        return true;
    }

    static Map<String,Object> marker(Map<String,Object> checkpoint){
        Object month=checkpoint.get(MONTH);
        return month==null?Map.of():Map.of(MONTH,month);
    }

    static LocalDate nextDay(Map<String,Object> checkpoint,LocalDate end){
        LocalDate next=end.plusDays(1);
        if(!checkpoint.containsKey(VERIFIED_FROM)||!checkpoint.containsKey(VERIFIED_UNTIL))return next;
        LocalDate first=LocalDate.parse(text(checkpoint.get(VERIFIED_FROM)));
        LocalDate last=LocalDate.parse(text(checkpoint.get(VERIFIED_UNTIL)));
        return !next.isBefore(first)&&!next.isAfter(last)?last.plusDays(1):next;
    }

    /** Used by CPC and GMS after a complete API range. */
    static boolean advanceShopee(Map<String,Object> checkpoint,LocalDate end,LocalDate until){
        LocalDate next=nextDay(checkpoint,end);
        if(!next.isAfter(until)){
            checkpoint.put("from",next.toString());
            return false;
        }
        if(resume(checkpoint))return false;
        var remaining=new ArrayList<>(rows(checkpoint.get("remaining")));
        var keep=new LinkedHashMap<>(marker(checkpoint));
        if(checkpoint.containsKey("unavailableBefore"))keep.put("unavailableBefore",checkpoint.get("unavailableBefore"));
        checkpoint.clear();checkpoint.putAll(keep);
        if(remaining.isEmpty())return true;
        checkpoint.putAll(remaining.removeFirst());
        if(!remaining.isEmpty())checkpoint.put("remaining",remaining);
        return false;
    }

    static String localDaySql(String zone){
        return "DATE(COALESCE(CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00',"+zone+"),UTC_TIMESTAMP(6)))";
    }

    static String previousMonthSql(String day){
        return "DATE_SUB(DATE_SUB("+day+",INTERVAL (DAYOFMONTH("+day+")-1) DAY),INTERVAL 1 MONTH)";
    }

    static String historicalRank(String checkpoint,String zone,boolean epoch){
        String day=localDaySql(zone), month="DATE_FORMAT("+day+",'%Y-%m')";
        String start=previousMonthSql(day);
        if(epoch)start="TIMESTAMPDIFF(SECOND,'1970-01-01',COALESCE(CONVERT_TZ("+start+","+zone+",'+00:00'),"+start+"))";
        String from="JSON_UNQUOTE(JSON_EXTRACT("+checkpoint+",'$.from'))";
        if(epoch)from="CAST("+from+" AS UNSIGNED)";
        return "CASE WHEN JSON_UNQUOTE(JSON_EXTRACT("+checkpoint+",'$."+MONTH+"'))="+month+
                " AND "+from+"<"+start+" THEN 1 ELSE 0 END";
    }
}
