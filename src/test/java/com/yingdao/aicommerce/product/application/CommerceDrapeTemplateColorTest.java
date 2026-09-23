package com.yingdao.aicommerce.product.application;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
class CommerceDrapeTemplateColorTest {
 @Test void coloredTemplatesNormalizeWithoutChangingBackgroundOrAlpha(){
  for(int color:new int[]{0xffff7799,0xff202030,0xffa08060,0xffeeeeee}){
   var image=new BufferedImage(120,120,BufferedImage.TYPE_INT_ARGB);
   for(int y=20;y<100;y++)for(int x=25;x<95;x++)image.setRGB(x,y,color);
   byte[] input=CommerceDrapeReuseWorkflow.encodeTemplate(image);
   assertDoesNotThrow(()->CommerceDrapeReuseWorkflow.requireWhiteTemplate(input));
   var normalized=CommerceMockupComposer.decode(CommerceDrapeReuseWorkflow.neutralTemplate(input));
   assertEquals(0,normalized.getRGB(0,0));
   assertEquals(0xfff0f0f0,normalized.getRGB(60,60));
   assertEquals(color,image.getRGB(60,60));
   var observed=CommerceGarmentColor.prepare(CommerceDrapeReuseWorkflow.neutralTemplate(input),input,"",false);
   assertEquals(String.format("#%06x",color&0xffffff).toLowerCase(),observed.report().observedSourceRgb().toLowerCase());
  }
 }
 @Test void invalidImageStillRejected(){assertThrows(IllegalArgumentException.class,()->CommerceDrapeReuseWorkflow.requireWhiteTemplate(new byte[]{1,2,3}));}
}
