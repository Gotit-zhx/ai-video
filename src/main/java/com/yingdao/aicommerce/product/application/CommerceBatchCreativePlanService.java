package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.product.application.CommerceBatchCreativePlanValidator.*;

@Service
public class CommerceBatchCreativePlanService {
    public record Request(int expectedVersion, String printTemplateSnapshotId,
                          List<Selection> sources, int variantsPerPrint,String variationArtworkId,String artworkSource,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<ReusedDesign> reusedArtworks,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String renderPolicy) {
        public Request(int expectedVersion,String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource,List<ReusedDesign> reusedArtworks){this(expectedVersion,printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,artworkSource,reusedArtworks,null);}
        public Request(int expectedVersion,String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId,String artworkSource){this(expectedVersion,printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,artworkSource,null);}
        public Request(int expectedVersion,String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint,String variationArtworkId){this(expectedVersion,printTemplateSnapshotId,sources,variantsPerPrint,variationArtworkId,null);}
        public Request(int expectedVersion,String printTemplateSnapshotId,List<Selection> sources,int variantsPerPrint){this(expectedVersion,printTemplateSnapshotId,sources,variantsPerPrint,null,null);}
    }
    public record Image(String id, String url, String role) {}
    public record SourceOption(String captureId, long version, long currentVersion, String title, List<Image> images) {}
    public record View(int version, Plan plan, List<SourceOption> sources, boolean locked,
                       CommercePrintTemplateService.Configuration printTemplateConfiguration) {
        public View(int version,Plan plan,List<SourceOption> sources,boolean locked){this(version,plan,sources,locked,null);}
    }
    @org.springframework.beans.factory.annotation.Autowired
    private CommerceArtworkLibraryService artworkLibrary;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate db;
    private final CommerceBatchMapper batches;
    private final CommerceBatchExecutionMapper runs;
    private final CommerceBatchCreativePlanMapper plans;
    private final CommerceTemplateMapper templates;
    private final ProductCaptureService captures;
    private final ObjectMapper json;

    public CommerceBatchCreativePlanService(CommerceBatchMapper batches, CommerceBatchExecutionMapper runs,
            CommerceBatchCreativePlanMapper plans, CommerceTemplateMapper templates,
            ProductCaptureService captures, ObjectMapper json) {
        this.batches=batches; this.runs=runs; this.plans=plans; this.templates=templates;
        this.captures=captures; this.json=json;
    }

    public View get(String batch, PlatformPrincipal user) {
        access(user);
        if (batches.get(user.organizationId(),batch)==null) throw conflict("批次不存在");
        var sources=sources(batch,user);
        var run=runs.get(user.organizationId(),batch);
        var row=run==null?plans.latest(user.organizationId(),batch):plans.forExecution(user.organizationId(),run.id());
        var plan=row==null?null:decode(row);
        return new View(row==null?0:row.version(),plan,sources,run!=null,configuration(plan,user));
    }

    @Transactional
    public View save(String batch, Request request, PlatformPrincipal user) {
        access(user);
        var found=batches.lock(user.organizationId(),batch);
        if (found==null) throw conflict("批次不存在");
        if (runs.get(user.organizationId(),batch)!=null||!"DRAFT".equals(found.status()))
            throw conflict("批次已启动或已取消，不能修改创作配置");
        var sourceState=sourceState(batch,user);
        var options=sourceState.options();
        var previous=plans.latest(user.organizationId(),batch);
        int version=previous==null?0:previous.version();
        if (request==null||request.expectedVersion()!=version||version==Integer.MAX_VALUE)
            throw conflict("创作配置已变化，请重新读取后保存");
        // Detail references are derived from owned source records, never from client-supplied IDs.
        var requestedSources=request.sources()==null?null:request.sources().stream().map(source->source==null?null:
                new Selection(source.captureId(),source.version(),source.imageId(),source.cleanupAreas(),source.colorTargets())).toList();
        var validated=validatePlan(request.printTemplateSnapshotId(),requestedSources,request.variantsPerPrint(),request.variationArtworkId(),request.artworkSource(),null,options,user);
        // Client-supplied color targets are never authoritative. A draft save freezes
        // the current, access-checked SKU evidence for the batch's fixed source version.
        var plan=freezeColors(freezeReused(validated,request.reusedArtworks(),user),sourceState,user,request.renderPolicy());
        validateFrozenArtworks(plan,configuration(plan,user),user.organizationId());
        var row=new CommerceBatchCreativePlanMapper.Row(UUID.randomUUID().toString(),user.organizationId(),batch,
                version+1,json.writeValueAsString(plan),user.userId(),Instant.now());
        plans.insert(row);
        return new View(row.version(),plan,options,false,configuration(plan,user));
    }

    // Called within start's transaction while holding the same batch row lock as save.
    public CommerceBatchCreativePlanMapper.Row prepareStart(String batch, int expectedVersion, PlatformPrincipal user) {
        access(user);
        var row=plans.latest(user.organizationId(),batch);
        if (row==null||expectedVersion<1||row.version()!=expectedVersion)
            throw conflict("请先保存并确认当前创作配置，配置变化后需重新读取");
        var plan=decode(row);
        try {validateRenderPolicy(plan.renderPolicy());}
        catch(IllegalArgumentException failure) {throw conflict(failure.getMessage());}
        var sourceState=sourceState(batch,user);
        validatePlan(plan.printTemplateSnapshotId(),plan.sources(),plan.variantsPerPrint(),plan.variationArtworkId(),plan.artworkSource(),plan.outputPolicy(),sourceState.options(),user);
        for(var source:plan.sources())if(!source.detailImageIds().isEmpty()) {
            var available=detailImageIds(sourceState.details().get(source.captureId()),user);
            if(!available.containsAll(source.detailImageIds()))throw conflict("固定详情图已不可用或被识别为尺码表，请重新保存创作配置；尚未开始生成");
        }
        var fixedTemplate=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());
        var configuration=json.readValue(fixedTemplate.configJson(),CommercePrintTemplateService.Configuration.class);
        try {validateRenderTemplate(plan.renderPolicy(),configuration);}
        catch(IllegalArgumentException failure){throw conflict(failure.getMessage());}
        validateFrozenArtworks(plan,configuration,user.organizationId());
        if(savedArtwork(plan))for(var design:plan.reusedArtworks())for(var frozen:design.groupArtifacts().values()){
            var current=artworkLibrary.resolve(frozen.id(),user);
            if(!frozen.storageKey().equals(current.storageKey())||!frozen.contentSha256().equals(current.contentSha256()))throw conflict("所选已有印花与固定图片指纹不一致，尚未开始执行");
        }
        if(CommerceSurfaceTemplateInputs.enabled(configuration)) CommerceSurfaceTemplateInputs.validate(configuration);
        else if(configuration.baseImages().get("FRONT")==null&&plan.sources().stream().anyMatch(s->s.cleanupAreas().isEmpty()))
            throw conflict("请标记每个来源的旧印花范围后保存配置，或选用已上传底图的模板；尚未启动或占用模型调用");
        return row;
    }

    public void bind(String execution, CommerceBatchCreativePlanMapper.Row row) { plans.bind(execution,row.id()); }

    /** Draft creation calls this in the same transaction; the source remains editable before starting. */
    public View initialize(String batch,String printSnapshot,int variants,PlatformPrincipal user) {
        return initialize(batch,printSnapshot,variants,null,user);
    }
    public View initialize(String batch,String printSnapshot,int variants,List<ReusedDesign> reusedArtworks,PlatformPrincipal user) {
        return initialize(batch,printSnapshot,variants,reusedArtworks,null,user);
    }
    public View initialize(String batch,String printSnapshot,int variants,List<ReusedDesign> reusedArtworks,String renderPolicy,PlatformPrincipal user) {
        access(user);
        var selected=sources(batch,user).stream().map(source->{
            var image=source.images().stream().filter(i->"MAIN".equals(i.role())).findFirst()
                    .orElseGet(()->source.images().stream().findFirst()
                            .orElseThrow(()->conflict("来源商品没有可提取的原图，请补齐图片后重试")));
            return new Selection(source.captureId(),source.version(),image.id());
        }).toList();
        return save(batch,new Request(0,printSnapshot,selected,reusedArtworks==null?variants:reusedArtworks.size(),null,reusedArtworks==null?"SOURCE_PRODUCT":"SAVED_ARTWORK",reusedArtworks,renderPolicy),user);
    }

    private Plan validatePlan(String snapshot, List<Selection> selected, int variants,String variationArtworkId,String artworkSource,String outputPolicy,
                              List<SourceOption> options, PlatformPrincipal user) {
        var template=snapshot==null?null:templates.snapshot(user.organizationId(),snapshot);
        if (template==null) throw conflict("所选商品套版模板版本不存在或无权访问");
        for (var source:options) if (source.currentVersion()!=source.version())
            throw conflict("来源版本已变化，请重新准备批次");
        var configuration=json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class);
        if(CommerceSurfaceTemplateInputs.enabled(configuration)) CommerceSurfaceTemplateInputs.validate(configuration);
        try {
            var plan=validate(snapshot,template.kind(),options.stream().map(s->new Source(s.captureId(),s.version(),
                    s.images().stream().map(Image::id).toList())).toList(),selected,variants);
            validateArtworkSource(artworkSource);
            validateOutputPolicy(outputPolicy);
            boolean sourceStyle=Set.of("SOURCE_PRODUCT","SAVED_ARTWORK").contains(Objects.toString(artworkSource,""));
            if(sourceStyle&&variationArtworkId!=null)throw conflict("来源商品画风模式按全部位置标记分组，不单选模板素材");
            if(sourceStyle&&CommerceSurfaceTemplateInputs.enabled(configuration)&&CommercePrintTemplateService.placementBindings(configuration).isEmpty())
                throw conflict("请先在四面图上放置印花位置标记并保存模板");
            String fixedArtwork=!sourceStyle&&CommerceSurfaceTemplateInputs.enabled(configuration)
                    ?CommerceSurfaceTemplateInputs.variationArtwork(configuration,variationArtworkId):null;
            if(!CommerceSurfaceTemplateInputs.enabled(configuration)&&variationArtworkId!=null)
                throw conflict("当前平面模板没有对应的上传印花，请重新选择创作配置");
            var result=new Plan(plan.printTemplateSnapshotId(),plan.sources(),plan.variantsPerPrint(),fixedArtwork,artworkSource,outputPolicy);
            if(sourceColorOutput(result))for(var source:result.sources()) {
                var readable=options.stream().filter(option->option.captureId().equals(source.captureId())).findFirst().orElseThrow();
                validateColorTargets(source,readable.images().stream().map(Image::id).toList());
            }
            return result;
        } catch (IllegalArgumentException e) { throw conflict(e.getMessage()); }
    }

    private Plan freezeReused(Plan plan,List<ReusedDesign> requested,PlatformPrincipal user) {
        if(!savedArtwork(plan)){
            if(requested!=null&&!requested.isEmpty())throw conflict("已选印花素材与创作模式不一致");
            return plan;
        }
        try {validateReusedDesigns(requested,configuration(plan,user));}
        catch(IllegalArgumentException failure){throw conflict(failure.getMessage());}
        if(requested.size()!=plan.variantsPerPrint())throw conflict("复用款数须与选择的印花组合数量一致");
        if(artworkLibrary==null)throw conflict("印花素材库暂不可用，请稍后重试");
        var frozen=new ArrayList<FrozenDesign>();var resolved=new HashMap<String,FrozenArtwork>();
        for(var selection:requested){
            var groups=new TreeMap<String,FrozenArtwork>();
            selection.groupArtifacts().forEach((group,id)->groups.put(group,resolved.computeIfAbsent(id,key->{
                var artwork=artworkLibrary.resolve(key,user);
                return new FrozenArtwork(artwork.id(),artwork.storageKey(),artwork.contentSha256());
            })));
            frozen.add(new FrozenDesign(groups));
        }
        return new Plan(plan.printTemplateSnapshotId(),plan.sources(),plan.variantsPerPrint(),null,"SAVED_ARTWORK",plan.outputPolicy(),plan.renderPolicy(),frozen);
    }

    private Plan freezeColors(Plan plan,SourceState state,PlatformPrincipal user,String requestedPolicy) {
        try {
            var template=configuration(plan,user);
            String policy=requestedPolicy==null?(CommercePrintTemplateService.photoLayout(template)?GROK_SCENE_POLICY:LOCAL_PHOTO_POLICY):requestedPolicy;
            validateRenderTemplate(policy,template);
            var selected=new ArrayList<Selection>();
            for(var source:plan.sources()) {
                var detail=state.details().get(source.captureId());
                var colors=CommerceSourceColorResolver.resolve(detail);
                if(colors.size()==1&&CommerceSourceColorResolver.DEFAULT_KEY.equals(colors.getFirst().key())) {
                    var color=colors.getFirst();
                    colors=List.of(new CommerceSourceColorResolver.Color(color.key(),color.label(),color.skuKeys(),source.imageId(),true));
                }
                var frozen=new Selection(source.captureId(),source.version(),source.imageId(),source.cleanupAreas(),colors,
                        modelDetailsSupported(template)?detailImageIds(detail,user):List.of());
                var readable=state.options().stream().filter(option->option.captureId().equals(source.captureId())).findFirst().orElseThrow();
                validateColorTargets(frozen,readable.images().stream().map(Image::id).toList());
                selected.add(frozen);
            }
            return new Plan(plan.printTemplateSnapshotId(),selected,plan.variantsPerPrint(),plan.variationArtworkId(),plan.artworkSource(),"SOURCE_SKU_COLORS",policy,plan.reusedArtworks());
        } catch(IllegalArgumentException failure) {throw conflict(failure.getMessage());}
    }

    /** One generated detail per owned source detail. Keep chart evidence separate and unmodified. */
    private List<String> detailImageIds(ProductCaptureService.CaptureDetail detail,PlatformPrincipal user) {
        var charts=new HashSet<String>();
        if(detail.normalizedData()!=null&&detail.normalizedData().get("sizeChartImageId") instanceof String id)charts.add(id);
        if(db!=null&&detail.capture().getId()!=null) {
            charts.addAll(db.queryForList("SELECT DISTINCT image_id FROM product_watermark_checks WHERE organization_id=? AND capture_id=? AND status='READY' AND JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType'))='SIZE_CHART'",
                    String.class,user.organizationId(),detail.capture().getId()));
        }
        var ids=new LinkedHashSet<String>();var assets=new HashSet<String>();
        var candidates=detail.images().stream().filter(Objects::nonNull)
                .filter(image->Objects.equals(detail.capture().getId(),image.getCaptureId())
                        &&Objects.equals(detail.capture().getOrganizationId(),image.getOrganizationId()))
                .sorted(Comparator.comparingInt(com.yingdao.aicommerce.product.domain.ProductCaptureImage::getSortOrder)).toList();
        var chartAssets=new HashSet<String>();
        for(var image:candidates)if(sizeChart(image,charts)&&image.getOriginalUrl()!=null)
            chartAssets.add(ProductCaptureImagePlan.assetKey(image.getOriginalUrl()));
        for(var image:candidates) {
            String role=Objects.toString(image.getRoleName(),"").trim().toUpperCase(Locale.ROOT);
            if(!Set.of("DETAIL","DESCRIPTION").contains(role)||sizeChart(image,charts))continue;
            String url=image.getOriginalUrl();
            if(image.getId()==null||image.getId().isBlank()||url==null||url.isBlank())continue;
            String asset=ProductCaptureImagePlan.assetKey(url);
            if(!chartAssets.contains(asset)&&assets.add(asset))ids.add(image.getId());
        }
        return List.copyOf(ids);
    }
    private static boolean sizeChart(com.yingdao.aicommerce.product.domain.ProductCaptureImage image,Set<String> detected){
        if("SIZE_CHART".equalsIgnoreCase(image.getRoleName())||detected.contains(image.getId()))return true;
        String label=Objects.toString(image.getAltText(),"").trim();
        return label.length()<=60&&label.matches("(?i)^(?:size[ _-]*(?:chart|guide)|measurement[ _-]*chart|尺码表|尺碼表|尺寸表|尺码指南|尺碼指南)(?:\\s*[（(](?:cm|mm|inch|inches|厘米|英寸)[）)])?$");
    }

    private record SourceState(List<SourceOption> options,Map<String,ProductCaptureService.CaptureDetail> details) {}
    private List<SourceOption> sources(String batch,PlatformPrincipal user) {return sourceState(batch,user).options();}
    private SourceState sourceState(String batch, PlatformPrincipal user) {
        var result=new LinkedHashMap<String,SourceOption>();
        var details=new LinkedHashMap<String,ProductCaptureService.CaptureDetail>();
        for (var item:batches.items(user.organizationId(),batch)) {
            var previous=result.get(item.captureId());
            if (previous!=null) {
                if (previous.version()!=item.captureVersion()) throw conflict("同一商品的批次来源版本不一致");
                continue;
            }
            var detail=captures.get(item.captureId(),user);
            details.put(item.captureId(),detail);
            result.put(item.captureId(),new SourceOption(item.captureId(),item.captureVersion(),detail.capture().getVersion(),
                    detail.capture().getTitle(),detail.images().stream()
                    .filter(Objects::nonNull)
                    .filter(i->Objects.equals(detail.capture().getId(),i.getCaptureId())
                            &&Objects.equals(detail.capture().getOrganizationId(),i.getOrganizationId()))
                    .filter(i->i.getOriginalUrl()!=null&&!i.getOriginalUrl().isBlank())
                    .map(i->new Image(i.getId(),i.getOriginalUrl(),i.getRoleName())).toList()));
        }
        return new SourceState(List.copyOf(result.values()),Map.copyOf(details));
    }

    private Plan decode(CommerceBatchCreativePlanMapper.Row row) { return json.readValue(row.planJson(),Plan.class); }
    private CommercePrintTemplateService.Configuration configuration(Plan plan,PlatformPrincipal user) {
        if(plan==null)return null;
        var snapshot=templates.snapshot(user.organizationId(),plan.printTemplateSnapshotId());
        return snapshot==null?null:json.readValue(snapshot.configJson(),CommercePrintTemplateService.Configuration.class);
    }
    private static void access(PlatformPrincipal user) {
        if (user==null||user.organizationId()==null||!user.permissionCodes().contains("product:publish"))
            throw new BusinessException(HttpStatus.FORBIDDEN,"无批次执行权限");
    }
    private static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,message); }
}
