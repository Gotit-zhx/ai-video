package com.yingdao.aicommerce.product.application;
import java.util.*;
final class DrapeTestFixture {
 static CommercePrintTemplateService.Configuration configuration(){
  var c=SavedArtworkFixture.configuration();
  var prints=c.photoPrints().stream().map(p->"FRONT".equals(p.side())?new CommercePrintTemplateService.PhotoPrint(p.id(),p.artworkId(),p.side(),.35,.2,.15,.15,0):p).toList();
  var changed=new CommercePrintTemplateService.Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),c.surfacePrints(),c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks(),c.renderPrintIds(),null,prints);
  return new CommercePrintTemplateService.Configuration(changed.product(),changed.areas(),changed.baseImages(),changed.reconstruction(),changed.sourceImageId(),changed.referenceSetId(),changed.surfacePrints(),changed.renderedViews(),changed.blankRenderedViews(),changed.surfaceUvViews(),changed.placementMasks(),changed.renderPrintIds(),CommercePrintTemplateService.renderFingerprint(changed),changed.photoPrints());
 }
 static byte[] white()throws Exception{return SavedArtworkFixture.image(0xffeeeeee,true);}
}
