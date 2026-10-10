package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import com.yingdao.aicommerce.shop.mapper.ShopOperatingSettingsMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class CommerceBatchService {
    public record Target(String captureId,String shopId) {}
    public record CreateRequest(String name,String templateSnapshotId,List<Target> targets,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String printTemplateSnapshotId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            List<CommerceBatchCreativePlanValidator.ReusedDesign> reusedArtworks,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String renderPolicy) {
        public CreateRequest(String name,String templateSnapshotId,List<Target> targets,String printTemplateSnapshotId,List<CommerceBatchCreativePlanValidator.ReusedDesign> reusedArtworks){this(name,templateSnapshotId,targets,printTemplateSnapshotId,reusedArtworks,null);}
        public CreateRequest(String name,String templateSnapshotId,List<Target> targets,String printTemplateSnapshotId){this(name,templateSnapshotId,targets,printTemplateSnapshotId,null);}
        public CreateRequest(String name,String templateSnapshotId,List<Target> targets){this(name,templateSnapshotId,targets,null);}
    }
    public record View(String id,String name,String templateSnapshotId,String status,
                       Instant createdAt,List<CommerceBatchItem> items) {}
    private final CommerceBatchMapper batches;
    private final CommerceTemplateMapper templates;
    private final ProductCaptureMapper captures;
    private final ShopMapper shops;
    private final ObjectMapper json;
    private final ShopOperatingSettingsMapper operatingSettings;
    private final CommerceBatchCreativePlanService creativePlans;
    public CommerceBatchService(CommerceBatchMapper batches,CommerceTemplateMapper templates,
            ProductCaptureMapper captures,ShopMapper shops,ObjectMapper json,ShopOperatingSettingsMapper operatingSettings) {
        this(batches,templates,captures,shops,json,operatingSettings,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CommerceBatchService(CommerceBatchMapper batches,CommerceTemplateMapper templates,
            ProductCaptureMapper captures,ShopMapper shops,ObjectMapper json,ShopOperatingSettingsMapper operatingSettings,
            CommerceBatchCreativePlanService creativePlans) {
        this.batches=batches; this.templates=templates; this.captures=captures; this.shops=shops; this.json=json; this.operatingSettings=operatingSettings;this.creativePlans=creativePlans;
    }
    public List<View> list(PlatformPrincipal user) {
        access(user,false);
        return batches.list(user.organizationId()).stream().map(b->view(b,List.of())).toList();
    }
    public View get(String id,PlatformPrincipal user) {
        access(user,false);
        var b=batches.get(user.organizationId(),id);
        if(b==null) throw error(HttpStatus.NOT_FOUND,"批次不存在");
        return view(b,batches.items(user.organizationId(),id));
    }
    @Transactional
    public View create(CreateRequest r,String key,PlatformPrincipal user) {
        access(user,true);
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw bad("需要8至128位幂等键");
        if(r==null || r.name()==null || r.name().isBlank() || r.name().trim().length()>120
                || r.targets()==null || r.targets().isEmpty() || r.targets().size()>500)
            throw bad("请填写批次名并选择1至500条商品店铺组合");
        if(r.targets().stream().anyMatch(t->t==null || t.captureId()==null || t.captureId().isBlank() || t.shopId()==null || t.shopId().isBlank())) throw bad("商品和店铺不能为空");
        if(new HashSet<>(r.targets()).size()!=r.targets().size()) throw bad("同一商品与店铺组合不能重复");
        int actualShopCount=(int)r.targets().stream().map(Target::shopId).distinct().count();
        if(actualShopCount<1||actualShopCount>50)throw bad("请选择1至50家目标店铺");
        try {CommerceBatchCreativePlanValidator.validateRenderPolicy(r.renderPolicy());}
        catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
        String hash=hash(json.writeValueAsString(r));
        var existing=batches.byKey(user.organizationId(),key);
        if(existing!=null) return replay(existing,hash,user);
        var snapshot=templates.snapshot(user.organizationId(),r.templateSnapshotId());
        if(snapshot==null) throw error(HttpStatus.NOT_FOUND,"模板版本不存在");
        if(!"WORKFLOW".equals(snapshot.kind()))throw bad("批次只能绑定铺货工作流模板");
        var c=CommerceTemplateShopResolver.withoutDestinations(
                json.readValue(snapshot.configJson(),CommerceBatchPlanner.Request.class),actualShopCount);
        String printSnapshot=r.printTemplateSnapshotId()==null?c.printTemplateSnapshotId():r.printTemplateSnapshotId();
        if(printSnapshot==null&&r.renderPolicy()!=null)throw bad("选择出图方式前请先选择商品印花模板");
        if(printSnapshot!=null){
            var print=templates.snapshot(user.organizationId(),printSnapshot);
            if(print==null||!"PRINT_LAYOUT".equals(print.kind()))throw bad("请选择本公司的商品印花模板版本");
            var printConfig=json.readValue(print.configJson(),CommercePrintTemplateService.Configuration.class);
            CommercePrintTemplateService.validate(printConfig);
            try {CommerceBatchCreativePlanValidator.validateRenderTemplate(r.renderPolicy(),printConfig);}
            catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
            if(CommerceSurfaceTemplateInputs.enabled(printConfig))CommerceSurfaceTemplateInputs.validate(printConfig);
            if(creativePlans==null)throw bad("创作配置服务尚未就绪");
            if(!user.permissionCodes().contains("product:publish"))throw error(HttpStatus.FORBIDDEN,"创建裂变配置需要商品发布权限");
        }
        if(r.reusedArtworks()!=null){
            if(printSnapshot==null)throw bad("请先选择已保存四面印花位置的模板");
            var print=templates.snapshot(user.organizationId(),printSnapshot);
            try {CommerceBatchCreativePlanValidator.validateReusedDesigns(r.reusedArtworks(),json.readValue(print.configJson(),CommercePrintTemplateService.Configuration.class));}
            catch(IllegalArgumentException failure){throw bad(failure.getMessage());}
            c=new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),r.reusedArtworks().size(),c.imagesPerDesign(),c.shopCount(),c.dailyListingsPerShop(),c.rightsConfirmed(),c.supplyConfirmed(),c.productionFilesConfirmed(),c.dailyBudgetPerShop(),c.observationDays(),c.targetRoi(),c.shopIds(),c.shopTargets(),c.budgetCurrency(),c.printTemplateSnapshotId());
        }
        new CommerceBatchPlanner().preview(c);
        Map<String,Integer> counts=new LinkedHashMap<>();
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        List<CommerceBatchItem> items=new ArrayList<>();
        Set<String> environments=new HashSet<>();
        for(Target t:r.targets()) {
            var capture=captures.selectById(t.captureId());
            if(capture==null || !user.organizationId().equals(capture.getOrganizationId())) throw error(HttpStatus.NOT_FOUND,"商品不存在或不可访问");
            var shop=shops.selectById(t.shopId());
            if(shop==null || !user.organizationId().equals(shop.getOrganizationId())) throw error(HttpStatus.NOT_FOUND,"店铺不存在或不可访问");
            if(!"ACTIVE".equals(shop.getStatus())) throw bad("目标店铺授权不可用");
            // The selected shop is authoritative for the destination, including cross-market templates.
            CommerceTemplateShopResolver.targetFor(c,shop,user.organizationId());
            var operating=operatingSettings.get(user.organizationId(),t.shopId());
            // Manual operating labels are optional; credentials determine API routing.
            if(operating!=null && ("TEST".equals(operating.environment()) || "LIVE".equals(operating.environment())))
                environments.add(operating.environment());
            if(environments.size()>1)throw bad("同一批次不能混用测试店铺与正式店铺");
            int dailyLimit=Math.min(c.dailyListingsPerShop(),operating==null?50:operating.dailyListingLimit());
            if(dailyLimit<1)throw bad("目标店铺每日刊登额度不可用，请更新店铺设置");
            int position=counts.merge(t.shopId(),1,Integer::sum);
            items.add(new CommerceBatchItem(UUID.randomUUID().toString(),user.organizationId(),id,
                    capture.getId(),capture.getVersion(),shop.getId(),shop.getName(),
                    (position-1)/dailyLimit+1,"DRAFT",now));
        }
        var batch=new CommerceBatch(id,user.organizationId(),r.name().trim(),snapshot.id(),key,hash,"DRAFT",user.userId(),now);
        try { batches.insert(batch); }
        catch(DuplicateKeyException e) {
            var winner=batches.byKey(user.organizationId(),key);
            if(winner==null) throw error(HttpStatus.CONFLICT,"批次正在创建，请使用原幂等键重试");
            return replay(winner,hash,user);
        }
        items.forEach(batches::insertItem);
        if(printSnapshot!=null){
            if(r.renderPolicy()!=null)creativePlans.initialize(id,printSnapshot,c.designsPerProduct(),r.reusedArtworks(),r.renderPolicy(),user);
            else if(r.reusedArtworks()==null)creativePlans.initialize(id,printSnapshot,c.designsPerProduct(),user);
            else creativePlans.initialize(id,printSnapshot,c.designsPerProduct(),r.reusedArtworks(),user);
        }
        return view(batch,List.copyOf(items));
    }
    private View replay(CommerceBatch b,String hash,PlatformPrincipal u) {
        if(!hash.equals(b.requestHash())) throw error(HttpStatus.CONFLICT,"幂等键已用于不同批次内容");
        return view(b,batches.items(u.organizationId(),b.id()));
    }
    private static View view(CommerceBatch b,List<CommerceBatchItem> items) {
        return new View(b.id(),b.name(),b.templateSnapshotId(),b.status(),b.createdAt(),items);
    }
    private static String hash(String s) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void access(PlatformPrincipal u,boolean edit) {
        if(u==null || u.organizationId()==null || u.organizationId().isBlank()
                || (edit ? !u.permissionCodes().contains("product:edit") :
                !(u.permissionCodes().contains("product:view") || u.permissionCodes().contains("product:publish"))))
            throw error(HttpStatus.FORBIDDEN,"无权访问本公司批次");
    }
    private static BusinessException bad(String m){return error(HttpStatus.BAD_REQUEST,m);}
    private static BusinessException error(HttpStatus status,String m){return new BusinessException(status,m);}
}
