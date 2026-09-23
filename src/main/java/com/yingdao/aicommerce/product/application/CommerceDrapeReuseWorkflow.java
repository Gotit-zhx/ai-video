package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** One paid pose master per source; fixed guide, matte and print tracking produce all SKU images locally. */
final class CommerceDrapeReuseWorkflow {
    static final String MASTER="DRAPE_MASTER",GUIDE="DRAPE_GUIDE",BLANK="DRAPE_BLANK",OUTPUT="DRAPED_FRONT";
    static final String VERSION=CommerceDrapeImageProcessor.VERSION;
    static final String LOCAL_STRATEGY="source-print-geometry-v2";
    private static final int MAX_LOCAL_ATTEMPTS=3;
    static final String LEGACY_PROMPT="生成一张真实服装电商摄影。图1只提供原商品的自然摆放姿态、承托物和场景风格；忽略其旧印花、人物和标签。图2是本次要拍摄的商品，保持纯白色短袖服装的版型、领口、袖型，以及唯一胸前印花的图案、颜色、数量、大小比例和所在侧；图3为同一印花的清晰母版，不额外增加图案。参照图1的承托方式重新摆放图2服装：若原图搭在椅子上，则双肩由椅背支撑、下摆落到座面形成柔软堆褶；若原图为平铺，则保持自然平铺。上胸及印花区域舒展、基本正对镜头，折叠主要在下腹和下摆，不遮挡或折断胸前印花。衣服必须被真实承托，有接触阴影；不要悬空，不叠成方块。移除图2的衣架。完整呈现领口、双袖和下摆，四周保留少量边距。衣服仍是纯白布，只有图2规定的一处胸前印花，不复制图1的旧大印花。柔和自然光，背景与白衣有清晰对比，保留来源场景配色和材质但可简化道具。无人物、人体、衣架、标签、新增文字、水印或拼图，只输出单张成品照片。";
    static final String TEMPLATE_FIRST_PROMPT="【模板优先的服装摄影编辑】图1是唯一商品母版，也是本次编辑的底图；图2仅供参考场景材质和自然承托方式；图3仅补充图1胸花的清晰纹理。优先级为图1版型与印花布局高于图2姿态和构图。不得把图2的衣服当商品。严格保持图1的领口、肩线、袖长、袖口宽度、衣身宽长比例和下摆形状，不替换成另一件衣服。印花必须保持图1的胸前所在侧、相对领口距离、相对衣身宽度的尺寸及唯一数量；图3画布尺寸绝不代表印花尺寸，小胸花不得放大为中间大图案，不居中搬移，不镜像、不增删图案。只在图1基础上做轻微自然搭放和柔软褶皱；当参考姿态会改变版型、胸花位置或遮挡衣服时，优先减少姿态变化，保留图1轮廓。完整呈现领口、左右袖口及整个下摆，服装任何部位不得触边或出画；服装整体置于画布中央80%区域，四边至少留10%背景空间，宁可缩小整件服装，不裁切或拉伸衣服。布料保持图1的中性白色，用于后续还原商品颜色；胸花区域舒展，不折断图案。场景只借用图2的背景配色和承托物，不复制原商品、文字或印花。无人物、人体、衣架、标签、新增文字、水印或拼图，输出单张完整服装照片。输出前自查：版型等同图1、胸花侧别位置比例等同图1、领袖下摆完整、四周留足边距；有冲突一律保留图1，牺牲场景和姿态变化。";
    static final String PROMPT="【真实场景自然搭放 V3】生成一张真实的服装电商摄影，三张参考图用途不同，以下商品、场景、褶皱要求必须同时满足。图1仅定义要出售的服装版型结构与胸花布局，不是输出底图；不要继承它的白底、衣架、平铺姿态或僵直二维外轮廓。保持图1的领型、袖型、袖长、肩宽及衣身长宽比例；允许重力弯曲和折叠改变画面中的轮廓，不把自然搭放误解为照搬平铺轮廓。图2定义必须采用的真实背景、承托物、摆放方式和光线：保留其可辨认的场景材质、颜色及主要承托物，不能变成纯白或纯色抠图背景。若图2衣服搭在椅子上，则本件衣服双肩自然搭于椅背，下身垂落并在座面形成明显柔软的重力弯曲、层次褶皱和接触阴影；若原图自然平铺，则保留相同场景中的松弛弯曲、下摆起伏和可见软褶。必须有真实承托和布料厚度，不悬空、不僵硬、不把微小织纹当作褶皱。上胸基本正对镜头、舒展可读，自然褶皱集中在下腹、下摆和袖部。图1胸花相对于领口和衣身的位置、所在侧、尺寸比例、图案和唯一数量必须保留；图3只提供这枚胸花的清晰纹理，图3画布不代表印花大小。禁止将小胸花放大、居中搬移、镜像或复制图2旧花纹。衣服保持中性白色以便后续统一换色，白布同时保留明暗褶皱，不能把白色理解为白背景。完整呈现领口、双袖及整个下摆，四周有适量场景边距，任何衣物部位不裁切。移除衣架、人物、标签和新增文字。只输出单张照片。输出前同时检查：图1版型结构与胸花比例正确；图2背景和承托物仍清晰可见；衣服有与承托方式一致的自然弯曲和明显褶皱。三者缺一都不合格，不能牺牲背景或褶皱来复制图1。";
    private final JdbcTemplate db;private final CommerceImageArchive archive;private final ObjectMapper json;
    private final CommerceDrapeImageProcessor processor;private final CommerceLocalPhotoWorkflow local;
    private final CommerceDrapeStyleReview styleReviewer;private final CommerceDrapeMasterRetry masterRetry;private final LongSupplier now;
    CommerceDrapeReuseWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,CommerceDrapeImageProcessor processor,CommerceLocalPhotoWorkflow local){
        this(db,archive,json,processor,local,null,null);
    }
    CommerceDrapeReuseWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,CommerceDrapeImageProcessor processor,CommerceLocalPhotoWorkflow local,CommerceDrapeStyleReview reviewer,CommerceDrapeMasterRetry retry){
        this(db,archive,json,processor,local,reviewer,retry,System::currentTimeMillis);
    }
    CommerceDrapeReuseWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,CommerceDrapeImageProcessor processor,CommerceLocalPhotoWorkflow local,CommerceDrapeStyleReview reviewer,CommerceDrapeMasterRetry retry,LongSupplier now){
        this.db=db;this.archive=archive;this.json=json;this.processor=processor;this.local=local;this.styleReviewer=reviewer;this.masterRetry=retry;this.now=now;
    }
    static String printId(CommercePrintTemplateService.Configuration config) {
        var prints=config.photoPrints().stream().filter(p->"FRONT".equals(p.side())).toList();
        if(prints.size()!=1)throw new IllegalArgumentException("搭放母图复用目前需要一处正面胸前印花；背面和侧面仍按模板补图");
        var p=prints.getFirst();
        if(!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.width())||!Double.isFinite(p.height())||p.width()<=0||p.height()<=0||p.x()<0||p.x()+p.width()>1||p.y()<0||p.y()+p.height()>1)
            throw new IllegalArgumentException("印花位置超出图片边界，请调整位置后继续");
        return p.id();
    }
    Result prepareGuide(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        if(ready(find(jobs,source,GUIDE,0,null)))return null;
        String id=printId(config),front=CommerceSurfaceTemplateInputs.key(run.organizationId(),config.baseImages().get("FRONT"));
        String uv=CommerceSurfaceTemplateInputs.key(run.organizationId(),config.surfaceUvViews().get("FRONT"));
        var first=required(jobs,source,"MOCKUP_FRONT",1,source.colorTargets().getFirst().key());
        String artwork=artwork(first,id);var refs=List.of(front,uv,artwork);
        return local.local(run,source,GUIDE,0,null,refs,VERSION+"/guide-neutral-v2",List.of(refs,config.renderBinding()),()->{
            byte[] white=neutralTemplate(bytes(run.organizationId(),front));
            var result=CommercePhotoMockupComposer.composePhoto("FRONT",white,white,white,bytes(run.organizationId(),uv),config.renderPrintIds(),Map.of(id,bytes(run.organizationId(),artwork)));
            return new CommerceLocalPhotoWorkflow.Produced(result.png(),Map.of("method",VERSION,"printId",id,"uvKey",uv,"artworkKey",artwork,"templateKey",front,"modelCalls",0));
        },"首款搭放定位图已准备；所有款色将复用一次生成的母图");
    }
    List<String> masterReferences(CommerceBatchCreativePlanValidator.Selection source,List<Map<String,Object>> jobs,String original){
        var guide=required(jobs,source,GUIDE,0,null);var refs=references(guide);
        if(refs.size()!=3)throw new IllegalArgumentException("搭放定位图缺少固定印花绑定");
        return List.of((String)guide.get("storage_key"),original,refs.get(2));
    }
    Result finish(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        try {return finishCurrent(run,source,config,jobs);}catch(RecoveryStop stop){return stop.result;}
    }
    private Result finishCurrent(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        String id=printId(config);var master=required(jobs,source,MASTER,0,null);var guide=required(jobs,source,GUIDE,0,null);
        String masterKey=(String)master.get("storage_key"),guideKey=(String)guide.get("storage_key");var guideRefs=references(guide);
        var masterRefs=references(master);
        boolean currentPrompt=CommerceDrapeMasterRetry.stylePrompt(Objects.toString(master.get("prompt"),""));
        boolean templateFirst=TEMPLATE_FIRST_PROMPT.equals(master.get("prompt"));
        boolean legacyPrompt=LEGACY_PROMPT.equals(master.get("prompt"));
        if(guideRefs.size()!=3||masterRefs.size()!=3||!(currentPrompt||templateFirst||legacyPrompt)||!masterRefs.get(legacyPrompt?1:0).equals(guideKey)||!masterRefs.get(2).equals(guideRefs.get(2)))
            throw new IllegalArgumentException("搭放母图与本批定位图不一致，原结果保留");
        String sceneKey=masterRefs.get(legacyPrompt?0:1);
        String uv=guideRefs.get(1),seedArtwork=guideRefs.get(2);var prepared=find(jobs,source,BLANK,0,null);
        if(!ready(prepared)) {
            Map<String,Object> style=Map.of();
            if(currentPrompt) {
                var saved=styleState(run,master);var state=saved.value();
                if(state.path("passed").isBoolean()&&state.path("passed").asBoolean()) {
                    requireStyleBinding(state,guideKey,sceneKey,masterKey);
                    style=styleEvidence(state);
                } else {
                    if(styleReviewer==null)throw new IllegalArgumentException("搭放风格验收服务不可用，保留已生成母图");
                    int attempts="REVIEW_RETRY_PENDING".equals(state.path("status").asText())?state.path("readAttempts").asInt(0):0;
                    if(attempts>0&&state.path("nextAttemptAt").asLong(0)>now.getAsLong())return Result.progress("母图已保存，等待自动重试风格检查");
                    try {style=styleReviewer.review(run.organizationId(),bytes(run.organizationId(),guideKey),bytes(run.organizationId(),sceneKey),bytes(run.organizationId(),masterKey));}
                    catch(CommerceDrapeStyleReview.ReviewUnavailableException unavailable) {
                        int count=attempts+1;
                        saveStyleState(run,master,saved,Map.of("status",count<3?"REVIEW_RETRY_PENDING":"REVIEW_UNAVAILABLE","readAttempts",count,"nextAttemptAt",now.getAsLong()+(count==1?15000:45000),"message","风格检查连接或结果异常，母图保留"));
                        return count<3?Result.progress("母图已保存，风格检查暂未返回，正在自动重试检查（"+count+"/3）"):
                                new Result(false,"风格检查连续未取得有效结果，母图保留；恢复仅重试检查，不再次生图");
                    }
                    if(Boolean.TRUE.equals(style.get("passed")))requireStyleBinding(json.valueToTree(style),guideKey,sceneKey,masterKey);
                    saveStyleState(run,master,saved,style);
                    if(!Boolean.TRUE.equals(style.get("passed"))) {
                        if(masterRetry==null)return new Result(false,"搭放母图风格未满足要求："+style.get("reason")+"；原图保留");
                        var retry=masterRetry.retry(run,master,style);
                        return retry.queued()?Result.progress(retry.message()):new Result(false,retry.message()+"；验收依据："+style.get("reason"));
                    }
                }
            }
            final Map<String,Object> styleReport=style;
            var refs=List.of(masterKey,guideKey,uv,seedArtwork);
            Object cacheInputs=currentPrompt?List.of(refs,sceneKey,CommerceDrapeStyleReview.CONTRACT_VERSION):refs;
            return localWithRecovery(run,source,master,List.of(guide),"prepare",BLANK,0,null,refs,VERSION+"/prepare",cacheInputs,List.of(cacheInputs,config.renderPrintIds()),()->{
                if(processor==null)throw new IllegalArgumentException("本地搭放服务不可用");
                var result=processor.prepare(bytes(run.organizationId(),masterKey),bytes(run.organizationId(),guideKey),bytes(run.organizationId(),uv),bytes(run.organizationId(),seedArtwork),config.renderPrintIds());
                CommerceDrapeImageProcessor.requirePassed(result);
                byte[] blank=CommerceDrapeImageProcessor.decoded(result.get("blank")),alpha=CommerceDrapeImageProcessor.decoded(result.get("alpha"));
                var report=new LinkedHashMap<String,Object>();report.put("method",VERSION);report.put("masterKey",masterKey);report.put("guideKey",guideKey);
                report.put("uvKey",uv);report.put("seedArtworkKey",seedArtwork);report.put("alphaKey",archive.store(run.organizationId(),alpha).storageKey());
                report.put("mapping",result.get("mapping"));report.put("check",result.get("report"));report.put("modelCalls",0);
                if(currentPrompt)report.put("styleReview",styleReport);
                return new CommerceLocalPhotoWorkflow.Produced(blank,report);
            },"搭放母图的衣服遮罩与印花位置已固定；后续换色换花不再生图");
        }
        var preparation=report(prepared);
        if(currentPrompt)requireStyleBinding(preparation.path("styleReview"),guideKey,sceneKey,masterKey);
        String blankKey=(String)prepared.get("storage_key"),alphaKey=preparation.path("alphaKey").asText();
        if(!masterKey.equals(preparation.path("masterKey").asText())||!guideKey.equals(preparation.path("guideKey").asText()))throw new IllegalArgumentException("搭放校准与母图不一致");
        for(int variant=1;variant<=sourceVariants(jobs,source);variant++)for(var color:source.colorTargets()) {
            if(ready(find(jobs,source,OUTPUT,variant,color.key())))continue;
            var precise=required(jobs,source,"MOCKUP_FRONT",variant,color.key());var base=required(jobs,source,"BASE_COLOR",0,color.key());
            String ink=artwork(precise,id);var evidence=report(base);if(evidence.has("color"))evidence=evidence.path("color");
            String rgb=evidence.path("observedSourceRgb").asText("");boolean original="TEMPLATE_ORIGINAL".equals(evidence.path("mode").asText());
            if(original) {
                byte[] template=bytes(run.organizationId(),guideRefs.getFirst());
                var observed=CommerceGarmentColor.prepare(neutralTemplate(template),template,"",false);
                rgb=observed.report().observedSourceRgb();original=false;
            }
            if(!original&&!rgb.matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("该颜色缺少可靠商品取色依据，不按名称猜色");
            final String targetRgb=rgb;final boolean originalColor=original;final int v=variant;
            var refs=List.of(masterKey,blankKey,alphaKey,ink,(String)precise.get("storage_key"),(String)base.get("storage_key"));
            Object renderInputs=List.of(refs,preparation.path("mapping"),precise.get("id"),color.key(),color.label(),targetRgb,originalColor);
            return localWithRecovery(run,source,master,List.of(guide,prepared,precise,base),"render:"+v+":"+color.key(),OUTPUT,v,color,refs,VERSION+"/render",renderInputs,renderInputs,()->{
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
                if(CommerceDrapeMasterRetry.stylePrompt(Objects.toString(master.get("prompt"),""))) {
                    var masterRefs=references(master);var guide=required(jobs,source,GUIDE,0,null);
                    if(masterRefs.size()!=3||!Objects.equals(guide.get("storage_key"),masterRefs.getFirst()))throw new IllegalArgumentException("搭放母图缺少本批定位参考");
                    requireStyleBinding(report(blank).path("styleReview"),masterRefs.getFirst(),masterRefs.get(1),(String)master.get("storage_key"));
                }
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
    private record CheckState(String raw,tools.jackson.databind.node.ObjectNode value) {}
    private static final class RecoveryStop extends RuntimeException {
        final Result result;RecoveryStop(Result result){super(result.message());this.result=result;}
    }
    private CheckState styleState(CommerceBatchExecution run,Map<String,Object> master) {
        var rows=db.queryForList("SELECT c.report_json FROM commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id WHERE j.id=? AND j.execution_id=? AND j.organization_id=?",master.get("id"),run.id(),run.organizationId());
        if(rows.isEmpty())return new CheckState(null,json.createObjectNode());
        String raw=Objects.toString(rows.getFirst().get("report_json"),"{}");var value=json.readTree(raw);
        if(!value.isObject())throw new RecoveryStop(failed("母图检查记录无效，已停止本地处理"));
        return new CheckState(raw,(tools.jackson.databind.node.ObjectNode)value);
    }
    private Map<String,Object> styleEvidence(tools.jackson.databind.JsonNode state) {
        var evidence=new LinkedHashMap<String,Object>();
        for(String field:List.of("passed","hasScene","hasSupportContact","hasNaturalDrape","preservesGarment","preservesPrintPlacement","fullyVisible","contractVersion","reason","guideSha256","sceneSha256","masterSha256"))
            evidence.put(field,json.convertValue(state.path(field),Object.class));
        return evidence;
    }
    private void saveStyleState(CommerceBatchExecution run,Map<String,Object> master,CheckState observed,Map<String,Object> style) {
        var merged=observed.value().deepCopy();
        for(String field:List.of("status","readAttempts","nextAttemptAt","message"))merged.remove(field);
        style.forEach((key,value)->{if(!"localRecovery".equals(key))merged.set(key,json.valueToTree(value));});
        saveCheck(run,master,List.of(),observed,merged);
    }
    /** Compare the complete observed report and frozen job identities under the active tenant lease. */
    private void saveCheck(CommerceBatchExecution run,Map<String,Object> master,List<Map<String,Object>> bindings,CheckState observed,tools.jackson.databind.node.ObjectNode value) {
        String guard="j.id=? AND j.execution_id=? AND j.organization_id=? AND j.kind='DRAPE_MASTER' AND j.status='READY'"
                +" AND j.capture_id=? AND j.capture_version=? AND j.storage_key=? AND j.content_sha256=? AND j.prompt=?"
                +" AND j.reference_keys=CAST(? AS JSON) AND e.organization_id=? AND e.created_by=? AND e.stage='MOCKUP'"
                +" AND e.status='RUNNING' AND e.lease_token=? AND e.lease_until>NOW(6)"
                +" AND NOT EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs newer WHERE newer.execution_id=j.execution_id AND newer.organization_id=j.organization_id"
                +" AND newer.capture_id=j.capture_id AND newer.capture_version=j.capture_version AND newer.kind='DRAPE_MASTER' AND newer.id<>j.id AND newer.status<>'RETRIED')";
        var args=new ArrayList<Object>();args.add(json.writeValueAsString(value));
        Collections.addAll(args,master.get("id"),run.id(),run.organizationId(),master.get("capture_id"),master.get("capture_version"),master.get("storage_key"),master.get("content_sha256"),master.get("prompt"),master.get("reference_keys"),run.organizationId(),run.createdBy(),run.leaseToken());
        for(var bound:bindings){
            guard+=" AND EXISTS (SELECT 1 FROM commerce_batch_artwork_jobs b WHERE b.id=? AND b.execution_id=j.execution_id AND b.organization_id=j.organization_id AND b.capture_id=j.capture_id AND b.capture_version=j.capture_version AND b.kind=? AND b.status='READY' AND b.storage_key=? AND b.reference_keys=CAST(? AS JSON))";
            Collections.addAll(args,bound.get("id"),bound.get("kind"),bound.get("storage_key"),bound.get("reference_keys"));
        }
        String sql;
        if(observed.raw()==null)sql="INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) SELECT j.id,?,NOW(6) FROM commerce_batch_artwork_jobs j JOIN commerce_batch_executions e ON e.id=j.execution_id WHERE "+guard;
        else {
            sql="UPDATE commerce_batch_artwork_checks c JOIN commerce_batch_artwork_jobs j ON j.id=c.job_id JOIN commerce_batch_executions e ON e.id=j.execution_id SET c.report_json=? WHERE "+guard+" AND c.report_json=CAST(? AS JSON)";
            args.add(observed.raw());
        }
        if(db.update(sql,args.toArray())!=1)throw new RecoveryStop(Result.unknown("执行租约、母图或检查状态已变化；已停止本地处理，保留全部素材"));
    }
    private Result localWithRecovery(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,Map<String,Object> master,
            List<Map<String,Object>> bindings,String op,String kind,int variant,CommerceSourceColorResolver.Color color,List<String> refs,String algorithm,Object cacheInputs,Object recoveryInputs,
            Supplier<CommerceLocalPhotoWorkflow.Produced> producer,String message) {
        String inputHash=CommerceImageArchive.sha(json.writeValueAsBytes(List.of(algorithm,recoveryInputs)));
        String operationKey=CommerceImageArchive.sha(json.writeValueAsBytes(List.of(op,inputHash,LOCAL_STRATEGY)));
        // The producer is not invoked on cache hits. Ownership/archive errors outside it never consume a retry.
        return local.local(run,source,kind,variant,color,refs,algorithm,cacheInputs,()->{
            var saved=styleState(run,master);var all=saved.value().deepCopy();
            var recovery=all.withObject("localRecovery");var operations=recovery.withObject("operations");
            var prior=operations.path(operationKey);int attempts=prior.path("attempts").asInt(0);
            if(!prior.isMissingNode()&&(!op.equals(prior.path("op").asText())||!inputHash.equals(prior.path("inputHash").asText())||!LOCAL_STRATEGY.equals(prior.path("strategyVersion").asText())||attempts<1||attempts>MAX_LOCAL_ATTEMPTS))
                throw new RecoveryStop(failed("本地恢复记录与固定输入不一致，已停止处理"));
            if("FAILED".equals(prior.path("status").asText()))throw new RecoveryStop(failed(prior.path("message").asText("本地处理已停止")));
            if(prior.path("nextAttemptAt").asLong(0)>now.getAsLong())throw new RecoveryStop(Result.progress("母图和印花已保存，等待本地处理自动重试（"+attempts+"/3）"));
            if(attempts>=MAX_LOCAL_ATTEMPTS){
                var stopped=prior.deepCopy();((tools.jackson.databind.node.ObjectNode)stopped).put("status","FAILED").put("message","本地处理已达到3次上限");
                operations.set(operationKey,stopped);saveCheck(run,master,bindings,saved,all);
                throw new RecoveryStop(failed("本地处理已达到3次上限"));
            }
            int count=attempts+1;long delay=count==1?15000:45000;
            var entry=json.createObjectNode();entry.put("op",op).put("inputHash",inputHash).put("strategyVersion",LOCAL_STRATEGY)
                    .put("attempts",count).put("status","RUNNING").put("startedAt",now.getAsLong()).put("nextAttemptAt",now.getAsLong()+90000+delay);
            operations.set(operationKey,entry);
            // Reserve before the request: a crash consumes the attempt, and restart observes the persisted deadline.
            saveCheck(run,master,bindings,saved,all);
            var reserved=styleState(run,master);
            if(!reserved.value().equals(all))throw new RecoveryStop(Result.unknown("本地处理状态已变化，未继续调用"));
            CommerceLocalPhotoWorkflow.Produced made;
            try {made=producer.get();}
            catch(CommerceDrapeImageProcessor.LocalProcessingException failure){
                boolean retryable=failure.retryable()&&count<MAX_LOCAL_ATTEMPTS;
                entry.put("status",retryable?"RETRY_PENDING":"FAILED").put("reasonCode",failure.code()).put("message",failure.getMessage())
                        .put("nextAttemptAt",retryable?now.getAsLong()+delay:0);
                saveCheck(run,master,bindings,reserved,all);
                throw new RecoveryStop(retryable?Result.progress("本地处理暂未完成，已保留母图和印花，正在自动重试（"+count+"/3）；不追加生图"):
                        failed(count>=MAX_LOCAL_ATTEMPTS?"本地处理连续3次未完成，自动重试已停止："+failure.getMessage():failure.getMessage()));
            } catch(IllegalArgumentException validation){
                String reason=safeValidationReason(validation);
                entry.put("status","FAILED").put("reasonCode","LOCAL_VALIDATION_FAILED").put("message",reason).put("nextAttemptAt",0);
                saveCheck(run,master,bindings,reserved,all);
                throw new RecoveryStop(failed(reason));
            }
            entry.put("status","SUCCEEDED").put("nextAttemptAt",0).put("completedAt",now.getAsLong());
            saveCheck(run,master,bindings,reserved,all);return made;
        },message);
    }
    private static String safeValidationReason(IllegalArgumentException failure){
        String reason=Objects.toString(failure.getMessage(),"");
        if(reason.length()<=240&&!reason.matches("(?s).*[\\p{Cntrl}].*")&&List.of("本地搭放","搭放定位","搭放合成","搭放结果").stream().anyMatch(reason::startsWith))return reason;
        return "固定输入或本地处理结果未通过校验";
    }
    private static Result failed(String message){return new Result(false,message+"；保留母图、印花与已完成成品，不追加生图","FAILED");}
    static void requireStyleBinding(tools.jackson.databind.JsonNode r,String guide,String scene,String master) {
        for(String flag:List.of("passed","hasScene","hasSupportContact","hasNaturalDrape","preservesGarment","preservesPrintPlacement","fullyVisible"))
            if(!r.path(flag).isBoolean()||!r.path(flag).asBoolean())throw new IllegalArgumentException("搭放母图缺少已通过的背景、褶皱及商品一致性验收");
        if(!CommerceDrapeStyleReview.CONTRACT_VERSION.equals(r.path("contractVersion").asText())
                ||!guide.endsWith("/"+r.path("guideSha256").asText())||!scene.endsWith("/"+r.path("sceneSha256").asText())||!master.endsWith("/"+r.path("masterSha256").asText())
                ||r.path("reason").asText().isBlank())throw new IllegalArgumentException("搭放风格验收与本批模板、场景或母图不一致");
    }
    private int sourceVariants(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source){return jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&"MOCKUP_FRONT".equals(j.get("kind"))&&ready(j)).mapToInt(j->((Number)j.get("variant_index")).intValue()).max().orElse(0);}
    private String artwork(Map<String,Object> guide,String id){String key=report(guide).path("artworkKeys").path(id).asText("");if(key.isBlank())throw new IllegalArgumentException("缺少该位置的固定透明印花");return key;}
    private tools.jackson.databind.JsonNode report(Map<String,Object> job){var rows=db.queryForList("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=?",job.get("id"));if(rows.isEmpty()||rows.getFirst().get("result_json")==null)throw new IllegalArgumentException("缺少本地套版检查记录");return json.readTree((String)rows.getFirst().get("result_json"));}
    private List<String> references(Map<String,Object> job){return json.readValue((String)job.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
    private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
    private static boolean ready(Map<String,Object> job){return job!=null&&"READY".equals(job.get("status"));}
    private static Map<String,Object> find(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,String kind,int v,String color){return jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&kind.equals(j.get("kind"))&&((Number)j.get("variant_index")).intValue()==v&&Objects.equals(color,j.get("color_key"))).findFirst().orElse(null);}
    private static Map<String,Object> required(List<Map<String,Object>> jobs,CommerceBatchCreativePlanValidator.Selection source,String kind,int v,String color){var j=find(jobs,source,kind,v,color);if(!ready(j))throw new IllegalArgumentException("搭放复用所需素材尚未归档："+kind);return j;}
    // Kept for existing launch/preflight callers: color is normalized, not rejected.
    static void requireWhiteTemplate(byte[] bytes){ neutralTemplate(bytes); }
    static byte[] neutralTemplate(byte[] bytes){
        var image=CommerceMockupComposer.decode(bytes);
        var mask=CommerceMockupComposer.decode(CommercePhotoMockupComposer.recolorMask(bytes));
        var values=new ArrayList<Integer>();
        for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3)
            if((mask.getRGB(x,y)>>>24)>240){int p=image.getRGB(x,y);values.add((int)Math.round(.2126*(p>>16&255)+.7152*(p>>8&255)+.0722*(p&255)));}
        if(values.isEmpty())throw new IllegalArgumentException("模板缺少可识别的服装区域");
        values.sort(Integer::compareTo);double median=values.get(values.size()/2);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int p=image.getRGB(x,y);if((mask.getRGB(x,y)>>>24)<=240)continue;
            double luminance=.2126*(p>>16&255)+.7152*(p>>8&255)+.0722*(p&255);
            int shade=(int)Math.round(Math.max(0,Math.min(255,240+luminance-median)));
            image.setRGB(x,y,(p&0xff000000)|(shade<<16)|(shade<<8)|shade);
        }
        return encodeTemplate(image);
    }
    static byte[] encodeTemplate(java.awt.image.BufferedImage image){
        try{var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();}
        catch(java.io.IOException failure){throw new IllegalArgumentException("模板图像转换失败",failure);}
    }

}
