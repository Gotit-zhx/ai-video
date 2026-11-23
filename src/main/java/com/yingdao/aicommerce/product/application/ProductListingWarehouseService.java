package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.application.*;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import com.yingdao.aicommerce.shop.infrastructure.TikTokShopOfficialClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ProductListingWarehouseService {
    private final JdbcTemplate db;private final ShopAuthorizationService shops;private final TikTokShopOfficialClient client;
    public ProductListingWarehouseService(JdbcTemplate db,ShopAuthorizationService shops,TikTokShopOfficialClient client){
        this.db=db;this.shops=shops;this.client=client;
    }
    public List<ShopAuthorizationService.ShopView> shops(PlatformPrincipal user){
        ProductListingTemplateService.requireAccess(user,false);
        return shops.list(user,"TIKTOK_SHOP").stream().filter(s->"ACTIVE".equals(s.status())).toList();
    }
    public Config config(String org,String shopId,String module){
        return config(org,shopId,module,"");
    }
    private Config config(String org,String shopId,String module,String captureTemplateId){
        String query="SELECT warehouse_id,version FROM product_listing_warehouses WHERE organization_id=? AND shop_id=? AND data_module=?";
        RowMapper<Config> mapper=(rs,n)->new Config(rs.getString(1),rs.getLong(2));
        String validated=CommerceModule.validate(module);
        if(!captureTemplateId.isEmpty()){
            var scoped=db.query(query+" AND capture_template_id=?",mapper,org,shopId,validated,captureTemplateId);
            if(!scoped.isEmpty())return scoped.getFirst();
            return new Config(config(org,shopId,validated).warehouseId(),0);
        }
        var rows=db.query(query+" AND capture_template_id=''",mapper,org,shopId,validated);
        return rows.isEmpty()?new Config("",0):rows.getFirst();
    }
    public View get(String shopId,PlatformPrincipal user){
        return get(shopId,user,"");
    }
    public View get(String shopId,PlatformPrincipal user,String captureTemplateId){
        ProductListingTemplateService.requireAccess(user,false);
        var connection=shops.connectionForPublishing(user.organizationId(),shopId,MarketplacePlatform.TIKTOK_SHOP);
        var capability=new TikTokShopListingCapabilityResolver(client).resolve(connection);
        var response=client.apiGet("/logistics/202309/warehouses",connection.tokens().accessToken(),connection.shop().getShopCipher(),Map.of());
        var data=response.get("data") instanceof Map<?,?> m?m:Map.of();
        return view(shopId,config(user.organizationId(),shopId,ProductListingTemplateService.settingsModule(captureTemplateId),captureTemplateId),data.get("warehouses"),capability);
    }
    public static View view(String shopId,Config config,Object raw,TikTokShopListingCapabilityResolver.Capability capability){
        var warehouses=TikTokWarehouseSelection.candidates(raw,capability);
        Map<String,Object> selected;String error="";
        try{selected=TikTokWarehouseSelection.choose(warehouses,config.warehouseId());}
        catch(BusinessException failure){selected=Map.of();error=failure.getMessage();}
        return new View(shopId,config.warehouseId(),config.version(),warehouses,
                String.valueOf(selected.getOrDefault("id","")),error);
    }
    public void save(Map<String,Object> body,PlatformPrincipal user){
        save(body,user,"");
    }
    public void save(Map<String,Object> body,PlatformPrincipal user,String captureTemplateId){
        ProductListingTemplateService.requireAccess(user,true);
        String module=ProductListingTemplateService.settingsModule(captureTemplateId);
        String shopId=Objects.toString(body.get("shopId"),""),warehouseId=Objects.toString(body.get("warehouseId"),"").strip();
        var current=get(shopId,user,captureTemplateId);TikTokWarehouseSelection.choose(current.warehouses(),warehouseId);
        long version;
        try{version=new java.math.BigDecimal(String.valueOf(body.get("version"))).longValueExact();}
        catch(Exception error){throw new BusinessException(HttpStatus.BAD_REQUEST,"请重新读取发货仓库后保存");}
        int changed;
        if(version==0)changed=db.update("""
            INSERT IGNORE INTO product_listing_warehouses(organization_id,shop_id,data_module,capture_template_id,warehouse_id,version,updated_by)
            VALUES(?,?,?,?,?,1,?)
            """,user.organizationId(),shopId,module,captureTemplateId,warehouseId,user.userId());
        else if(version>0)changed=db.update("""
            UPDATE product_listing_warehouses SET warehouse_id=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
            WHERE organization_id=? AND shop_id=? AND data_module=? AND version=? AND capture_template_id=?
            """,warehouseId,user.userId(),user.organizationId(),shopId,module,version,captureTemplateId);
        else throw new BusinessException(HttpStatus.BAD_REQUEST,"发货仓库配置版本无效");
        if(changed!=1)throw new BusinessException(HttpStatus.CONFLICT,"发货仓库配置已被修改，请重新读取后保存");
    }
    public record Config(String warehouseId,long version){}
    public record View(String shopId,String warehouseId,long version,List<Map<String,Object>> warehouses,String resolvedWarehouseId,String error){}
}
