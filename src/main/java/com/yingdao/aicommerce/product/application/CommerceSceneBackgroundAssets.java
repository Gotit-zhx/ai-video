package com.yingdao.aicommerce.product.application;

import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** One tenant-scoped background plate; garment colors and artwork never enter its identity. */
final class CommerceSceneBackgroundAssets {
    static final String KIND="SCENE_BACKGROUND";
    static final String VERSION="grok-empty-scene-v1";
    static final String LEGACY_PROMPT="生成一张可复用的空场景背景板，不生成商品成品。参考图1是本次选中用于提取印花的来源商品原照：移除衣服、衣架及其投影，合理补全被遮挡的墙面和场景，延续原照片的墙面材质、空间、光线和视角。参考图2是套版模板的正面白底参考，只用于判断画布构图、商品占位和留白，不得复制或重新绘制衣服。输出与参考图2相同比例、可在中央完整放入该服装的干净背景。移除服装及其衣架、人物、衣服标签和印花，保留原场景已有物件及其自然细节，不新增文字、标签、水印或拼图。背景以图1为准；若图1本来是纯色背景，保持该纯色，不凭空添加场景。背景是基于原照重建的场景，不要求逐像素一致。";
    static final String LEGACY_STYLE_PROMPT="设计一张用于服装商品展示的全新空场景背景，只输出单张背景图。参考图1是本次选中用于提取印花的来源商品原照，只从其背景提取场景类型、配色、材质、光线和整体氛围；忽略衣服、印花、人物及品牌文字。以提取的背景风格重新设计场景，可以调整道具种类、数量、布局和拍摄角度，不需要还原原照片的物件或空间，不做原图修补。参考图2仅提供输出画幅比例、服装居中占位及袖口所需留白，不提供背景风格。构图简洁、有真实摄影质感，柔和自然光；道具疏密有序地安排在画面外围，中央预留适合放置参考图2服装的干净展示区域，避免杂乱细线和高对比图案干扰服装轮廓。若图1为纯色背景，则设计同色系的简洁棚拍背景。画面不生成服装、衣架、人物、印花、文字标注、品牌标识、水印或拼图。";
    static final String PROMPT="设计一张用于服装商品展示的全新空场景背景，只输出单张背景图。参考图1是本次选中用于提取印花的来源商品原照，只从其背景提取场景类型、配色、材质、光线和整体氛围；忽略衣服、印花、人物以及包括道具文字在内的所有文字。以提取的背景风格重新设计场景，可以调整道具种类、数量、布局和拍摄角度，不需要还原原照片的物件或空间，不做原图修补。参考图2仅提供输出画幅比例、服装居中占位及袖口所需留白，不提供背景风格；其白底和商品占位不可画成画面内的板、框或色块。构图简洁、有真实摄影质感，柔和自然光；道具疏密有序地安排在画面外围，中央只是同一连续场景内留给后续服装合成的空置空间，场景材质、纹理与光线自然贯通中央和四周，避免杂乱细线和高对比图案干扰后续服装轮廓。不要用矩形展示板、白板、纸张、画框、独立纯色色块或遮盖块表现留白，不画任何占位边界。若图1为纯色背景，则整幅采用同色系的简洁无缝棚拍背景，中央不另设区域。画面不生成服装、衣架、人物、印花、任何文字（包括书刊、包装及其他道具上的文字）、品牌标识、水印或拼图。";

    static List<String> references(String originalKey,CommercePrintTemplateService.Configuration config,String org){
        if(!CommercePrintTemplateService.photoLayout(config)||originalKey==null||config.baseImages().get("FRONT")==null)
            throw new IllegalArgumentException("场景套版需要选中的来源原照及已保存的四面照片配置");
        if(!originalKey.matches("[a-f0-9]{64}/[a-f0-9]{64}"))throw new IllegalArgumentException("来源原照归档地址无效");
        return List.of(originalKey,CommerceSurfaceTemplateInputs.key(org,config.baseImages().get("FRONT")));
    }
    static void validateGeometry(CommercePrintTemplateService.Configuration config,String org,CommerceImageArchive archive){
        if(!CommercePrintTemplateService.photoLayout(config))throw new IllegalArgumentException("场景套版仅支持已校准的四面照片模板");
        for(String side:CommerceSurfaceTemplateInputs.PHOTO_SIDES)
            CommercePhotoMockupComposer.recolorMask(CommerceSurfaceTemplateInputs.bytes(archive.read(org,CommerceSurfaceTemplateInputs.key(org,config.baseImages().get(side)))));
    }
    static String cacheKey(CommerceMockupDerivativeCache cache,List<String> references,String fingerprint,String prompt){
        return cache.key(VERSION,List.of(references,fingerprint,prompt));
    }
    static Map<String,Object> report(List<String> references,String fingerprint){
        return Map.of("method",VERSION,"kind",KIND,"referenceKeys",references,"modelFingerprint",fingerprint,
                "sharedAcrossVariants",true,"sharedAcrossColors",true,"sharedAcrossMatchingSourceImages",true,
                "backgroundPixelPreserved",false,"limitation","背景由 Grok 参考来源商品的场景风格生成；服装与印花由固定照片在本地合成。");
    }
    static String readyKey(CommerceBatchCreativePlanValidator.Plan plan,CommercePrintTemplateService.Configuration config,
                           CommerceBatchCreativePlanValidator.Selection source,List<Map<String,Object>> jobs,String org,CommerceImageArchive archive,ObjectMapper json){
        if(!CommerceBatchCreativePlanValidator.sceneOutput(plan))return null;
        var plates=jobs.stream().filter(j->KIND.equals(j.get("kind"))&&source.captureId().equals(j.get("capture_id"))).toList();
        if(plates.size()!=1)throw new IllegalArgumentException("共用场景背景尚未唯一归档，请继续原背景任务");
        var job=plates.getFirst();
        List<String> refs=json.readValue(Objects.toString(job.get("reference_keys"),"[]"),json.getTypeFactory().constructCollectionType(List.class,String.class));
        if(!"READY".equals(job.get("status"))||!source.captureId().equals(job.get("capture_id"))
                ||!(job.get("variant_index") instanceof Number n)||n.intValue()!=0||job.get("color_key")!=null
                ||refs.size()!=2||!references(refs.getFirst(),config,org).equals(refs)
                ||!Set.of(PROMPT,LEGACY_STYLE_PROMPT,LEGACY_PROMPT).contains(Objects.toString(job.get("prompt"),"")))
            throw new IllegalArgumentException("共用场景背景的状态、原图或构图绑定不一致，保留原任务处理");
        String key=(String)job.get("storage_key"),sha=(String)job.get("content_sha256");
        if(sha==null||!Objects.equals(CommerceSurfaceTemplateInputs.key(org,sha),key))throw new IllegalArgumentException("共用背景归档指纹不一致");
        archive.read(org,refs.getFirst());
        archive.read(org,key);
        return key;
    }
    private CommerceSceneBackgroundAssets(){}
}
