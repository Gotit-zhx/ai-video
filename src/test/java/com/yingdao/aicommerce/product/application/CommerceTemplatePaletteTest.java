package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceTemplatePaletteTest {
    final ObjectMapper json=new ObjectMapper();
    final List<CommerceTemplatePalette.Color> palette=List.of(
            new CommerceTemplatePalette.Color("雾蓝","#ace"),new CommerceTemplatePalette.Color("墨黑","#252A26"));

    @Test void validHexIsNormalizedAndDuplicatesOrEmptyNewPaletteAreRejected() {
        assertEquals("#AACCEE",palette.getFirst().hex());
        assertDoesNotThrow(()->CommerceTemplatePalette.validate(palette));
        for(var invalid:List.of(List.<CommerceTemplatePalette.Color>of(),
                List.of(new CommerceTemplatePalette.Color("bad","blue")),
                List.of(palette.getFirst(),new CommerceTemplatePalette.Color("another","#AACCEE")),
                List.of(palette.getFirst(),new CommerceTemplatePalette.Color("雾蓝","#FFFFFF"))))
            assertThrows(IllegalArgumentException.class,()->CommerceTemplatePalette.validate(invalid));
    }
    @Test void paletteDoesNotNeedCompetitorColorPhotosAndRetainsSizeMatrix() {
        var detail=detail();
        var colors=CommerceTemplatePalette.resolve(palette,detail);
        assertEquals(List.of("#AACCEE","#252A26"),colors.stream().map(CommerceSourceColorResolver.Color::hex).toList());
        assertTrue(colors.stream().allMatch(c->c.imageId()==null&&!c.hasColorReference()));
        assertEquals(List.of("S","M"),colors.getFirst().skuKeys());
        assertDoesNotThrow(()->CommerceBatchCreativePlanValidator.validateColorTargets(
                new CommerceBatchCreativePlanValidator.Selection("capture",1,"main",List.of(),colors),List.of("main")));
        var manifests=colors.stream().map(c->Map.<String,Object>of("colorKey",c.key(),"colorLabel",c.label(),
                "colorHex",c.hex(),"skuKeys",c.skuKeys())).toList();
        var urls=new HashMap<String,String>();
        manifests.forEach(c->((List<String>)c.get("skuKeys")).forEach(key->urls.put(
                CommercePublicationDrafts.skuImageKey(c,key),"image-"+c.get("colorKey"))));
        var drafts=new CommercePublicationDrafts(null,null,null,null,null,json,null);
        var skus=drafts.plannedSkus(detail.skus(),manifests,urls,1);
        assertEquals(4,skus.size());
        assertEquals(4,skus.stream().map(s->s.source().getSkuKey()).distinct().count());
        assertEquals(List.of("S","M","S","M"),skus.stream().map(s->json.readTree(s.attributesJson()).path("Size").asText()).toList());
        assertTrue(skus.stream().allMatch(s->s.source().getPrice().compareTo(BigDecimal.TEN)==0&&s.source().getStock()==12));
        assertTrue(skus.stream().allMatch(s->s.source().getExternalSkuId()==null));
        assertEquals("image-template-aaccee",skus.getFirst().imageUrl());
        assertEquals("image-template-252a26",skus.getLast().imageUrl());
        assertEquals(skus.stream().map(s->s.source().getSkuKey()).toList(),
                drafts.plannedSkus(detail.skus(),manifests,urls,1).stream().map(s->s.source().getSkuKey()).toList());
    }
    @Test void selectedStyleIsTenantCheckedBeforeSaveAndOptionalStyleNeedsNoLookup() {
        var mapper=mock(CommerceTemplateMapper.class);var styles=mock(ProductMainImageTemplateService.class);
        var service=new CommercePrintTemplateService(mapper,json);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mainImageStyles",styles);
        var user=new PlatformPrincipal("u","org","org","u","u","",true,List.of(),List.of("product:edit","product:view"));
        var c=config(null);
        var saved=service.save(new CommercePrintTemplateService.Request(null,0,"配色",c),user);
        assertEquals(palette,saved.configuration().colors());assertNull(saved.configuration().mainImageStyleTemplateId());
        verifyNoInteractions(styles);
        String foreign="11111111-1111-1111-1111-111111111111";
        when(styles.current(foreign,user)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"不存在"));
        clearInvocations(mapper);
        assertThrows(BusinessException.class,()->service.save(new CommercePrintTemplateService.Request(null,0,"外部",config(foreign)),user));
        verify(mapper,never()).insert(any());
        var legacy=new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(new CommercePrintTemplateService.Area("front","FRONT",.2,.2,.3,.3,200,200,300)));
        assertNull(json.readValue(json.writeValueAsString(legacy),CommercePrintTemplateService.Configuration.class).colors());
    }
    @Test void draftFreezesTemplatePaletteInsteadOfClientOrSkuColors() {
        var fixture=new CommerceBatchCreativePlanServiceTest();
        var configured=new LinkedHashMap<String,Object>(json.convertValue(config(null),Map.class));
        configured.put("baseImages",Map.of("FRONT","a".repeat(64)));
        fixture.surfaceDraft(json.convertValue(configured,CommercePrintTemplateService.Configuration.class));
        var detail=detail();
        when(fixture.captures.get("c",fixture.user)).thenReturn(detail);
        var selected=new CommerceBatchCreativePlanValidator.Selection("c",1,"main",List.of(),
                List.of(new CommerceSourceColorResolver.Color("fake","fake",List.of("unowned"),"main",true,"#FF0000")));
        var result=fixture.service.save("b",new CommerceBatchCreativePlanService.Request(0,"surface-snapshot",List.of(selected),2),fixture.user);
        assertEquals("TEMPLATE_COLORS",result.plan().outputPolicy());
        assertEquals(List.of("#AACCEE","#252A26"),result.plan().sources().getFirst().colorTargets().stream().map(CommerceSourceColorResolver.Color::hex).toList());
        var row=org.mockito.ArgumentCaptor.forClass(com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper.Row.class);
        verify(fixture.plans).insert(row.capture());
        when(fixture.plans.latest("org","b")).thenReturn(row.getValue());
        assertSame(row.getValue(),fixture.service.prepareStart("b",1,fixture.user));
    }
    private CommercePrintTemplateService.Configuration config(String style) {
        var base=new CommercePrintTemplateService.Configuration("T_SHIRT",
                List.of(new CommercePrintTemplateService.Area("front","FRONT",.2,.2,.3,.3,200,200,300)),
                Map.of("FRONT","a".repeat(64)));
        var map=new LinkedHashMap<String,Object>(json.convertValue(base,Map.class));
        map.put("colors",palette);map.put("mainImageStyleTemplateId",style);
        // The service-only configuration does not require an archive in the save test.
        map.put("baseImages",Map.of());
        return json.convertValue(map,CommercePrintTemplateService.Configuration.class);
    }
    private ProductCaptureService.CaptureDetail detail() {
        var capture=mock(ProductCapture.class);
        when(capture.getId()).thenReturn("c");when(capture.getOrganizationId()).thenReturn("org");when(capture.getVersion()).thenReturn(1L);
        var skus=List.of("S","M").stream().map(size->new ProductCaptureSku("org","c",size,"old-"+size,"old-"+size,size,
                BigDecimal.TEN,BigDecimal.TEN,12,null,"CAPTURED",json.writeValueAsString(Map.of("Color","竞品原色","Size",size)),"{}",0)).toList();
        var image=new ProductCaptureImage("org","c","https://example.invalid/main.png","MAIN","main",0,"image/png","{}");
        org.springframework.test.util.ReflectionTestUtils.setField(image,"id","main");
        return new ProductCaptureService.CaptureDetail(capture,skus,List.of(image),List.of(),Map.of(),Map.of());
    }
}
