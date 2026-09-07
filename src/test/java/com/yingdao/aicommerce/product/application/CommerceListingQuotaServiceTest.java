package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.domain.*;
import com.yingdao.aicommerce.shop.mapper.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommerceListingQuotaServiceTest {
    final CommerceBatchMapper batches=mock(CommerceBatchMapper.class);
    final CommerceListingQuotaMapper quota=mock(CommerceListingQuotaMapper.class);
    final ShopMapper shops=mock(ShopMapper.class);
    final ShopOperatingSettingsMapper settings=mock(ShopOperatingSettingsMapper.class);
    final CommerceListingQuotaService service=new CommerceListingQuotaService(batches,quota,shops,settings);
    final PlatformPrincipal user=new PlatformPrincipal("u","org","code","login","User","x",true,List.of(),List.of("product:view","product:publish"));
    final LocalDate date=LocalDate.now(ZoneId.of("Asia/Manila")).plusDays(1);
    void setup(){
        when(batches.lock("org","batch")).thenReturn(new CommerceBatch("batch","org","name","snapshot","key","hash","DRAFT","u",Instant.now()));
        when(batches.items("org","batch")).thenReturn(List.of(new CommerceBatchItem("item","org","batch","capture",1,"shop","name",1,"DRAFT",Instant.now())));
        when(quota.list("org","batch")).thenReturn(List.of());
        var shop=mock(Shop.class);when(shop.getOrganizationId()).thenReturn("org");when(shop.getStatus()).thenReturn("ACTIVE");when(shop.getRegion()).thenReturn("PH");when(shops.selectById("shop")).thenReturn(shop);
        when(settings.get("org","shop")).thenReturn(new ShopOperatingSettings("shop","org","","TEST",50,null,1,"u",Instant.now()));
    }
    @Test void reservesOnlyWhenCapacityAllows(){setup();when(quota.reserve("org","shop",date,1,50)).thenReturn(1);var r=service.reserve("batch",new CommerceListingQuotaService.Request(date,true),user);assertEquals("Asia/Manila",r.getFirst().timeZone());verify(quota).insert(any());}
    @Test void fullDayRejectsWithoutCreatingReservations(){setup();assertThrows(BusinessException.class,()->service.reserve("batch",new CommerceListingQuotaService.Request(date,true),user));verify(quota,never()).insert(any());}
    @Test void sameBatchDateIsIdempotent(){setup();var old=new CommerceListingReservation("r","org","batch","item","shop",date,"Asia/Manila","u",Instant.now());when(quota.list("org","batch")).thenReturn(List.of(old));assertEquals(List.of(old),service.reserve("batch",new CommerceListingQuotaService.Request(date,true),user));verify(quota,never()).reserve(any(),any(),any(),anyInt(),anyInt());}
    @Test void changedDateCannotDoubleReserve(){setup();var old=new CommerceListingReservation("r","org","batch","item","shop",date,"Asia/Manila","u",Instant.now());when(quota.list("org","batch")).thenReturn(List.of(old));assertThrows(BusinessException.class,()->service.reserve("batch",new CommerceListingQuotaService.Request(date.plusDays(1),true),user));}
    @Test void requiresExplicitConfirmation(){setup();assertThrows(BusinessException.class,()->service.reserve("batch",new CommerceListingQuotaService.Request(date,false),user));verifyNoInteractions(quota);}
    @Test void pastDateRejected(){setup();assertThrows(BusinessException.class,()->service.reserve("batch",new CommerceListingQuotaService.Request(date.minusDays(5),true),user));verify(quota,never()).reserve(any(),any(),any(),anyInt(),anyInt());}
}
