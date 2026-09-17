package com.yingdao.aicommerce.product.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceSceneBackgroundComposerTest {
    byte[] png(BufferedImage image)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    BufferedImage solid(int w,int h,int color){var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,color);return image;}
    BufferedImage garment(int cloth){var image=solid(100,120,0xffffffff);for(int y=15;y<105;y++)for(int x=25;x<75;x++)image.setRGB(x,y,cloth);return image;}
    @Test void preservesClothWhiteInkAndPlacementWhileReplacingOnlyScene()throws Exception{
        var ref=garment(0xffdddddd);var print=garment(0xff222222);
        for(int y=40;y<52;y++)for(int x=40;x<58;x++)print.setRGB(x,y,0xffffffff);
        var result=CommerceSceneBackgroundComposer.compose(png(print),png(ref),png(solid(100,120,0xff956330)));
        var output=CommerceMockupComposer.decode(result.png());
        for(int y=18;y<102;y++)for(int x=28;x<72;x++)assertEquals(print.getRGB(x,y),output.getRGB(x,y));
        assertEquals(0xff956330,output.getRGB(0,0));assertEquals(0,result.report().protectedInteriorChangedPixels());
        assertTrue(result.report().backgroundPixels()>4000);assertEquals(100,output.getWidth());assertEquals(120,output.getHeight());
    }
    @Test void reusesIdenticalBackgroundAcrossDifferentColorsAndPrints()throws Exception{
        var ref=png(garment(0xffcccccc));var scene=png(solid(100,120,0xff624525));
        var a=CommerceMockupComposer.decode(CommerceSceneBackgroundComposer.compose(png(garment(0xff222222)),ref,scene).png());
        var b=CommerceMockupComposer.decode(CommerceSceneBackgroundComposer.compose(png(garment(0xffee7799)),ref,scene).png());
        for(int y=0;y<120;y++)for(int x=0;x<22;x++)assertEquals(a.getRGB(x,y),b.getRGB(x,y));
        assertNotEquals(a.getRGB(50,60),b.getRGB(50,60));
    }
    @Test void removesWhiteMatteFromAntialiasedBlackSleeveEdge()throws Exception{
        var ref=garment(0xffaaaaaa);var print=garment(0xff202020);
        for(int y=15;y<105;y++){ref.setRGB(24,y,0xffdddddd);print.setRGB(24,y,0xffaaaaaa);}
        var result=CommerceMockupComposer.decode(CommerceSceneBackgroundComposer.compose(png(print),png(ref),png(solid(100,120,0xff101010))).png());
        assertTrue((result.getRGB(24,60)&255)<50,"outer edge must blend cloth with the new dark scene, not keep white matte");
        assertEquals(0xff202020,result.getRGB(50,60));
    }
    @Test void doesNotKeyOutAnEnclosedWhiteGarmentHighlight()throws Exception{
        var ref=garment(0xffeeeeee);for(int y=40;y<70;y++)for(int x=35;x<60;x++)ref.setRGB(x,y,0xffffffff);
        var out=CommerceMockupComposer.decode(CommerceSceneBackgroundComposer.compose(png(ref),png(ref),png(solid(100,120,0xff774411))).png());
        assertEquals(0xffffffff,out.getRGB(45,55));
    }
    @Test void rejectsDifferentGeometryDimensionsBeforeUsingAnUnrelatedMask()throws Exception{
        assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundComposer.compose(png(garment(0xff222222)),png(solid(80,80,0xffffffff)),png(solid(100,120,0xff774411))));
    }
    @Test void rejectsComplexSourceEdgesInsteadOfErasingUnknownForeground()throws Exception{
        var ref=garment(0xffdddddd);for(int y=0;y<120;y++)ref.setRGB(0,y,y%2==0?0xffff0000:0xff00ff00);
        assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundComposer.compose(png(ref),png(ref),png(solid(100,120,0xff774411))));
    }
    @Test void refusesToSilentlyEraseArtworkPlacedOutsideTheGarment()throws Exception{
        var reference=garment(0xffdddddd);var printed=garment(0xffdddddd);
        for(int y=30;y<40;y++)for(int x=5;x<15;x++)printed.setRGB(x,y,0xffe02222);
        assertTrue(assertThrows(IllegalArgumentException.class,()->CommerceSceneBackgroundComposer.compose(png(printed),png(reference),png(solid(100,120,0xff774411)))).getMessage().contains("丢失图案"));
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="scene.fixture",matches=".+")
    void actualGarmentFixtureCanBeRenderedForVisualReview()throws Exception{
        String input=System.getProperty("scene.fixture");
        var dir=java.nio.file.Path.of(input);byte[] ref=java.nio.file.Files.readAllBytes(dir.resolve("front.png"));
        byte[] background=java.nio.file.Files.readAllBytes(dir.resolve("scene.png"));
        var config=new tools.jackson.databind.ObjectMapper().readValue(java.nio.file.Files.readString(dir.resolve("template-config.json")),CommercePrintTemplateService.Configuration.class);
        byte[] uv=java.nio.file.Files.readAllBytes(dir.resolve("uv.png")),artwork=java.nio.file.Files.readAllBytes(dir.resolve("artwork.png"));
        var printKeys=new java.util.LinkedHashMap<String,byte[]>();config.renderPrintIds().forEach(id->printKeys.put(id,artwork));
        byte[] whiteMockup=CommercePhotoMockupComposer.composePhoto("FRONT",ref,ref,ref,uv,config.renderPrintIds(),printKeys).png();
        var result=CommerceSceneBackgroundComposer.compose(whiteMockup,ref,background);java.nio.file.Files.write(dir.resolve("scene-composite-white.png"),result.png());
        // A second SKU proves the background is reused with no model-generated clothing.
        byte[] black=png(garment(0xff303030));var colored=CommerceGarmentColor.prepare(ref,black,"黑色",false);
        byte[] blackMockup=CommercePhotoMockupComposer.composePhoto("FRONT",colored.png(),ref,ref,uv,config.renderPrintIds(),printKeys).png();
        var blackResult=CommerceSceneBackgroundComposer.compose(blackMockup,ref,background);java.nio.file.Files.write(dir.resolve("scene-composite-black.png"),blackResult.png());
        assertEquals(0,result.report().protectedInteriorChangedPixels());
    }
}
