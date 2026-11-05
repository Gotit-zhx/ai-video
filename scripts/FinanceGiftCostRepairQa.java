package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.CannotAcquireLockException;

/** No database writes: check maintenance retry limits and reject false zero-cost acceptance. */
public final class FinanceGiftCostRepairQa {
    static void require(boolean value){if(!value)throw new AssertionError();}
    static Map<String,Object> cost(Object...lines){return Map.of("status","READY","lines",List.of(lines));}
    static void rejected(Map<String,Object> cost){
        try{FinanceGiftCostRepair.verifyCost(cost);throw new AssertionError("Invalid cost accepted");}
        catch(IllegalStateException expected){}
    }
    public static void main(String[] args)throws Exception{
        var calls=new AtomicInteger();var pauses=new AtomicInteger();
        require(FinanceGiftCostRepair.retry(()->{
            if(calls.incrementAndGet()<3)throw new CannotAcquireLockException("busy");return 7;
        },ms->pauses.incrementAndGet())==7);
        require(calls.get()==3&&pauses.get()==2);
        calls.set(0);pauses.set(0);
        try{
            FinanceGiftCostRepair.retry(()->{calls.incrementAndGet();throw new CannotAcquireLockException("busy");},ms->pauses.incrementAndGet());
            throw new AssertionError("Unbounded retry");
        }catch(CannotAcquireLockException expected){require(calls.get()==5&&pauses.get()==4);}
        calls.set(0);
        try{
            FinanceGiftCostRepair.retry(()->{calls.incrementAndGet();throw new IllegalArgumentException();},ms->{throw new AssertionError();});
            throw new AssertionError("Permanent failure swallowed");
        }catch(IllegalArgumentException expected){require(calls.get()==1);}
        var gift=new LinkedHashMap<String,Object>(Map.of("sellerSku"," gift "));
        FinanceOrderCosts.fixedGift(gift);
        FinanceGiftCostRepair.verifyCost(cost(gift,Map.of("sellerSku","PAID","amountCny",new BigDecimal("12.34"))));
        var invalid=new LinkedHashMap<>(gift);invalid.remove("amountCny");rejected(cost(invalid));
        invalid.put("amountCny",BigDecimal.ONE);rejected(cost(invalid));
        invalid.put("amountCny",BigDecimal.ZERO);invalid.put("issue","missing");rejected(cost(invalid));
        rejected(cost(Map.of("sellerSku","PAID")));
        FinanceGiftCostRepair.verifyCost(Map.of("status","EXCLUDED","lines",List.of()));
        var resume=Map.<String,Object>of("shop","b","cursorId","saved");
        require(FinanceGiftCostRepair.cursorFor(resume,"a")==null);
        require("saved".equals(FinanceGiftCostRepair.cursorFor(resume,"b")));
        require("".equals(FinanceGiftCostRepair.cursorFor(resume,"c")));
        require(FinanceGiftCostRepair.cursorFor(Map.of("shop","b","shopComplete",true),"b")==null);
        var repo=new FinanceRepository(new org.springframework.jdbc.core.JdbcTemplate(),new tools.jackson.databind.ObjectMapper());
        var raw=Map.<String,Object>of("update_time",2,"line_items",List.of(Map.of("seller_sku","GIFT")));
        var refreshed=new LinkedHashMap<String,Object>(Map.of("raw_json",repo.write(raw),"source_status","COMPLETED",
            "source_hash",FinanceOrderCosts.sourceHash(repo,raw,"COMPLETED")));
        require(FinanceGiftCostRepair.currentCost(repo,refreshed));
        refreshed.put("source_hash","obsolete");
        require(!FinanceGiftCostRepair.currentCost(repo,refreshed));
        System.out.println("15 maintenance retry, resume and GIFT acceptance checks passed");
    }
}
