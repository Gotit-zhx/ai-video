package com.yingdao.aicommerce.finance;

import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

record FinanceReportSelection(Type type, Filter filter, Map<String,String> parameters) {
    enum Type {
        RECORDS("财务报表"), ADVERTISING("广告消耗"), FEE_MATRIX("平台费用汇总"),
        ORDER_COST("订单成本"), QIANYI("千易售后");
        final String label;
        Type(String label){this.label=label;}
    }

    private static final Set<String> KEYS=Set.of("start","end","shopId","groupId","platform","currency",
            "displayCurrency","granularity","fxSnapshot","view","kind","format","orderScope","numbers","category",
            "advertisingOnly","advertisingFees","search","assignment","recordId","costStatus",
            "dateField","receiptScope","receiptStatus","type","returnStatus","refundStatus");

    static FinanceReportSelection parse(FinanceController guard,String name,Map<String,String> input){
        final Type type;
        try{type=Type.valueOf(text(name));}
        catch(IllegalArgumentException invalid){throw bad("不支持该报表导出");}
        if(input==null||input.size()>48)throw bad("报表筛选不完整");
        var q=new TreeMap<String,String>();
        input.forEach((key,value)->{
            if(!KEYS.contains(key)||value==null)return;
            int max="numbers".equals(key)?16000:"search".equals(key)?500:128;
            if(value.length()>max)throw bad("报表筛选内容过长");
            if(!value.isBlank()||Set.of("start","end","type","returnStatus","refundStatus","receiptStatus").contains(key))q.put(key,value);
        });
        boolean qianyi=type==Type.QIANYI;
        if(qianyi)q.putAll(FinanceQianyiReturns.selection(q));
        boolean unbounded=qianyi&&text(q.get("start")).isBlank()&&text(q.get("end")).isBlank();
        if(!unbounded&&(text(q.get("start")).isBlank()||text(q.get("end")).isBlank()))throw bad("请选择完整报表日期");
        var dates=new TreeMap<>(q);
        if(unbounded){
            LocalDate today=LocalDate.now(FinanceCalendar.DISPLAY_ZONE);
            dates.put("start",today.minusYears(5).toString());
            dates.put("end",today.toString());
        }
        if(type==Type.RECORDS)q.put("view",FinanceFields.view(q.getOrDefault("view",q.get("kind"))));
        if(type==Type.ORDER_COST){
            q.put("kind",FinanceCostExport.kind(FinanceCostReport.scopeKind(q.get("kind"),!text(q.get("recordId")).isBlank())));
            q.put("costStatus",FinanceCostReport.costStatus(q.get("costStatus")));
        }
        if(type==Type.FEE_MATRIX||qianyi||type==Type.ORDER_COST){q.remove("displayCurrency");q.remove("fxSnapshot");}
        Filter filter=guard.filter(dates);
        return new FinanceReportSelection(type,filter,Collections.unmodifiableMap(q));
    }

    String get(String key){return text(parameters.get(key));}
    boolean cny(){return "CNY".equals(get("displayCurrency"));}
    boolean csv(){return type==Type.RECORDS&&"csv".equals(get("format"));}
    boolean allDates(){return type==Type.QIANYI&&get("start").isBlank();}
    String fingerprint(){
        var scope=new TreeMap<>(parameters);
        scope.remove("format");
        scope.remove("fxSnapshot");
        return hash(type.name()+scope);
    }
}
