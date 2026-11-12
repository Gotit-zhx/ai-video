package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Strict human-file parsing preserves the original row and numeric-cell evidence until validation. */
final class FinanceCostInputPreview {
    static final int DISPLAY_LIMIT=200, LARGE_ORDER_SCOPE=1000;
    static final Set<String> METADATA=Set.of("unit","identifierType","sourceKey","evidenceRef","reason","revision","spuUniform","currency");
    static Map<String,Object> metadata(Map<String,Object> raw){
        var result=new LinkedHashMap<String,Object>();
        for(var entry:raw.entrySet()){
            if(!METADATA.contains(entry.getKey()))throw bad("不支持的成本资料字段："+entry.getKey());
            Object value=entry.getValue();
            if(Set.of("revision","spuUniform").contains(entry.getKey())){if(!(value instanceof Boolean))throw bad("资料确认字段必须为布尔值");}
            else if(!(value instanceof String)||text(value).length()>2000)throw bad("资料字段必须是有效文本");
            result.put(entry.getKey(),value);
        }
        return result;
    }
    static List<Map<String,Object>> parse(byte[] bytes,String name){
        var table=FinanceWorkbook.readCostInputTable(bytes,name);int header=-1,sku=-1,warehouse=-1,month=-1,price=-1,type=-1,unit=-1,currency=-1;
        var months=new LinkedHashMap<Integer,String>();
        for(int r=0;r<Math.min(8,table.size());r++){
            // A candidate header is one physical row, never a union with preceding notes.
            header=sku=warehouse=month=price=type=unit=currency=-1;months.clear();
            var row=table.get(r);
            for(int c=0;c<row.size();c++){
                String h=raw(row,c);
                if(Set.of("型号","成本型号","SKU","sku").contains(h)){sku=c;header=r;}
                if(Set.of("仓库","成本仓","成本仓库").contains(h))warehouse=c;
                if(Set.of("月份","成本月份").contains(h))month=c;
                if(Set.of("单价","成本单价","单位成本","期末单价").contains(h))price=c;
                if(Set.of("编号类型","标识类型","identifierType").contains(h))type=c;
                if(Set.of("单位","计价单位","unit").contains(h))unit=c;
                if(Set.of("币种","currency").contains(h))currency=c;
                String parsed=FinanceCostWorkspace.month(h);if(parsed!=null)months.put(c,parsed);
            }
            if(header>=0)break;
        }
        if(sku<0||warehouse<0||(months.isEmpty()&&(month<0||price<0)))throw bad("需要型号、仓库和月份单价列");
        var rows=new ArrayList<Map<String,Object>>();
        for(int r=header+1;r<table.size();r++){
            var input=table.get(r);String code=raw(input,sku);
            if(code.isBlank()&&input.stream().allMatch(String::isBlank))continue;
            var row=new LinkedHashMap<String,Object>();row.put("row",r+1);row.put("sku",code);row.put("warehouse",raw(input,warehouse));
            if(cell(input,sku).startsWith("[NUMBER]")){
                BigDecimal n=decimal(code);
                if(n==null||n.signum()<0||(long)n.precision()-n.scale()>15||n.stripTrailingZeros().scale()>0||code.matches(".*[eE].*"))throw bad("第 "+(r+1)+" 行编号为不安全的 Excel 数值，请从原始资料恢复为文本");
                // Never persist an identifier as JSON numeric: decimal cells otherwise read back as Double.
                row.put("sku",n.toBigIntegerExact().toString());
                row.put("identifierEvidence",Map.of("cellType","NUMERIC","raw",code));
            }
            if(type>=0)row.put("identifierType",raw(input,type));if(unit>=0)row.put("unit",raw(input,unit));if(currency>=0)row.put("currency",raw(input,currency));
            var prices=new LinkedHashMap<String,Object>();
            if(months.isEmpty())prices.put(Optional.ofNullable(FinanceCostWorkspace.month(raw(input,month))).orElse(raw(input,month)),raw(input,price));
            else for(var column:months.entrySet()){String value=raw(input,column.getKey());if(!value.isBlank())prices.put(column.getValue(),value);}
            for(var value:prices.values()){var amount=decimal(value);if(amount!=null&&(long)amount.precision()-amount.scale()>18)throw bad("第 "+(r+1)+" 行单价超出范围");}
            row.put("prices",prices);rows.add(row);
        }
        return rows;
    }
    private static String cell(List<String> row,int index){return index<0||index>=row.size()?"":text(row.get(index)).trim();}
    private static String raw(List<String> row,int index){String value=cell(row,index);return value.startsWith("[NUMBER]")?value.substring(8):value;}
    static Map<String,Object> parseFailure(String reason){
        String message=reason.isBlank()?"表格结构、编号或成本值无效，请按原始资料修正后重传":reason;
        var issue=new LinkedHashMap<>(issue("FILE_INVALID","BLOCK",message));
        var row=java.util.regex.Pattern.compile("第\\s*(\\d{1,6})\\s*行").matcher(message);
        if(row.find())issue.put("row",Integer.parseInt(row.group(1)));
        return Map.of("blocked",true,"risk","BLOCKED","changedCells",0,"unchangedCells",0,"issues",List.of(issue));
    }
    static Map<String,Object> issue(String code,String severity,String message){return Map.of("code",code,"severity",severity,"message",message);}
    static boolean incomplete(Map<String,Object> validation){return rows(validation.get("issues")).stream().anyMatch(i->Set.of("UNIT_UNKNOWN","IDENTIFIER_TYPE_UNKNOWN","SPU_UNIFORM_UNCONFIRMED").contains(i.get("code")));}
    static List<Map<String,Object>> fileDiff(List<Map<String,Object>> incoming,List<Map<String,Object>> current,String name){
        var old=new HashMap<String,Map<String,Object>>();for(var row:current)old.put(row.get("warehouse")+"\0"+row.get("sku"),row);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:incoming)for(var price:map(row.get("prices")).entrySet()){
            var prior=old.getOrDefault(row.get("warehouse")+"\0"+row.get("sku"),Map.of());
            var diff=new LinkedHashMap<String,Object>();diff.put("sku",row.get("sku"));diff.put("warehouse",row.get("warehouse"));diff.put("month",price.getKey());
            diff.put("oldPrice",map(prior.get("prices")).get(price.getKey()));diff.put("newPrice",price.getValue());
            diff.put("oldSource",map(map(prior.get("priceSources")).get(price.getKey())).getOrDefault("name",""));diff.put("newSource",name);
            if(result.size()<DISPLAY_LIMIT)result.add(diff);
        }
        return result;
    }
    static List<Map<String,Object>> configurationDiff(Map<String,Object> before,Map<String,Object> after){
        var keys=new TreeSet<String>();keys.addAll(before.keySet());keys.addAll(after.keySet());var result=new ArrayList<Map<String,Object>>();
        for(String key:keys)if(!Set.of("version","confirmed","zeroConfirmed","metadata").contains(key)&&!Objects.equals(before.get(key),after.get(key))){
            var diff=new LinkedHashMap<String,Object>();diff.put("field",key);diff.put("before",before.get(key));diff.put("after",after.get(key));result.add(diff);
        }
        return result;
    }
}
