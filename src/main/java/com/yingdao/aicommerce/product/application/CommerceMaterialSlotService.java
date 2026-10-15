package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.CommerceDesignMapper;
import com.yingdao.aicommerce.product.mapper.CommerceDesignOutputMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** One immutable creation request is one complete set, including still-pending variants. */
@Service
public class CommerceMaterialSlotService {
    public record Image(String designId,int variant,String status,String contentSha256){}
    public record Slot(String id,String captureId,long captureVersion,String mode,String brief,Instant createdAt,List<Image> images){}
    public record Page(int page,boolean hasMore,List<Slot> slots){}
    private final CommerceDesignMapper designs;
    private final CommerceDesignOutputMapper outputs;
    public CommerceMaterialSlotService(CommerceDesignMapper designs,CommerceDesignOutputMapper outputs){this.designs=designs;this.outputs=outputs;}
    public Page list(int page,PlatformPrincipal user){
        access(user);if(page<0||page>100000)throw error(HttpStatus.BAD_REQUEST,"分页参数无效");
        var ids=designs.slotIds(user.organizationId(),user.externallyRegistered()?user.userId():null,page*20,21);
        return new Page(page,ids.size()>20,ids.stream().limit(20).map(id->get(id,user)).toList());
    }
    public Slot get(String id,PlatformPrincipal user){
        access(user);var first=designs.getMaterialSlot(user.organizationId(),id);
        if(first==null||user.externallyRegistered()&&!first.createdBy().equals(user.userId()))throw error(HttpStatus.NOT_FOUND,"图集不存在");
        var rows=designs.byRequest(user.organizationId(),first.requestKey());
        if(rows.isEmpty()||rows.stream().anyMatch(d->!d.captureId().equals(first.captureId())||d.captureVersion()!=first.captureVersion()||!d.requestHash().equals(first.requestHash())||user.externallyRegistered()&&!d.createdBy().equals(user.userId())))throw error(HttpStatus.CONFLICT,"图集来源记录不一致");
        var images=rows.stream().sorted(Comparator.comparingInt(d->d.variantIndex())).map(d->{var output=outputs.byDesign(user.organizationId(),d.id());return new Image(d.id(),d.variantIndex(),output==null?"NOT_GENERATED":output.status(),output==null?null:output.contentSha256());}).toList();
        String slotId=rows.stream().map(d->d.id()).min(String::compareTo).orElseThrow();
        return new Slot(slotId,first.captureId(),first.captureVersion(),first.mode(),first.brief(),first.createdAt(),images);
    }
    private static void access(PlatformPrincipal u){if(u==null||u.organizationId()==null||!u.permissionCodes().stream().anyMatch(p->p.equals("product:view")||p.equals("product:publish")))throw error(HttpStatus.FORBIDDEN,"无素材查看权限");}
    private static BusinessException error(HttpStatus code,String message){return new BusinessException(code,message);}
}
