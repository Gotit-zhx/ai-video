package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Exact identifiers only: bounded, parameterized, and never coerced to floating point. */
public final class FinanceRecordSearch {
    private FinanceRecordSearch(){}
    public static List<String> numbers(String input){
        String value=text(input).trim();
        if(value.isEmpty())return List.of();
        if(value.length()>16000)throw bad("编号查询最多支持 100 个编号");
        var ids=new LinkedHashSet<String>();
        for(String id:value.split("[\\s,，;；]+")){
            if(id.isEmpty())continue;
            if(id.length()>200)throw bad("单个编号不能超过 200 个字符");
            ids.add(id);
        }
        if(ids.size()>100)throw bad("编号查询最多支持 100 个编号");
        return List.copyOf(ids);
    }
    static FinanceRepository.Query apply(FinanceRepository.Query query,String alias,String input,boolean bills){
        var ids=numbers(input);if(ids.isEmpty())return query;
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        var args=new ArrayList<>(query.args());var parts=new ArrayList<String>();
        for(String column:List.of("order_id","external_id","statement_id")){
            parts.add(alias+"."+column+" IN ("+slots+")");args.addAll(ids);
        }
        if(bills){
            parts.add("("+alias+".kind='BILL' AND EXISTS(SELECT 1 FROM finance_records search_tx"
                    +" WHERE search_tx.organization_id="+alias+".organization_id AND search_tx.shop_id="+alias+".shop_id"
                    +" AND search_tx.kind='SETTLEMENT' AND search_tx.source='API'"
                    +" AND search_tx.statement_id="+alias+".external_id AND search_tx.order_id IN ("+slots+")))");
            args.addAll(ids);
        }
        return new FinanceRepository.Query(query.where()+" AND ("+String.join(" OR ",parts)+")",args);
    }
    static FinanceRepository.Query orders(FinanceRepository.Query query,String column,String input){
        var ids=numbers(input);if(ids.isEmpty())return query;
        var args=new ArrayList<>(query.args());args.addAll(ids);
        return new FinanceRepository.Query(query.where()+" AND "+column+" IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args);
    }
}
