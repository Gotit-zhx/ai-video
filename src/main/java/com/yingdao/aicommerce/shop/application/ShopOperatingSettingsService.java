package com.yingdao.aicommerce.shop.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.mapper.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.application.CommerceBatchPlanner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;

@Service
public class ShopOperatingSettingsService {
    public record Request(String groupName,String environment,int dailyListingLimit,
                          String defaultTemplateSnapshotId,long expectedVersion) {}
    private final ShopMapper shops;
    private final ShopOperatingSettingsMapper settings;
    private final CommerceTemplateMapper templates;
    private final ObjectMapper json;
    public ShopOperatingSettingsService(ShopMapper shops,ShopOperatingSettingsMapper settings,
            CommerceTemplateMapper templates,ObjectMapper json){this.shops=shops;this.settings=settings;this.templates=templates;this.json=json;}
    private Shop owned(String id,PlatformPrincipal user,boolean edit){
        if(user==null || user.organizationId()==null || !(edit?user.permissionCodes().contains("product:publish"):
                user.permissionCodes().contains("product:view")||user.permissionCodes().contains("product:publish")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"无店铺管理权限");
        Shop s=shops.selectById(id);
        if(s==null || !user.organizationId().equals(s.getOrganizationId())) throw new BusinessException(HttpStatus.NOT_FOUND,"店铺不存在");
        return s;
    }
    public ShopOperatingSettings get(String id,PlatformPrincipal user){
        owned(id,user,false);
        var value=settings.get(user.organizationId(),id);
        return value!=null?value:new ShopOperatingSettings(id,user.organizationId(),"","UNKNOWN",50,null,0,null,null);
    }
    @Transactional
    public ShopOperatingSettings save(String id,Request r,PlatformPrincipal user){
        Shop shop=owned(id,user,true);
        if(r==null || r.environment()==null || !Set.of("UNKNOWN","TEST","LIVE").contains(r.environment())
                || r.dailyListingLimit()<1 || r.dailyListingLimit()>500 || r.expectedVersion()<0 || r.expectedVersion()==Long.MAX_VALUE
                || r.groupName()==null || r.groupName().trim().length()>80)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"请检查分组、环境、每日额度及版本");
        String template=r.defaultTemplateSnapshotId();
        if(template!=null && template.isBlank())template=null;
        if(template!=null){
            var snapshot=templates.snapshot(user.organizationId(),template);
            if(snapshot==null)throw new BusinessException(HttpStatus.NOT_FOUND,"默认模板不存在");
            if(!"WORKFLOW".equals(snapshot.kind()))throw new BusinessException(HttpStatus.BAD_REQUEST,"店铺默认上架模板必须为铺货工作流类型");
            var config=json.readValue(snapshot.configJson(),CommerceBatchPlanner.Request.class);
            if(!config.platform().name().equals(shop.getPlatform()) || !config.market().equalsIgnoreCase(shop.getRegion()))
                throw new BusinessException(HttpStatus.BAD_REQUEST,"默认模板平台与站点不匹配");
        }
        var saved=new ShopOperatingSettings(id,user.organizationId(),r.groupName().trim(),r.environment(),
                r.dailyListingLimit(),template,r.expectedVersion()+1,user.userId(),Instant.now());
        try{
            if(r.expectedVersion()==0)settings.insert(saved);
            else if(settings.update(saved,r.expectedVersion())!=1)throw conflict();
        }catch(DuplicateKeyException e){throw conflict();}
        return saved;
    }
    private static BusinessException conflict(){return new BusinessException(HttpStatus.CONFLICT,"店铺配置已改变，请重新读取后保存");}
}
