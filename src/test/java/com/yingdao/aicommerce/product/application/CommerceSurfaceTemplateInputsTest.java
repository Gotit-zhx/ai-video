package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommerceSurfaceTemplateInputsTest {
    private CommerceImageArchive archive() throws Exception {
        var archive=mock(CommerceImageArchive.class);
        byte[] blank=SurfaceTemplateFixture.blank(),visible=SurfaceTemplateFixture.visibleUv(),hidden=SurfaceTemplateFixture.hiddenUv();
        when(archive.read(eq("org"),anyString())).thenAnswer(call->{String key=call.getArgument(1);return SurfaceTemplateFixture.reference(
                key.endsWith(SurfaceTemplateFixture.hash('e'))?visible:key.endsWith(SurfaceTemplateFixture.hash('f'))?hidden:blank);});
        return archive;
    }
    @Test void fixedBackPlacementDoesNotBecomeAFrontSlot() throws Exception {
        var archive=mock(CommerceImageArchive.class);
        byte[] blank=SurfaceTemplateFixture.blank(),visible=SurfaceTemplateFixture.visibleUv(),hidden=SurfaceTemplateFixture.hiddenUv();
        when(archive.read(eq("org"),anyString())).thenAnswer(call->{String key=call.getArgument(1);return SurfaceTemplateFixture.reference(
                key.endsWith(SurfaceTemplateFixture.hash('e'))?visible:key.endsWith(SurfaceTemplateFixture.hash('f'))?hidden:blank);});
        var sides=CommerceSurfaceTemplateInputs.load(SurfaceTemplateFixture.configuration(),archive,"org");
        assertEquals(Set.of("BACK"),sides.keySet());
        assertEquals(Set.of("back-slot"),sides.get("BACK").visiblePrintIds());
        assertEquals("MOCKUP_BACK",CommerceSurfaceTemplateInputs.kind("BACK"));
    }
    @Test void staleBindingAndIncompleteSurfaceDraftCannotUseLegacyDefaultArea() {
        var c=SurfaceTemplateFixture.configuration();
        assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.validate(SurfaceTemplateFixture.binding(c,"stale")));
        var noPrint=new CommercePrintTemplateService.Configuration(c.product(),List.of(),c.baseImages(),c.reconstruction(),
                c.sourceImageId(),c.referenceSetId(),List.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),null);
        assertTrue(CommerceSurfaceTemplateInputs.enabled(noPrint));
        var failure=assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.validate(noPrint));
        assertTrue(failure.getMessage().contains("默认正面"));
    }
    @Test void entirelyHiddenPlacementIsReportedRatherThanDropped() throws Exception {
        var archive=mock(CommerceImageArchive.class);var hidden=SurfaceTemplateFixture.reference(SurfaceTemplateFixture.hiddenUv());
        when(archive.read(eq("org"),anyString())).thenReturn(hidden);
        var failure=assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.load(SurfaceTemplateFixture.configuration(),archive,"org"));
        assertTrue(failure.getMessage().contains("不可见"));
    }
    @Test void topAndBottomOnlyPrintsUseFrontAppearancePhotoWithoutRequiringMoreGeneratedPhotos() throws Exception {
        for(String side:List.of("TOP","BOTTOM")) {
            var uv=new HashMap<>(SurfaceTemplateFixture.sides(SurfaceTemplateFixture.hash('f')));uv.put(side,SurfaceTemplateFixture.hash('e'));
            var c=SurfaceTemplateFixture.withUvViews(SurfaceTemplateFixture.configuration(),uv);
            var sides=CommerceSurfaceTemplateInputs.load(c,archive(),"org");
            assertEquals(Set.of(side),sides.keySet());assertFalse(c.baseImages().containsKey(side));
            assertEquals(CommerceSurfaceTemplateInputs.key("org",c.baseImages().get("FRONT")),sides.get(side).photoKey());
            assertEquals("MOCKUP_"+side,CommerceSurfaceTemplateInputs.kind(side));
            assertTrue(CommerceSurfaceTemplateInputs.isSurfaceJob("MOCKUP_"+side));
        }
    }
    @Test void supplementalCamerasAreSkippedWhenStandardViewsAlreadyCoverEveryPrint() throws Exception {
        var c=SurfaceTemplateFixture.configuration();var uv=new HashMap<>(c.surfaceUvViews());
        uv.put("TOP",SurfaceTemplateFixture.hash('7'));uv.put("BOTTOM",SurfaceTemplateFixture.hash('8'));
        c=SurfaceTemplateFixture.withUvViews(c,uv);var archive=archive();
        assertEquals(Set.of("BACK"),CommerceSurfaceTemplateInputs.load(c,archive,"org").keySet());
        verify(archive,never()).read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('7')));
        verify(archive,never()).read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('8')));
    }
    @Test void missingSecondPrintIsStillRejectedAfterAllSixViews() throws Exception {
        var c=SurfaceTemplateFixture.multipleArtwork();var uv=new HashMap<>(c.surfaceUvViews());
        uv.put("TOP",SurfaceTemplateFixture.hash('e'));uv.put("BOTTOM",SurfaceTemplateFixture.hash('e'));
        var allSix=SurfaceTemplateFixture.withUvViews(c,uv);
        var failure=assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.load(allSix,archive(),"org"));
        assertTrue(failure.getMessage().contains("仍不可见"));
    }
    @Test void supplementalCameraIncludesOnlyNewCoverageAndPreservesOtherVisiblePatterns() throws Exception {
        var c=SurfaceTemplateFixture.multipleArtwork();var uv=new HashMap<>(c.surfaceUvViews());
        uv.put("TOP",SurfaceTemplateFixture.hash('7'));uv.put("BOTTOM",SurfaceTemplateFixture.hash('8'));
        c=SurfaceTemplateFixture.withUvViews(c,uv);var archive=archive();
        when(archive.read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('7'))))
                .thenReturn(SurfaceTemplateFixture.reference(CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff000001,0xff000002},{0,0}})));
        var sides=CommerceSurfaceTemplateInputs.load(c,archive,"org");
        assertEquals(Set.of("BACK","TOP"),sides.keySet());
        assertEquals(Set.of("back-slot","fixed-badge"),sides.get("TOP").visiblePrintIds());
        verify(archive,never()).read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('8')));
    }
    @Test void multipleArtworksRequireExplicitChoiceAndKeepUnselectedArtBytes() throws Exception {
        var c=SurfaceTemplateFixture.multipleArtwork();
        assertTrue(assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.variationArtwork(c,null)).getMessage().contains("选择本次"));
        assertEquals(SurfaceTemplateFixture.hash('2'),CommerceSurfaceTemplateInputs.variationArtwork(c,SurfaceTemplateFixture.hash('2')));
        var archive=mock(CommerceImageArchive.class);
        byte[] original=CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff0000ff}}),variant=CommerceSurfaceMockupComposerTest.png(new int[][]{{0xffff0000}});
        when(archive.read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('5')))).thenReturn(SurfaceTemplateFixture.reference(original));
        var side=new CommerceSurfaceTemplateInputs.Side("BACK","original","photo","blank","uv",SurfaceTemplateFixture.blank(),SurfaceTemplateFixture.visibleUv(),Set.of("back-slot","fixed-badge"));
        var replacements=CommerceSurfaceTemplateInputs.replacements(c,side,SurfaceTemplateFixture.hash('2'),variant,archive,"org");
        assertArrayEquals(variant,replacements.get("back-slot"));
        assertArrayEquals(original,replacements.get("fixed-badge"));
        verify(archive).read("org",CommerceSurfaceTemplateInputs.key("org",SurfaceTemplateFixture.hash('5')));
    }

    @Test void repeatedCheckerUsesOneVariantAcrossFrontBackAndEveryPlacementWhileBadgeStaysOriginal() throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();
        var assets=RepeatedSurfaceArtworkFixture.assets();
        var archive=mock(CommerceImageArchive.class);
        when(archive.read(eq("org"),anyString())).thenAnswer(call->
                SurfaceTemplateFixture.reference(assets.get(((String)call.getArgument(1)).substring(65))));
        var sides=CommerceSurfaceTemplateInputs.load(config,archive,"org");
        assertEquals(Set.of("FRONT","BACK"),sides.keySet());
        clearInvocations(archive);
        byte[] first=assets.get(SurfaceTemplateFixture.hash('4'));
        var front=CommerceSurfaceTemplateInputs.replacements(config,sides.get("FRONT"),RepeatedSurfaceArtworkFixture.CHECKER,first,archive,"org");
        var back=CommerceSurfaceTemplateInputs.replacements(config,sides.get("BACK"),RepeatedSurfaceArtworkFixture.CHECKER,first,archive,"org");
        assertSame(first,front.get("front-checker"));
        assertSame(first,front.get("front-repeat"));
        assertSame(first,back.get("back-checker"));
        assertArrayEquals(assets.get(RepeatedSurfaceArtworkFixture.BADGE),front.get("fixed-badge"));
        assertArrayEquals(front.get("fixed-badge"),back.get("fixed-badge"));
        var frontImage=CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(sides.get("FRONT").blank(),sides.get("FRONT").uv(),config.renderPrintIds(),front));
        var backImage=CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(sides.get("BACK").blank(),sides.get("BACK").uv(),config.renderPrintIds(),back));
        var texture=CommerceMockupComposer.decode(first);
        for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
            assertEquals(texture.getRGB(x,y),frontImage.getRGB(x,y));
            assertEquals(texture.getRGB(x,y),frontImage.getRGB(x+3,y));
            assertEquals(texture.getRGB(x,y),backImage.getRGB(x,y));
        }
        assertEquals(0xff0000ff,frontImage.getRGB(6,0));
        assertEquals(0xff0000ff,backImage.getRGB(6,0));
        assertEquals(0xff444444,frontImage.getRGB(7,1));
        verify(archive,never()).read("org",CommerceSurfaceTemplateInputs.key("org",RepeatedSurfaceArtworkFixture.CHECKER));

        byte[] second=assets.get(SurfaceTemplateFixture.hash('7'));
        var secondFront=CommerceSurfaceTemplateInputs.replacements(config,sides.get("FRONT"),RepeatedSurfaceArtworkFixture.CHECKER,second,archive,"org");
        var secondImage=CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(sides.get("FRONT").blank(),sides.get("FRONT").uv(),config.renderPrintIds(),secondFront));
        assertEquals(0xff00ff00,secondImage.getRGB(0,0));
        assertEquals(secondImage.getRGB(0,0),secondImage.getRGB(3,0));
        assertNotEquals(frontImage.getRGB(0,0),secondImage.getRGB(0,0));
        assertEquals(0xffff0000,frontImage.getRGB(0,0));
        assertEquals(0xff0000ff,secondImage.getRGB(6,0));
    }

    @Test void sourceProductGroupsReplaceEveryTestPatternAndRejectMissingOrForeignGroup() throws Exception {
        var config=RepeatedSurfaceArtworkFixture.configuration();
        var assets=RepeatedSurfaceArtworkFixture.assets();
        var archive=mock(CommerceImageArchive.class);
        when(archive.read(eq("org"),anyString())).thenAnswer(call->
                SurfaceTemplateFixture.reference(assets.get(((String)call.getArgument(1)).substring(65))));
        var sides=CommerceSurfaceTemplateInputs.load(config,archive,"org");
        assertEquals(List.of(RepeatedSurfaceArtworkFixture.CHECKER,RepeatedSurfaceArtworkFixture.BADGE),CommerceSurfaceTemplateInputs.artworkGroups(config));
        byte[] checkerVariant=assets.get(SurfaceTemplateFixture.hash('4')),badgeVariant=assets.get(SurfaceTemplateFixture.hash('7'));
        var variants=Map.of(RepeatedSurfaceArtworkFixture.CHECKER,checkerVariant,RepeatedSurfaceArtworkFixture.BADGE,badgeVariant);
        for(var side:sides.values()) {
            var replacements=CommerceSurfaceTemplateInputs.replacements(config,side,variants);
            for(String id:side.visiblePrintIds())assertSame(id.equals("fixed-badge")?badgeVariant:checkerVariant,replacements.get(id));
            var image=CommerceMockupComposer.decode(CommerceSurfaceMockupComposer.compose(side.blank(),side.uv(),config.renderPrintIds(),replacements));
            assertEquals(0xffff0000,image.getRGB(0,0));
            assertEquals(0xffff00ff,image.getRGB(6,0)); // Badge UV v=0 samples the new design's lower left.
        }
        assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.replacements(config,sides.get("FRONT"),Map.of(RepeatedSurfaceArtworkFixture.CHECKER,checkerVariant)));
        assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.replacements(config,sides.get("FRONT"),Map.of(RepeatedSurfaceArtworkFixture.CHECKER,checkerVariant,"foreign",badgeVariant)));
        assertEquals("VARIATE_G32",CommerceSurfaceTemplateInputs.variationKind(32));
        assertThrows(BusinessException.class,()->CommerceSurfaceTemplateInputs.variationKind(33));
    }
}
