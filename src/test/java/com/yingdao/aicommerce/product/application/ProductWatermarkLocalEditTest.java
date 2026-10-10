package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ProductWatermarkLocalEditTest {
 @Test void preservesNativeDimensionsAndEveryPixelOutsideMask()throws Exception{
  var source=new BufferedImage(180,270,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<270;y++)for(int x=0;x<180;x++)source.setRGB(x,y,(x<<16)|(y<<8)|17);
  // An interior mark must be removed; a plain linear gradient alone is fully
  // reconstructed by boundary correction and is no longer evidence of an edit.
  source.setRGB(45,94,0xffffff);
  var candidate=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);
  var output=CommerceMockupComposer.decode(ProductWatermarkLocalEdit.preserve(png(source),png(candidate),List.of(new CommerceArtworkLocalEdit.Region(.2,.3,.1,.1))));
  assertEquals(180,output.getWidth());assertEquals(270,output.getHeight());int changed=0;
  for(int y=0;y<270;y++)for(int x=0;x<180;x++){
   if(x<36||x>=55||y<81||y>=108)assertEquals(source.getRGB(x,y),output.getRGB(x,y),x+","+y);
   else if(source.getRGB(x,y)!=output.getRGB(x,y))changed++;
  }
  assertTrue(changed>0);
 }
 @Test void removesInteriorMarkAndCorrectsCandidateColorOffset()throws Exception{
  var source=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
  var candidate=new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<100;y++)for(int x=0;x<100;x++){source.setRGB(x,y,0xb4b4b4);candidate.setRGB(x,y,0xc8c8c8);}
  source.setRGB(50,50,0x969696);
  var output=CommerceMockupComposer.decode(ProductWatermarkLocalEdit.preserve(png(source),png(candidate),List.of(new CommerceArtworkLocalEdit.Region(.1,.4,.8,.2))));
  for(int y=0;y<100;y++)for(int x=0;x<100;x++)assertEquals(0xb4b4b4,output.getRGB(x,y)&0xffffff);
 }
 @Test void rejectsWholeImageAndOutOfBoundsEdits(){
  assertThrows(IllegalArgumentException.class,()->ProductWatermarkLocalEdit.preserve(new byte[0],new byte[0],List.of(new CommerceArtworkLocalEdit.Region(0,0,1,1))));
  assertThrows(IllegalArgumentException.class,()->ProductWatermarkLocalEdit.preserve(new byte[0],new byte[0],List.of(new CommerceArtworkLocalEdit.Region(.9,0,.2,.1))));
 }
 @Test void rejectsUnexpectedGeneratedAspectRatio()throws Exception{
  var source=png(new BufferedImage(180,270,BufferedImage.TYPE_INT_RGB));
  var candidate=png(new BufferedImage(1024,768,BufferedImage.TYPE_INT_RGB));
  assertThrows(IllegalArgumentException.class,()->ProductWatermarkLocalEdit.preserve(source,candidate,List.of(new CommerceArtworkLocalEdit.Region(.2,.3,.1,.1))));
 }
 private byte[] png(BufferedImage image)throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
}
