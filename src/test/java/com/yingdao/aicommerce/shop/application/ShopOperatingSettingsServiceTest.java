package com.yingdao.aicommerce.shop.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.application.CommerceBatchPlanner;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ShopOperatingSettingsServiceTest {
    final ShopMapper shops=mock(ShopMapper.class);
    final ShopOperatingSettingsMapper mapper=mock(ShopOperatingSettingsMapper.class);
    final CommerceTemplateMapper templates=mock(CommerceTemplateMapper.class);
    final ShopOperatingSettingsService service=new ShopOperatingSettingsService(shops,mapper,templates,new ObjectMapper());
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:view","product:publish"));
    void shop(String org){var s=mock(Shop.class);when(s.getOrganizationId()).thenReturn(org);when(shops.selectById("shop")).thenReturn(s);}
    @Test void unconfiguredShopDefaultsToUnknownWithoutWriting(){shop("org");var value=service.get("shop",user);assertEquals("UNKNOWN",value.environment());assertEquals(0,value.version());verify(mapper,never()).insert(any());}
    @Test void crossCompanyAccessIsDenied(){shop("foreign");assertEquals(HttpStatus.NOT_FOUND,assertThrows(BusinessException.class,()->service.get("shop",user)).getStatus());verifyNoInteractions(mapper);}
    @Test void createStoresCurrentUserAndCompany(){shop("org");var value=service.save("shop",new ShopOperatingSettingsService.Request("A组","TEST",30,null,0),user);assertEquals("org",value.organizationId());assertEquals("u",value.updatedBy());assertEquals(1,value.version());verify(mapper).insert(value);}
    @Test void staleUpdateIsRejected(){shop("org");assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.save("shop",new ShopOperatingSettingsService.Request("A","LIVE",30,null,1),user)).getStatus());}
    @Test void invalidQuotaAndEnvironmentRejected(){shop("org");assertThrows(BusinessException.class,()->service.save("shop",new ShopOperatingSettingsService.Request("A","LIVE",0,null,0),user));assertThrows(BusinessException.class,()->service.save("shop",new ShopOperatingSettingsService.Request("A","PROD",30,null,0),user));verifyNoInteractions(mapper);}
    @Test void foreignDefaultTemplateRejected(){shop("org");assertThrows(BusinessException.class,()->service.save("shop",new ShopOperatingSettingsService.Request("A","TEST",30,"foreign-snapshot",0),user));verifyNoInteractions(mapper);}
    void template(List<String> selected){
        shop("org");var shop=shops.selectById("shop");when(shop.getId()).thenReturn("shop");when(shop.getStatus()).thenReturn("ACTIVE");
        when(shop.getPlatform()).thenReturn("TIKTOK_SHOP");when(shop.getRegion()).thenReturn("VN");
        var config=new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,CommerceBatchPlanner.Platform.SHOPEE,"PH",1,1,1,1,50,false,false,false,BigDecimal.ZERO,7,BigDecimal.ONE,selected);
        when(templates.snapshot("org","mixed-template")).thenReturn(new CommerceTemplateVersion("mixed-template","org","template",1,"WORKFLOW","混合模板",new ObjectMapper().writeValueAsString(config),"u",Instant.now()));
    }
    @Test void selectedShopCanUseDefaultTemplateAcrossPlatformsAndMarkets(){
        template(List.of("shop"));
        var result=service.save("shop",new ShopOperatingSettingsService.Request("A","TEST",30,"mixed-template",0),user);
        assertEquals("mixed-template",result.defaultTemplateSnapshotId());verify(mapper).insert(result);
    }
    @Test void reusableDefaultTemplateIgnoresLegacySavedDestinations(){
        template(List.of("different-shop"));
        var saved=service.save("shop",new ShopOperatingSettingsService.Request("A","TEST",30,"mixed-template",0),user);
        assertEquals("mixed-template",saved.defaultTemplateSnapshotId());verify(mapper).insert(saved);
    }
}
