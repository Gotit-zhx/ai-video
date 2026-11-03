package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Return amounts are never treated as inventory costs. Only exact stock movements supply money. */
final class FinanceQianyiEvidence {
    record Result(String status,String note,List<Map<String,Object>> movements){}
    static Map<String,Object> sanitize(Map<String,Object> source){
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("returnNumber","onlineOrderNumber","orderNumber","shop","warehouse","warehouseId",
                "returnStatus","refundStatus","status","type","asnNumber","asnId","warehouseEntryNumber","warehouseEntryID",
                "receiveTime","finishTime","updateTime","createTime","commitTime","isDeleted",
                "currency","orderRefundAmount","totalAmount","orderStatus","customNumber","carrier","returnSn"))
            if(source.containsKey(field))result.put(field,source.get(field));
        for(String field:List.of("returnNumber","onlineOrderNumber","orderNumber","warehouseId","asnId","warehouseEntryID"))
            if(result.get(field)!=null)result.put(field,text(result.get(field)));
        var lines=new ArrayList<Map<String,Object>>();
        for(var line:rows(source.get("returnSkuList"))){
            var copy=new LinkedHashMap<String,Object>();
            for(String field:List.of("sku","orderSkuId","quantity","selected","isDeleted","goodQuantity","badQuantity",
                    "itemRefundAmount","refundAmount"))
                if(line.containsKey(field))copy.put(field,line.get(field));
            if(copy.get("orderSkuId")!=null)copy.put("orderSkuId",text(copy.get("orderSkuId")));
            lines.add(copy);
        }
        if(lines.size()>1000)throw bad("千易退货单商品行超过限制，未截断明细");
        result.put("returnSkuList",lines);
        if(source.get(FinanceQianyiReceipt.FIELD) instanceof Map<?,?>){
            var receipt=new LinkedHashMap<String,Object>();var proof=map(source.get(FinanceQianyiReceipt.FIELD));
            for(String key:List.of("source","sourceSignature","state","trackingNumber","eventDate","event"))
                if(proof.containsKey(key))receipt.put(key,proof.get(key));
            result.put(FinanceQianyiReceipt.FIELD,receipt);
        }
        return result;
    }
    static boolean deleted(Object value){return Boolean.TRUE.equals(value)||"1".equals(text(value));}
    static Map<String,Object> costFields(Map<String,Object> source){
        var fields=new LinkedHashMap<>(source);
        for(String name:List.of(FinanceQianyiReceipt.FIELD,"customNumber","carrier","returnSn"))fields.remove(name);
        return fields;
    }
    static String status(Map<String,Object> source){
        if(deleted(source.get("isDeleted"))||Set.of("CLOSE","CLOSED").contains(first(source,"returnStatus","status")))return "CLOSED";
        if(!"SALES_RETURN".equals(source.get("type"))||"NO_NEED_RETURN".equals(first(source,"returnStatus","status")))return "NO_RETURN";
        return "FINISH".equals(first(source,"returnStatus","status"))?"PENDING":"WAIT_RECEIPT";
    }
    static LocalDate receiptDate(Map<String,Object> source,String zone){
        long millis=FinanceQianyiReceipt.received(source.get("receiveTime"));
        return millis<=0?null:Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).toLocalDate();
    }
    static List<String> documents(Map<String,Object> source){
        return List.of(text(source.get("returnNumber")),first(source,"asnNumber","warehouseEntryNumber"))
            .stream().filter(v->!v.isBlank()).distinct().toList();
    }
    static Result pending(String note){return new Result("PENDING",note,List.of());}
    static Result verify(Map<String,Object> source,List<Map<String,Object>> logs){
        var expected=new TreeMap<String,BigDecimal>();
        for(var line:rows(source.get("returnSkuList"))){
            if(deleted(line.get("isDeleted"))||line.containsKey("selected")&&number(line.get("selected"))==0)continue;
            String sku=text(line.get("sku"));var quantity=decimal(line.get("quantity"));
            if(sku.isBlank()||quantity==null||quantity.signum()<0)return pending("千易退货商品或数量缺失");
            if(quantity.signum()>0)expected.merge(sku,quantity,BigDecimal::add);
        }
        if(expected.isEmpty())return pending("千易未返回实际退货商品数量");
        var actual=new TreeMap<String,BigDecimal>();var selected=new LinkedHashMap<String,Map<String,Object>>();
        var documents=documents(source);
        for(var log:logs){
            if(!documents.contains(text(log.get("billNumber"))))continue;
            if(!"I_RETURN".equals(log.get("bizType")))continue;
            // The API filters inventoryType but does not document it on returned rows.
            if(log.containsKey("inventoryType")&&!"AVAILABLE".equals(log.get("inventoryType")))continue;
            String warehouseId=text(source.get("warehouseId")),logWarehouse=text(log.get("warehouseId"));
            if(!warehouseId.isBlank()&&!logWarehouse.isBlank()){
                if(!warehouseId.equals(logWarehouse))return pending("千易退货单与库存流水仓库不一致");
            }else if(text(source.get("warehouse")).isBlank()||!text(source.get("warehouse")).equals(text(log.get("warehouseName"))))
                return pending("千易退货单与库存流水仓库缺失或不一致");
            String id=text(log.get("uniqueId")),sku=text(log.get("sku"));
            if(id.isBlank()||id.length()>200)return pending("千易库存流水缺少唯一编号");
            var quantity=decimal(log.get("inventoryDiff"));var amount=decimal(log.get("cost"));
            if(quantity==null||quantity.signum()<=0)return pending("千易退货库存数量变化异常");
            if(!expected.containsKey(sku))return pending("千易退货商品与库存流水不一致");
            if(amount==null)return pending("千易库存流水未返回成本字段 cost");
            if(amount.signum()<0||amount.precision()-amount.scale()>18)return pending("千易库存成本金额异常");
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("movementId",id);evidence.put("sku",sku);evidence.put("quantity",quantity);
            evidence.put("amountCny",amount);evidence.put("warehouse",text(log.get("warehouseName")));
            evidence.put("receiptId",text(source.get("returnNumber")));evidence.put("sourceField","inventory.cost");
            evidence.put("billNumber",log.get("billNumber"));evidence.put("bizType","I_RETURN");
            evidence.put("costBasis","千易已确认退货的实际库存流水成本；非退款金额，不再乘数量");
            var previous=selected.putIfAbsent(id,evidence);
            if(previous!=null){
                if(!previous.equals(evidence))return pending("千易同一流水返回了不一致的成本");
                continue;
            }
            actual.merge(sku,quantity,BigDecimal::add);
        }
        if(actual.size()!=expected.size()||expected.entrySet().stream().anyMatch(e->e.getValue().compareTo(actual.getOrDefault(e.getKey(),BigDecimal.ZERO))!=0))
            return pending("千易退货数量与已返回库存流水尚未一致");
        return new Result("READY","按千易退货库存流水成本计入",new ArrayList<>(selected.values()));
    }
}
