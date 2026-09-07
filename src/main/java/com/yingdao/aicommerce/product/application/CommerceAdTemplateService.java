package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceTemplateVersion;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class CommerceAdTemplateService {
    public record Policy(String currency,int observationDays,long minimumImpressions,long minimumOrders,
            BigDecimal totalBudgetCap,BigDecimal optimizeThreshold,BigDecimal campaignTarget,BigDecimal retestTarget) {}
    public record Save(String templateId,int expectedVersion,String name,Policy policy) {}
    public record View(String templateId,int version,String snapshotId,String name,Policy policy,Instant createdAt) {}
    private final CommerceTemplateMapper mapper;private final CommerceAdEvaluator evaluator;private final ObjectMapper json;
    public CommerceAdTemplateService(CommerceTemplateMapper mapper,CommerceAdEvaluator evaluator,ObjectMapper json){this.mapper=mapper;this.evaluator=evaluator;this.json=json;}
    public List<View> list(PlatformPrincipal user){access(user,false);return mapper.listKind(user.organizationId(),"AD_TEST").stream().map(this::view).toList();}
    public List<View> history(String id,PlatformPrincipal user){access(user,false);return mapper.history(user.organizationId(),validId(id)).stream().filter(t->"AD_TEST".equals(t.kind())).map(this::view).toList();}
    @Transactional
    public View save(Save r,PlatformPrincipal user){
        access(user,true);
        if(r==null||r.name()==null||r.name().isBlank()||r.name().trim().length()>120||r.expectedVersion()<0||r.expectedVersion()==Integer.MAX_VALUE||r.policy()==null)throw error(HttpStatus.BAD_REQUEST,"请填写模板名称、有效版本和测款规则");
        Policy p=r.policy();
        evaluator.evaluate(new CommerceAdEvaluator.Request(p.currency(),0,p.observationDays(),0,0,p.minimumImpressions(),p.minimumOrders(),BigDecimal.ZERO,BigDecimal.ZERO,p.totalBudgetCap(),p.optimizeThreshold(),p.campaignTarget(),p.retestTarget(),false));
        String id=r.templateId()==null?UUID.randomUUID().toString():validId(r.templateId());
        if(r.expectedVersion()>0){
            if(mapper.lockRoot(user.organizationId(),id)==null)throw error(HttpStatus.NOT_FOUND,"广告模板不存在");
            var current=mapper.latest(user.organizationId(),id);
            if(!"AD_TEST".equals(current.kind()))throw error(HttpStatus.BAD_REQUEST,"模板类型不匹配");
            if(current.version()!=r.expectedVersion())throw error(HttpStatus.CONFLICT,"模板已更新，请刷新后保存新版本");
        }
        var row=new CommerceTemplateVersion(UUID.randomUUID().toString(),user.organizationId(),id,r.expectedVersion()+1,"AD_TEST",r.name().trim(),json.writeValueAsString(p),user.userId(),Instant.now());
        try{mapper.insert(row);}catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"模板版本已存在，请刷新");}
        return view(row);
    }
    private View view(CommerceTemplateVersion row){return new View(row.templateId(),row.version(),row.id(),row.name(),json.readValue(row.configJson(),Policy.class),row.createdAt());}
    private static String validId(String id){if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw error(HttpStatus.BAD_REQUEST,"模板ID无效");return id;}
    private static void access(PlatformPrincipal u,boolean write){if(u==null||u.organizationId()==null||u.organizationId().isBlank()||!(write?u.permissionCodes().contains("product:edit"):u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))throw error(HttpStatus.FORBIDDEN,"无广告测款模板权限");}
    private static BusinessException error(HttpStatus status,String message){return new BusinessException(status,message);}
}
