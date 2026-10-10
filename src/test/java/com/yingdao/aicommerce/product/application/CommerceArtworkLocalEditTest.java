package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CommerceArtworkLocalEditTest {
 byte[] image(int w,int h,Color c)throws Exception{var im=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(c);g.fillRect(0,0,w,h);g.dispose();var b=new ByteArrayOutputStream();ImageIO.write(im,"png",b);return b.toByteArray();}
 @Test void preservesEveryOutsidePixelAndEditsMultipleRegionsOnColoredBase()throws Exception{
  byte[] source=image(1024,1024,Color.PINK);
  var candidate=CommerceMockupComposer.decode(source);var g=candidate.createGraphics();g.setColor(Color.BLUE);g.fillRect(280,280,180,440);g.fillRect(125,530,50,60);g.dispose();var encoded=new ByteArrayOutputStream();ImageIO.write(candidate,"png",encoded);byte[] raw=encoded.toByteArray();
  var regions=List.of(new CommerceArtworkLocalEdit.Region(.25,.25,.25,.5),new CommerceArtworkLocalEdit.Region(.1,.5,.1,.1));
  var result=CommerceMockupComposer.decode(CommerceArtworkLocalEdit.preserve(source,raw,regions));
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)if(!(x>=256&&x<512&&y>=256&&y<768)&&!(x>=102&&x<205&&y>=512&&y<615))assertEquals(Color.PINK.getRGB(),result.getRGB(x,y),"outside pixel "+x+","+y);
  assertEquals(Color.BLUE.getRGB(),result.getRGB(300,300));assertEquals(Color.PINK.getRGB(),result.getRGB(512,110),"collar stays original");assertNotEquals(Color.BLUE.getRGB(),result.getRGB(256,256),"edge feathers inward");
 }
 @Test void rejectsInvalidMasksAndPreservesSquareContainGeometry()throws Exception{
  assertThrows(IllegalArgumentException.class,()->CommerceArtworkLocalEdit.validate(List.of()));assertThrows(IllegalArgumentException.class,()->CommerceArtworkLocalEdit.validate(List.of(new CommerceArtworkLocalEdit.Region(0,0,Double.NaN,1))));assertThrows(IllegalArgumentException.class,()->CommerceArtworkLocalEdit.validate(List.of(new CommerceArtworkLocalEdit.Region(.9,0,.2,1))));
  var fixed=CommerceMockupComposer.decode(CommerceArtworkLocalEdit.preserve(image(128,64,Color.PINK),image(128,64,Color.BLUE),List.of(new CommerceArtworkLocalEdit.Region(.25,.3,.5,.4))));assertEquals(Color.WHITE.getRGB(),fixed.getRGB(100,100));assertEquals(Color.PINK.getRGB(),fixed.getRGB(100,300));assertEquals(Color.PINK.getRGB(),fixed.getRGB(400,400),"uniform global color drift is corrected");
 }
 @Test void promptsPermitArtworkCreativityButKeepExtractionFaithful(){assertTrue(CommerceSampleService.printPrompt("PRINT_VARIANT",1).contains("二创"));assertTrue(CommerceSampleService.printPrompt("PRINT_EXTRACT",0).contains("忠实保留"));}
}
