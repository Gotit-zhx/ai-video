package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.product.mapper.CommerceDesignMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
@Service
public class CommerceDesignService {
    public record Request(String captureId,long captureVersion,String mode,int variants,String brief,String rightsEvidence) {}
    private final CommerceDesignMapper mapper;private final ProductCaptureService captures;private final ObjectMapper json;
    public CommerceDesignService(CommerceDesignMapper mapper,ProductCaptureService captures,ObjectMapper json){this.mapper=mapper;this.captures=captures;this.json=json;}
    public List<CommerceDesignDraft> list(PlatformPrincipal user){access(user,false);return mapper.list(user.organizationId(),user.externallyRegistered()?user.userId():null);}
    @Transactional
    public List<CommerceDesignDraft> create(Request r,String key,PlatformPrincipal user){
        access(user,true);
        if(r==null||r.captureId()==null||r.captureId().isBlank()||r.captureVersion()<0||r.variants()<1||r.variants()>10||!Set.of("STOCK_MARKETING","POD_DESIGN").contains(Objects.toString(r.mode(),"")))throw error(HttpStatus.BAD_REQUEST,"请选择商品版本、现货/POD模式及1至10个设计草稿");
        if(r.brief()==null||r.brief().isBlank()||r.brief().length()>2000||r.rightsEvidence()==null||r.rightsEvidence().isBlank()||r.rightsEvidence().length()>2000)throw error(HttpStatus.BAD_REQUEST,"设计说明和使用权依据须为1至2000字");
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,128}"))throw error(HttpStatus.BAD_REQUEST,"请提供8至128位幂等键");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(r).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        // Recheck access even when replaying an old request.
        var detail=captures.get(r.captureId(),user);
        var prior=mapper.byRequest(user.organizationId(),key);
        if(!prior.isEmpty()){
            if(prior.size()!=r.variants()||prior.stream().anyMatch(d->!d.requestHash().equals(hash)))throw error(HttpStatus.CONFLICT,"幂等键已用于不同设计请求");
            return prior;
        }
        if(detail.capture().getVersion()!=r.captureVersion())throw error(HttpStatus.CONFLICT,"商品已修改，请刷新后固定当前版本");
        var images=detail.images().stream().filter(i->i.getOriginalUrl()!=null&&!i.getOriginalUrl().isBlank()).map(i->Map.of("imageId",i.getId(),"originalUrl",i.getOriginalUrl(),"role",Objects.toString(i.getRoleName(),"GALLERY"))).toList();
        if(images.isEmpty())throw error(HttpStatus.CONFLICT,"来源商品没有可用参考图");
        String snapshot=json.writeValueAsString(images);var now=Instant.now();var rows=new ArrayList<CommerceDesignDraft>();
        for(int index=1;index<=r.variants();index++){
            var row=new CommerceDesignDraft(UUID.randomUUID().toString(),user.organizationId(),r.captureId(),r.captureVersion(),key,hash,index,r.mode(),r.brief(),r.rightsEvidence(),snapshot,"DRAFT",user.userId(),now);
            try{mapper.insert(row);}catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"同一设计请求正在保存，请使用原幂等键重试");}
            rows.add(row);
        }
        return List.copyOf(rows);
    }
    private static void access(PlatformPrincipal u,boolean edit){if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:edit"):u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无设计工作台权限");}
    private static BusinessException error(HttpStatus s,String message){return new BusinessException(s,message);}
}
