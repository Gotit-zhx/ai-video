package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
@Service
public class CommerceDesignGenerationService {
    public record Request(List<String> imageIds,boolean confirmed){}
    public record State(String designId,String attemptId,String status,boolean generationEnabled,String message){}
    private final CommerceDesignMapper designs;private final CommerceDesignAttemptMapper attempts;private final ProductCaptureService captures;
    private final CommerceDesignReferencePreparer preparer;private final CommerceDesignGenerationCoordinator coordinator;private final CommerceDesignResultCollector collector;
    private final ModelConfigService models;private final ObjectMapper json;private final boolean enabled;
    public CommerceDesignGenerationService(CommerceDesignMapper designs,CommerceDesignAttemptMapper attempts,ProductCaptureService captures,
            CommerceDesignReferencePreparer preparer,CommerceDesignGenerationCoordinator coordinator,CommerceDesignResultCollector collector,
            ModelConfigService models,ObjectMapper json,@Value("${app.commerce-design-generation-enabled:false}")boolean enabled){
        this.designs=designs;this.attempts=attempts;this.captures=captures;this.preparer=preparer;this.coordinator=coordinator;this.collector=collector;this.models=models;this.json=json;this.enabled=enabled;
    }
    public State state(String id,PlatformPrincipal u){authorize(id,u,false);return state(id,attempts.byDesign(u.organizationId(),id));}
    /** Synchronous single-design operation. No external call is inside a database transaction. */
    public State generate(String id,Request request,PlatformPrincipal u){
        var draft=authorize(id,u,true);
        if(request==null||!request.confirmed()||request.imageIds()==null||request.imageIds().isEmpty()||request.imageIds().size()>4||request.imageIds().stream().anyMatch(v->v==null||v.isBlank())||new HashSet<>(request.imageIds()).size()!=request.imageIds().size())throw error(HttpStatus.BAD_REQUEST,"请明确选择1至4张参考图，并确认可能产生模型费用");
        var attempt=attempts.byDesign(u.organizationId(),id);
        if(attempt!=null){
            var references=new ArrayList<CommerceDesignGenerationCoordinator.PreparedReference>();
            for(var node:json.readTree(attempt.referenceManifestJson()))references.add(new CommerceDesignGenerationCoordinator.PreparedReference(node.path("imageId").asText(),node.path("storageKey").asText()));
            if(!references.stream().map(CommerceDesignGenerationCoordinator.PreparedReference::imageId).toList().equals(request.imageIds()))throw error(HttpStatus.CONFLICT,"本设计已固定不同参考图，请另建草稿");
            if("RESULT_RECEIVED".equals(attempt.status())){collector.collect(u.organizationId(),attempt.id());return state(id,attempts.byDesign(u.organizationId(),id));}
            if(!"PREPARED".equals(attempt.status()))return state(id,attempt);
            requireEnabledModel(u.organizationId());
            coordinator.execute(draft,references);
        }else{
            requireEnabledModel(u.organizationId());
            if(captures.get(draft.captureId(),u).capture().getVersion()!=draft.captureVersion())throw error(HttpStatus.CONFLICT,"来源商品已修改，请创建新版本设计草稿");
            var references=preparer.prepare(draft,request.imageIds());coordinator.execute(draft,references);
        }
        var current=attempts.byDesign(u.organizationId(),id);
        if(current!=null&&"RESULT_RECEIVED".equals(current.status()))collector.collect(u.organizationId(),current.id());
        return state(id,attempts.byDesign(u.organizationId(),id));
    }
    private void requireEnabledModel(String org){if(!enabled)throw error(HttpStatus.CONFLICT,"商品设计生成尚未由管理员开启，不会调用模型");if(models.imageConnection(org).isEmpty())throw error(HttpStatus.CONFLICT,"当前公司没有配置图片模型");}
    private CommerceDesignDraft authorize(String id,PlatformPrincipal u,boolean edit){
        if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:edit"):u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无设计生成权限");
        var d=designs.get(u.organizationId(),id);if(d==null||u.externallyRegistered()&&!u.userId().equals(d.createdBy()))throw error(HttpStatus.NOT_FOUND,"设计不存在");captures.get(d.captureId(),u);return d;
    }
    private State state(String id,CommerceDesignAttempt a){String status=a==null?"NOT_STARTED":a.status();String message=switch(status){case "SUBMITTING","UNKNOWN"->"提交中或结果待核查，不会自动重新生成";case "RESULT_RECEIVED"->"已有模型结果，可重试下载，不重新生成";case "OUTPUT_ARCHIVED"->"结果已归档，请进行质量审核";default->enabled?"尚未生成，需确认参考图与模型费用":"管理员尚未开启商品设计生成";};return new State(id,a==null?null:a.id(),status,enabled,message);}
    private static BusinessException error(HttpStatus s,String message){return new BusinessException(s,message);}
}
