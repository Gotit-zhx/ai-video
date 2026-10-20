package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import static com.yingdao.aicommerce.product.application.CommerceBatchStageHandler.Result;

/** One local immutable derivative per worker turn. No image model or model-budget dependency. */
final class CommerceLocalPhotoWorkflow {
    static final String POLICY=CommerceBatchCreativePlanValidator.LOCAL_PHOTO_POLICY;
    record Produced(byte[] png,Object report) {}
    interface ColorSource {String archive(CommerceSourceColorResolver.Color color);}
    private final CommerceGarmentForeground foreground;
    private final JdbcTemplate db;
    private final CommerceImageArchive archive;
    private final ObjectMapper json;
    private final CommerceMockupDerivativeCache cache;
    CommerceLocalPhotoWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json) {
        this(db,archive,json,null);
    }
    CommerceLocalPhotoWorkflow(JdbcTemplate db,CommerceImageArchive archive,ObjectMapper json,CommerceGarmentForeground foreground) {
        this.foreground=foreground;this.db=db;this.archive=archive;this.json=json;this.cache=new CommerceMockupDerivativeCache(db,archive,json);
    }

    Result prepareColors(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String base,
                         List<Map<String,Object>> jobs,ColorSource colorSource) {
        try {
            for(var color:source.colorTargets()) {
                if(ready(find(jobs,source.captureId(),"BASE_COLOR",0,color.key())))continue;
                String swatch=colorSource.archive(color);
                boolean original=CommerceSourceColorResolver.DEFAULT_KEY.equals(color.key());
                return local(run,source,"BASE_COLOR",0,color,List.of(base,swatch),CommerceGarmentColor.VERSION+"/"+CommerceGarmentForeground.VERSION+(color.hex()==null?"":"/template-hex-v1"),
                        List.of(base,swatch,color.key(),color.label(),original),
                        ()->prepareColor(bytes(run.organizationId(),base),bytes(run.organizationId(),swatch),color.label(),original,color.hex()),
                        "已固定「"+color.label()+"」商品底图，可供全部新款复用；未调用生图模型");
            }
            return null;
        } catch(RuntimeException failure) {
            return new Result(false,"商品底图准备已停止："+safe(failure)+"；已完成素材保留，未新增印花生图调用");
        }
    }

    Produced prepareColor(byte[] base,byte[] swatch,String label,boolean original) {
        return prepareColor(base,swatch,label,original,null);
    }
    Produced prepareColor(byte[] base,byte[] swatch,String label,boolean original,String hex) {
        if(hex!=null){
            var result=CommerceGarmentColor.prepareHex(base,hex,label);
            return new Produced(result.png(),result.report());
        }
        try {
            var result=CommerceGarmentColor.prepare(base,swatch,label,original);
            return new Produced(result.png(),result.report());
        } catch(CommerceGarmentColor.ColorException failure) {
            if(foreground==null || !Set.of("COLOR_BACKGROUND_COMPLEX","COLOR_FOREGROUND_UNCERTAIN").contains(failure.code()))throw failure;
            var result=CommerceGarmentColor.prepare(base,foreground.isolate(swatch),label,original);
            return new Produced(result.png(),Map.of("color",result.report(),"sourceSegmentation",CommerceGarmentForeground.VERSION,
                    "sourceSha256",CommerceImageArchive.sha(swatch),"geometryEvidence",false));
        }
    }

    Result execute(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Plan plan,
                   CommerceBatchCreativePlanValidator.Selection source,CommercePrintTemplateService.Configuration config,
                   CommerceSurfaceTemplateInputs.Side side,String base,List<Map<String,Object>> jobs,ColorSource colorSource) {
        try {
            boolean surface=side!=null;
            String sceneKey=CommerceSceneBackgroundAssets.readyKey(plan,config,source,jobs,run.organizationId(),archive,json);
            if(surface&&!CommerceBatchCreativePlanValidator.groupedArtwork(plan)) {
                String selected=CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId());
                var uploadedGroups=CommerceSurfaceTemplateInputs.artworkGroups(config);
                for(int g=0;g<uploadedGroups.size();g++) {
                    String artwork=uploadedGroups.get(g);if(artwork.equals(selected))continue;
                    String kind="MATTE_G"+(g+1),input=CommerceSurfaceTemplateInputs.key(run.organizationId(),artwork);
                    var existing=find(jobs,source.captureId(),kind,0,null);
                    if(ready(existing)){uploadedMatte(run,source,artwork,uploadedGroups,jobs);continue;}
                    // Static uploaded groups are prepared once, not once per generated variant or color.
                    var seeds=matteSeeds(run.organizationId(),input);
                    return local(run,source,kind,0,null,List.of(input),CommerceArtworkMatte.VERSION,matteInputs(input,seeds),
                            ()->{var result=CommerceArtworkMatte.prepare(bytes(run.organizationId(),input),seeds);return new Produced(result.png(),result.report());},
                            "保留图案组 "+(g+1)+" 的透明母版已保存，全部款式与颜色共用；未调用生图模型");
                }
            }
            var groups=surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)
                    ?CommerceSurfaceTemplateInputs.artworkGroups(config):List.of("planar");
            // All placement groups keep a single transparent master, including groups hidden from the main view.
            for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(int g=0;g<groups.size();g++) {
                String kind=surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)?"MATTE_G"+(g+1):"MATTE";
                if(ready(find(jobs,source.captureId(),kind,variant,null)))continue;
                String variationKind=kind.equals("MATTE")?"VARIATE":"VARIATE_G"+(g+1);
                var original=find(jobs,source.captureId(),variationKind,variant,null);
                if(!ready(original))return new Result(false,"缺少已归档的新印花，保留现有素材；请先完成图案生成");
                String input=(String)original.get("storage_key");
                var seeds=matteSeeds(run.organizationId(),input);
                return local(run,source,kind,variant,null,List.of(input),CommerceArtworkMatte.VERSION,matteInputs(input,seeds),
                        ()->{var result=CommerceArtworkMatte.prepare(bytes(run.organizationId(),input),seeds);return new Produced(result.png(),result.report());},
                        "透明印花母版已保存，同组位置和商品颜色共用；未调用生图模型");
            }
            if(base==null)return new Result(false,"缺少固定的去印花底图，未合成商品图片");
            var prepared=prepareColors(run,source,base,jobs,colorSource);
            if(prepared!=null)return prepared;
            for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(var color:source.colorTargets()) {
                String kind=surface?CommerceSurfaceTemplateInputs.kind(side.side()):"MOCKUP";
                if(ready(find(jobs,source.captureId(),kind,variant,color.key())))continue;
                var baseJob=find(jobs,source.captureId(),"BASE_COLOR",0,color.key());
                if(!ready(baseJob))return new Result(false,"对应颜色的固定底图未准备完成，未合成商品图片");
                String colorBase=(String)baseJob.get("storage_key");
                var replacementKeys=new LinkedHashMap<String,String>();
                if(surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)) {
                    for(var print:CommercePrintTemplateService.placementBindings(config))if(side.visiblePrintIds().contains(print.id())) {
                        int g=groups.indexOf(print.artworkId());
                        var matte=find(jobs,source.captureId(),"MATTE_G"+(g+1),variant,null);
                        replacementKeys.put(print.id(),(String)matte.get("storage_key"));
                    }
                } else {
                    String matte=(String)find(jobs,source.captureId(),"MATTE",variant,null).get("storage_key");
                    if(surface) {
                        String selected=CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId());
                        var uploadedGroups=CommerceSurfaceTemplateInputs.artworkGroups(config);
                        for(var print:CommercePrintTemplateService.placementBindings(config))if(side.visiblePrintIds().contains(print.id())) {
                            String key=print.artworkId().equals(selected)?matte:uploadedMatte(run,source,print.artworkId(),uploadedGroups,jobs);
                            replacementKeys.put(print.id(),key);
                        }
                    } else for(var area:config.areas())replacementKeys.put(area.id(),matte);
                }
                var refs=new ArrayList<String>();refs.add(colorBase);
                if(surface){refs.add(side.blankKey());refs.add(side.uvKey());}
                refs.addAll(new LinkedHashSet<>(replacementKeys.values()));
                Object binding=surface?List.of(config.renderBinding(),side.side(),config.renderPrintIds()):config.areas();
                var inputs=new ArrayList<Object>(List.of(POLICY,binding,colorBase,replacementKeys,color.key(),color.label(),base));
                if(sceneKey!=null){refs.add(sceneKey);inputs.addAll(List.of(CommerceSceneBackgroundComposer.VERSION,sceneKey));}
                return local(run,source,kind,variant,color,refs,CommercePrintTemplateService.photoLayout(config)?CommercePhotoMockupComposer.PHOTO_VERSION:CommercePhotoMockupComposer.VERSION,inputs,()->{
                    var replacements=new LinkedHashMap<String,byte[]>();
                    replacementKeys.forEach((id,key)->replacements.put(id,bytes(run.organizationId(),key)));
                    var result=surface?(CommercePrintTemplateService.photoLayout(config)?CommercePhotoMockupComposer.composePhoto(side.side(),bytes(run.organizationId(),colorBase),bytes(run.organizationId(),base),side.blank(),side.uv(),config.renderPrintIds(),replacements):CommercePhotoMockupComposer.composeRecolored(side.side(),bytes(run.organizationId(),colorBase),bytes(run.organizationId(),base),side.blank(),side.uv(),config.renderPrintIds(),replacements))
                            :CommercePhotoMockupComposer.composePlanar(bytes(run.organizationId(),colorBase),config.areas(),replacements);
                    var report=new LinkedHashMap<String,Object>();
                    report.put("status","LOCAL_PHOTO_REVIEW_REQUIRED");report.put("productionReady",false);
                    report.put("method",POLICY);report.put("photoCheck",result.report());
                    report.put("artworkKeys",replacementKeys);report.put("baseKey",colorBase);
                    report.put("colorKey",color.key());report.put("colorLabel",color.label());
                    report.put("modelCalls",0);report.put("limitation","固定底图和透明母版已合成，背景与印区外像素受保护；原始版型、照片映射、布料颜色及印花位置仍需视觉复核。");
                    byte[] output=result.png();
                    if(sceneKey!=null){
                        var scene=CommerceSceneBackgroundComposer.compose(output,bytes(run.organizationId(),base),bytes(run.organizationId(),sceneKey));
                        output=scene.png();report.put("sceneKey",sceneKey);report.put("sceneCheck",scene.report());
                        report.put("renderPolicy",CommerceBatchCreativePlanValidator.GROK_SCENE_POLICY);
                        report.put("limitation","固定衣服与印花内区像素由本地合成保留；背景由 Grok 基于来源原照生成一次并共享，不表示背景逐像素一致。");
                    }
                    return new Produced(output,report);
                },"商品主图已本地套版 · "+color.label()+"；保留底图与共享母版，待质量复核");
            }
            return null; // This source is complete; caller may continue other sources in the same turn.
        } catch(IllegalArgumentException failure) {
            return new Result(false,"本地套版已停止："+safe(failure)+"；已完成素材保留，未重新调用生图模型");
        } catch(RuntimeException failure) {
            return new Result(false,"本地套版素材处理未完成："+safe(failure)+"；可恢复本地处理，已完成的生图不会重跑");
        }
    }

    Result review(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Plan plan,
                  CommercePrintTemplateService.Configuration config,List<Map<String,Object>> jobs) {
        boolean surface=CommerceSurfaceTemplateInputs.enabled(config);
        for(var source:plan.sources())for(int variant=1;variant<=plan.variantsPerPrint();variant++)for(var color:source.colorTargets()) {
            final String sceneKey;
            try {sceneKey=CommerceSceneBackgroundAssets.readyKey(plan,config,source,jobs,run.organizationId(),archive,json);}
            catch(IllegalArgumentException failure){return new Result(false,safe(failure));}
            int currentVariant=variant;
            var matches=jobs.stream().filter(j->source.captureId().equals(j.get("capture_id"))&&Objects.equals(color.key(),j.get("color_key"))
                    &&((Number)j.get("variant_index")).intValue()==currentVariant
                    &&(surface?"MOCKUP_FRONT".equals(j.get("kind")):"MOCKUP".equals(j.get("kind")))).toList();
            if(matches.size()!=1||!ready(matches.getFirst()))return new Result(false,"商品各款各色主图尚未全部归档，保留已完成图片");
            var job=matches.getFirst();
            var rows=db.queryForList("SELECT result_json FROM commerce_batch_artwork_jobs WHERE id=? AND organization_id=?",job.get("id"),run.organizationId());
            if(rows.isEmpty()||rows.getFirst().get("result_json")==null)return new Result(false,"本地套版缺少固定输入和检查记录，不能完成批次");
            var report=json.readTree((String)rows.getFirst().get("result_json"));
            List<String> refs=json.readValue((String)job.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));
            if(!"LOCAL_PHOTO_REVIEW_REQUIRED".equals(report.path("status").asText())
                    ||!POLICY.equals(job.get("model_config_id"))||!POLICY.equals(report.path("method").asText())
                    ||!color.key().equals(report.path("colorKey").asText())||!color.label().equals(report.path("colorLabel").asText())
                    ||refs.isEmpty()||!Objects.equals(refs.getFirst(),report.path("baseKey").asText())
                    ||!report.path("artworkKeys").isObject()||(!CommercePrintTemplateService.photoLayout(config)&&report.path("artworkKeys").isEmpty())
                    ||(!CommercePrintTemplateService.photoLayout(config)&&report.path("photoCheck").path("mappedPixels").asLong(0)<=0)
                    ||report.path("photoCheck").path("protectedChangedPixels").asLong(-1)!=0)
                return new Result(false,"本地套版检查记录与当前颜色、底图或图案不一致，或保护区域发生变化，请核对结果");
            if(sceneKey!=null&&(!sceneKey.equals(report.path("sceneKey").asText())||!refs.contains(sceneKey)
                    ||!CommerceBatchCreativePlanValidator.GROK_SCENE_POLICY.equals(report.path("renderPolicy").asText())
                    ||!CommerceSceneBackgroundComposer.VERSION.equals(report.path("sceneCheck").path("method").asText())
                    ||report.path("sceneCheck").path("protectedInteriorChangedPixels").asLong(-1)!=0
                    ||!sceneKey.endsWith("/"+report.path("sceneCheck").path("sceneSha256").asText())
                    ||!Objects.equals(config.baseImages().get("FRONT"),report.path("sceneCheck").path("geometrySha256").asText())))
                return new Result(false,"商品主图未使用本批固定共用场景，或服装印花内区发生变化，请恢复本地合成");
            var expectedPrintIds=surface?CommerceSurfaceMockupComposer.inspect(bytes(run.organizationId(),CommerceSurfaceTemplateInputs.key(run.organizationId(),config.surfaceUvViews().get("FRONT"))),config.renderPrintIds()).visiblePrintIds():new LinkedHashSet<>(config.areas().stream().map(CommercePrintTemplateService.Area::id).toList());
            var actualKeys=json.convertValue(report.path("artworkKeys"),Map.class);
            if(!actualKeys.keySet().equals(expectedPrintIds)||!refs.containsAll(actualKeys.values()))return new Result(false,"本地套版印花绑定不完整，不能将遗漏或错用图案的结果标记完成");
            var baseJob=find(jobs,source.captureId(),"BASE_COLOR",0,color.key());
            if(!ready(baseJob)||!Objects.equals(baseJob.get("storage_key"),report.path("baseKey").asText()))return new Result(false,"商品主图未使用该颜色已固定的底图，请核对套版输入");
            var expectedArtworks=new LinkedHashMap<String,String>();
            if(surface&&CommerceBatchCreativePlanValidator.groupedArtwork(plan)) {
                var groups=CommerceSurfaceTemplateInputs.artworkGroups(config);
                for(var print:CommercePrintTemplateService.placementBindings(config))if(expectedPrintIds.contains(print.id())) {
                    var matte=find(jobs,source.captureId(),"MATTE_G"+(groups.indexOf(print.artworkId())+1),variant,null);
                    if(!ready(matte))return new Result(false,"对应图案组的透明母版尚未归档，不能完成检查");
                    expectedArtworks.put(print.id(),(String)matte.get("storage_key"));
                }
            } else {
                var matte=find(jobs,source.captureId(),"MATTE",variant,null);
                if(!ready(matte))return new Result(false,"本款透明印花母版尚未归档，不能完成检查");
                if(surface) {
                    String selected=CommerceSurfaceTemplateInputs.variationArtwork(config,plan.variationArtworkId());
                    var uploadedGroups=CommerceSurfaceTemplateInputs.artworkGroups(config);
                    for(var print:CommercePrintTemplateService.placementBindings(config))if(expectedPrintIds.contains(print.id())) {
                        String key;
                        try {key=print.artworkId().equals(selected)?(String)matte.get("storage_key"):uploadedMatte(run,source,print.artworkId(),uploadedGroups,jobs);}
                        catch(IllegalArgumentException failure){return new Result(false,safe(failure));}
                        expectedArtworks.put(print.id(),key);
                    }
                } else for(var area:config.areas())expectedArtworks.put(area.id(),(String)matte.get("storage_key"));
            }
            if(!expectedArtworks.equals(actualKeys))return new Result(false,"本款印花母版与固定位置组不一致，不能将图案互换后的结果标记完成");
            archive.read(run.organizationId(),(String)job.get("storage_key"));
            db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",job.get("id"),json.writeValueAsString(report));
        }
        return null;
    }

    private String uploadedMatte(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,
                                 String artwork,List<String> groups,List<Map<String,Object>> jobs) {
        var matte=find(jobs,source.captureId(),"MATTE_G"+(groups.indexOf(artwork)+1),0,null);
        String input=CommerceSurfaceTemplateInputs.key(run.organizationId(),artwork);
        if(!ready(matte)||!POLICY.equals(matte.get("model_config_id")))
            throw new IllegalArgumentException("保留图案组缺少已处理的透明母版，不能直接套用原上传图；请继续母版处理");
        List<String> refs;
        try {refs=json.readValue((String)matte.get("reference_keys"),json.getTypeFactory().constructCollectionType(List.class,String.class));}
        catch(RuntimeException failure){throw new IllegalArgumentException("保留图案组的透明母版缺少来源绑定，请核对固定上传素材");}
        if(!List.of(input).equals(refs)||!cache.key(CommerceArtworkMatte.VERSION,matteInputs(input,matteSeeds(run.organizationId(),input))).equals(matte.get("model_fingerprint")))
            throw new IllegalArgumentException("保留图案组的透明母版与固定上传素材不一致，已停止套版");
        return (String)matte.get("storage_key");
    }

    Result local(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,
                 CommerceSourceColorResolver.Color color,List<String> refs,String algorithm,Object inputs,
                 Supplier<Produced> producer,String message) {
        String key=cache.key(algorithm,inputs);
        var cached=cache.find(run.organizationId(),key);
        if(cached==null){var made=producer.get();cached=cache.store(run.organizationId(),key,kind,made.png(),made.report());}
        if(cached==null)throw new IllegalStateException("派生素材缓存未写入");
        return archiveEntry(run,source,kind,variant,color,refs,cached,message);
    }
    Result archiveEntry(CommerceBatchExecution run,CommerceBatchCreativePlanValidator.Selection source,String kind,int variant,
                        CommerceSourceColorResolver.Color color,List<String> refs,CommerceMockupDerivativeCache.Entry entry,String message) {
        String id=UUID.randomUUID().toString();
        String request=CommerceBatchArtworkService.requestKey(run.id(),source.captureId(),kind,variant,color==null?null:color.key());
        int inserted=db.update("""
            INSERT IGNORE INTO commerce_batch_artwork_jobs
            (id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,
             model_config_id,model_fingerprint,reference_keys,result_json,storage_key,content_sha256,message,color_key,color_label,created_at,updated_at)
            SELECT ?,?,?,?,?,?,?,?,'READY',?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6)
            FROM commerce_batch_executions e WHERE e.id=? AND e.organization_id=? AND e.status IN ('RUNNING','PAUSING')
              AND e.lease_token=? AND e.lease_until>NOW(6) AND e.created_by=?
            """,id,run.organizationId(),run.id(),source.captureId(),source.version(),kind,variant,request,
                "固定照片本地处理，输入与算法版本已归档",POLICY,entry.key(),json.writeValueAsString(refs),json.writeValueAsString(entry.report()),
                entry.storageKey(),entry.sha(),message,color==null?null:color.key(),color==null?null:color.label(),
                run.id(),run.organizationId(),run.leaseToken(),run.createdBy());
        if(inserted==0) {
            var existing=db.queryForList("SELECT id FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND request_key=? AND status='READY'",run.id(),run.organizationId(),request);
            return existing.isEmpty()?Result.unknown("执行租约已变化，本地素材已缓存；未写入失效任务，也未调用生图模型"):
                    Result.progress("已复用本地归档结果，继续下一张");
        }
        db.update("INSERT IGNORE INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES(?,?,NOW(6))",id,json.writeValueAsString(entry.report()));
        return Result.progress(message);
    }
    private byte[] bytes(String org,String key){return Base64.getDecoder().decode(archive.read(org,key).dataBase64());}
    private List<CommerceArtworkMatte.BackgroundSeed> matteSeeds(String org,String input){
        String sha=input.substring(input.lastIndexOf('/')+1);
        var rows=db.queryForList("SELECT background_seeds_json FROM commerce_artwork_matte_guides WHERE organization_id=? AND source_sha256=?",org,sha);
        return rows.isEmpty()?List.of():json.readValue((String)rows.getFirst().get("background_seeds_json"),json.getTypeFactory().constructCollectionType(List.class,CommerceArtworkMatte.BackgroundSeed.class));
    }
    private static Object matteInputs(String input,List<CommerceArtworkMatte.BackgroundSeed> seeds){return seeds.isEmpty()?List.of(input):List.of(input,seeds);}
    private static String safe(Throwable t){String s=Objects.toString(t.getMessage(),"归档或本地处理异常").replaceAll("[\\r\\n\\t]+"," ");return s.substring(0,Math.min(500,s.length()));}
    private static boolean ready(Map<String,Object> job){return job!=null&&"READY".equals(job.get("status"));}
    private static Map<String,Object> find(List<Map<String,Object>> jobs,String source,String kind,int variant,String color){return jobs.stream().filter(j->source.equals(j.get("capture_id"))&&kind.equals(j.get("kind"))&&((Number)j.get("variant_index")).intValue()==variant&&Objects.equals(color,j.get("color_key"))).findFirst().orElse(null);}
}
