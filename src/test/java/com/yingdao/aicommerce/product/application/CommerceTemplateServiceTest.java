package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.domain.MarketplacePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceTemplateServiceTest {
    private final CommerceTemplateMapper mapper=mock(CommerceTemplateMapper.class);
    private final ShopMapper shops=mock(ShopMapper.class);
    private final ObjectMapper json=new ObjectMapper();
    private final CommerceTemplateService service=new CommerceTemplateService(mapper,new CommerceBatchPlanner(),json,
            new CommerceTemplateShopResolver(shops));
    private final PlatformPrincipal user=new PlatformPrincipal("u1","org1","org","login","User","x",true,
            List.of(),List.of("product:view","product:edit"));
    private CommerceBatchPlanner.Request config() {
        return new CommerceBatchPlanner.Request(CommerceBatchPlanner.Mode.POD_DESIGN,
                CommerceBatchPlanner.Platform.TIKTOK_SHOP,"PH",30,10,1,6,50,
                true,true,true,new BigDecimal("1000"),7,new BigDecimal("6.5"));
    }
    @Test void createsImmutableVersionAndClearsBatchDeclarations() {
        var v=service.save(new CommerceTemplateService.SaveRequest(null,0,"PH测款",config()),user);
        assertEquals(1,v.version()); assertFalse(v.configuration().rightsConfirmed());
        assertFalse(v.configuration().supplyConfirmed()); assertFalse(v.configuration().productionFilesConfirmed());
        verify(mapper).insert(argThat(t->t.organizationId().equals("org1") && t.createdBy().equals("u1") && t.version()==1));
    }
    @Test void onlyReadsCurrentOrganization() {
        when(mapper.listKind("org1","WORKFLOW")).thenReturn(List.of());
        assertTrue(service.list(user).isEmpty()); verify(mapper).listKind("org1","WORKFLOW");
    }
    @Test void foreignTemplateCannotBeUpdated() {
        String id=UUID.randomUUID().toString();
        var error=assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(id,1,"test",config()),user));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatus());
        verify(mapper).lockRoot("org1",id); verify(mapper,never()).insert(any());
    }
    @Test void staleVersionRejectedWithoutOverwritingHistory() {
        String id=UUID.randomUUID().toString();
        var current=new CommerceTemplateVersion(UUID.randomUUID().toString(),"org1",id,2,"WORKFLOW","test",
                json.writeValueAsString(config()),"u1",Instant.now());
        when(mapper.lockRoot("org1",id)).thenReturn(current);
        when(mapper.latest("org1",id)).thenReturn(current);
        var e=assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(id,1,"test",config()),user));
        assertEquals(HttpStatus.CONFLICT,e.getStatus()); verify(mapper,never()).insert(any());
    }
    @Test void matchingVersionAppendsSnapshot() {
        String id=UUID.randomUUID().toString();
        var current=new CommerceTemplateVersion(UUID.randomUUID().toString(),"org1",id,1,"WORKFLOW","test",
                json.writeValueAsString(config()),"u1",Instant.now());
        when(mapper.lockRoot("org1",id)).thenReturn(current); when(mapper.latest("org1",id)).thenReturn(current);
        var v=service.save(new CommerceTemplateService.SaveRequest(id,1,"new",config()),user);
        assertEquals(2,v.version()); assertEquals(id,v.templateId()); assertNotEquals(current.id(),v.snapshotId());
    }
    @Test void readOnlyUserCannotSave() {
        var viewer=new PlatformPrincipal("u1","org1","org","login","User","x",true,List.of(),List.of("product:view"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save(
                new CommerceTemplateService.SaveRequest(null,0,"test",config()),viewer)).getStatus());
        verifyNoInteractions(mapper);
    }
    private CommerceBatchPlanner.Request withShops(List<String> ids) {
        var c=config();
        return new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),
                c.designsPerProduct(),c.imagesPerDesign(),99,c.dailyListingsPerShop(),true,true,true,
                c.dailyBudgetPerShop(),c.observationDays(),c.targetRoi(),ids,
                List.of(new CommerceBatchPlanner.ShopTarget("old-shop",CommerceBatchPlanner.Platform.TEMU,"US","USD")),"PHP");
    }
    @Test void newVersionsDiscardAllShopSelectionsWithoutQueryingAuthorization() {
        for(var ids:List.of(List.of("expired-shop","foreign-shop"),List.of("duplicate","duplicate"),List.of(" "),
                java.util.stream.IntStream.range(0,51).mapToObj(i->"old-shop-"+i).toList())) {
            var saved=service.save(new CommerceTemplateService.SaveRequest(null,0,"通用模板",withShops(ids)),user);
            assertEquals(1,saved.configuration().shopCount());
            assertEquals(List.of(),saved.configuration().shopIds());
            assertEquals(List.of(),saved.configuration().shopTargets());
            assertEquals(CommerceBatchPlanner.Platform.TIKTOK_SHOP,saved.configuration().platform());
            assertEquals("PH",saved.configuration().market());
        }
        verifyNoInteractions(shops);
    }
    @Test void strippedDestinationsPreserveBudgetDenominationAndAllCreativeSettings() {
        var c=withShops(List.of("old-shop"));
        var saved=service.save(new CommerceTemplateService.SaveRequest(null,0,"通用模板",c),user).configuration();
        assertEquals(c.mode(),saved.mode());assertEquals(c.designsPerProduct(),saved.designsPerProduct());
        assertEquals(c.imagesPerDesign(),saved.imagesPerDesign());assertEquals(c.dailyListingsPerShop(),saved.dailyListingsPerShop());
        assertEquals(c.dailyBudgetPerShop(),saved.dailyBudgetPerShop());assertEquals("PHP",saved.budgetCurrency());
        assertEquals(c.observationDays(),saved.observationDays());assertEquals(c.targetRoi(),saved.targetRoi());
        verifyNoInteractions(shops);
    }
    @Test void readingOldSnapshotDoesNotRemoveItsHistoricalShopSelection() {
        String id=UUID.randomUUID().toString();var original=withShops(List.of("old-shop"));
        var old=new CommerceTemplateVersion("old-snapshot","org1",id,1,"WORKFLOW","old",json.writeValueAsString(original),"u1",Instant.now());
        when(mapper.history("org1",id)).thenReturn(List.of(old));
        assertEquals(List.of("old-shop"),service.history(id,user).getFirst().configuration().shopIds());
        assertEquals(99,service.history(id,user).getFirst().configuration().shopCount());
        verify(mapper,never()).insert(any());verifyNoInteractions(shops);
    }
    private CommerceBatchPlanner.Request withPrint(String snapshot) {
        var c=config();
        return new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),c.designsPerProduct(),
                c.imagesPerDesign(),c.shopCount(),c.dailyListingsPerShop(),true,true,true,c.dailyBudgetPerShop(),
                c.observationDays(),c.targetRoi(),List.of("old-shop"),List.of(),null,snapshot);
    }
    @Test void preservesOwnedPrintTemplateBindingWhileRemovingShops() {
        when(mapper.snapshot("org1","print-snapshot")).thenReturn(new CommerceTemplateVersion("print-snapshot","org1","print-root",1,"PRINT_LAYOUT","print","{}","u1",Instant.now()));
        var saved=service.save(new CommerceTemplateService.SaveRequest(null,0,"带版型模板",withPrint("print-snapshot")),user);
        assertEquals("print-snapshot",saved.configuration().printTemplateSnapshotId());
        assertTrue(saved.configuration().shopIds().isEmpty());verifyNoInteractions(shops);
    }
    @Test void foreignOrWrongKindPrintBindingCannotBeSaved() {
        assertThrows(BusinessException.class,()->service.save(new CommerceTemplateService.SaveRequest(null,0,"test",withPrint("foreign")),user));
        when(mapper.snapshot("org1","workflow")).thenReturn(new CommerceTemplateVersion("workflow","org1","root",1,"WORKFLOW","workflow","{}","u1",Instant.now()));
        assertThrows(BusinessException.class,()->service.save(new CommerceTemplateService.SaveRequest(null,0,"test",withPrint("workflow")),user));
        verify(mapper,never()).insert(any());verifyNoInteractions(shops);
    }
    @Test void legacySnapshotsWithoutTargetsContinueToDeserializeAndAppend() {
        String legacy="""
                {"mode":"POD_DESIGN","platform":"SHOPEE","market":"PH","sourceProducts":1,
                 "designsPerProduct":1,"imagesPerDesign":1,"shopCount":1,"dailyListingsPerShop":50,
                 "rightsConfirmed":false,"supplyConfirmed":false,"productionFilesConfirmed":false,
                 "dailyBudgetPerShop":0,"observationDays":7,"targetRoi":1}
                """;
        var c=json.readValue(legacy,CommerceBatchPlanner.Request.class);
        assertNull(c.shopIds());assertNull(c.shopTargets());assertNull(c.printTemplateSnapshotId());
        var saved=service.save(new CommerceTemplateService.SaveRequest(null,0,"旧模板",c),user);
        assertEquals(List.of(),saved.configuration().shopTargets());
        assertEquals("PHP",saved.configuration().budgetCurrency());verifyNoInteractions(shops);
    }
}
