package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.security.*;

@Service
public class CommerceBatchDesignAssetService {
    public record Request(String itemId,String designId,String contentSha256,int imagePosition,boolean confirmed){}
    public record Candidate(String designId,String outputId,String contentSha256,int variantIndex,String mode){}
    private final CommerceBatchMapper batches;
    private final CommerceBatchDesignAssetMapper assets;
    private final CommerceBatchReviewMapper batchReviews;
    private final CommerceDesignMapper designs;
    private final CommerceDesignOutputMapper outputs;
    private final CommerceDesignReviewMapper reviews;
    private final CommerceTemplateMapper templates;
    private final ProductCaptureService captures;
    private final CommerceImageArchive archive;
    private final ObjectMapper json;
    public CommerceBatchDesignAssetService(CommerceBatchMapper batches,CommerceBatchDesignAssetMapper assets,
            CommerceBatchReviewMapper batchReviews,CommerceDesignMapper designs,CommerceDesignOutputMapper outputs,
            CommerceDesignReviewMapper reviews,CommerceTemplateMapper templates,ProductCaptureService captures,
            CommerceImageArchive archive,ObjectMapper json){
        this.batches=batches;this.assets=assets;this.batchReviews=batchReviews;this.designs=designs;this.outputs=outputs;
        this.reviews=reviews;this.templates=templates;this.captures=captures;this.archive=archive;this.json=json;
    }
    public List<CommerceBatchDesignAsset> list(String batch,PlatformPrincipal user){
        access(user,false);
        if(batches.get(user.organizationId(),batch)==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        for(var item:batches.items(user.organizationId(),batch))captures.get(item.captureId(),user);
        return assets.list(user.organizationId(),batch);
    }
    public List<Candidate> candidates(String batch,String itemId,PlatformPrincipal user){
        access(user,false);
        var found=batches.get(user.organizationId(),batch);
        if(found==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        var template=templates.snapshot(user.organizationId(),found.templateSnapshotId());
        if(template==null)throw error(HttpStatus.CONFLICT,"模板快照不可用");
        String mode=json.readTree(template.configJson()).path("mode").asText();
        var item=batches.items(user.organizationId(),batch).stream().filter(i->i.id().equals(itemId)).findFirst()
                .orElseThrow(()->error(HttpStatus.NOT_FOUND,"批次商品不存在"));
        captures.get(item.captureId(),user);
        var result=new ArrayList<Candidate>();
        for(var design:designs.forCapture(user.organizationId(),item.captureId(),item.captureVersion(),user.externallyRegistered()?user.userId():null)){
            if(!design.captureId().equals(item.captureId())||design.captureVersion()!=item.captureVersion()||!mode.equals(design.mode()))continue;
            var output=outputs.byDesign(user.organizationId(),design.id());
            if(output==null||!"QUALITY_APPROVED".equals(output.status()))continue;
            var review=reviews.get(user.organizationId(),output.id());
            if(review!=null&&"APPROVE".equals(review.decision())&&output.contentSha256().equals(review.contentSha256()))
                result.add(new Candidate(design.id(),output.id(),output.contentSha256(),design.variantIndex(),design.mode()));
        }
        return List.copyOf(result);
    }
    @Transactional
    public CommerceBatchDesignAsset bind(String id,Request request,PlatformPrincipal user){
        access(user,true);
        if(request==null||!request.confirmed()||request.itemId()==null||request.designId()==null||request.contentSha256()==null
                ||!request.contentSha256().matches("[a-f0-9]{64}")||request.imagePosition()<1||request.imagePosition()>9)
            throw error(HttpStatus.BAD_REQUEST,"请选择商品、图片及1至9的图片顺序，并明确确认");
        var batch=batches.lock(user.organizationId(),id);
        if(batch==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        if(!"DRAFT".equals(batch.status()))throw error(HttpStatus.CONFLICT,"仅草稿批次可绑定图片");
        var item=batches.items(user.organizationId(),id).stream().filter(i->i.id().equals(request.itemId())).findFirst()
                .orElseThrow(()->error(HttpStatus.NOT_FOUND,"批次商品不存在"));
        var capture=captures.get(item.captureId(),user).capture();
        if(capture.getVersion()!=item.captureVersion())throw error(HttpStatus.CONFLICT,"来源商品版本已改变，请重新建立批次");
        var design=designs.get(user.organizationId(),request.designId());
        if(design==null||user.externallyRegistered()&&!user.userId().equals(design.createdBy()))throw error(HttpStatus.NOT_FOUND,"设计不存在");
        if(!design.captureId().equals(item.captureId())||design.captureVersion()!=item.captureVersion())throw error(HttpStatus.CONFLICT,"设计来源与批次商品版本不一致");
        var template=templates.snapshot(user.organizationId(),batch.templateSnapshotId());
        if(template==null||!json.readTree(template.configJson()).path("mode").asText().equals(design.mode()))throw error(HttpStatus.CONFLICT,"设计用途与批次模板不一致");
        var output=outputs.byDesign(user.organizationId(),design.id());
        if(output==null||!"QUALITY_APPROVED".equals(output.status())||!request.contentSha256().equals(output.contentSha256()))throw error(HttpStatus.CONFLICT,"图片未通过审核或内容版本已改变");
        var review=reviews.get(user.organizationId(),output.id());
        if(review==null||!"APPROVE".equals(review.decision())||!output.contentSha256().equals(review.contentSha256()))throw error(HttpStatus.CONFLICT,"图片缺少匹配的批准记录");
        // Re-read actual archived bytes, not only a caller-provided URL or a database flag.
        var image=archive.read(user.organizationId(),output.storageKey());
        try{
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(image.dataBase64())));
            if(!actual.equals(output.contentSha256()))throw error(HttpStatus.CONFLICT,"归档图片与审核内容不一致");
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        for(var existing:assets.list(user.organizationId(),id)){
            if(!existing.itemId().equals(item.id()))continue;
            if(existing.imagePosition()==request.imagePosition()){
                if(existing.outputId().equals(output.id())&&existing.contentSha256().equals(output.contentSha256()))return existing;
                throw error(HttpStatus.CONFLICT,"此顺序已有图片，绑定记录不可覆盖；请另建批次");
            }
            if(existing.outputId().equals(output.id()))throw error(HttpStatus.CONFLICT,"同一商品不能重复绑定同一结果");
        }
        if(batchReviews.get(user.organizationId(),id)!=null)throw error(HttpStatus.CONFLICT,"批次已审核，不能追加图片；请另建批次");
        var asset=new CommerceBatchDesignAsset(UUID.randomUUID().toString(),user.organizationId(),id,item.id(),request.imagePosition(),
                design.id(),output.id(),output.contentSha256(),review.id(),user.userId(),Instant.now());
        assets.insert(asset);return asset;
    }
    private static void access(PlatformPrincipal user,boolean edit){
        if(user==null||user.organizationId()==null||!(edit?user.permissionCodes().contains("product:publish"):
                user.permissionCodes().contains("product:view")||user.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无批次图片管理权限");
    }
    private static BusinessException error(HttpStatus status,String message){return new BusinessException(status,message);}
}
