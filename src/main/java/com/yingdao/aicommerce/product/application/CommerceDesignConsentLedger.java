package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
/** Consent to possible provider charges, not a receipt or a monetary budget reservation. */
@Service
public class CommerceDesignConsentLedger {
    private final CommerceDesignMapper designs;private final CommerceDesignConsentMapper consents;private final ObjectMapper json;
    public CommerceDesignConsentLedger(CommerceDesignMapper designs,CommerceDesignConsentMapper consents,ObjectMapper json){this.designs=designs;this.consents=consents;this.json=json;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public CommerceDesignConsent record(String designId,List<String> ids,ModelConfigService.RuntimeModelConnection model,PlatformPrincipal user){
        if(user==null||user.organizationId()==null||!user.permissionCodes().contains("product:edit"))throw error(HttpStatus.FORBIDDEN,"无生成确认权限");
        var design=designs.lock(user.organizationId(),designId);if(design==null||user.externallyRegistered()&&!user.userId().equals(design.createdBy()))throw error(HttpStatus.NOT_FOUND,"设计不存在");
        if(ids==null||ids.isEmpty()||ids.size()>4||ids.stream().anyMatch(i->i==null||i.isBlank())||new HashSet<>(ids).size()!=ids.size()||model==null||model.id()==null||model.modelId()==null)throw error(HttpStatus.BAD_REQUEST,"确认信息不完整");
        var sourceIds=new HashSet<String>();for(var image:json.readTree(design.sourceImagesJson()))sourceIds.add(image.path("imageId").asText());
        if(!sourceIds.containsAll(ids))throw error(HttpStatus.BAD_REQUEST,"参考图不属于设计来源，不能记录费用确认");
        String fingerprint=fingerprint(model);
        String imageJson=json.writeValueAsString(ids);var existing=consents.get(user.organizationId(),designId);
        if(existing!=null){
            if(existing.captureVersion()!=design.captureVersion()||!json.readTree(existing.imageIdsJson()).equals(json.readTree(imageJson))||!existing.modelConnectionId().equals(model.id())||!existing.modelId().equals(model.modelId())||!fingerprint.equals(existing.modelConfigurationSha256()))throw error(HttpStatus.CONFLICT,"此设计的参考图或模型配置已变化，或旧确认缺少配置快照，请另建草稿");
            return existing;
        }
        var consent=new CommerceDesignConsent(UUID.randomUUID().toString(),user.organizationId(),designId,design.captureVersion(),imageJson,model.id(),model.modelId(),user.userId(),Instant.now(),fingerprint);consents.insert(consent);return consent;
    }
    /** No credentials are persisted or hashed; rotating an API key does not change the chosen model. */
    private String fingerprint(ModelConfigService.RuntimeModelConnection model){
        var fields=Arrays.asList(model.id(),model.baseUrl(),model.modelId(),model.apiFormat(),model.provider());
        if(fields.stream().anyMatch(v->v==null||v.isBlank()))throw error(HttpStatus.BAD_REQUEST,"模型连接配置不完整");
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static BusinessException error(HttpStatus status,String message){return new BusinessException(status,message);}
}
