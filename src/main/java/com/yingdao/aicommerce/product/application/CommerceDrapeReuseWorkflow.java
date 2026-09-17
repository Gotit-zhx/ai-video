package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** One paid pose master per source; fixed guide, matte and print tracking produce all SKU images locally. */
final class CommerceDrapeReuseWorkflow {
    static final String MASTER="DRAPE_MASTER",GUIDE="DRAPE_GUIDE",BLANK="DRAPE_BLANK",OUTPUT="DRAPED_FRONT";
    static final String VERSION=CommerceDrapeImageProcessor.VERSION;
    static final String PROMPT="生成一张真实服装电商摄影。图1只提供原商品的自然摆放姿态、承托物和场景风格；忽略其旧印花、人物和标签。图2是本次要拍摄的商品，保持纯白色短袖服装的版型、领口、袖型，以及唯一胸前印花的图案、颜色、数量、大小比例和所在侧；图3为同一印花的清晰母版，不额外增加图案。参照图1的承托方式重新摆放图2服装：若原图搭在椅子上，则双肩由椅背支撑、下摆落到座面形成柔软堆褶；若原图为平铺，则保持自然平铺。上胸及印花区域舒展、基本正对镜头，折叠主要在下腹和下摆，不遮挡或折断胸前印花。衣服必须被真实承托，有接触阴影；不要悬空，不叠成方块。移除图2的衣架。完整呈现领口、双袖和下摆，四周保留少量边距。衣服仍是纯白布，只有图2规定的一处胸前印花，不复制图1的旧大印花。柔和自然光，背景与白衣有清晰对比，保留来源场景配色和材质但可简化道具。无人物、人体、衣架、标签、新增文字、水印或拼图，只输出单张成品照片。";
    private final JdbcTemplate db;private final CommerceImageArchive archive;private final ObjectMapper json;
    private final CommerceDrapeImageProcessor processor;private final CommerceLocalPhotoWorkflow local;
    CommerceDrapeReuseWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,CommerceDrapeImageProcessor processor,CommerceLocalPhotoWorkflow local){
        this.db=db;this.archive=archive;this.json=json;this.processor=processor;this.local=local;
    }
    static String printId(CommercePrintTemplateService.Configuration config) {
        var prints=config.photoPrints().stream().filter(p->"FRONT".equals(p.side())).toList();
        if(prints.size()!=1)throw new IllegalArgumentException("搭放母图复用目前需要一处正面胸前印花；背面和侧面仍按模板补图");
        var p=prints.getFirst();
        if(p.width()*p.height()>.065||p.x()<.2||p.x()+p.width()>.8||p.y()<.12||p.y()+p.height()>.55)
            throw new IllegalArgumentException("搭放母图复用目前支持小面积胸前印花；大花、袖口或下摆需独立校准，不会猜测位置或追加生图");
        return p.id();
    }
    Result prepareGuide(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        if(ready(find(jobs,source,GUIDE,0,null)))return null;
        String id=printId(config),front=CommerceSurfaceTemplateInputs.key(run.organizationId(),config.baseImages().get("FRONT"));
        String uv=CommerceSurfaceTemplateInputs.key(run.organizationId(),config.surfaceUvViews().get("FRONT"));
        var first=required(jobs,source,"MOCKUP_FRONT",1,source.colorTargets().getFirst().key());
        String artwork=artwork(first,id);var refs=List.of(front,uv,artwork);
        return local.local(run,source,GUIDE,0,null,refs,VERSION+"/guide",List.of(refs,config.renderBinding()),()->{
            byte[] white=bytes(run.organizationId(),front);requireWhiteTemplate(white);
            var result=CommercePhotoMockupComposer.composePhoto("FRONT",white,white,white,bytes(run.organizationId(),uv),config.renderPrintIds(),Map.of(id,bytes(run.organizationId(),artwork)));
            return new CommerceLocalPhotoWorkflow.Produced(result.png(),Map.of("method",VERSION,"printId",id,"uvKey",uv,"artworkKey",artwork,"templateKey",front,"modelCalls",0));
        },"首款搭放定位图已准备；所有款色将复用一次生成的母图");
    }
    List<String> masterReferences(CommerceBatchCreativePlanValidator.Selection source,List<Map<String,Object>> jobs,String original){
        var guide=required(jobs,source,GUIDE,0,null);var refs=references(guide);
        if(refs.size()!=3)throw new IllegalArgumentException("搭放定位图缺少固定印花绑定");
        return List.of(original,(String)guide.get("storage_key"),refs.get(2));
    }
    Result finish(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        String id=printId(config);var master=required(jobs,source,MASTER,0,null);var guide=required(jobs,source,GUIDE,0,null);
        String masterKey=(String)master.get("storage_key"),guideKey=(String)guide.get("storage_key");var guideRefs=references(guide);
        var masterRefs=references(master);
        if(masterRefs.size()!=3||!masterRefs.get(1).equals(guideKey)||!masterRefs.get(2).equals(guideRefs.get(2))||!PROMPT.equals(master.get("prompt")))
            throw new IllegalArgumentException("搭放母图与本批定位图不一致，原结果保留");
        String uv=guideRefs.get(1),seedArtwork=guideRefs.get(2);var prepared=find(jobs,source,BLANK,0,null);
        if(!ready(prepared)) {
            var refs=List.of(masterKey,guideKey,uv,seedArtwork);
            return local.local(run,source,BLANK,0,null,refs,VERSION+"/prepare",refs,()->{
                if(processor==null)throw new IllegalArgumentException("本地搭放服务不可用");
                var result=processor.prepare(bytes(run.organizationId(),masterKey),bytes(run.organizationId(),guideKey),bytes(run.organizationId(),uv),bytes(run.organizationId(),seedArtwork),config.renderPrintIds());
                CommerceDrapeImageProcessor.requirePassed(result);
                byte[] blank=CommerceDrapeImageProcessor.decoded(result.get("blank")),alpha=CommerceDrapeImageProcessor.decoded(result.get("alpha"));
                var report=new LinkedHashMap<String,Object>();report.put("method",VERSION);report.put("masterKey",masterKey);report.put("guideKey",guideKey);
                report.put("uvKey",uv);report.put("seedArtworkKey",seedArtwork);report.put("alphaKey",archive.store(run.organizationId(),alpha).storageKey());
                report.put("mapping",result.get("mapping"));report.put("check",result.get("report"));report.put("modelCalls",0);
                return new CommerceLocalPhotoWorkflow.Produced(blank,report);
            },"搭放母图的衣服遮罩与印花位置已固定；后续换色换花不再生图");
        }
        var preparation=report(prepared);String blankKey=(String)prepared.get("storage_key"),alphaKey=preparation.path("alphaKey").asText();
        if(!masterKey.equals(preparation.path("masterKey").asText())||!guideKey.equals(preparation.path("guideKey").asText()))throw new IllegalArgumentException("搭放校准与母图不一致");
        for(int variant=1;variant<=sourceVariants(jobs,source);variant++)for(var color:source.colorTargets()) {
            if(ready(find(jobs,source,OUTPUT,variant,color.key())))continue;
            var precise=required(jobs,source,"MOCKUP_FRONT",variant,color.key());var base=required(jobs,source,"BASE_COLOR",0,color.key());
            String ink=artwork(precise,id);var evidence=report(base);if(evidence.has("color"))evidence=evidence.path("color");
            String rgb=evidence.path("observedSourceRgb").asText("");boolean original="TEMPLATE_ORIGINAL".equals(evidence.path("mode").asText());
            if(!original&&!rgb.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("该颜色缺少可靠商品取色依据，不按名称猜色");
            final String targetRgb=rgb;final boolean originalColor=original;final int v=variant;
            var refs=List.of(masterKey,blankKey,alphaKey,ink,(String)precise.get("storage_key"),(String)base.get("storage_key"));
            return local.local(run,source,OUTPUT,v,color,refs,VERSION+"/render",List.of(refs,preparation.path("mapping"),precise.get("id"),color.key(),color.label(),targetRgb,originalColor),()->{
                var made=processor.render(bytes(run.organizationId(),masterKey),bytes(run.organizationId(),blankKey),bytes(run.organizationId(),alphaKey),json.convertValue(preparation.path("mapping"),Map.class),bytes(run.organizationId(),ink),targetRgb,originalColor);
                CommerceDrapeImageProcessor.requirePassed(made);
                byte[] image=CommerceDrapeImageProcessor.decoded(made.get("data"));
                var output=new LinkedHashMap<String,Object>();output.put("status","DRAPED_COMPOSITE_READY");output.put("method",VERSION);
                output.put("masterKey",masterKey);output.put("blankKey",blankKey);output.put("alphaKey",alphaKey);output.put("artworkKey",ink);output.put("printId",id);
                output.put("localGuideId",precise.get("id"));output.put("localGuideKey",precise.get("storage_key"));output.put("colorKey",color.key());output.put("colorLabel",color.label());
                output.put("check",made.get("report"));output.put("modelCalls",0);output.put("limitation","复用同一搭放姿态，印花局部映射与去旧花光照为本地估计；不表示恢复被原花遮住的真实织纹。");
                return new CommerceLocalPhotoWorkflow.Produced(image,output);
            },"搭放成品已本地生成 · "+color.label()+" / V"+v+"；复用同一母图，未追加生图");
        }
        return null;
    }
    Result review(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Plan plan,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs){
        try {
            String id=printId(config);
            for(var source:plan.sources())for(int v=1;v<=plan.variantsPerPrint();v++)for(var color:source.colorTargets()) {
                var actual=required(jobs,source,OUTPUT,v,color.key());
                var precise=required(jobs,source,"MOCKUP_FRONT",v,color.key());
                var master=required(jobs,source,MASTER,0,null);
                var blank=required(jobs,source,BLANK,0,null);
                var r=report(actual);var refs=references(actual);String ink=artwork(precise,id);
                String alphaKey=report(blank).path("alphaKey").asText("");
                if(!"DRAPED_COMPOSITE_READY".equals(r.path("status").asText())||!VERSION.equals(r.path("method").asText())
                        ||!Objects.equals(master.get("storage_key"),r.path("masterKey").asText())||!Objects.equals(blank.get("storage_key"),r.path("blankKey").asText())
                        ||!Objects.equals(precise.get("id"),r.path("localGuideId").asText())||!Objects.equals(precise.get("storage_key"),r.path("localGuideKey").asText())
                        ||!ink.equals(r.path("artworkKey").asText())||!color.key().equals(r.path("colorKey").asText())||!color.label().equals(r.path("colorLabel").asText())
                        ||alphaKey.isBlank()||!alphaKey.equals(r.path("alphaKey").asText())||!id.equals(r.path("printId").asText())
                        ||!refs.containsAll(List.of((String)master.get("storage_key"),(String)blank.get("storage_key"),alphaKey,ink,(String)precise.get("storage_key")))
                        ||!r.path("check").path("passed").asBoolean(false))throw new IllegalArgumentException("搭放成品缺少同款同色母图、印花或保护区域检查");
                refs.forEach(key->archive.read(run.organizationId(),key));archive.read(run.organizationId(),(String)actual.get("storage_key"));
            }
            return null;
        }catch(RuntimeException error){return new Result(false,"搭放成品检查未通过："+Objects.toString(error.getMessage(),"归档不完整")+"；保留母图，未追加生图");}
    }
    private int sourceVariants(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source){return jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&"MOCKUP_FRONT".equals(j.get("kind"))&&ready(j)).mapToInt(j->((Number)j.get("variant_index")).intValue()).max().orElse(0);}
    private String artwork(Map<String,Object> guide,String id){String key=report(guide).path("artworkKeys").path(id).asText("");if(key.isBlank())throw new IllegalArgumentException("缺少该位置的固定透明印花");return key;}
    private tools.jackson.databind.JsonNode report(Map<String,Object> job){var rows=db.queryForList("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=?",job.get("id"));if(rows.isEmpty()||rows.getFirst().get("result_json")==null)throw new IllegalArgumentException("缺少本地套版检查记录");return json.readTree((String)rows.getFirst().get("result_json"));}
    private List<String> references(Map<String,Object> job){return json.readValue((String)job.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
    private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
    private static boolean ready(Map<String,Object> job){return job!=null&&"READY".equals(job.get("status"));}
    private static Map<String,Object> find(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,String kind,int v,String color){return jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&kind.equals(j.get("kind"))&&((Number)j.get("variant_index")).intValue()==v&&Objects.equals(color,j.get("color_key"))).findFirst().orElse(null);}
    private static Map<String,Object> required(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,String kind,int v,String color){var j=find(jobs,source,kind,v,color);if(!ready(j))throw new IllegalArgumentException("搭放复用所需素材尚未归档："+kind);return j;}
    static void requireWhiteTemplate(byte[] bytes){
        var image=CommerceMockupComposer.decode(bytes);var mask=CommerceMockupComposer.decode(CommercePhotoMockupComposer.recolorMask(bytes));var values=new ArrayList<Integer>();int samples=0;
        for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3)if((mask.getRGB(x,y)>>>24)>240){samples++;int p=image.getRGB(x,y),r=p>>16&255,g=p>>8&255,b=p&255;if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<20)values.add((r+g+b)/3);}
        values.sort(Integer::compareTo);if(values.size()<50||values.size()<samples*.7||values.get(values.size()/2)<225)throw new IllegalArgumentException("搭放复用需要纯白色服装模板作为统一母图，当前模板布色不符合，未提交搭放生图");
    }
}
