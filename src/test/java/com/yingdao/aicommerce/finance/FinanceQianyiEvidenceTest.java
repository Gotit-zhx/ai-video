package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceQianyiEvidenceTest {
    static Map<String,Object> receipt(){
        return new LinkedHashMap<>(Map.of("returnNumber","R1","onlineOrderNumber","586208660231587825",
            "warehouse","PH","type","SALES_RETURN","returnStatus","FINISH","createTime",1788210000000L,
            "updateTime",1788470000000L,"receiveTime",1788291000000L,
            "returnSkuList",List.of(Map.of("sku","SKU-A","quantity",2,"selected",1))));
    }
    static Map<String,Object> movement(){
        return new LinkedHashMap<>(Map.of("uniqueId","987654321012345678","billNumber","R1","sku","SKU-A",
            "warehouseName","PH","bizType","I_RETURN","operateType","INCREASE","inventoryDiff",2,"cost","10.25"));
    }
    @Test void exactMovementIsDeduplicatedAndRefundAmountsNeverSupplyMissingCosts(){
        var source=receipt();source.put("totalAmount","9999");source.put("buyerId","private");
        var clean=FinanceQianyiEvidence.sanitize(source);
        assertFalse(clean.containsKey("buyerId"));assertEquals("9999",clean.get("totalAmount"));
        var result=FinanceQianyiEvidence.verify(clean,List.of(movement(),movement()));
        assertEquals("READY",result.status());assertEquals(1,result.movements().size());
        assertEquals(decimal("10.25"),result.movements().getFirst().get("amountCny"));
        var withoutCost=movement();withoutCost.remove("cost");
        assertEquals("PENDING",FinanceQianyiEvidence.verify(source,List.of(withoutCost)).status());
        withoutCost.put("cost",0);
        assertEquals("READY",FinanceQianyiEvidence.verify(source,List.of(withoutCost)).status(),"confirmed zero differs from missing cost");
    }
    @Test void warehouseSkuQuantityAndSourceIdentityMustAgree(){
        for(var change:List.of(Map.entry("warehouseName","other"),Map.entry("sku","other"),Map.entry("inventoryDiff","1"),
            Map.entry("uniqueId",""),Map.entry("cost","-1"),Map.entry("billNumber","OTHER"))){
            var log=movement();log.put(change.getKey(),change.getValue());
            assertEquals("PENDING",FinanceQianyiEvidence.verify(receipt(),List.of(log)).status(),change.getKey());
        }
        var source=receipt();source.remove("warehouse");var log=movement();log.remove("warehouseName");
        assertEquals("PENDING",FinanceQianyiEvidence.verify(source,List.of(log)).status());
        var conflict=movement();conflict.put("cost","11");
        assertEquals("PENDING",FinanceQianyiEvidence.verify(receipt(),List.of(movement(),conflict)).status());
    }
    @Test void statusesDatesAndInventoryRequestFollowDocumentedFields(){
        var source=receipt();source.put("status","WAIT_STORAGE");
        assertEquals("PENDING",FinanceQianyiEvidence.status(source),"new returnStatus takes precedence");
        source.put("returnStatus","CLOSE");assertEquals("CLOSED",FinanceQianyiEvidence.status(source));
        source.put("returnStatus","FINISH");source.put("type","RETURN");assertEquals("NO_RETURN",FinanceQianyiEvidence.status(source));
        assertEquals(Instant.ofEpochMilli(1788291000000L).atZone(ZoneId.of("Asia/Manila")).toLocalDate(),
            FinanceQianyiEvidence.receiptDate(source,"Asia/Manila"));
        var request=FinanceQianyiWorker.inventoryParameters(source,"R1",2);
        assertEquals(List.of("I_RETURN"),request.get("typeList"));assertFalse(request.containsKey("bizTypeList"));
        for(String key:List.of("operateTimeFrom","operateTimeTo","createTimeFrom","createTimeTo"))assertNotNull(request.get(key));
        assertEquals(2,request.get("page"));assertEquals(100,request.get("pageSize"));
    }
}
