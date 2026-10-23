package com.yingdao.aicommerce.shop.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Select only the authorized shop's active sales warehouses; return warehouses are never eligible. */
public final class TikTokWarehouseSelection {
    private TikTokWarehouseSelection() {}
    public static List<Map<String,Object>> candidates(Object raw,TikTokShopListingCapabilityResolver.Capability capability){
        if(!(raw instanceof List<?> rows))throw unavailable("未读取到店铺发货仓库，请稍后重试");
        Set<String> allowed=new HashSet<>();
        capability.inventoryRules().forEach(rule->allowed.add(text(rule.get("local_warehouse_id"))));
        var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(Object item:rows){
            if(!(item instanceof Map<?,?> row))continue;
            String id=text(row.get("id")),status=text(row.containsKey("effect_status")?row.get("effect_status"):row.get("status"));
            if(id.isBlank()||!"SALES_WAREHOUSE".equals(row.get("type"))||
                    (!status.isBlank()&&!Set.of("ENABLED","ACTIVE").contains(status)))continue;
            if("LOCAL_REPLICATION".equals(capability.listingMethod())&&!allowed.contains(id))continue;
            if(!seen.add(id))throw unavailable("店铺返回了重复的发货仓库，请重新读取");
            var warehouse=new LinkedHashMap<String,Object>();
            warehouse.put("id",id);warehouse.put("name",text(row.get("name")).strip());
            warehouse.put("subType",text(row.get("sub_type")));warehouse.put("isDefault",Boolean.TRUE.equals(row.get("is_default")));
            var parts=new ArrayList<String>();
            if(row.get("address") instanceof Map<?,?> address)
                for(String key:List.of("region","state","city","district","distict","town","address_detail"))
                    if(!text(address.get(key)).isBlank()&&!parts.contains(text(address.get(key))))parts.add(text(address.get(key)));
            warehouse.put("address",String.join(" · ",parts));result.add(warehouse);
        }
        return List.copyOf(result);
    }
    public static Map<String,Object> choose(List<Map<String,Object>> warehouses,String selected){
        if(!text(selected).isBlank())return warehouses.stream().filter(w->selected.equals(w.get("id"))).findFirst()
                .orElseThrow(()->unavailable("配置的发货仓库已停用或不属于当前店铺，请重新选择"));
        if(warehouses.isEmpty())throw unavailable("目标店铺没有可用的发货仓库，请在 TikTok Shop 后台配置发货地址");
        // Platform default wins. Without one, prefer an overseas sales warehouse, then use
        // the lowest stable ID so API ordering cannot silently change the selected warehouse.
        return warehouses.stream().min(Comparator
                .comparingInt((Map<String,Object> w)->Boolean.TRUE.equals(w.get("isDefault"))?0:
                        "CB_OVERSEA_WAREHOUSE".equals(w.get("subType"))?1:2)
                .thenComparing(w->text(w.get("id")))).orElseThrow();
    }
    static String text(Object value){return value==null?"":String.valueOf(value);}
    private static BusinessException unavailable(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
