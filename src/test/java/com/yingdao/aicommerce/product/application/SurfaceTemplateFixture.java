package com.yingdao.aicommerce.product.application;

import java.util.*;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.*;

final class SurfaceTemplateFixture {
    static String hash(char value) { return String.valueOf(value).repeat(64); }
    static Map<String,String> sides(String id) { return Map.of("FRONT",id,"BACK",id,"LEFT",id,"RIGHT",id); }
    static Configuration configuration() {
        var images=sides(hash('b'));
        var mesh=new Reconstruction(1,"garment-coarse-v1",images,
                List.of(List.of(0d,0d,0d),List.of(1d,0d,0d),List.of(0d,1d,0d)),List.of(List.of(0,1,2)),Map.of());
        var prints=List.of(new SurfacePrint("back-slot",hash('2'),List.of(0d,0d,0d),List.of(0d,0d,-1d),
                List.of(1d,0d,0d),.3,.4,0,"outer"));
        var uv=new HashMap<>(sides(hash('f')));uv.put("BACK",hash('e'));
        var c=new Configuration("T_SHIRT",List.of(),images,mesh,hash('a'),"reference-set",prints,
                sides(hash('c')),sides(hash('d')),uv,sides(hash('1')),List.of("back-slot"),null);
        return binding(c,renderFingerprint(c));
    }
    static Configuration binding(Configuration c,String binding) {
        return new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),
                c.referenceSetId(),c.surfacePrints(),c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),
                c.placementMasks(),c.renderPrintIds(),binding);
    }
    static Configuration multipleArtwork() {
        var c=configuration();var prints=new ArrayList<>(c.surfacePrints());
        prints.add(new SurfacePrint("fixed-badge",hash('5'),List.of(0d,0d,0d),List.of(0d,0d,-1d),List.of(1d,0d,0d),.1,.1,0,"outer"));
        var changed=new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),
                prints,c.renderedViews(),c.blankRenderedViews(),c.surfaceUvViews(),c.placementMasks(),List.of("back-slot","fixed-badge"),null);
        return binding(changed,renderFingerprint(changed));
    }
    static Configuration withUvViews(Configuration c,Map<String,String> uv) {
        var rendered=new HashMap<>(c.renderedViews());var blank=new HashMap<>(c.blankRenderedViews());var masks=new HashMap<>(c.placementMasks());
        for(String side:uv.keySet()){
            rendered.putIfAbsent(side,hash('c'));blank.putIfAbsent(side,hash('d'));masks.putIfAbsent(side,hash('1'));
        }
        var changed=new Configuration(c.product(),c.areas(),c.baseImages(),c.reconstruction(),c.sourceImageId(),c.referenceSetId(),
                c.surfacePrints(),rendered,blank,uv,masks,c.renderPrintIds(),null);
        return binding(changed,renderFingerprint(changed));
    }
    static byte[] blank() throws Exception { return CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff444444,0xff444444},{0xff444444,0xff444444}}); }
    static byte[] visibleUv() throws Exception { return CommerceSurfaceMockupComposerTest.png(new int[][]{{0xff000001,0},{0,0}}); }
    static byte[] hiddenUv() throws Exception { return CommerceSurfaceMockupComposerTest.png(new int[][]{{0,0},{0,0}}); }
    static CommerceDesignImageGateway.Reference reference(byte[] bytes) {
        return new CommerceDesignImageGateway.Reference("image/png",Base64.getEncoder().encodeToString(bytes));
    }
}
