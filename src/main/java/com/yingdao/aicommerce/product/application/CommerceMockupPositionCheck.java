package com.yingdao.aicommerce.product.application;
import java.util.List;

/** Conservative pixel-change diagnostic. Not semantic print localization or an approval gate. */
public final class CommerceMockupPositionCheck {
 public record Report(String status,long outsideChangedPixels,long outsideComparedPixels,double outsideChangedRatio,int channelThreshold,int marginPixels,boolean productionReady,String limitation,CommerceColoredPrintLocator.Result colorLocalization){}
 public static Report check(byte[] base,byte[] guide,byte[] candidate,List<CommercePrintTemplateService.Area> areas){var r=check(base,candidate,areas);return new Report(r.status(),r.outsideChangedPixels(),r.outsideComparedPixels(),r.outsideChangedRatio(),r.channelThreshold(),r.marginPixels(),false,r.limitation(),CommerceColoredPrintLocator.compare(base,guide,candidate));}
 private CommerceMockupPositionCheck(){}
 public static Report check(byte[] base,byte[] candidate,List<CommercePrintTemplateService.Area> areas){
  if(areas==null||areas.isEmpty())throw new IllegalArgumentException("缺少已绑定印花区域");
  CommercePrintTemplateService.validate(new CommercePrintTemplateService.Configuration("T_SHIRT",areas));
  var before=CommerceMockupComposer.square(base);var after=CommerceMockupComposer.square(candidate);
  int threshold=40,margin=10;long changed=0,compared=0;
  for(int y=0;y<1024;y++)for(int x=0;x<1024;x++){
   boolean allowed=false;for(var area:areas)if(x>=Math.floor(area.x()*1024)-margin&&x<Math.ceil((area.x()+area.width())*1024)+margin&&y>=Math.floor(area.y()*1024)-margin&&y<Math.ceil((area.y()+area.height())*1024)+margin){allowed=true;break;}if(allowed)continue;
   compared++;int a=before.getRGB(x,y),b=after.getRGB(x,y),delta=0;for(int shift:new int[]{0,8,16})delta=Math.max(delta,Math.abs(((a>>shift)&255)-((b>>shift)&255)));if(delta>=threshold)changed++;
  }
  double ratio=compared==0?0:(double)changed/compared;
  return new Report(compared==0?"INSUFFICIENT_OUTSIDE_AREA":ratio>.005?"OUTSIDE_CHANGE_REVIEW":"REVIEW_REQUIRED",changed,compared,ratio,threshold,margin,false,"启发式像素差检查，阈值未按商品类目校准。区域外变化可能来自印花越界、光影或纹理重绘；无报警不代表印花完整、版型保真或可上架。未识别区域内的位置偏移，也不自动纠正图片。",null);
 }
}
