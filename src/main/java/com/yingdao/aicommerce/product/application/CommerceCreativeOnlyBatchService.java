package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** A source-backed creative run has no shop, quota or publication permission. */
@Service
public class CommerceCreativeOnlyBatchService {
    public record Request(String name,String printTemplateSnapshotId,List<String> captureIds) {}
    private final CommerceBatchMapper batches;private final CommerceTemplateMapper templates;
    private final ProductCaptureService captures;private final CommerceBatchService views;private final JdbcTemplate db;private final ObjectMapper json;
    public CommerceCreativeOnlyBatchService(CommerceBatchMapper batches,CommerceTemplateMapper templates,ProductCaptureService captures,CommerceBatchService views,JdbcTemplate db,ObjectMapper json){this.batches=batches;this.templates=templates;this.captures=captures;this.views=views;this.db=db;this.json=json;}
    @Transactional public CommerceBatchService.View create(Request request,String key,PlatformPrincipal user){
        if(user==null||!user.permissionCodes().contains("product:edit")||!user.permissionCodes().contains("product:publish"))throw new BusinessException(HttpStatus.FORBIDDEN,"无批次创作权限");
        if(request==null||request.name()==null||request.name().isBlank()||request.name().length()>120||request.captureIds()==null||request.captureIds().isEmpty()||request.captureIds().size()>500||request.captureIds().stream().anyMatch(Objects::isNull)||new HashSet<>(request.captureIds()).size()!=request.captureIds().size()||key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))throw bad("请填写名称、套版模板和不重复的来源商品");
        String hash=CommerceImageArchive.sha(("CREATIVE_ONLY:"+json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
        var prior=batches.byKey(user.organizationId(),key);if(prior!=null)return replay(prior,hash,user);
        var template=templates.snapshot(user.organizationId(),request.printTemplateSnapshotId());
        if(template==null||!"PRINT_LAYOUT".equals(template.kind()))throw bad("请选择商品套版模板");
        CommercePrintTemplateService.validate(json.readValue(template.configJson(),CommercePrintTemplateService.Configuration.class));
        var selected=request.captureIds().stream().map(id->captures.get(id,user).capture()).toList();
        var now=Instant.now();String id=UUID.randomUUID().toString();
        var batch=new CommerceBatch(id,user.organizationId(),request.name().trim(),template.id(),key,hash,"DRAFT",user.userId(),now);
        try{batches.insert(batch);}catch(DuplicateKeyException e){var winner=batches.byKey(user.organizationId(),key);if(winner==null)throw e;return replay(winner,hash,user);}
        db.update("INSERT INTO commerce_batch_creation_modes(batch_id,organization_id,mode) VALUES(?,?,'CREATIVE_ONLY')",id,user.organizationId());
        for(var capture:selected)batches.insertItem(new CommerceBatchItem(UUID.randomUUID().toString(),user.organizationId(),id,capture.getId(),capture.getVersion(),null,"仅创作 · 不上架",0,"DRAFT",now));
        return views.get(id,user);
    }
    private CommerceBatchService.View replay(CommerceBatch batch,String hash,PlatformPrincipal user){if(!hash.equals(batch.requestHash()))throw bad("请求编号已用于其他批次");for(var item:batches.items(user.organizationId(),batch.id()))captures.get(item.captureId(),user);return views.get(batch.id(),user);}
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
