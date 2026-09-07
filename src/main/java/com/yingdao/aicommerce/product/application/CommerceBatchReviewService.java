package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchReview;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Service
public class CommerceBatchReviewService {
    public record Evidence(String itemId,long captureVersion,String rightsEvidence,String supplySku,
                           String qualityReview,String productionEvidence) {}
    public record Request(boolean confirmed,List<Evidence> items) {}
    private final CommerceBatchMapper batches;
    private final CommerceTemplateMapper templates;
    private final CommerceBatchReviewMapper reviews;
    private final CommerceBatchPreflightService preflight;
    private final ObjectMapper json;
    public CommerceBatchReviewService(CommerceBatchMapper batches,CommerceTemplateMapper templates,
            CommerceBatchReviewMapper reviews,CommerceBatchPreflightService preflight,ObjectMapper json){
        this.batches=batches;this.templates=templates;this.reviews=reviews;this.preflight=preflight;this.json=json;
    }
    public CommerceBatchReview get(String batch,PlatformPrincipal user){
        access(user,false);
        if(batches.get(user.organizationId(),batch)==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        return reviews.get(user.organizationId(),batch);
    }
    @Transactional
    public CommerceBatchReview submit(String id,Request request,PlatformPrincipal user){
        access(user,true);
        if(request==null||!request.confirmed()||request.items()==null||request.items().isEmpty()||request.items().size()>500)
            throw error(HttpStatus.BAD_REQUEST,"需要确认并逐条提供审核依据");
        var batch=batches.lock(user.organizationId(),id);
        if(batch==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        if(!"DRAFT".equals(batch.status()))throw error(HttpStatus.CONFLICT,"仅草稿可以提交审核依据");
        var items=batches.items(user.organizationId(),id);
        var snapshot=templates.snapshot(user.organizationId(),batch.templateSnapshotId());
        if(snapshot==null)throw error(HttpStatus.CONFLICT,"模板快照不可用");
        boolean pod=json.readValue(snapshot.configJson(),CommerceBatchPlanner.Request.class).mode()==CommerceBatchPlanner.Mode.POD_DESIGN;
        Map<String,Evidence> evidence=new TreeMap<>();
        for(var e:request.items()){
            if(e==null||e.itemId()==null||evidence.put(e.itemId(),e)!=null)throw error(HttpStatus.BAD_REQUEST,"审核条目重复或缺少ID");
            require(e.rightsEvidence(),"使用权依据");require(e.supplySku(),"供应商SKU");require(e.qualityReview(),"质量复核记录");
            if(pod)require(e.productionEvidence(),"POD生产基础款、规格及生产文件依据");
            else if(e.productionEvidence()!=null&&e.productionEvidence().length()>2000)throw error(HttpStatus.BAD_REQUEST,"生产依据过长");
        }
        if(evidence.size()!=items.size())throw error(HttpStatus.BAD_REQUEST,"审核依据必须覆盖所有实际条目");
        for(var item:items){
            var e=evidence.get(item.id());
            if(e==null||e.captureVersion()!=item.captureVersion())throw error(HttpStatus.CONFLICT,"审核条目或商品版本不匹配");
        }
        String payload=json.writeValueAsString(evidence.values());
        var existing=reviews.get(user.organizationId(),id);
        if(existing!=null){
            if(!json.readTree(payload).equals(json.readTree(existing.evidenceJson())))throw error(HttpStatus.CONFLICT,"批次已有不可变审核记录；变更商品或依据请另建批次");
            return existing;
        }
        var report=preflight.check(id,user);
        if(report.checkedItems()!=items.size()||report.itemsWithoutDataBlockers()!=items.size()
                ||report.batchBlockers().contains("检测到测试店铺和正式店铺混用"))
            throw error(HttpStatus.CONFLICT,"请先解决发布前检查中的条目问题和环境混用");
        var review=new CommerceBatchReview(UUID.randomUUID().toString(),user.organizationId(),id,
                batch.templateSnapshotId(),payload,user.userId(),Instant.now());
        reviews.insert(review);
        return review;
    }
    private static void require(String value,String label){if(value==null||value.isBlank()||value.length()>2000)throw error(HttpStatus.BAD_REQUEST,label+"须为1至2000字，填写真实来源或内部记录编号");}
    private static void access(PlatformPrincipal u,boolean edit){
        if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:publish"):
                u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无批次审核权限");
    }
    private static BusinessException error(HttpStatus s,String m){return new BusinessException(s,m);}
}
