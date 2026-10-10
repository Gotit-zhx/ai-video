package com.yingdao.aicommerce.product.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.photoLayout;
import static com.yingdao.aicommerce.product.application.CommercePrintTemplateService.placementBindings;

/** Reads only tenant-local assets from the immutable template snapshot used by this execution. */
final class CommerceSurfaceTemplateInputs {
    static final List<String> PHOTO_SIDES = List.of("FRONT", "BACK", "LEFT", "RIGHT");
    static final List<String> SIDES = List.of("FRONT", "BACK", "LEFT", "RIGHT", "TOP", "BOTTOM");
    record Side(String side, String originalKey, String photoKey, String blankKey, String uvKey,
                byte[] blank, byte[] uv, Set<String> visiblePrintIds) {}
    private CommerceSurfaceTemplateInputs() {}

    static boolean enabled(CommercePrintTemplateService.Configuration c) {
        return c.sourceImageId() != null || c.referenceSetId() != null || !placementBindings(c).isEmpty();
    }
    static void validate(CommercePrintTemplateService.Configuration c) {
        if (placementBindings(c).isEmpty()) throw bad("请先在商品四面图上放置印花并保存效果；不会改用默认正面槽位");
        if (c.sourceImageId() == null || c.referenceSetId() == null || (!photoLayout(c)&&c.reconstruction() == null)
                || !c.baseImages().keySet().containsAll(PHOTO_SIDES))
            throw bad("套版缺少上传原图、同组四面参考或有效位置配置，请在模板配置中补齐并保存");
        CommercePrintTemplateService.requireSurfaceRenders(c);
    }

    static Map<String, Side> load(CommercePrintTemplateService.Configuration c, CommerceImageArchive archive, String org) {
        validate(c);
        String original = key(org, c.sourceImageId());
        archive.read(org, original);
        for(String artwork:artworkGroups(c))
            archive.read(org,key(org,artwork));
        var result = new LinkedHashMap<String, Side>();
        var visible = new HashSet<String>();
        for (String side : SIDES) {
            boolean supplemental = !PHOTO_SIDES.contains(side);
            // Extra cameras exist only to expose placements hidden from the four normal views.
            if (supplemental && (photoLayout(c)||visible.containsAll(c.renderPrintIds()) || !c.renderedViews().containsKey(side))) continue;
            String photo = key(org, c.baseImages().get(supplemental ? "FRONT" : side)), blank = key(org, c.blankRenderedViews().get(side)),
                    uv = key(org, c.surfaceUvViews().get(side));
            archive.read(org, photo);
            archive.read(org, key(org, c.renderedViews().get(side)));
            archive.read(org, key(org, c.placementMasks().get(side)));
            byte[] blankBytes = bytes(archive.read(org, blank)), uvBytes = bytes(archive.read(org, uv));
            var projection = CommerceSurfaceMockupComposer.inspect(uvBytes, c.renderPrintIds());
            var decoded = CommerceMockupComposer.decode(blankBytes);
            if (decoded.getWidth() != projection.width() || decoded.getHeight() != projection.height())
                throw bad("三维无印花模型与UV图尺寸不一致，请重新保存模型效果");
            boolean addsCoverage = !visible.containsAll(projection.visiblePrintIds());
            if (projection.coveredPixels() > 0 && (!supplemental || addsCoverage))
                result.put(side, new Side(side, original, photo, blank, uv, blankBytes, uvBytes, projection.visiblePrintIds()));
            visible.addAll(projection.visiblePrintIds());
        }
        if (!visible.containsAll(c.renderPrintIds()))
            throw bad("部分印花在已保存的模型效果中仍不可见，请调整印花位置并重新保存；不会丢弃这些印花");
        return Collections.unmodifiableMap(result);
    }

    /** The photo workflow reuses one saved primary view; it never generates auxiliary photographs. */
    static Side loadPrimary(CommercePrintTemplateService.Configuration c,CommerceImageArchive archive,String org) {
        validate(c);
        String side="FRONT",photo=key(org,c.baseImages().get(side)),blank=key(org,c.blankRenderedViews().get(side)),uv=key(org,c.surfaceUvViews().get(side));
        archive.read(org,photo);
        byte[] blankBytes=bytes(archive.read(org,blank)),uvBytes=bytes(archive.read(org,uv));
        var projection=CommerceSurfaceMockupComposer.inspect(uvBytes,c.renderPrintIds());
        if(projection.coveredPixels()==0&&!photoLayout(c))throw bad("当前模板正面没有可见印花；请保存带有目标印花的主图位置，未把背面印花移到正面");
        return new Side(side,key(org,c.sourceImageId()),photo,blank,uv,blankBytes,uvBytes,projection.visiblePrintIds());
    }

    static String variationArtwork(CommercePrintTemplateService.Configuration c,String selected) {
        var artworks=artworkGroups(c);
        if(selected==null&&artworks.size()==1)return artworks.getFirst();
        if(selected==null)throw bad("模板有多张独立印花，请在创作配置中选择本次要裂变的印花；其他图案将原样保留");
        if(!artworks.contains(selected))throw bad("选择的裂变印花不属于固定模板版本，请重新选择");
        return selected;
    }
    static List<String> artworkGroups(CommercePrintTemplateService.Configuration c) {
        return placementBindings(c).stream().map(CommercePrintTemplateService.PlacementBinding::artworkId).distinct().toList();
    }
    static String variationKind(int group) {
        if(group<1||group>32)throw bad("印花位置组编号无效");
        return "VARIATE_G"+group;
    }
    /** Placeholder artwork identifies a placement group only; every group must receive a generated design. */
    static Map<String,byte[]> replacements(CommercePrintTemplateService.Configuration c,Side side,
                                          Map<String,byte[]> variants) {
        if(variants==null||!variants.keySet().equals(new LinkedHashSet<>(artworkGroups(c)))
                ||variants.values().stream().anyMatch(bytes->bytes==null||bytes.length==0))
            throw bad("部分印花位置组缺少已归档的新设计，不能保留测试图案或使用其他组替代");
        var output=new LinkedHashMap<String,byte[]>();
        for(var print:placementBindings(c))if(side.visiblePrintIds().contains(print.id()))
            output.put(print.id(),variants.get(print.artworkId()));
        return output;
    }
    static Map<String,byte[]> replacements(CommercePrintTemplateService.Configuration c,Side side,String targetArtwork,
                                            byte[] variant,CommerceImageArchive archive,String org) {
        String selected=variationArtwork(c,targetArtwork);
        var output=new LinkedHashMap<String,byte[]>();
        for(var print:placementBindings(c))if(side.visiblePrintIds().contains(print.id()))
            output.put(print.id(),print.artworkId().equals(selected)?variant:bytes(archive.read(org,key(org,print.artworkId()))));
        return output;
    }

    static String kind(String side) {
        if (!SIDES.contains(side)) throw bad("三维套版面向无效");
        return "MOCKUP_" + side;
    }
    static boolean isSurfaceJob(String kind) { return kind != null && SIDES.stream().anyMatch(s -> kind(s).equals(kind)); }
    static String side(String kind) {
        if (!isSurfaceJob(kind)) throw bad("三维套版任务面向无效");
        return kind.substring("MOCKUP_".length());
    }
    static String key(String org, String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw bad("固定模板素材标识无效");
        return CommerceImageArchive.sha(org.getBytes(StandardCharsets.UTF_8)) + "/" + id;
    }
    static byte[] bytes(CommerceDesignImageGateway.Reference reference) { return Base64.getDecoder().decode(reference.dataBase64()); }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.CONFLICT, message); }
}
