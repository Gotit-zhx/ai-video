package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class CommerceMockupComposerTest {
 byte[] image(int w,int h,Color color)throws Exception{var im=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=im.createGraphics();g.setColor(color);g.fillRect(0,0,w,h);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(im,"png",bytes);return bytes.toByteArray();}
 CommercePrintTemplateService.Area area(){return new CommercePrintTemplateService.Area("main","FRONT",.25,.25,.5,.5,240,240,300);}
 @Test void preservesBaseOutsideExactPlacementAndRetainsWhiteArtwork()throws Exception{var output=ImageIO.read(new ByteArrayInputStream(CommerceMockupComposer.compose(image(64,64,Color.BLUE),List.of(new CommerceMockupComposer.Layer(area(),image(64,64,Color.WHITE))))));assertEquals(1024,output.getWidth());assertEquals(Color.BLUE.getRGB(),output.getRGB(100,100));assertEquals(Color.WHITE.getRGB(),output.getRGB(512,512));assertEquals(Color.BLUE.getRGB(),output.getRGB(900,900));}
 @Test void containsNonSquareBaseAndArtworkWithoutStretch()throws Exception{var output=ImageIO.read(new ByteArrayInputStream(CommerceMockupComposer.compose(image(128,64,Color.BLUE),List.of(new CommerceMockupComposer.Layer(area(),image(128,64,Color.RED))))));assertEquals(Color.WHITE.getRGB(),output.getRGB(50,50));assertEquals(Color.BLUE.getRGB(),output.getRGB(512,300));assertEquals(Color.RED.getRGB(),output.getRGB(512,512));}
 @Test void rejectsInvalidImageAndOutOfBounds()throws Exception{assertThrows(IllegalArgumentException.class,()->CommerceMockupComposer.compose(new byte[12],List.of(new CommerceMockupComposer.Layer(area(),new byte[12]))));var bad=new CommercePrintTemplateService.Area("main","FRONT",.9,.25,.5,.5,240,240,300);assertThrows(RuntimeException.class,()->CommerceMockupComposer.compose(image(64,64,Color.BLUE),List.of(new CommerceMockupComposer.Layer(bad,image(64,64,Color.RED)))));}
}
