package com.yingdao.aicommerce.shop.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
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
}
