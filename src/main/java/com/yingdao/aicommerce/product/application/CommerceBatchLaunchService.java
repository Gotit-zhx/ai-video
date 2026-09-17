package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/** Atomically saves the configured draft and queues its existing artwork workflow. */
@Service
public class CommerceBatchLaunchService {
    public record Request(String name,String templateSnapshotId,List<CommerceBatchService.Target> targets,
                          String printTemplateSnapshotId,boolean confirmed,
                          List<CommerceBatchPublicationOptions.PublicationRequest> publicationRequests,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<CommerceBatchCreativePlanValidator.ReusedDesign> reusedArtworks,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String renderPolicy) {
        public Request(String name,String templateSnapshotId,List<CommerceBatchService.Target> targets,String printTemplateSnapshotId,boolean confirmed,List<CommerceBatchPublicationOptions.PublicationRequest> publicationRequests,List<CommerceBatchCreativePlanValidator.ReusedDesign> reusedArtworks){this(name,templateSnapshotId,targets,printTemplateSnapshotId,confirmed,publicationRequests,reusedArtworks,null);}
        public Request(String name,String templateSnapshotId,List<CommerceBatchService.Target> targets,String printTemplateSnapshotId,boolean confirmed,List<CommerceBatchPublicationOptions.PublicationRequest> publicationRequests){this(name,templateSnapshotId,targets,printTemplateSnapshotId,confirmed,publicationRequests,null);}
        public Request(String name,String templateSnapshotId,List<CommerceBatchService.Target> targets,String printTemplateSnapshotId,boolean confirmed){this(name,templateSnapshotId,targets,printTemplateSnapshotId,confirmed,null);}
    }
    public record Result(CommerceBatchService.View batch,CommerceBatchExecution execution,int maxModelCalls) {}

    private final CommerceBatchService batches;
    private final CommerceBatchCreativePlanService plans;
    private final CommerceBatchExecutionService executions;
    private final CommerceImageArchive archive;
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceBatchPublicationOptions publicationOptions;

    public CommerceBatchLaunchService(CommerceBatchService batches,CommerceBatchCreativePlanService plans,
            CommerceBatchExecutionService executions,CommerceImageArchive archive) {
        this.batches=batches;this.plans=plans;this.executions=executions;this.archive=archive;
    }

    @Transactional
    public Result launch(Request request,String key,PlatformPrincipal user) {
        if(user==null||user.organizationId()==null||user.organizationId().isBlank()
                ||!user.permissionCodes().contains("product:edit")||!user.permissionCodes().contains("product:publish"))
            throw new BusinessException(HttpStatus.FORBIDDEN,"创建并开始裂变需要商品编辑与发布权限");
        if(request==null||!request.confirmed())throw bad("请明确确认开始图片生成");
        try {CommerceBatchCreativePlanValidator.validateRenderPolicy(request.renderPolicy());}
        catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
        var batch=batches.create(new CommerceBatchService.CreateRequest(request.name(),request.templateSnapshotId(),
                request.targets(),request.printTemplateSnapshotId(),request.reusedArtworks(),request.renderPolicy()),key,user);
        // The original request key also protects retries after the response was lost. Never restart or replace a run.
        var existing=executions.get(batch.id(),user);
        if(existing!=null)return new Result(batch,existing,executions.limit(batch.id(),user).maxCalls());
        if(publicationOptions!=null){
            publicationOptions.save(batch.id(),request.publicationRequests(),user);
            publicationOptions.requireReady(batch.id(),user);
        }
        var configuration=plans.get(batch.id(),user);
        if(configuration==null||configuration.plan()==null||configuration.version()<1
                ||configuration.plan().printTemplateSnapshotId()==null||configuration.printTemplateConfiguration()==null)
            throw bad("请先选择配置好的商品印花模板后开始裂变");
        int calls=requiredModelCalls(configuration.plan(),configuration.printTemplateConfiguration(),user.organizationId());
        var execution=executions.start(batch.id(),true,calls,configuration.version(),user);
        return new Result(batch,execution,calls);
    }

    int requiredModelCalls(CommerceBatchCreativePlanValidator.Plan plan,
            CommercePrintTemplateService.Configuration configuration,String organizationId) {
        if(plan.sources()==null||plan.sources().isEmpty()||plan.sources().size()>500
                ||plan.sources().stream().anyMatch(source->source==null||source.captureId()==null)
                ||new HashSet<>(plan.sources().stream().map(CommerceBatchCreativePlanValidator.Selection::captureId).toList()).size()!=plan.sources().size()
                ||plan.variantsPerPrint()<1||plan.variantsPerPrint()>20)
            throw bad("创作配置的来源或款式数量无效，请重新保存");
        CommercePrintTemplateService.validate(configuration);
        try{CommerceBatchCreativePlanValidator.validateFrozenArtworks(plan,configuration,organizationId);}
        catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
        long sources=plan.sources().size(),variants=plan.variantsPerPrint(),calls,printCalls;
        long colorCount=0;
        try {
            CommerceBatchCreativePlanValidator.validateOutputPolicy(plan.outputPolicy());
            CommerceBatchCreativePlanValidator.validateRenderTemplate(plan.renderPolicy(),configuration);
            if(CommerceBatchCreativePlanValidator.localPhotoOutput(plan)&&!CommerceBatchCreativePlanValidator.sourceColorOutput(plan))
                throw new IllegalArgumentException("本地套版需要固定每色出图规则，请重新保存创作配置");
            if(CommerceBatchCreativePlanValidator.sourceColorOutput(plan))for(var source:plan.sources()) {
                CommerceBatchCreativePlanValidator.validateColorTargets(source,null);
                colorCount+=source.colorTargets().size();
            }
        } catch(IllegalArgumentException failure) {throw bad(failure.getMessage());}
        if(CommerceSurfaceTemplateInputs.enabled(configuration)) {
            int faces;
            try {
                if(CommerceBatchCreativePlanValidator.localPhotoOutput(plan)) {
                    var primary=CommerceSurfaceTemplateInputs.loadPrimary(configuration,archive,organizationId);
                    if(CommerceBatchCreativePlanValidator.drapeOutput(plan))CommerceDrapeReuseWorkflow.requireWhiteTemplate(CommerceSurfaceTemplateInputs.bytes(archive.read(organizationId,primary.photoKey())));
                    if(CommerceBatchCreativePlanValidator.drapeOutput(plan)&&!primary.visiblePrintIds().equals(java.util.Set.of(configuration.photoPrints().stream().filter(print->"FRONT".equals(print.side())).findFirst().orElseThrow().id())))throw new IllegalArgumentException("母图复用的正面位置图必须准确绑定唯一正面印花");
                    if(CommercePrintTemplateService.photoLayout(configuration)) {
                        if(CommerceBatchCreativePlanValidator.sceneOutput(plan))CommerceSceneBackgroundAssets.validateGeometry(configuration,organizationId,archive);
                        for(var face:CommerceSurfaceTemplateInputs.load(configuration,archive,organizationId).values()) {
                            byte[] photo=CommerceSurfaceTemplateInputs.bytes(archive.read(organizationId,face.photoKey()));
                            CommercePhotoMockupComposer.inspectPhotoMapping(face.side(),photo,face.blank(),face.uv(),configuration.renderPrintIds());
                        }
                    } else CommercePhotoMockupComposer.inspectMapping(CommerceSurfaceTemplateInputs.bytes(archive.read(organizationId,primary.photoKey())),primary.blank(),primary.uv(),configuration.renderPrintIds());
                    faces=1;
                } else faces=CommerceSurfaceTemplateInputs.load(configuration,archive,organizationId).size();
            }
            catch(BusinessException failure) {throw failure;}
            catch(RuntimeException failure) {throw bad("商品套版模板预检未通过："+failure.getMessage()+"；尚未启动图片生成");}
            if(CommerceBatchCreativePlanValidator.savedArtwork(plan))return (CommerceBatchCreativePlanValidator.sceneOutput(plan)||CommerceBatchCreativePlanValidator.drapeOutput(plan))?plan.sources().size():0;
            boolean sourceStyle=CommerceBatchCreativePlanValidator.sourceProductStyle(plan);
            int groups=sourceStyle?CommerceSurfaceTemplateInputs.artworkGroups(configuration).size():1;
            if(!sourceStyle)CommerceSurfaceTemplateInputs.variationArtwork(configuration,plan.variationArtworkId());
            printCalls=sources*((sourceStyle?1:0)+variants*groups);
            calls=CommerceBatchCreativePlanValidator.localPhotoOutput(plan)
                    ?sources*((sourceStyle?1:0)+variants*groups)
                    :CommerceBatchCreativePlanValidator.sourceColorOutput(plan)
                    ?sources+variants*(sources*groups+colorCount)
                    :sources*((sourceStyle?1:0)+variants*(groups+faces));
        } else {
            printCalls=sources*(1+variants);
            if(configuration.areas().stream().anyMatch(area->!"FRONT".equals(area.side())))
                throw bad("当前自动创作的平面模板仅支持正面印花，请选择正面模板或完整三维模板");
            if(CommerceBatchCreativePlanValidator.localPhotoOutput(plan)&&configuration.baseImages().get("FRONT")!=null) {
                try {CommercePhotoMockupComposer.foregroundMask(CommerceSurfaceTemplateInputs.bytes(archive.read(organizationId,CommerceSurfaceTemplateInputs.key(organizationId,configuration.baseImages().get("FRONT")))));}
                catch(RuntimeException failure) {throw bad("固定照片预检未通过："+failure.getMessage()+"；尚未启动图片生成");}
            }
            calls=CommerceBatchCreativePlanValidator.localPhotoOutput(plan)
                    ?sources*(1+variants+(configuration.baseImages().get("FRONT")==null?1:0))
                    :CommerceBatchCreativePlanValidator.sourceColorOutput(plan)
                    ?sources*(1+(configuration.baseImages().get("FRONT")==null?1:0))+variants*(sources+colorCount)
                    :sources*(1+2*variants+(configuration.baseImages().get("FRONT")==null?1:0));
        }
        // Reserve room for at most three attempts per logical print. Product photos,
        // blank garments and compositions retain their original single-call budget.
        calls+=2*printCalls;
        if(CommerceBatchCreativePlanValidator.sceneOutput(plan)||CommerceBatchCreativePlanValidator.drapeOutput(plan))calls+=sources;
        if(calls>1000)throw bad("本批含印花重试的调用上限为 "+calls+" 次，超过单批1000次上限；请减少商品或裂变款式数量后重试");
        return (int)calls;
    }

    private static BusinessException bad(String message) {return new BusinessException(HttpStatus.CONFLICT,message);}
}
