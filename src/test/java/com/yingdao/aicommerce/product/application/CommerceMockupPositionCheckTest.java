package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class CommerceMockupPositionCheckTest {
 byte[] image(boolean outside)throws Exception{var im=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);if(outside){g.setColor(Color.BLACK);g.fillRect(50,50,200,200);}g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();}
 final CommercePrintTemplateService.Area area=new CommercePrintTemplateService.Area("main","FRONT",.3,.3,.4,.4,200,200,300);
 @Test void identicalImageIsNotApproved()throws Exception{var r=CommerceMockupPositionCheck.check(image(false),image(false),List.of(area));assertEquals("REVIEW_REQUIRED",r.status());assertEquals(0,r.outsideChangedPixels());assertFalse(r.productionReady());}
 @Test void outsideChangeRequiresReview()throws Exception{var r=CommerceMockupPositionCheck.check(image(false),image(true),List.of(area));assertEquals("OUTSIDE_CHANGE_REVIEW",r.status());assertEquals(40000,r.outsideChangedPixels());assertFalse(r.productionReady());}
 @Test void fullCanvasCannotProvideOutsideEvidence()throws Exception{var all=new CommercePrintTemplateService.Area("all","FRONT",0,0,1,1,200,200,300);var r=CommerceMockupPositionCheck.check(image(false),image(true),List.of(all));assertEquals("INSUFFICIENT_OUTSIDE_AREA",r.status());assertFalse(r.productionReady());}
}
