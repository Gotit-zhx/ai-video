package com.yingdao.aicommerce.shop.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TikTokWarehouseSelectionTest {
    final TikTokShopListingCapabilityResolver.Capability local=new TikTokShopListingCapabilityResolver.Capability("LOCAL",null,List.of());
    Map<String,Object> warehouse(String id,String type,String status,String subtype,boolean defaultWarehouse){
        return Map.of("id",id,"name","仓库 "+id,"type",type,"effect_status",status,"sub_type",subtype,"is_default",defaultWarehouse);
    }
    @Test void sweetMeMultipleWarehousesWithoutDefaultSelectStableOverseasSalesWarehouse(){
        var rows=List.of(warehouse("7573610496839321365","SALES_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",false),
                warehouse("7563288949508196117","SALES_WAREHOUSE","ENABLED","CB_DIRECT_SHIPPING_WAREHOUSE",false),
                warehouse("7573618811490387732","RETURN_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",false),
                warehouse("7563746552255661844","SALES_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",false));
        var options=TikTokWarehouseSelection.candidates(rows,local);
        assertEquals(3,options.size());
        assertEquals("7563746552255661844",TikTokWarehouseSelection.choose(options,"").get("id"));
        var reversed=new ArrayList<>(rows);Collections.reverse(reversed);
        assertEquals("7563746552255661844",TikTokWarehouseSelection.choose(TikTokWarehouseSelection.candidates(reversed,local),"").get("id"));
    }
    @Test void explicitSelectionAndPlatformDefaultWinWithoutAllowingDisabledOrReturnWarehouses(){
        var rows=List.of(warehouse("1","SALES_WAREHOUSE","DISABLED","CB_OVERSEA_WAREHOUSE",true),
                warehouse("2","RETURN_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",true),
                warehouse("3","SALES_WAREHOUSE","ENABLED","CB_DIRECT_SHIPPING_WAREHOUSE",true),
                warehouse("4","SALES_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",false));
        var options=TikTokWarehouseSelection.candidates(rows,local);
        assertEquals("3",TikTokWarehouseSelection.choose(options,"").get("id"));
        assertEquals("4",TikTokWarehouseSelection.choose(options,"4").get("id"));
        for(String id:List.of("1","2","foreign"))assertThrows(RuntimeException.class,()->TikTokWarehouseSelection.choose(options,id));
    }
    @Test void crossBorderSelectionOnlyUsesOfficialLocalWarehouseIds(){
        var capability=new TikTokShopListingCapabilityResolver.Capability("LOCAL_REPLICATION",null,
                List.of(Map.of("local_warehouse_id","allowed","allocation_mode","MANUAL")));
        var rows=List.of(warehouse("foreign","SALES_WAREHOUSE","ENABLED","CB_OVERSEA_WAREHOUSE",true),
                warehouse("allowed","SALES_WAREHOUSE","ENABLED","CB_DIRECT_SHIPPING_WAREHOUSE",false));
        var options=TikTokWarehouseSelection.candidates(rows,capability);
        assertEquals(1,options.size());assertEquals("allowed",TikTokWarehouseSelection.choose(options,"").get("id"));
        assertThrows(RuntimeException.class,()->TikTokWarehouseSelection.choose(options,"foreign"));
    }
    @Test void effectStatusOverridesLegacyStatusAndEmptyListCannotInventAWarehouse(){
        var row=new HashMap<>(warehouse("disabled","SALES_WAREHOUSE","DISABLED","CB_OVERSEA_WAREHOUSE",true));
        row.put("status","ENABLED");
        assertTrue(TikTokWarehouseSelection.candidates(List.of(row),local).isEmpty());
        assertThrows(RuntimeException.class,()->TikTokWarehouseSelection.choose(List.of(),""));
    }
}
