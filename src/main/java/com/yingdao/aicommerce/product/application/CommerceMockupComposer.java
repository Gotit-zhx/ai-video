package com.yingdao.aicommerce.product.application;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Exact square-canvas placement guide, not a photorealistic or print-ready artifact. */
public final class CommerceMockupComposer {
 public record Layer(CommercePrintTemplateService.Area area,byte[] bytes){}
 private CommerceMockupComposer(){}
 public static byte[] compose(byte[] base,List<Layer> layers){
  if(layers==null||layers.isEmpty()||layers.size()>8)throw new IllegalArgumentException("请选择1至8个印花槽位");
  var output=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);var g=output.createGraphics();
  try{
   g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);
   g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
   contain(g,decode(base),0,0,1024,1024);
   for(var layer:layers){var a=layer.area();CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",List.of(a)));
    contain(g,decode(layer.bytes()),(int)Math.round(a.x()*1024),(int)Math.round(a.y()*1024),(int)Math.round(a.width()*1024),(int)Math.round(a.height()*1024));
   }
  }finally{g.dispose();}
  try(var bytes=new ByteArrayOutputStream()){ImageIO.write(output,"png",bytes);return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException("套版参考图编码失败",e);}
 }
 private static void contain(Graphics2D g,BufferedImage image,int x,int y,int w,int h){double scale=Math.min((double)w/image.getWidth(),(double)h/image.getHeight());int width=Math.max(1,(int)Math.round(image.getWidth()*scale)),height=Math.max(1,(int)Math.round(image.getHeight()*scale));g.drawImage(image,x+(w-width)/2,y+(h-height)/2,width,height,null);}
 static BufferedImage decode(byte[] bytes){
  if(bytes==null||bytes.length>8*1024*1024)throw new IllegalArgumentException("参考图大小无效");
  try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException("套版参考图须为可解码的PNG或JPEG");var reader=readers.next();try{reader.setInput(in);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>8192||h>8192||(long)w*h>25000000)throw new IllegalArgumentException("参考图像素过大");return reader.read(0);}finally{reader.dispose();}}catch(IOException e){throw new IllegalArgumentException("参考图无法解码",e);}
 }
 static BufferedImage square(byte[] bytes){var output=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);var g=output.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);contain(g,decode(bytes),0,0,1024,1024);}finally{g.dispose();}return output;}
}
