package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

final class FinanceCostSlotDiff {
    private record Key(String sku,String warehouse,String month) {}

    static Map<String,Object> compare(List<Map<String,Object>> before,List<Map<String,Object>> after){
        var old=prices(before);var incoming=prices(after);
        int added=0,changed=0,removed=0;
        var missingMonths=new TreeSet<String>();
        var examples=new ArrayList<Map<String,String>>();
        for(var entry:incoming.entrySet()){
            var value=old.get(entry.getKey());
            if(value==null)added++;
            else if(value.compareTo(entry.getValue())!=0)changed++;
        }
        for(var key:old.keySet())if(!incoming.containsKey(key)){
            removed++;missingMonths.add(key.month());
            if(examples.size()<10)examples.add(Map.of("sku",key.sku(),"warehouse",key.warehouse(),"month",key.month()));
        }
        return Map.of("added",added,"changed",changed,"removed",removed,"missingMonths",List.copyOf(missingMonths),"missingExamples",examples);
    }

    private static Map<Key,BigDecimal> prices(List<Map<String,Object>> rows){
        var result=new LinkedHashMap<Key,BigDecimal>();
        for(var row:rows)for(var price:map(row.get("prices")).entrySet()){
            var value=decimal(price.getValue());
            if(value!=null)result.put(new Key(text(row.get("sku")),text(row.get("warehouse")),price.getKey()),value);
        }
        return result;
    }
}
