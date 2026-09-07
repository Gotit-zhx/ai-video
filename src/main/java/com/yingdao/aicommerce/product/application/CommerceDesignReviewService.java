package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
@Service
public class CommerceDesignReviewService {
    public record Checks(boolean singleImage,boolean noAnatomyArtifacts,boolean matchesDesignScope,boolean noInventedBackPrint){}
    public record Request(String contentSha256,String decision,Checks checks,String notes,boolean confirmed){}
    public record View(String outputId,String contentSha256,String mimeType,String status,CommerceDesignReview review){}
    private final CommerceDesignMapper designs;private final CommerceDesignOutputMapper outputs;private final CommerceDesignReviewMapper reviews;
    private final ProductCaptureService captures;private final CommerceImageArchive archive;private final ObjectMapper json;
    public CommerceDesignReviewService(CommerceDesignMapper designs,CommerceDesignOutputMapper outputs,CommerceDesignReviewMapper reviews,ProductCaptureService captures,CommerceImageArchive archive,ObjectMapper json){this.designs=designs;this.outputs=outputs;this.reviews=reviews;this.captures=captures;this.archive=archive;this.json=json;}
    public View view(String design,PlatformPrincipal u){authorize(design,u,false);var output=outputs.byDesign(u.organizationId(),design);return output==null?null:new View(output.id(),output.contentSha256(),output.mimeType(),output.status(),reviews.get(u.organizationId(),output.id()));}
    public CommerceDesignImageGateway.Reference image(String design,PlatformPrincipal u){authorize(design,u,false);var output=outputs.byDesign(u.organizationId(),design);if(output==null)throw error(HttpStatus.NOT_FOUND,"尚无生成结果");return archive.read(u.organizationId(),output.storageKey());}
    @Transactional
    public CommerceDesignReview review(String design,Request r,PlatformPrincipal u){
        authorize(design,u,true);
        if(r==null||!r.confirmed()||r.checks()==null||r.notes()==null||r.notes().isBlank()||r.notes().length()>2000||!("APPROVE".equals(r.decision())||"REJECT".equals(r.decision())))throw error(HttpStatus.BAD_REQUEST,"请明确审核结论、逐项检查并填写复核说明");
        if("APPROVE".equals(r.decision())&&!(r.checks().singleImage()&&r.checks().noAnatomyArtifacts()&&r.checks().matchesDesignScope()&&r.checks().noInventedBackPrint()))throw error(HttpStatus.BAD_REQUEST,"检查项未全部通过，不能批准图片");
        var found=outputs.byDesign(u.organizationId(),design);if(found==null)throw error(HttpStatus.NOT_FOUND,"尚无可审核图片");
        var output=outputs.lock(u.organizationId(),found.id());
        if(!output.contentSha256().equals(r.contentSha256()))throw error(HttpStatus.CONFLICT,"图片内容版本不匹配，请重新查看");
        String checks=json.writeValueAsString(r.checks());var existing=reviews.get(u.organizationId(),output.id());
        if(existing!=null){if(!existing.decision().equals(r.decision())||!existing.notes().equals(r.notes())||!json.readTree(existing.checksJson()).equals(json.readTree(checks)))throw error(HttpStatus.CONFLICT,"审核记录不可覆盖，请创建新设计后重新审核");return existing;}
        if(!"PENDING_REVIEW".equals(output.status()))throw error(HttpStatus.CONFLICT,"图片不在待审核状态");
        archive.read(u.organizationId(),output.storageKey());
        var record=new CommerceDesignReview(UUID.randomUUID().toString(),u.organizationId(),output.id(),output.contentSha256(),r.decision(),checks,r.notes(),u.userId(),Instant.now());reviews.insert(record);
        if(outputs.reviewed(u.organizationId(),output.id(),"APPROVE".equals(r.decision())?"QUALITY_APPROVED":"REJECTED")!=1)throw error(HttpStatus.CONFLICT,"图片状态已改变");
        return record;
    }
    private void authorize(String design,PlatformPrincipal u,boolean edit){
        if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:edit"):u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无设计审核权限");
        var draft=designs.get(u.organizationId(),design);if(draft==null||u.externallyRegistered()&&!u.userId().equals(draft.createdBy()))throw error(HttpStatus.NOT_FOUND,"设计不存在");
        captures.get(draft.captureId(),u);
    }
    private static BusinessException error(HttpStatus s,String message){return new BusinessException(s,message);}
}
