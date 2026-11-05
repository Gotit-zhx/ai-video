package com.yingdao.aicommerce.finance;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Query dates are calendar labels, never browser or server-local instants. */
final class FinanceCalendar {
    static final String VERSION="shop-calendar-v1";
    static final ZoneId DISPLAY_ZONE=ZoneId.of("Asia/Shanghai");
    static final String NOTE="统计日期按店铺当地时间；同步时间按北京时间显示。";
    static final String QUALIFICATION="抓取完成表示接口分页和明细完整，不代表已与官方原始文件逐笔对账。";
    private FinanceCalendar(){}

    static YearMonth previousMonth(Instant now){return YearMonth.from(now.atZone(DISPLAY_ZONE)).minusMonths(1);}
    static Map<String,Object> contract(){
        return Map.of("version",VERSION,"selection","SHOP_LOCAL_CALENDAR","endInclusive",true,
                "operationalTimezone",DISPLAY_ZONE.getId(),"note",NOTE,
                "qianyiReceiptTimezone",DISPLAY_ZONE.getId(),"completionNote",QUALIFICATION);
    }
    static List<Map<String,Object>> windows(Filter filter,List<Map<String,Object>> shops){
        var result=new ArrayList<Map<String,Object>>();
        for(var shop:shops){
            if(!FinanceGroups.matches(shop,filter))continue;
            var row=new LinkedHashMap<String,Object>();
            row.put("shopId",text(shop.get("id")));
            row.put("shopName",first(shop,"display_name","name"));
            row.put("timezone",text(shop.get("timezone")));
            row.put("startDate",filter.start().toString());row.put("endDate",filter.end().toString());
            try{
                ZoneId zone=ZoneId.of(text(shop.get("timezone")));
                Instant start=filter.start().atStartOfDay(zone).toInstant();
                Instant end=filter.end().plusDays(1).atStartOfDay(zone).toInstant();
                row.put("startInclusive",start.toString());row.put("endExclusive",end.toString());
                row.put("beijingStart",display(start));row.put("beijingEndExclusive",display(end));
            }catch(DateTimeException invalid){
                row.put("warning","店铺时区无效，不能确认对应的实际时间边界");
            }
            result.add(row);
        }
        return result;
    }
    static String display(Instant time){
        return time.atZone(DISPLAY_ZONE).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
    }
    static List<List<String>> exportWindows(Filter filter,List<Map<String,Object>> shops){
        var rows=new ArrayList<List<String>>();
        rows.add(List.of("店铺","店铺时区","当地开始日期（含）","当地结束日期（含）","对应北京时间起点（含）","对应北京时间终点（不含）","说明"));
        for(var window:windows(filter,shops))rows.add(List.of(text(window.get("shopName")),text(window.get("timezone")),
                text(window.get("startDate")),text(window.get("endDate")),text(window.get("beijingStart")),
                text(window.get("beijingEndExclusive")),text(window.get("warning"))));
        rows.add(List.of("口径说明",NOTE+" 官方报表日期保持日历日期；时间边界不用于替代平台报表的账单日期规则。"));
        rows.add(List.of("千易收货", "实际 receiveTime 独立按北京时间筛选，不替代平台结算日期。"));
        return rows;
    }
}
