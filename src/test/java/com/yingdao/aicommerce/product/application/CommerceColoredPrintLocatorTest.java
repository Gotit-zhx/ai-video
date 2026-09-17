package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class CommerceColoredPrintLocatorTest {
 byte[] image(Color base,Color print,int x,int y,int w,int h)throws Exception{var im=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(base);g.fillRect(0,0,1024,1024);g.setColor(print);g.fillRect(x,y,w,h);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();}
 byte[] blank()throws Exception{return image(Color.WHITE,Color.WHITE,0,0,1,1);}
 @Test void findsTranslationWithoutApproving(){assertDoesNotThrow(()->{var r=CommerceColoredPrintLocator.compare(blank(),image(Color.WHITE,Color.RED,300,300,200,200),image(Color.WHITE,Color.RED,330,280,200,200));assertEquals("COLOR_BOUNDS_DRIFT_REVIEW",r.status());assertEquals(30,r.shiftXPixels());assertEquals(-20,r.shiftYPixels());assertFalse(r.productionReady());});}
 @Test void identicalColorShapeStillRequiresReview()throws Exception{var img=image(Color.WHITE,Color.RED,300,300,200,200);var r=CommerceColoredPrintLocator.compare(blank(),img,img);assertEquals("REVIEW_REQUIRED",r.status());assertFalse(r.productionReady());}
 @Test void blackPrintCannotBeClaimedLocalized()throws Exception{var img=image(Color.WHITE,Color.BLACK,300,300,200,200);var r=CommerceColoredPrintLocator.compare(blank(),img,img);assertEquals("INSUFFICIENT_COLOR_SIGNAL",r.status());assertNull(r.widthRatio());}
 @Test void coloredBaseIsUnsupported()throws Exception{var img=image(Color.BLUE,Color.RED,300,300,200,200);assertEquals("UNSUPPORTED_COLORED_BASE",CommerceColoredPrintLocator.compare(img,img,img).status());}
}
