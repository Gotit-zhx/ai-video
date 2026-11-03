package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Month status uses successful scans, unfinished ranges, and persisted open bills. */
final class FinanceSyncMonth {
    private FinanceSyncMonth(){}
    static List<Map<String,Object>> describe(List<Map<String,Object>> states,List<Map<String,Object>> openBills,Instant now){
        var billsByShop=new HashMap<String,List<Map<String,Object>>>();
        for(var bill:openBills)billsByShop.computeIfAbsent(text(bill.get("shop_id")),k->new ArrayList<>()).add(bill);
        for(var row:states){
            row.put("previous_month_complete",false);
            row.put("previous_month_checked",false);
            try{
                ZoneId zone=ZoneId.of(text(row.get("timezone")));
                LocalDate end=now.atZone(zone).toLocalDate().withDayOfMonth(1),start=end.minusMonths(1);
                String channel=text(row.get("channel"));
                row.put("previous_month",YearMonth.from(start).toString());
                long open=0;
                if("SETTLEMENT".equals(channel))for(var bill:billsByShop.getOrDefault(text(row.get("shop_id")),List.of())){
                    LocalDate day=LocalDate.parse(text(bill.get("record_date")));
                    if(!day.isBefore(start)&&day.isBefore(end))open+=number(bill.get("bills"));
                }
                row.put("previous_month_open_bills",open);
                String reason="";
                if(!Set.of("ORDER","SETTLEMENT").contains(channel))reason="不属于上月订单与结算完成判断";
                else if(row.get("last_success_at")==null||row.get("coverage_start")==null
                        ||LocalDate.parse(text(row.get("coverage_start"))).isAfter(start)
                        ||instant(row.get("last_success_at")).isBefore(end.atStartOfDay(zone).toInstant())
                        ||instant(row.get("last_success_at")).isAfter(now))
                    reason="上月"+("ORDER".equals(channel)?"历史订单":"结算")+"尚无完整同步记录";
                else if(pendingRange(row,start,end,zone))
                    reason="上月"+("ORDER".equals(channel)?"历史订单":"结算明细")+"仍在抓取或等待重试";
                else if(open>0)reason="上月还有 "+open+" 张账单待补齐明细或确认结算日期";
                row.put("previous_month_checked",true);
                row.put("previous_month_complete",reason.isEmpty());
                row.put("previous_month_reason",reason);
            }catch(RuntimeException invalid){
                row.put("previous_month_reason","店铺时区或同步日期待确认");
            }
        }
        return states;
    }
    private static boolean pendingRange(Map<String,Object> row,LocalDate start,LocalDate end,ZoneId zone){
        if("SUCCESS".equals(row.get("status")))return false;
        long from=number(row.get("window_start")),until=number(row.get("target_until"));
        // An outstanding scan without a valid range cannot establish completion.
        if(from<946684800L||until<=from||until>=4102444800L)return true;
        return from<end.atStartOfDay(zone).toEpochSecond()&&until>start.atStartOfDay(zone).toEpochSecond();
    }
}
