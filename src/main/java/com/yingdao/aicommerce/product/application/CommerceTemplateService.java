package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CommerceTemplateService {
    public record SaveRequest(String templateId, int expectedVersion, String name,
                              CommerceBatchPlanner.Request configuration) {}
    public record View(String templateId, int version, String snapshotId, String name,
                       CommerceBatchPlanner.Request configuration, Instant createdAt) {}
    private final CommerceTemplateMapper mapper;
    private final CommerceBatchPlanner planner;
    private final ObjectMapper json;
    public CommerceTemplateService(CommerceTemplateMapper mapper, CommerceBatchPlanner planner, ObjectMapper json) {
        this.mapper=mapper; this.planner=planner; this.json=json;
    }
    public List<View> list(PlatformPrincipal user) {
        readAccess(user);
        return mapper.list(user.organizationId()).stream().map(this::view).toList();
    }
    public List<View> history(String id, PlatformPrincipal user) {
        readAccess(user);
        return mapper.history(user.organizationId(), validId(id)).stream().map(this::view).toList();
    }
    @Transactional
    public View save(SaveRequest r, PlatformPrincipal user) {
        readAccess(user);
        if (!user.permissionCodes().contains("product:edit"))
            throw new BusinessException(HttpStatus.FORBIDDEN,"保存模板需要商品编辑权限");
        if (r==null || r.name()==null || r.name().isBlank() || r.name().trim().length()>120
                || r.expectedVersion()<0) throw bad("模板名称须为1至120字，版本不能为负数");
        planner.preview(r.configuration());
        // Readiness declarations are per batch, not reusable evidence in a template.
        var c=r.configuration();
        var clean=new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),
                c.designsPerProduct(),c.imagesPerDesign(),c.shopCount(),c.dailyListingsPerShop(),
                false,false,false,c.dailyBudgetPerShop(),c.observationDays(),c.targetRoi());
        String id=r.templateId()==null ? UUID.randomUUID().toString() : validId(r.templateId());
        if (r.expectedVersion()>0) {
            if (mapper.lockRoot(user.organizationId(),id)==null)
                throw new BusinessException(HttpStatus.NOT_FOUND,"模板不存在");
            var current=mapper.latest(user.organizationId(),id);
            if(current.version()!=r.expectedVersion())
                throw new BusinessException(HttpStatus.CONFLICT,"模板已被更新，请刷新后另存新版本");
        }
        var saved=new CommerceTemplateVersion(UUID.randomUUID().toString(),user.organizationId(),id,
                Math.addExact(r.expectedVersion(),1),"WORKFLOW",r.name().trim(),json.writeValueAsString(clean),
                user.userId(),Instant.now());
        try { mapper.insert(saved); }
        catch (DuplicateKeyException e) { throw new BusinessException(HttpStatus.CONFLICT,"模板版本已存在，请刷新"); }
        return view(saved);
    }
    private View view(CommerceTemplateVersion t) {
        return new View(t.templateId(),t.version(),t.id(),t.name(),
                json.readValue(t.configJson(),CommerceBatchPlanner.Request.class),t.createdAt());
    }
    private static String validId(String id) {
        if(id==null || !id.matches("[0-9a-fA-F-]{36}")) throw bad("模板ID无效");
        return id;
    }
    private static void readAccess(PlatformPrincipal u) {
        if(u==null || u.organizationId()==null || u.organizationId().isBlank()
                || !(u.permissionCodes().contains("product:view") || u.permissionCodes().contains("product:publish")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"无权访问本公司模板");
    }
    private static BusinessException bad(String s) { return new BusinessException(HttpStatus.BAD_REQUEST,s); }
}
