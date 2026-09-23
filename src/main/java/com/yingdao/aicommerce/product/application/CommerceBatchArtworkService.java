package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** One model attempt per worker turn; completed artifacts are reused across all later turns. */
@Service
public class CommerceBatchArtworkService {
    public record Artifact(String id,String captureId,String kind,int variant,String status,String imageUrl,String contentSha256,String message,Object check,String colorKey,String colorLabel) {
        @com.fasterxml.jackson.annotation.JsonProperty("backImageUrl")
        public String backImageUrl() {
            return Set.of("MOCKUP_FRONT","DRAPED_FRONT").contains(kind)&&"READY".equals(status)&&imageUrl!=null&&check!=null
                    ?imageUrl.replaceFirst("/image$","/back-image"):null;
        }
        public Artifact(String id,String captureId,String kind,int variant,String status,String imageUrl,String contentSha256,String message,Object check) {
            this(id,captureId,kind,variant,status,imageUrl,contentSha256,message,check,null,null);
        }
    }
    private final JdbcTemplate db;
    private final CommerceBatchCreativePlanMapper plans;
    private final CommerceTemplateMapper templates;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceGarmentForeground foreground;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceDrapeImageProcessor drapeProcessor;
    private final ProductCaptureService captures;
    private final CommerceImageArchive archive;
    private final MarketplaceImageDownloader downloader;
    private final ModelConfigService models;
    private final CommerceDesignImageGateway gateway;
    private final CommerceBatchArtworkLedger ledger;
    private final CommerceBatchExecutionService executions;
    private final ObjectMapper json;
    private final CommerceCaptureSourceArchiveService sourceArchives;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceTransparentArtworkRetry automaticArtworkRetry;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceDrapeStyleReview drapeStyleReviewer;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceDrapeMasterRetry drapeMasterRetry;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceModelDetailWorkflow modelDetails;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceModelDetailGridWorkflow modelDetailGrids;

    public CommerceBatchArtworkService(JdbcTemplate db,CommerceBatchCreativePlanMapper plans,CommerceTemplateMapper templates,
            ProductCaptureService captures,CommerceImageArchive archive,MarketplaceImageDownloader downloader,
            ModelConfigService models,CommerceDesignImageGateway gateway,CommerceBatchArtworkLedger ledger,
            CommerceBatchExecutionService executions,ObjectMapper json,CommerceCaptureSourceArchiveService sourceArchives) {
        this.db=db;this.plans=plans;this.templates=templates;this.captures=captures;this.archive=archive;
        this.downloader=downloader;this.models=models;this.gateway=gateway;this.ledger=ledger;this.executions=executions;this.json=json;this.sourceArchives=sourceArchives;
    }

    public List<Artifact> list(String batch,PlatformPrincipal user) {
        var run=executions.get(batch,user);if(run==null)return List.of();
        var result=new ArrayList<Artifact>(rows(run).stream().map(row->new Artifact((String)row.get("id"),(String)row.get("capture_id"),(String)row.get("kind"),
                ((Number)row.get("variant_index")).intValue(),(String)row.get("status"),row.get("storage_key")==null?null:
                "/api/v1/commerce-batches/"+batch+"/execution/artifacts/"+row.get("id")+"/image",(String)row.get("content_sha256"),(String)row.get("message"),checkFor((String)row.get("id")),(String)row.get("color_key"),(String)row.get("color_label"))).toList());
        for(var row:rows(run)) if(hasGuide(row)) {
            var keys=referenceKeys(row);
            int guideIndex=guideIndex(row);
            if(keys.size()>guideIndex) result.add(new Artifact(row.get("id")+"-guide",(String)row.get("capture_id"),
                    "PLACEMENT_GUIDE_"+(CommerceSurfaceTemplateInputs.isSurfaceJob((String)row.get("kind"))?CommerceSurfaceTemplateInputs.side((String)row.get("kind")):"FRONT"),
                    ((Number)row.get("variant_index")).intValue(),"READY",
                    "/api/v1/commerce-batches/"+batch+"/execution/artifacts/"+row.get("id")+"-guide/image",
                    keys.get(guideIndex).substring(65),"按已固定位置替换的印花定位图；AI候选另行保存",null,(String)row.get("color_key"),(String)row.get("color_label")));
        }
        for(var row:db.queryForList("SELECT r.*,j.capture_id,j.kind,j.variant_index,j.color_key,j.color_label FROM commerce_batch_artwork_repairs r JOIN commerce_batch_artwork_jobs j ON j.id=r.job_id WHERE r.organization_id=? AND r.execution_id=? ORDER BY r.created_at,r.id",user.organizationId(),run.id()))result.add(new Artifact((String)row.get("id"),(String)row.get("capture_id"),row.get("kind")+"_REPAIRED",((Number)row.get("variant_index")).intValue(),"READY","/api/v1/commerce-batches/"+batch+"/execution/artifacts/"+row.get("id")+"/image",(String)row.get("content_sha256"),(json.readTree((String)row.get("policy_json")).path("algorithm").asText().equals("LOCAL_MASK_HARMONIC_V2")?"边缘融合修复版":"初版局部修复")+"；原始候选保留，仍待质量复核",row.get("report_json")==null?null:json.readTree((String)row.get("report_json")),(String)row.get("color_key"),(String)row.get("color_label")));
        return result;
    }

    public CommerceDesignImageGateway.Reference image(String batch,String id,PlatformPrincipal user) {
        var run=executions.get(batch,user);
        if(run==null)throw error("批次尚未启动");
        if(id.endsWith("-guide")) {
            String jobId=id.substring(0,id.length()-6);
            var job=rows(run).stream().filter(r->jobId.equals(r.get("id"))&&hasGuide(r))
                    .findFirst().orElseThrow(()->error("三维印花引导图不存在"));
            var keys=referenceKeys(job);int guideIndex=guideIndex(job);if(keys.size()<=guideIndex)throw error("印花引导图尚未归档");
            return archive.read(user.organizationId(),keys.get(guideIndex));
        }
        var repaired=db.queryForList("SELECT storage_key FROM commerce_batch_artwork_repairs WHERE id=? AND organization_id=? AND execution_id=?",id,user.organizationId(),run.id());
        if(!repaired.isEmpty())return archive.read(user.organizationId(),(String)repaired.getFirst().get("storage_key"));
        var found=rows(run).stream().filter(r->id.equals(r.get("id"))).findFirst().orElseThrow(()->error("图集素材不存在"));
        if(!Set.of("READY","TRANSPARENCY_REJECTED").contains(found.get("status"))||found.get("storage_key")==null)throw error("素材尚未归档");
        return archive.read(user.organizationId(),(String)found.get("storage_key"));
    }

    public CommerceDesignImageGateway.Reference sourcePreview(String batch,String capture,PlatformPrincipal user) {
        var run=executions.get(batch,user);if(run==null)throw error("批次尚未启动");
        var jobs=rows(run).stream().filter(r->capture.equals(r.get("capture_id"))&&"EXTRACT".equals(r.get("kind"))).toList();
        if(!jobs.isEmpty()&&!referenceKeys(jobs.getFirst()).isEmpty())return archive.read(user.organizationId(),referenceKeys(jobs.getFirst()).getFirst());
        var record=plans.forExecution(user.organizationId(),run.id());if(record==null)throw error("缺少固定来源配置");
        var plan=json.readValue(record.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
        var selected=plan.sources().stream().filter(s->capture.equals(s.captureId())).findFirst().orElseThrow(()->error("来源不属于当前批次"));
        var detail=captures.get(capture,user);
        if(detail.capture().getVersion()!=selected.version())throw error("来源商品版本已变化");
        return archive.read(user.organizationId(),archiveSource(selected,detail,user));
    }

    public Result execute(CommerceBatchExecution run,PlatformPrincipal user) {
        if(user==null||!Objects.equals(run.organizationId(),user.organizationId())||!Objects.equals(run.createdBy(),user.userId())
                ||!user.permissionCodes().contains("product:edit")||!user.permissionCodes().contains("product:publish"))
            return new Result(false,"自动创作需要启动者保有商品编辑与发布权限");
        var record=plans.forExecution(run.organizationId(),run.id());
        if(record==null)return new Result(false,"旧执行未保存创作配置，请建立并配置新批次");
        var plan=json.readValue(record.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
        try {CommerceBatchCreativePlanValidator.validateRenderPolicy(plan.renderPolicy());}
        catch(IllegalArgumentException failure) {return new Result(false,failure.getMessage());}
        boolean localPhoto=CommerceBatchCreativePlanValidator.localPhotoOutput(plan);
        if(localPhoto&&!CommerceBatchCreativePlanValidator.sourceColorOutput(plan))return new Result(false,"本地套版缺少固定的每色出图规则，请重新保存新批次配置");
        var localWorkflow=localPhoto?new CommerceLocalPhotoWorkflow(db,archive,json,foreground):null;
        var drapeWorkflow=CommerceBatchCreativePlanValidator.drapeOutput(plan)?new CommerceDrapeReuseWorkflow(db,archive,json,drapeProcessor,localWorkflow,drapeStyleReviewer,drapeMasterRetry):null;
        var template=templates.snapshot(run.organizationId(),plan.printTemplateSnapshotId());
        if(template==null||!"PRINT_LAYOUT".equals(template.kind()))return new Result(false,"固定的套版模板不可用");
        var config=json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class);
        CommercePrintTemplateService.validate(config);
        if(CommerceBatchCreativePlanValidator.sceneOutput(plan)&&!CommercePrintTemplateService.photoLayout(config))return new Result(false,"场景套版需要已保存的四面照片模板，未提交图片生成");
        if(drapeWorkflow!=null)try {CommerceDrapeReuseWorkflow.printId(config);CommerceDrapeReuseWorkflow.requireWhiteTemplate(decode(archive.read(user.organizationId(),CommerceSurfaceTemplateInputs.key(user.organizationId(),config.baseImages().get("FRONT")))));}
        catch(IllegalArgumentException unsupported){return new Result(false,unsupported.getMessage());}
        try {CommerceBatchCreativePlanValidator.validateFrozenArtworks(plan,config,run.organizationId());}
        catch(IllegalArgumentException failure){return new Result(false,failure.getMessage());}
        boolean savedArtwork=CommerceBatchCreativePlanValidator.savedArtwork(plan);
        boolean surface=CommerceSurfaceTemplateInputs.enabled(config);
        boolean colorOutput=CommerceBatchCreativePlanValidator.sourceColorOutput(plan);
        boolean sourceProduct=surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan);
        Map<String,CommerceSurfaceTemplateInputs.Side> surfaceSides=Map.of();
        if(surface) try {
            if(localPhoto){var primary=CommerceSurfaceTemplateInputs.loadPrimary(config,archive,user.organizationId());surfaceSides=Map.of(primary.side(),primary);}
            else surfaceSides=CommerceSurfaceTemplateInputs.load(config,archive,user.organizationId());
            if(!sourceProduct)CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId());
        }
        catch(RuntimeException failure) {return new Result(false,"固定商品套版不可用，未提交生图："+failure.getMessage());}
        String surfaceArtwork=surface&&!sourceProduct?CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId()):null;
        var artworkGroups=sourceProduct?CommerceSurfaceTemplateInputs.artworkGroups(config):List.<String>of();
        if(!surface&&config.areas().stream().anyMatch(a->!"FRONT".equals(a.side())))
            return new Result(false,"当前自动任务仅配置了正面来源，不能推测或复制背面印花；请使用明确的正面套版模板");
        if(!surface&&!"REVIEW".equals(run.stage())&&config.baseImages().get("FRONT")==null&&plan.sources().stream().anyMatch(s->s.cleanupAreas().isEmpty()))
            return new Result(false,"请在新批次创作配置中标记旧印花范围，或使用已上传底图的模板；未提交新模型调用");
        var details=new LinkedHashMap<String,ProductCaptureService.CaptureDetail>();
        for(var source:plan.sources()) {
            var detail=captures.get(source.captureId(),user);
            if(detail.capture().getVersion()!=source.version()||detail.images().stream().noneMatch(i->i.getId().equals(source.imageId())))
                return new Result(false,"来源版本或所选原图已变化，停止新模型调用："+source.captureId());
            if(colorOutput)try {
                CommerceBatchCreativePlanValidator.validateColorTargets(source,detail.images().stream().map(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getId).toList());
            } catch(IllegalArgumentException failure) {return new Result(false,failure.getMessage());}
            details.put(source.captureId(),detail);
        }
        var outputSides=colorOutput&&surface?List.of(preferredSide(surfaceSides)):List.copyOf(surfaceSides.values());
        var completed=rows(run);
        // Recheck archived generated inputs as well as new responses: old white-background
        // results and cached outputs must not bypass the native transparency requirement.
        if(savedArtwork){
            try {validateSavedJobs(plan,artworkGroups,completed,user);}
            catch(RuntimeException failure){return new Result(false,"已有印花校验未通过："+failure.getMessage()+"；未调用生图模型");}
        }
        var invalidPrint=validateReadyPrints(run,completed,user,!surface||sourceProduct);
        if(invalidPrint!=null)return invalidPrint;
        if(localPhoto&&run.stage().equals("EXTRACT")) {
            // Validate registration before spending on new artwork. The source photo is used for
            // garment appearance; the rendered mesh supplies coordinates only.
            try {
                if(surface) {
                    var primary=outputSides.getFirst();
                    if(CommercePrintTemplateService.photoLayout(config)) {
                        if(CommerceBatchCreativePlanValidator.sceneOutput(plan))CommerceSceneBackgroundAssets.validateGeometry(config,user.organizationId(),archive);
                        for(var face:CommerceSurfaceTemplateInputs.load(config,archive,user.organizationId()).values()) {
                            byte[] photo=CommerceSurfaceTemplateInputs.bytes(archive.read(user.organizationId(),face.photoKey()));
                            CommercePhotoMockupComposer.inspectPhotoMapping(face.side(),photo,face.blank(),face.uv(),config.renderPrintIds());
                        }
                    } else CommercePhotoMockupComposer.inspectMapping(decode(archive.read(user.organizationId(),primary.photoKey())),primary.blank(),primary.uv(),config.renderPrintIds());
                }
            } catch(RuntimeException failure) {return new Result(false,"照片位置预检未通过："+failure.getMessage()+"；尚未提交新的印花生成");}
            for(var source:plan.sources()) {
                String base;
                if(surface)base=outputSides.getFirst().photoKey();
                else if(config.baseImages().get("FRONT")!=null)base=CommerceSurfaceTemplateInputs.key(user.organizationId(),config.baseImages().get("FRONT"));
                else {
                    var blank=find(completed,source.captureId(),"BLANK",0);
                    if(!ready(blank)) {
                        if(source.cleanupAreas().isEmpty())return new Result(false,"原商品换花需要先保存旧印花范围；不会对整张实物图随意去花");
                        String original=archiveSource(source,details.get(source.captureId()),user);
                        if(blank==null) {
                            var reused=reusePreparedBase(run,source,"BLANK",List.of(original),CommerceSampleService.blankPrompt(),source.cleanupAreas(),user);
                            if(reused!=null)return reused;
                        }
                        return advance(run,source,"BLANK",0,CommerceSampleService.blankPrompt(),()->List.of(original),blank,user);
                    }
                    base=(String)blank.get("storage_key");
                }
                var prepared=localWorkflow.prepareColors(run,source,base,completed,
                        color->archiveColor(source,color,details.get(source.captureId()),user));
                if(prepared!=null)return prepared;
            }
        }
        if(localPhoto&&run.stage().equals("REVIEW")) {
            var reviewed=localWorkflow.review(run,plan,config,completed);
            if(reviewed!=null)return reviewed;
            if(drapeWorkflow!=null){var drapeReview=drapeWorkflow.review(run,plan,config,completed);if(drapeReview!=null)return drapeReview;}
            try {for(var source:plan.sources())for(int v=1;v<=plan.variantsPerPrint();v++)
                CommerceModelDetailWorkflow.requiredKeys(source,v,completed,db,json,archive,user.organizationId());}
            catch(IllegalArgumentException incomplete){return new Result(false,incomplete.getMessage()+"；主图与详情齐全后才进入待上架");}
            boolean only=db.queryForObject("SELECT COUNT(*)>0 FROM commerce_batch_creation_modes WHERE batch_id=? AND organization_id=? AND mode='CREATIVE_ONLY'",Boolean.class,run.batchId(),run.organizationId());
            return new Result(true,only?"各款各色商品主图已合成归档；仅创作批次已完成。":"本地套版与保护区域自动检查通过，直接进入发布准备。");
        }
        if(CommerceBatchCreativePlanValidator.sceneOutput(plan)&&"MOCKUP".equals(run.stage())) {
            for(var source:plan.sources()){
                var scene=prepareScene(run,plan,source,details.get(source.captureId()),config,completed,user);
                if(scene!=null)return scene;
            }
        }
        for(var source:plan.sources()) {
            if(savedArtwork&&Set.of("EXTRACT","VARIATE").contains(run.stage())){
                if("EXTRACT".equals(run.stage())){
                    var existing=find(completed,source.captureId(),"EXTRACT",0);
                    if(!ready(existing))return archiveSavedArtwork(run,source,"EXTRACT",0,plan.reusedArtworks().getFirst().groupArtifacts().get(artworkGroups.getFirst()),existing,user);
                }else for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(int group=0;group<artworkGroups.size();group++){
                    String kind=CommerceSurfaceTemplateInputs.variationKind(group+1);var existing=find(completed,source.captureId(),kind,variant);
                    if(!ready(existing))return archiveSavedArtwork(run,source,kind,variant,plan.reusedArtworks().get(variant-1).groupArtifacts().get(artworkGroups.get(group)),existing,user);
                }
                continue;
            }
            if(surface) {
                var fixedArtwork=find(completed,source.captureId(),"EXTRACT",0);
                if(sourceProduct&&fixedArtwork!=null&&"ARCHIVED_ARTWORK".equals(fixedArtwork.get("model_config_id")))
                    return new Result(false,"此执行已固定为模板测试印花来源，请新建创作批次；不能覆盖原任务改作商品印花提取");
                if(!"EXTRACT".equals(run.stage())&&!ready(fixedArtwork))
                    return new Result(false,sourceProduct?"缺少已归档的来源商品原印花，请先完成原图提取；测试图案不能作为画风来源":"缺少已固定的模板上传印花，请先完成印花来源归档；不会从采集图猜测替代");
                if(!sourceProduct&&ready(fixedArtwork)&&!CommerceSurfaceTemplateInputs.key(user.organizationId(),surfaceArtwork).equals(fixedArtwork.get("storage_key")))
                    return new Result(false,"此执行旧印花来源与模板选中的上传印花不一致，请新建创作批次；不会覆盖原任务来源");
            }
            if(run.stage().equals("EXTRACT")) {
                var job=find(completed,source.captureId(),"EXTRACT",0);
                if(surface&&!sourceProduct) {
                    if(!ready(job))return archiveSurfaceArtwork(run,source,surfaceArtwork,job,user);
                    continue;
                }
                if(!ready(job)) {
                    if(localPhoto&&job==null) {
                        String input=archiveSource(source,details.get(source.captureId()),user);
                        var reused=reusePreparedBase(run,source,"EXTRACT",List.of(input),CommerceSampleService.printPrompt("PRINT_EXTRACT",0),List.of(),user);
                        if(reused!=null)return reused;
                    }
                    return advance(run,source,"EXTRACT",0,CommerceSampleService.printPrompt("PRINT_EXTRACT",0),
                            ()->List.of(archiveSource(source,details.get(source.captureId()),user)),job,user);
                }
            } else if(run.stage().equals("VARIATE")) {
                var extract=find(completed,source.captureId(),"EXTRACT",0);
                if(!ready(extract))return new Result(false,"缺少已归档的提取印花，不能裂变");
                for(int i=1;i<=plan.variantsPerPrint();i++) {
                    if(sourceProduct) {
                        for(int group=1;group<=artworkGroups.size();group++) {
                            String kind=CommerceSurfaceTemplateInputs.variationKind(group);
                            var job=find(completed,source.captureId(),kind,i);
                            if(!ready(job))return advance(run,source,kind,i,groupVariationPrompt(i,group),
                                    ()->List.of((String)extract.get("storage_key")),job,user);
                        }
                        continue;
                    }
                    var job=find(completed,source.captureId(),"VARIATE",i);
                    if(!ready(job))return advance(run,source,"VARIATE",i,variationPrompt(i),()->List.of((String)extract.get("storage_key")),job,user);
                }
            } else if(run.stage().equals("MOCKUP")) {
                if(surface) {
                    if(localPhoto) {
                        var target=outputSides.getFirst();
                        var result=localWorkflow.execute(run,plan,source,config,target,target.photoKey(),completed,
                                color->archiveColor(source,color,details.get(source.captureId()),user));
                        if(result!=null)return result;
                        if(drapeWorkflow!=null) {
                            try {
                                var guide=drapeWorkflow.prepareGuide(run,source,config,completed);if(guide!=null)return guide;
                                var master=find(completed,source.captureId(),CommerceDrapeReuseWorkflow.MASTER,0);
                                if(!ready(master))return advance(run,source,CommerceDrapeReuseWorkflow.MASTER,0,CommerceDrapeReuseWorkflow.PROMPT,
                                        ()->drapeWorkflow.masterReferences(source,completed,archiveSource(source,details.get(source.captureId()),user)),master,user);
                                var derived=drapeWorkflow.finish(run,source,config,completed);if(derived!=null)return derived;
                            } catch(RuntimeException failure){return new Result(false,"搭放母图复用已停止："+Objects.toString(failure.getMessage(),"本地处理未完成")+"；已完成母图与印花保留，不会追加生图");}
                        }
                        continue;
                    }
                    for(int i=1;i<=plan.variantsPerPrint();i++) {
                        var variant=sourceProduct?null:find(completed,source.captureId(),"VARIATE",i);
                        var groupKeys=new LinkedHashMap<String,String>();
                        if(sourceProduct) {
                            for(int group=1;group<=artworkGroups.size();group++) {
                                var groupVariant=find(completed,source.captureId(),CommerceSurfaceTemplateInputs.variationKind(group),i);
                                if(!ready(groupVariant))return new Result(false,"缺少已归档的印花位置组 "+group+" · 变体 "+i+"，不能套版或保留测试图案");
                                groupKeys.put(artworkGroups.get(group-1),(String)groupVariant.get("storage_key"));
                            }
                        } else if(!ready(variant))return new Result(false,"缺少已归档的独立印花变体，不能套版");
                        for(var target:outputSides) for(var color:outputColors(source,colorOutput)) {
                            String kind=CommerceSurfaceTemplateInputs.kind(target.side());
                            var job=find(completed,source.captureId(),kind,i,colorKey(color));
                            if(!ready(job))return advance(run,source,kind,i,color,
                                    surfacePrompt(target.side(),!sourceProduct&&!colorOutput)+(color==null?"":colorPrompt(color,true)),()->{
                                Map<String,byte[]> replacements;
                                if(sourceProduct) {
                                    var designs=new LinkedHashMap<String,byte[]>();
                                    groupKeys.forEach((artwork,key)->designs.put(artwork,decode(archive.read(user.organizationId(),key))));
                                    replacements=CommerceSurfaceTemplateInputs.replacements(config,target,designs);
                                } else {
                                    byte[] print=decode(archive.read(user.organizationId(),(String)variant.get("storage_key")));
                                    replacements=CommerceSurfaceTemplateInputs.replacements(config,target,surfaceArtwork,print,archive,user.organizationId());
                                }
                                var guide=archive.store(user.organizationId(),CommerceSurfaceMockupComposer.compose(
                                        target.blank(),target.uv(),config.renderPrintIds(),replacements));
                                if(color!=null)return List.of(target.originalKey(),target.photoKey(),guide.storageKey(),
                                        archiveColor(source,color,details.get(source.captureId()),user));
                                if(sourceProduct)return List.of(target.originalKey(),target.photoKey(),guide.storageKey());
                                return List.of(target.originalKey(),target.photoKey(),guide.storageKey(),(String)variant.get("storage_key"));
                            },job,user);
                        }
                    }
                    continue;
                }
                String uploaded=config.baseImages().get("FRONT");
                String base;
                if(uploaded==null) {
                    var blank=find(completed,source.captureId(),"BLANK",0);
                    var extract=find(completed,source.captureId(),"EXTRACT",0);
                    if(!ready(extract))return new Result(false,"缺少已固定的来源图片，不能清除原印花");
                    if(!ready(blank)&&source.cleanupAreas().isEmpty())return new Result(false,"请在新批次创作配置中标记旧印花范围，或选择已上传底图的模板；不会猜测需要清除的区域");
                    if(!ready(blank)) {
                        if(localPhoto&&blank==null) {
                            var reused=reusePreparedBase(run,source,"BLANK",referenceKeys(extract),CommerceSampleService.blankPrompt(),source.cleanupAreas(),user);
                            if(reused!=null)return reused;
                        }
                        return advance(run,source,"BLANK",0,CommerceSampleService.blankPrompt(),()->referenceKeys(extract),blank,user);
                    }
                    base=(String)blank.get("storage_key");
                } else base=CommerceImageArchive.sha(user.organizationId().getBytes(StandardCharsets.UTF_8))+"/"+uploaded;
                if(localPhoto) {
                    var result=localWorkflow.execute(run,plan,source,config,null,base,completed,
                            color->archiveColor(source,color,details.get(source.captureId()),user));
                    if(result!=null)return result;
                    continue;
                }
                for(int i=1;i<=plan.variantsPerPrint();i++) for(var color:outputColors(source,colorOutput)) {
                    var variant=find(completed,source.captureId(),"VARIATE",i);
                    if(!ready(variant))return new Result(false,"缺少已归档的独立印花变体，不能套版");
                    var job=find(completed,source.captureId(),"MOCKUP",i,colorKey(color));
                    if(!ready(job))return advance(run,source,"MOCKUP",i,color,
                            CommerceSampleService.mockupPrompt("FRONT")+(color==null?"":colorPrompt(color,false)),()->{
                        byte[] print=decode(archive.read(user.organizationId(),(String)variant.get("storage_key")));
                        var layers=config.areas().stream().map(a->new CommerceMockupComposer.Layer(a,print)).toList();
                        var guide=archive.store(user.organizationId(),CommerceMockupComposer.compose(decode(archive.read(user.organizationId(),base)),layers));
                        return color==null?List.of(base,guide.storageKey()):List.of(base,guide.storageKey(),
                                archiveColor(source,color,details.get(source.captureId()),user));
                    },job,user);
                }
            } else if(run.stage().equals("REVIEW")) {
                if(surface) {
                    for(int i=1;i<=plan.variantsPerPrint();i++) for(var target:outputSides) for(var color:outputColors(source,colorOutput)) {
                        var job=find(completed,source.captureId(),CommerceSurfaceTemplateInputs.kind(target.side()),i,colorKey(color));
                        if(!ready(job))return new Result(false,"三维套版各面结果尚未全部归档，不能完成检查");
                        if(checkFor((String)job.get("id"))==null) {
                            var report=CommerceSurfaceMockupComposer.check(target.blank(),target.uv(),
                                    decode(archive.read(user.organizationId(),(String)job.get("storage_key"))),config.renderPrintIds());
                            db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",job.get("id"),json.writeValueAsString(color==null?report:colorReport(report)));
                            return Result.progress("已保存三维套版位置检查 · "+target.side()+" · 变体 "+i+colorSuffix(color)+"；精确位置图与AI候选均已保留");
                        }
                    }
                    continue;
                }
                for(int i=1;i<=plan.variantsPerPrint();i++) for(var color:outputColors(source,colorOutput)) {
                    var job=find(completed,source.captureId(),"MOCKUP",i,colorKey(color));
                    if(!ready(job))return new Result(false,"套版结果尚未全部归档，不能完成检查");
                    if(checkFor((String)job.get("id"))==null) {
                        var refs=referenceKeys(job);
                        var report=CommerceMockupPositionCheck.check(decode(archive.read(user.organizationId(),refs.get(0))),
                                decode(archive.read(user.organizationId(),refs.get(1))),decode(archive.read(user.organizationId(),(String)job.get("storage_key"))),config.areas());
                        db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",job.get("id"),json.writeValueAsString(color==null?report:colorReport(report)));
                        return Result.progress("已保存套版位置检查 · 变体 "+i+colorSuffix(color)+"；机器检查不代替质量批准");
                    }
                }
            } else throw error("不支持的自动创作阶段");
        }
        if(run.stage().equals("MOCKUP")) {
            if(modelDetailGrids!=null){
                try {for(var source:plan.sources())for(int variant=1;variant<=plan.variantsPerPrint();variant++){
                    final int v=variant;
                    var result=modelDetailGrids.execute(run,source,v,completed,
                            imageId->archiveSource(new CommerceBatchCreativePlanValidator.Selection(source.captureId(),source.version(),imageId),details.get(source.captureId()),user),
                            (kind,prompt,refs,job)->advance(run,source,kind,v,prompt,()->refs,job,user));
                    if(result!=null)return result;
                }}catch(RuntimeException failure){return new Result(false,"详情处理未完成，已保存结果保留："+Objects.toString(failure.getMessage(),"素材处理异常"),"FAILED");}
            }else {
            try {for(var source:plan.sources())for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(int index=1;index<=source.detailImageIds().size();index++) {
                String kind=CommerceModelDetailPolicy.kind(index);
                var job=find(completed,source.captureId(),kind,variant);
                String product=CommerceModelDetailWorkflow.productKey(source,variant,completed);
                String imageId=source.detailImageIds().get(index-1);
                String style;
                if(job==null)style=archiveSource(new CommerceBatchCreativePlanValidator.Selection(source.captureId(),source.version(),imageId),details.get(source.captureId()),user);
                else {var frozen=referenceKeys(job);
                    if(frozen.size()!=2||!product.equals(frozen.getFirst()))throw new IllegalArgumentException("模特详情图固定的新款参考不一致");
                    style=frozen.get(1);}
                if(!ready(job))return advance(run,source,kind,variant,CommerceModelDetailPolicy.prompt(index),()->List.of(product,style),job,user);
                if(modelDetails==null)return new Result(false,"模特详情图检查服务不可用，已生成图片保留");
                var checked=modelDetails.review(run,source,variant,index,job,product,style);if(checked!=null)return checked;
            }} catch(RuntimeException failure){return new Result(false,"模特实穿详情图暂未完成："+Objects.toString(failure.getMessage(),"素材读取失败")+"；已归档图片保留，不重复提交未知请求");}
            }
        }
        if(run.stage().equals("REVIEW")) {
            try {for(var source:plan.sources())for(int v=1;v<=plan.variantsPerPrint();v++)
                CommerceModelDetailWorkflow.requiredKeys(source,v,completed,db,json,archive,user.organizationId());}
            catch(IllegalArgumentException incomplete){return new Result(false,incomplete.getMessage()+"；主图与详情齐全后才进入待上架");}
            boolean only=db.queryForObject("SELECT COUNT(*)>0 FROM commerce_batch_creation_modes WHERE batch_id=? AND organization_id=? AND mode='CREATIVE_ONLY'",Boolean.class,run.batchId(),run.organizationId());
            return new Result(only,only?"仅创作流程已完成：提取、裂变、套版和位置检查均已归档。候选仍待业务质量复核，未执行上架。":"自动图片检查已保存，待业务质量复核与发布配置确认；不会自动批准上架");
        }
        return new Result(true,"已归档本阶段全部图片（"+run.stage()+"），自动进入下一阶段；候选图片仍需质量复核");
    }

    private void validateSavedJobs(CommerceBatchCreativePlanValidator.Plan plan,List<String> groups,List<Map<String,Object>> jobs,PlatformPrincipal user){
        var checked=new HashSet<String>();
        for(var job:jobs){
            String kind=Objects.toString(job.get("kind"),"");if(!("EXTRACT".equals(kind)||kind.startsWith("VARIATE")))continue;
            int variant=((Number)job.get("variant_index")).intValue();
            CommerceBatchCreativePlanValidator.FrozenArtwork expected;
            if("EXTRACT".equals(kind))expected=plan.reusedArtworks().getFirst().groupArtifacts().get(groups.getFirst());
            else {
                if(!kind.matches("VARIATE_G[1-9][0-9]*")||variant<1||variant>plan.variantsPerPrint())throw new IllegalArgumentException("复用记录的款式或位置组无效");
                int group=Integer.parseInt(kind.substring("VARIATE_G".length()))-1;
                if(group>=groups.size())throw new IllegalArgumentException("复用记录出现未知位置组");
                expected=plan.reusedArtworks().get(variant-1).groupArtifacts().get(groups.get(group));
            }
            if(!ready(job)||!"SAVED_ARTWORK".equals(job.get("model_config_id"))||!expected.storageKey().equals(job.get("storage_key"))||!expected.contentSha256().equals(job.get("content_sha256")))
                throw new IllegalArgumentException("当前执行的已有印花引用与固定素材不一致");
            if(checked.add(expected.storageKey()))readSavedArtwork(expected,user);
        }
    }
    private byte[] readSavedArtwork(CommerceBatchCreativePlanValidator.FrozenArtwork artwork,PlatformPrincipal user){
        byte[] bytes=decode(archive.read(user.organizationId(),artwork.storageKey()));
        if(!artwork.contentSha256().equals(CommerceImageArchive.sha(bytes)))throw new IllegalArgumentException("原始印花内容与保存时的指纹不一致");
        CommerceTransparentArtworkPolicy.requireNative(bytes);return bytes;
    }
    private Result archiveSavedArtwork(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,
                                       CommerceBatchCreativePlanValidator.FrozenArtwork artwork,Map<String,Object> existing,PlatformPrincipal user){
        if(existing!=null)return new Result(false,"当前执行已有不同印花任务，不能覆盖为素材库引用；已保留原任务");
        try {readSavedArtwork(artwork,user);}
        catch(RuntimeException failure){return new Result(false,"已有印花暂不可复用："+failure.getMessage()+"；未调用生图模型");}
        db.update("""
            INSERT IGNORE INTO commerce_batch_artwork_jobs
            (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,result_json,message,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,'READY',?,'SAVED_ARTWORK',?,?,?,?,?,?,NOW(6),NOW(6))
            """,UUID.randomUUID().toString(),run.organizationId(),run.id(),source.captureId(),source.version(),kind,variant,
                requestKey(run.id(),source.captureId(),kind,variant,null),"复用已选择的透明印花原图，不重新提取或裂变",
                artwork.contentSha256(),json.writeValueAsString(List.of(artwork.storageKey())),artwork.storageKey(),artwork.contentSha256(),
                json.writeValueAsString(Map.of("artworkSource","SAVED_ARTWORK","sourceArtifactId",artwork.id(),"sourceSha256",artwork.contentSha256(),"modelCalls",0)),
                "已有透明印花已关联，未调用生图模型");
        return Result.progress("已复用透明印花 · "+kind+(variant>0?" / V"+variant:"")+"；无需新增生图");
    }

    private Result archiveSurfaceArtwork(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,
                                         String artwork,Map<String,Object> existing,PlatformPrincipal user) {
        if(existing!=null)return new Result(false,"此执行已有其他印花提取请求，不能改换为模板上传印花；请新建创作批次");
        String storage=CommerceSurfaceTemplateInputs.key(user.organizationId(),artwork);
        archive.read(user.organizationId(),storage);
        String key=CommerceImageArchive.sha((run.id()+":"+source.captureId()+":EXTRACT:0").getBytes(StandardCharsets.UTF_8));
        db.update("""
            INSERT IGNORE INTO commerce_batch_artwork_jobs
            (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,message,created_at,updated_at)
            VALUES(?,?,?,?,?,'EXTRACT',0,?,'READY',?,'ARCHIVED_ARTWORK',?,?,?,?,?,NOW(6),NOW(6))
            """,UUID.randomUUID().toString(),run.organizationId(),run.id(),source.captureId(),source.version(),key,
                "复用固定模板中用户选中的上传独立印花，不从商品照片重新提取",CommerceImageArchive.sha(storage.getBytes(StandardCharsets.UTF_8)),
                json.writeValueAsString(List.of(storage)),storage,artwork,"模板上传印花已固定，未调用生图模型");
        return Result.progress("已固定模板上传印花，无需重新提取；继续裂变");
    }

    static String surfacePrompt(String side) {
        return surfacePrompt(side,true);
    }
    static String surfacePrompt(String side,boolean separateArtwork) {
        CommerceSurfaceTemplateInputs.kind(side);
        boolean supplemental = Set.of("TOP", "BOTTOM").contains(side);
        return "生成一张商品的"+Map.of("FRONT","正面","BACK","背面","LEFT","左侧","RIGHT","右侧","TOP","俯视","BOTTOM","仰视").get(side)+"实物套版候选。"
                +"参考图1是用户上传的原始实物图，用于保留该商品真实版型、衣领、袖型、层次和材质；禁止改用通用T恤。"
                +(supplemental ? "参考图2是同一商品的正面纯色图，仅作为版型、颜色和材质参考，不是当前俯视或仰视机位的照片，不得照着图2输出正面。" : "参考图2是同一商品当前方位的纯色图，用于核对该面的结构和颜色。")
                +"参考图3是冻结三维模型上按UV精确替换新印花后的定位图，其机位、各处印花的中心、大小、旋转、朝向、可见遮挡和曲面弯曲为构图依据。"
                +(separateArtwork?"参考图4是本次裂变的新印花。图3已精确替换选中图案，同时保留其他独立图案；严格按图3输出，不要将图4覆盖到其他图案位置。":"参考图3已经包含全部新印花，请完整保留其中印花设计。")
                +"本步骤只做实物套版，印花二次创作已在上一步完成；不得再次更换图案主体、角色、物种、构图、配色或画风。"
                +"同一种新图案在多个位置出现时，保持完全相同的设计，只按各位置已固定的大小、朝向和曲面产生透视及光影变化。"
                +"不增加、镜像或移动印花，不把背面或袖子印花挪到胸前。"
                +"以图3相同方位和画面构图输出，保持原实物版型及无印花区域，不出现人、文字标注、拼图或新logo。";
    }

    private Result prepareScene(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Plan plan,
            CommerceBatchCreativePlanValidator.Selection source,ProductCaptureService.CaptureDetail detail,
            CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs,PlatformPrincipal user) {
        var existing=find(jobs,source.captureId(),CommerceSceneBackgroundAssets.KIND,0);
        try {
            // The first preparation freezes processed source bytes; later turns never re-download or replace them.
            var refs=existing==null?CommerceSceneBackgroundAssets.references(archiveSource(source,detail,user),config,run.organizationId()):referenceKeys(existing);
            if(ready(existing)) {
                CommerceSceneBackgroundAssets.readyKey(plan,config,source,jobs,run.organizationId(),archive,json);
                return null;
            }
            if(existing==null) {
                var model=models.selectCommerceMockupRuntimeConnection(user).orElse(null);
                if(model==null)return new Result(false,"场景背景需要已启用的 Grok 图片模型，请在模型设置中配置");
                refs.forEach(key->archive.read(run.organizationId(),key));
                var cache=new CommerceMockupDerivativeCache(db,archive,json);
                String fp=fingerprint(model),key=CommerceSceneBackgroundAssets.cacheKey(cache,refs,fp,CommerceSceneBackgroundAssets.PROMPT);
                var cached=cache.find(run.organizationId(),key);
                if(cached!=null) {
                    String id=UUID.randomUUID().toString(),request=requestKey(run.id(),source.captureId(),CommerceSceneBackgroundAssets.KIND,0,null);
                    int inserted=db.update("""
                        INSERT IGNORE INTO commerce_batch_artwork_jobs
                        (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,
                         model_config_id,model_fingerprint,reference_keys,result_json,storage_key,content_sha256,message,created_at,updated_at)
                        SELECT ?,?,?,?,?,?,0,?,'READY',?,?,?,?,?,?,?,'已复用共用场景背景，未新增模型调用',NOW(6),NOW(6)
                        FROM commerce_batch_executions e WHERE e.id=? AND e.organization_id=? AND e.status IN ('RUNNING','PAUSING')
                          AND e.lease_token=? AND e.lease_until>NOW(6) AND e.created_by=?
                        """,id,run.organizationId(),run.id(),source.captureId(),source.version(),CommerceSceneBackgroundAssets.KIND,request,
                            CommerceSceneBackgroundAssets.PROMPT,model.id(),fp,json.writeValueAsString(refs),json.writeValueAsString(cached.report()),cached.storageKey(),cached.sha(),
                            run.id(),run.organizationId(),run.leaseToken(),run.createdBy());
                    if(inserted==0)return Result.unknown("执行租约或场景任务已变化，缓存背景已保留，未重复提交模型");
                    db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",id,json.writeValueAsString(cached.report()));
                    return Result.progress("已复用 Grok 场景背景，同商品全部款式与颜色共用；未新增模型调用");
                }
            }
            return advance(run,source,CommerceSceneBackgroundAssets.KIND,0,CommerceSceneBackgroundAssets.PROMPT,()->refs,existing,user);
        } catch(RuntimeException failure) {
            return new Result(false,"场景背景准备未完成："+Objects.toString(failure.getMessage(),"固定素材暂不可读")+"；原任务与已完成印花保留，不会重复提交未知请求");
        }
    }

    private Result advance(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,
            String prompt,java.util.function.Supplier<List<String>> inputs,Map<String,Object> existing,PlatformPrincipal user) {
        return advance(run,source,kind,variant,null,prompt,inputs,existing,user);
    }
    private Result advance(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,
            CommerceSourceColorResolver.Color color,String prompt,java.util.function.Supplier<List<String>> inputs,Map<String,Object> existing,PlatformPrincipal user) {
        var job=existing;
        if(job==null) {
            var model=artworkConnection(kind,user).orElse(null);
            if(model==null)return new Result(false,CommerceSceneBackgroundAssets.KIND.equals(kind)?"场景背景需要已启用的 Grok 图片模型，请在模型设置中配置":isGeneratedPrint(kind)?"透明印花需要已配置并启用的 IMG 2.0 模型，请在模型设置中配置":"当前公司没有可用图片模型");
            List<String> refs;
            try { refs=inputs.get(); } catch(RuntimeException failure) {return new Result(false,"读取或归档参考图失败，未提交模型，可处理后恢复");}
            String key=requestKey(run.id(),source.captureId(),kind,variant,colorKey(color));
            if(CommerceModelDetailGridPolicy.isKind(kind)){
                Integer prior=db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND capture_id=? AND capture_version=? AND kind=? AND variant_index=?",Integer.class,run.id(),run.organizationId(),source.captureId(),source.version(),kind,variant);
                int generation=prior==null?0:prior;
                if(!CommerceModelDetailGridPolicy.usesSourceStyle(prompt)&&generation>=CommerceModelDetailGridPolicy.MAX_ATTEMPTS)return new Result(false,"该详情组已达到自动恢复上限，已完成结果保留；当前请求已结束","FAILED");
                key=CommerceImageArchive.sha((key+":grid-generation:"+generation).getBytes(StandardCharsets.UTF_8));
            }
            if(color==null)db.update("""
                INSERT IGNORE INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,'PREPARED',?,?,?,?,NOW(6),NOW(6))
                """,UUID.randomUUID().toString(),run.organizationId(),run.id(),source.captureId(),source.version(),kind,variant,key,prompt,model.id(),fingerprint(model),json.writeValueAsString(refs));
            else db.update("""
                INSERT IGNORE INTO commerce_batch_artwork_jobs
                (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,color_key,color_label,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,'PREPARED',?,?,?,?,?,?,NOW(6),NOW(6))
                """,UUID.randomUUID().toString(),run.organizationId(),run.id(),source.captureId(),source.version(),kind,variant,key,prompt,model.id(),fingerprint(model),json.writeValueAsString(refs),color.key(),color.label());
            job=db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND request_key=?",run.id(),key);
        }
        String id=(String)job.get("id"),state=(String)job.get("status");
        if(Set.of("TRANSPARENCY_REJECTED","INVALID_IMAGE_REJECTED").contains(state))return retryOrStop(run,job,new Result(false,Objects.toString(job.get("message"),"模型未返回可用的原生透明印花，已保留原始响应，未继续套版"),"FAILED"));
        if(Set.of("SUBMITTING","UNKNOWN").contains(state))return Result.unknown("模型提交结果尚不确定，保留请求 "+id+"，不会重复付费调用");
        if("REJECTED".equals(state)) {
            String message=Objects.toString(job.get("message"),"图片模型拒绝生成：服务商明确拒绝本次请求");
            if(!message.endsWith("本批次已停止，未生成本张图片，不会自动重试"))
                message=message.replaceFirst("；本次请求不会自动重试$","")+"；本批次已停止，未生成本张图片，不会自动重试";
            return retryOrStop(run,job,new Result(false,message,"FAILED"));
        }
        if("READY".equals(state))return Result.progress("已复用归档结果");
        if("RESULT_RECEIVED".equals(state))return collect(job,run,user);
        if(!"PREPARED".equals(state))return Result.unknown("无法识别图片任务状态，停止自动调用");
        var model=(sceneModel(kind)
                ?models.commerceMockupConnectionById(user.organizationId(),(String)job.get("model_config_id"))
                :artworkConnection(kind,user)).orElse(null);
        if(model==null||!Objects.equals(model.id(),job.get("model_config_id"))||!fingerprint(model).equals(job.get("model_fingerprint")))
            return new Result(false,"已固定的图片模型配置已变化，未提交调用；请恢复对应配置后继续");
        List<CommerceDesignImageGateway.Reference> references;
        try { references=referenceKeys(job).stream().map(key->archive.read(user.organizationId(),key)).toList();
            if(CommerceModelDetailGridPolicy.isKind(kind))references=gridReferences(references,(String)job.get("prompt"));
            else if(CommerceModelDetailPolicy.isKind(kind)&&CommerceModelDetailGridPolicy.usesAmbience((String)job.get("prompt"))){
                if(references.size()!=2)throw new IllegalArgumentException("详情纠正需要固定商品与风格参考");
                references=List.of(references.getFirst(),imageReference(CommerceModelDetailGridComposer.styleAmbience(Base64.getDecoder().decode(references.get(1).dataBase64()))));
            }}
        catch(RuntimeException failure) {return new Result(false,"固定参考图不可读，未提交模型，请检查归档后恢复");}
        String claim=ledger.claim(run,id,(String)job.get("request_key"));
        if(claim.equals("LIMIT_REACHED"))return new Result(false,"模型调用次数上限已到，保留已完成结果；不会继续付费生成");
        if(claim.equals("PAUSE_REQUESTED"))return new Result(false,"已暂停，未启动新的模型调用","PAUSED");
        if(!claim.equals("CLAIMED"))return Result.unknown("执行租约或图片提交状态已变化，停止当前调用");
        CommerceDesignImageGateway.Response result;
        try { result=CommerceModelDetailGridPolicy.isKind(kind)
                ?gateway.generateProductDetailGrid((String)job.get("prompt"),id,model,references)
                :sceneModel(kind)
                ?gateway.generateProductPreview((String)job.get("prompt"),id,model,references,true)
                :kind.startsWith("MOCKUP")
                ?gateway.generateProductPreview((String)job.get("prompt"),id,model,references)
                :(kind.equals("EXTRACT")||kind.startsWith("VARIATE"))
                ?gateway.generateTransparentArtwork((String)job.get("prompt"),id,model,references)
                :gateway.generateArtwork((String)job.get("prompt"),id,model,references); }
        catch(CommerceImageRejectedException failure) {
            String detail=Objects.toString(failure.getMessage(),"").replaceAll("[\\r\\n\\t]+"," ").trim();
            if(detail.isEmpty())detail="服务商明确拒绝本次请求";
            if(detail.length()>850)detail=detail.substring(0,850);
            String message="图片模型拒绝生成："+detail+"；本批次已停止，未生成本张图片，不会自动重试";
            var rejection=new LinkedHashMap<String,Object>();
            rejection.put("requestState","REJECTED");rejection.put("failureKind",failure.failureKind());
            rejection.put("providerStatus",failure.providerStatus());
            db.update("UPDATE commerce_batch_artwork_jobs SET status='REJECTED',message=?,result_json=?,updated_at=NOW(6) WHERE id=? AND status='SUBMITTING'",
                    message,json.writeValueAsString(rejection),id);
            if(CommerceModelDetailGridPolicy.isKind(kind)&&modelDetailGrids!=null
                    &&CommerceDetailGenerationRecovery.retryable(rejection)){
                modelDetailGrids.markFailure(run,job,"TEMPORARY_PROVIDER_REJECTION","四宫格服务暂时不可用，自动恢复该组");
                return Result.progress("四宫格服务暂时繁忙，已记录失败，自动恢复该组");
            }
            // A definitive provider refusal ends this attempt. Its reserved call remains part of the original ledger.
            return retryOrStop(run,job,new Result(false,message,"FAILED"));
        }
        catch(RuntimeException failure) {
            boolean detail=CommerceModelDetailPolicy.isKind(kind)||CommerceModelDetailGridPolicy.isKind(kind);
            db.update("UPDATE commerce_batch_artwork_jobs SET status='UNKNOWN',message=?,updated_at=NOW(6) WHERE id=? AND status='SUBMITTING'",detail?"详情请求连接中断，正在自动核对已保存结果并准备恢复":"模型调用结果未知，禁止盲目重复提交",id);
            return Result.unknown(detail?"详情图连接中断，系统正在自动核对原请求并恢复；成功图片保留":"模型结果未知，请核查请求 "+id+"；不会自动重试");
        }
        // Persist a late response even if the worker lease expired: never lose a paid result.
        db.update("UPDATE commerce_batch_artwork_jobs SET status='RESULT_RECEIVED',result_json=?,message='模型结果已保存，等待图片归档',updated_at=NOW(6) WHERE id=? AND status='SUBMITTING'",json.writeValueAsString(result),id);
        return collect(db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE id=?",id),run,user);
    }

    private Result collect(Map<String,Object> job,CommerceBatchExecution run,PlatformPrincipal user) {
        try {
            if(!job.containsKey("result_json")||!job.containsKey("execution_id"))job=db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND organization_id=?",job.get("id"),user.organizationId());
            var response=json.readValue((String)job.get("result_json"),CommerceDesignImageGateway.Response.class);
            boolean grid=CommerceModelDetailGridPolicy.isKind((String)job.get("kind"));
            byte[] bytes;
            if(response!=null&&response.artifact()!=null&&response.artifact().dataBase64()!=null) {
                if(response.artifact().dataBase64().length()>(grid?44_740_000:12_000_000))throw error("图片过大");
                try {bytes=Base64.getDecoder().decode(response.artifact().dataBase64());}
                catch(IllegalArgumentException invalid){return rejectInvalidImage(run,job,"返回的图片编码无效");}
            } else if(response!=null&&response.result()!=null&&response.result().url()!=null)bytes=downloader.download(response.result().url()).bytes();
            else return rejectInvalidImage(run,job,"模型已返回但没有图片内容");
            try {if(grid)bytes=CommerceModelDetailGridComposer.normalizeGenerated(bytes);else CommerceMockupComposer.decode(bytes);}
            catch(IllegalArgumentException invalid){
                if(grid&&modelDetailGrids!=null){modelDetailGrids.markFailure(run,job,"GRID_LAYOUT_INVALID","四宫格输出无法切分，已保存原响应并自动重试");return Result.progress("四宫格输出无效，自动恢复该组");}
                return rejectInvalidImage(run,job,"模型返回的图片为空、损坏或无法解码");}
            // Keep the raw response archived before applying the immutable local-edit policy.
            archive.store(user.organizationId(),bytes);
            if("BLANK".equals(job.get("kind"))||("MOCKUP".equals(job.get("kind"))&&job.get("color_key")==null)) {
                var fixed=plans.forExecution(user.organizationId(),(String)job.get("execution_id"));
                var plan=json.readValue(fixed.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
                List<CommerceArtworkLocalEdit.Region> regions;
                if("BLANK".equals(job.get("kind"))){String captureId=(String)job.get("capture_id");regions=plan.sources().stream().filter(s->s.captureId().equals(captureId)).findFirst().orElseThrow().cleanupAreas();}
                else {var template=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());var config=json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class);regions=CommerceArtworkLocalEdit.placement(config.areas());}
                bytes=CommerceArtworkLocalEdit.preserve(decode(archive.read(user.organizationId(),referenceKeys(job).getFirst())),bytes,regions);
            }
            var stored=archive.store(user.organizationId(),bytes);
            String archiveMessage="图片已归档";
            if("EXTRACT".equals(job.get("kind"))||Objects.toString(job.get("kind"),"").startsWith("VARIATE")){
                var transparency=CommerceArtworkTransparency.inspect(bytes);
                db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",job.get("id"),json.writeValueAsString(Map.of("transparency",transparency)));
                try {CommerceTransparentArtworkPolicy.requireNative(bytes);}
                catch(IllegalArgumentException invalid) {
                    return retryOrStop(run,job,rejectTransparency(job,stored,invalid.getMessage(),"RESULT_RECEIVED"));
                }
                archiveMessage="原生透明印花已归档，透明背景检查通过";
            }
            if(Set.of("EXTRACT","BLANK").contains((String)job.get("kind"))) {
                var fixed=plans.forExecution(user.organizationId(),(String)job.get("execution_id"));
                var fixedPlan=fixed==null?null:json.readValue(fixed.planJson(),CommerceBatchCreativePlanValidator.Plan.class);
                if(CommerceBatchCreativePlanValidator.localPhotoOutput(fixedPlan)) {
                    String kind=(String)job.get("kind");
                    String captureId=(String)job.get("capture_id");
                    var regions=kind.equals("BLANK")?fixedPlan.sources().stream().filter(s->s.captureId().equals(captureId)).findFirst().orElseThrow().cleanupAreas():List.of();
                    var cache=new CommerceMockupDerivativeCache(db,archive,json);
                    String cacheKey=cache.key("photo-input-"+kind+"-v1",List.of(referenceKeys(job),job.get("prompt"),regions));
                    cache.store(user.organizationId(),cacheKey,kind,bytes,Map.of("status","CACHED_INPUT_REVIEW_REQUIRED","productionReady",false,"kind",kind,"sourceJobId",job.get("id")));
                }
            }
            if(CommerceSceneBackgroundAssets.KIND.equals(job.get("kind"))) {
                var cache=new CommerceMockupDerivativeCache(db,archive,json);
                var refs=referenceKeys(job);String fp=(String)job.get("model_fingerprint");
                var sceneReport=CommerceSceneBackgroundAssets.report(refs,fp);
                var cached=cache.store(user.organizationId(),CommerceSceneBackgroundAssets.cacheKey(cache,refs,fp,(String)job.get("prompt")),CommerceSceneBackgroundAssets.KIND,bytes,sceneReport);
                if(cached==null)throw new IllegalStateException("背景缓存未写入");
                // A concurrent earlier batch wins the immutable cache. Preserve the provider response
                // above, while all future compositions use the same committed plate.
                stored=archive.store(user.organizationId(),decode(archive.read(user.organizationId(),cached.storageKey())));
                db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",job.get("id"),json.writeValueAsString(sceneReport));
                archiveMessage="Grok 共用场景背景已归档；同商品全部款式、颜色和方位复用，服装印花本地合成";
            }
            db.update("UPDATE commerce_batch_artwork_jobs SET status='READY',storage_key=?,content_sha256=?,message=?,updated_at=NOW(6) WHERE id=? AND status='RESULT_RECEIVED'",stored.storageKey(),stored.sha256(),archiveMessage,job.get("id"));
            return Result.progress("已归档 "+job.get("kind")+" · 变体 "+job.get("variant_index")+(job.get("color_label")==null?"":" · "+job.get("color_label"))+"，继续处理下一张");
        } catch(RuntimeException failure) {
            String reason=archiveFailureReason(failure);
            String message="模型响应已保存，但图片归档未完成："+reason+"；系统最多自动恢复3次，仅重试归档，不重复调用模型";
            db.update("UPDATE commerce_batch_artwork_jobs SET message=?,updated_at=NOW(6) WHERE id=? AND status='RESULT_RECEIVED'",message,job.get("id"));
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Image archive pending for job {}: {}",job.get("id"),reason);
            return new Result(false,message);
        }
    }

    static String archiveFailureReason(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.net.UnknownHostException)return "图片域名解析失败";
            if(cause instanceof java.net.SocketTimeoutException)return "下载图片超时";
            if(cause instanceof javax.net.ssl.SSLException)return "图片服务器 TLS 连接失败";
            if(cause instanceof java.sql.SQLException sql)return "归档数据库写入失败（"+sql.getSQLState()+"/"+sql.getErrorCode()+"）";
            if(cause instanceof java.nio.file.AccessDeniedException)return "本地图片目录不可写";
        }
        if(failure instanceof com.yingdao.aicommerce.shop.infrastructure.MarketplaceApiException)
            return "模型图片下载失败";
        return "归档处理失败（"+failure.getClass().getSimpleName()+"）";
    }

    private Result validateReadyPrints(CommerceBatchExecution run,List<Map<String,Object>> jobs,PlatformPrincipal user,boolean generatedExtraction) {
        for(var job:jobs) {
            String kind=Objects.toString(job.get("kind"),"");
            if(Set.of("TRANSPARENCY_REJECTED","INVALID_IMAGE_REJECTED").contains(job.get("status")))
                return retryOrStop(run,job,new Result(false,Objects.toString(job.get("message"),"模型未返回可用的原生透明印花，已保留原图，未继续套版"),"FAILED"));
            // Reused masters were already checked against their frozen SHA once per unique asset above.
            if("SAVED_ARTWORK".equals(job.get("model_config_id")))continue;
            if(!ready(job)||!(kind.startsWith("VARIATE")||(generatedExtraction&&kind.equals("EXTRACT")&&!"ARCHIVED_ARTWORK".equals(job.get("model_config_id")))))continue;
            byte[] bytes;
            try {bytes=decode(archive.read(user.organizationId(),(String)job.get("storage_key")));}
            catch(RuntimeException unavailable){return new Result(false,"已归档印花暂不可读取，未继续生成或套版；恢复后复用已有结果");}
            try {CommerceTransparentArtworkPolicy.requireNative(bytes);}
            catch(IllegalArgumentException invalid){return retryOrStop(run,job,rejectTransparency(job,null,invalid.getMessage(),"READY"));}
        }
        return null;
    }

    private Result retryOrStop(CommerceBatchExecution run,Map<String,Object> job,Result stopped) {
        if("SAVED_ARTWORK".equals(job.get("model_config_id"))||automaticArtworkRetry==null||!isGeneratedPrint(Objects.toString(job.get("kind"),"")))return stopped;
        var decision=automaticArtworkRetry.retry(run,job);
        if(decision.queued())return Result.progress(decision.message());
        if(Set.of("UNKNOWN","UNRESOLVED","LEASE_LOST").contains(decision.status()))return Result.unknown(decision.message());
        if(Set.of("PAUSED","PAUSE_REQUESTED").contains(decision.status()))return new Result(false,decision.message(),"PAUSED");
        return new Result(false,stopped.message()+"；"+decision.message(),"FAILED");
    }

    private Result rejectInvalidImage(CommerceBatchExecution run,Map<String,Object> job,String reason) {
        if(!isGeneratedPrint(Objects.toString(job.get("kind"),"")))return new Result(false,"模型响应已保存，但图片归档未完成；恢复仅重试归档，不重复调用模型");
        String message="IMG 2.0 印花输出不完整："+reason+"；原始模型响应已保留，未继续套版";
        db.update("UPDATE commerce_batch_artwork_jobs SET status='INVALID_IMAGE_REJECTED',message=?,updated_at=NOW(6) WHERE id=? AND status='RESULT_RECEIVED'",message,job.get("id"));
        return retryOrStop(run,job,new Result(false,message,"FAILED"));
    }

    private static boolean isGeneratedPrint(String kind) {return "EXTRACT".equals(kind)||kind.startsWith("VARIATE");}
    private static boolean sceneModel(String kind){return CommerceSceneBackgroundAssets.KIND.equals(kind)||CommerceDrapeReuseWorkflow.MASTER.equals(kind)||CommerceModelDetailPolicy.isKind(kind)||CommerceModelDetailGridPolicy.isKind(kind);}
    private static List<CommerceDesignImageGateway.Reference> gridReferences(List<CommerceDesignImageGateway.Reference> references,String prompt){
        if(references.size()<2||references.size()>5)throw new IllegalArgumentException("四宫格需要新商品与1至4张原详情参考");
        var styles=references.subList(1,references.size()).stream().map(r->Base64.getDecoder().decode(r.dataBase64())).toList();
        if(CommerceModelDetailGridPolicy.usesSourceStyle(prompt)){
            var guides=CommerceModelDetailGridPolicy.styles(prompt);
            if(guides.size()!=styles.size())throw new IllegalArgumentException("原详情设计数量不一致");
            for(int i=0;i<styles.size();i++)if(!CommerceImageArchive.sha(styles.get(i)).equals(guides.get(i).sourceSha256()))throw new IllegalArgumentException("原详情设计与参考图不一致");
            return List.of(imageReference(CommerceModelDetailGridComposer.productGrid(Base64.getDecoder().decode(references.getFirst().dataBase64()),styles.size())),
                    imageReference(CommerceModelDetailGridPolicy.requiresTemplateProduct(prompt)
                            ? CommerceModelDetailGridComposer.poseOnlyStyleGrid(styles,guides.stream().map(CommerceModelDetailStyleGuide.Guide::regions).toList())
                            : CommerceModelDetailGridComposer.selectiveStyleGrid(styles,guides.stream().map(CommerceModelDetailStyleGuide.Guide::regions).toList())));
        }
        if(CommerceModelDetailGridPolicy.usesAmbience(prompt))return List.of(
                imageReference(CommerceModelDetailGridComposer.productGrid(Base64.getDecoder().decode(references.getFirst().dataBase64()),styles.size())),
                imageReference(CommerceModelDetailGridComposer.styleAmbienceGrid(styles)));
        byte[] grid=CommerceModelDetailGridComposer.compose(styles);
        grid=CommerceModelDetailGridComposer.normalizeGenerated(grid);
        String mime=grid[0]==(byte)0xff?"image/jpeg":"image/png";
        return List.of(references.getFirst(),new CommerceDesignImageGateway.Reference(mime,Base64.getEncoder().encodeToString(grid)));
    }
    private static CommerceDesignImageGateway.Reference imageReference(byte[] bytes){return new CommerceDesignImageGateway.Reference(bytes[0]==(byte)0xff?"image/jpeg":"image/png",Base64.getEncoder().encodeToString(bytes));}
    private Optional<ModelConfigService.RuntimeModelConnection> artworkConnection(String kind,PlatformPrincipal user) {
        return sceneModel(kind)?models.selectCommerceMockupRuntimeConnection(user)
                :isGeneratedPrint(kind)?models.transparentArtworkConnection(user.organizationId()):models.imageConnection(user.organizationId());
    }

    private Result rejectTransparency(Map<String,Object> job,CommerceImageArchive.Archived stored,String reason,String expected) {
        String message="印花透明检查未通过 · "+job.get("kind")+" / V"+job.get("variant_index")+"："+reason+"；原始结果已保留，未继续套版";
        if(stored==null)db.update("UPDATE commerce_batch_artwork_jobs SET status='TRANSPARENCY_REJECTED',message=?,updated_at=NOW(6) WHERE id=? AND status=?",message,job.get("id"),expected);
        else db.update("UPDATE commerce_batch_artwork_jobs SET status='TRANSPARENCY_REJECTED',storage_key=?,content_sha256=?,message=?,updated_at=NOW(6) WHERE id=? AND status=?",stored.storageKey(),stored.sha256(),message,job.get("id"),expected);
        return new Result(false,message,"FAILED");
    }

    String archiveSource(CommerceBatchCreativePlanValidator.Selection source,ProductCaptureService.CaptureDetail detail,PlatformPrincipal user) {
        var image=detail.images().stream().filter(i->i.getId().equals(source.imageId())).findFirst().orElseThrow();
        if ("PROCESSED".equals(image.getStatus()) && image.getProcessedUrl()!=null && !image.getProcessedUrl().isBlank()) {
            String url=image.getProcessedUrl();
            byte[] prepared;
            if (url.startsWith(ProductWatermarkAssets.PREFIX)) {
                String id=url.substring(ProductWatermarkAssets.PREFIX.length());
                if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("去水印素材地址无效，请重新确认处理结果");
                var rows=db.queryForList("SELECT output_key FROM product_watermark_checks WHERE id=? AND organization_id=? AND capture_id=? AND image_id=? AND status='READY'",id,user.organizationId(),source.captureId(),source.imageId());
                if (rows.size()!=1 || rows.getFirst().get("output_key")==null)
                    throw new IllegalArgumentException("去水印素材未就绪或不属于当前商品，已停止；不会静默改用带水印原图");
                prepared=Base64.getDecoder().decode(archive.read(user.organizationId(),(String)rows.getFirst().get("output_key")).dataBase64());
            } else prepared=downloader.download(url).bytes();
            CommerceMockupComposer.decode(prepared);
            return archive.store(user.organizationId(),prepared).storageKey();
        }
        String cached=sourceArchives.find(source.captureId(),source.imageId(),source.version(),user);if(cached!=null)return cached;
        byte[] bytes=downloader.download(image.getOriginalUrl()).bytes();CommerceMockupComposer.decode(bytes);
        return archive.store(user.organizationId(),bytes).storageKey();
    }
    private Result reusePreparedBase(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,List<String> refs,
                                     String prompt,Object regions,PlatformPrincipal user) {
        var cache=new CommerceMockupDerivativeCache(db,archive,json);
        var entry=cache.find(user.organizationId(),cache.key("photo-input-"+kind+"-v1",List.of(refs,prompt,regions)));
        if(entry==null)return null;
        if(kind.equals("EXTRACT")){
            try {CommerceTransparentArtworkPolicy.requireNative(decode(archive.read(user.organizationId(),entry.storageKey())));}
            catch(IllegalArgumentException invalid){return null;} // Ignore obsolete white-background cache; use this batch's authorized generation.
        }
        return new CommerceLocalPhotoWorkflow(db,archive,json).archiveEntry(run,source,kind,0,null,refs,entry,
                kind.equals("EXTRACT")?"已复用相同原图的印花提取，未重复调用模型":"已复用相同底图与编辑范围的去印花结果，未重复调用模型");
    }
    private String archiveColor(CommerceBatchCreativePlanValidator.Selection source,CommerceSourceColorResolver.Color color,
                                ProductCaptureService.CaptureDetail detail,PlatformPrincipal user) {
        return archiveSource(new CommerceBatchCreativePlanValidator.Selection(source.captureId(),source.version(),color.imageId()),detail,user);
    }
    private List<Map<String,Object>> rows(CommerceBatchExecution run) {return db.queryForList("SELECT id,capture_id,kind,variant_index,status,storage_key,content_sha256,message,reference_keys,prompt,model_config_id,model_fingerprint,request_key,color_key,color_label,capture_version,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.algorithm')) AS detail_algorithm,JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.parentJobId')) AS detail_parent_job_id FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status<>'RETRIED' ORDER BY created_at,id",run.id(),run.organizationId());}
    private List<String> referenceKeys(Map<String,Object> job) {return json.readValue((String)job.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
    private Object checkFor(String job) {var checks=db.queryForList("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?",job);return checks.isEmpty()?null:json.readTree((String)checks.getFirst().get("report_json"));}
    private static Map<String,Object> find(List<Map<String,Object>> rows,String source,String kind,int variant) {return find(rows,source,kind,variant,null);}
    private static Map<String,Object> find(List<Map<String,Object>> rows,String source,String kind,int variant,String colorKey) {return rows.stream().filter(r->source.equals(r.get("capture_id"))&&kind.equals(r.get("kind"))&&((Number)r.get("variant_index")).intValue()==variant&&Objects.equals(colorKey,r.get("color_key"))).findFirst().orElse(null);}
    static String requestKey(String run,String capture,String kind,int variant,String colorKey) {
        return CommerceImageArchive.sha((run+":"+capture+":"+kind+":"+variant+(colorKey==null?"":":color:"+colorKey)).getBytes(StandardCharsets.UTF_8));
    }
    static CommerceSurfaceTemplateInputs.Side preferredSide(Map<String,CommerceSurfaceTemplateInputs.Side> sides) {
        if(sides.isEmpty())throw error("固定模板没有可见印花面，不能生成商品颜色图");
        return sides.containsKey("FRONT")?sides.get("FRONT"):sides.values().iterator().next();
    }
    private static List<CommerceSourceColorResolver.Color> outputColors(CommerceBatchCreativePlanValidator.Selection source,boolean colors) {
        return colors?source.colorTargets():Collections.singletonList(null);
    }
    private static String colorKey(CommerceSourceColorResolver.Color color) {return color==null?null:color.key();}
    private static String colorSuffix(CommerceSourceColorResolver.Color color) {return color==null?"":" · "+color.label();}
    private static boolean hasGuide(Map<String,Object> job) {return !CommerceLocalPhotoWorkflow.POLICY.equals(job.get("model_config_id"))&&(CommerceSurfaceTemplateInputs.isSurfaceJob((String)job.get("kind"))||("MOCKUP".equals(job.get("kind"))&&job.get("color_key")!=null));}
    private static int guideIndex(Map<String,Object> job) {return CommerceSurfaceTemplateInputs.isSurfaceJob((String)job.get("kind"))?2:1;}
    static String colorPrompt(CommerceSourceColorResolver.Color color,boolean surface) {
        return "本次按来源商品已固定的 SKU 颜色生成一张商品照片，颜色标识「"+color.label()+"」。"
                +"参考图"+(surface?4:3)+"仅作为衣服底色的色样，不作为版型、印花、人物或构图参考；真实版型必须以用户模板和前面的定位图为准。"
                +"唯一额外授权的修改是把衣服底色匹配这张 SKU 色样，覆盖前文保留原底色的要求；保留布料纹理和褶皱，不改变背景颜色。"
                +"同款各颜色必须使用定位图中完全相同的新印花、位置和尺寸，不改变印花自身配色，不增加新颜色款式，不输出色卡、多视角或拼图；仅输出当前颜色的一张商品图。";
    }
    private static final String COLOR_LIMITATION="本次已授权按 SKU 改变衣服底色；区域外像素差及颜色定位统计可能包含授权换色，不代表印花越界或失败。仍需核对版型、印花位置和颜色，不能自动批准上架。";
    static CommerceSurfaceMockupComposer.Report colorReport(CommerceSurfaceMockupComposer.Report report) {
        return new CommerceSurfaceMockupComposer.Report("SURFACE_COLOR_REVIEW_REQUIRED",report.outsideChangedPixels(),report.outsideComparedPixels(),
                report.outsideChangedRatio(),report.exactGuideAvailable(),false,COLOR_LIMITATION+report.limitation());
    }
    static CommerceMockupPositionCheck.Report colorReport(CommerceMockupPositionCheck.Report report) {
        return new CommerceMockupPositionCheck.Report("COLOR_REVIEW_REQUIRED",report.outsideChangedPixels(),report.outsideComparedPixels(),report.outsideChangedRatio(),
                report.channelThreshold(),report.marginPixels(),false,COLOR_LIMITATION+report.limitation(),report.colorLocalization());
    }
    private static boolean ready(Map<String,Object> row) {return row!=null&&"READY".equals(row.get("status"));}
    private static byte[] decode(CommerceDesignImageGateway.Reference ref) {return Base64.getDecoder().decode(ref.dataBase64());}
    static String groupVariationPrompt(int variant,int group) {
        CommerceSurfaceTemplateInputs.variationKind(group);
        return "这是第 "+variant+" 款的图案组 "+group+"，独立设计一个新主体或新构图；同组各位置共用本次一张设计。"
                +"参考图是来源商品提取的原始印花，继承其画风，主体可充分二次创作。"+variationPrompt(variant);
    }
    static String variationPrompt(int index) {return "这是第 "+index+" 张独立印花变体。"+CommerceSampleService.printPrompt("PRINT_VARIANT",1+(index-1)%3);}
    static String fingerprint(ModelConfigService.RuntimeModelConnection model) {return CommerceImageArchive.sha((model.id()+"\n"+model.baseUrl()+"\n"+model.modelId()+"\n"+model.apiFormat()+"\n"+model.provider()).getBytes(StandardCharsets.UTF_8));}
    private static BusinessException error(String message) {return new BusinessException(HttpStatus.CONFLICT,message);}
}
