package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchItem;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchReviewMapper;
import com.yingdao.aicommerce.product.mapper.CommerceListingQuotaMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchCreativePlanMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchExecutionMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import com.yingdao.aicommerce.shop.mapper.ShopOperatingSettingsMapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Read-only revalidation. This report is not an execution permit. */
@Service
public class CommerceBatchPreflightService {
    public record ItemCheck(String itemId,String captureId,String shopId,List<String> blockers) {}
    public record Report(String batchId,Instant checkedAt,boolean executable,int checkedItems,
                         int itemsWithoutDataBlockers,List<String> batchBlockers,List<ItemCheck> items) {}
    private final CommerceBatchService batches;
    private final CommerceTemplateMapper templates;
    private final ProductCaptureService captures;
    private final ShopMapper shops;
    private final ShopOperatingSettingsMapper operating;
    private final ObjectMapper json;
    private final CommerceBatchReviewMapper reviews;
    private final CommerceListingQuotaMapper quota;
    private final CommerceBatchCreativePlanMapper plans;
    private final CommerceBatchExecutionMapper executions;
    public CommerceBatchPreflightService(CommerceBatchService batches,CommerceTemplateMapper templates,
            ProductCaptureService captures,ShopMapper shops,ShopOperatingSettingsMapper operating,ObjectMapper json,CommerceBatchReviewMapper reviews,CommerceListingQuotaMapper quota){
        this(batches,templates,captures,shops,operating,json,reviews,quota,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CommerceBatchPreflightService(CommerceBatchService batches,CommerceTemplateMapper templates,
            ProductCaptureService captures,ShopMapper shops,ShopOperatingSettingsMapper operating,ObjectMapper json,
            CommerceBatchReviewMapper reviews,CommerceListingQuotaMapper quota,
            CommerceBatchCreativePlanMapper plans,CommerceBatchExecutionMapper executions){
        this.batches=batches;this.templates=templates;this.captures=captures;this.shops=shops;this.operating=operating;this.json=json;this.reviews=reviews;this.quota=quota;this.plans=plans;this.executions=executions;
    }
    public Report check(String id,PlatformPrincipal user){
        var batch=batches.get(id,user);
        var snapshot=templates.snapshot(user.organizationId(),batch.templateSnapshotId());
        if(snapshot==null)throw new BusinessException(HttpStatus.CONFLICT,"批次模板快照不可用");
        if(!"WORKFLOW".equals(snapshot.kind()))return new Report(id,Instant.now(),false,batch.items().size(),0,List.of("这是仅创作批次，没有目标店铺和发布配置，不会执行上架"),List.of());
        var config=json.readValue(snapshot.configJson(),CommerceBatchPlanner.Request.class);
        boolean localPhoto=localPhotoBatch(id,user);
        List<String> global=new ArrayList<>();
        long actualShops=batch.items().stream().map(CommerceBatchItem::shopId).distinct().count();
        if(actualShops<1||actualShops>50)global.add("批次实际目标店铺数须为1至50家");
        if(!localPhoto&&reviews.get(user.organizationId(),id)==null)
            global.add("批次尚未绑定使用权、供货和质量审核证据；需要针对本批实际商品审批");
        if(!"DRAFT".equals(batch.status()))global.add("批次不是待执行草稿："+batch.status());
        var reservations=quota.list(user.organizationId(),id);
        var reservedItems=new HashMap<String,com.yingdao.aicommerce.product.domain.CommerceListingReservation>();
        reservations.forEach(r->reservedItems.put(r.itemId(),r));
        boolean quotaComplete=reservations.size()==batch.items().size() && batch.items().stream().allMatch(item->{
            var r=reservedItems.get(item.id());
            return r!=null && item.shopId().equals(r.shopId()) && "RESERVED".equals(r.status());
        });
        if(!quotaComplete)global.add("实际条目尚未全部绑定有效店铺日额度预留");
        var checkedDays=new HashSet<String>();
        for(var r:reservations){
            if(!checkedDays.add(r.shopId()+":"+r.listingDate()))continue;
            var setting=operating.get(user.organizationId(),r.shopId());
            var count=quota.reservedCount(user.organizationId(),r.shopId(),r.listingDate());
            long batchCount=reservations.stream().filter(v->v.shopId().equals(r.shopId())&&v.listingDate().equals(r.listingDate())).count();
            if(count==null||count<batchCount)global.add("店铺日额度台账不一致："+r.shopId()+" / "+r.listingDate());
            else if(setting!=null && count>setting.dailyListingLimit())global.add("跨批次预留超过店铺当前日限额："+r.shopId()+" / "+r.listingDate());
        }
        if(!localPhoto){
            global.add("尚未验证官方API环境与店铺环境的一致性");
            global.add("批次自动上架尚未开放；本次仅检查发布条件，不会生成、发布、投放或删除");
        }
        if(!localPhoto&&config.mode()==CommerceBatchPlanner.Mode.POD_DESIGN)
            global.add("POD需逐设计绑定真实基础款、印刷区域、生产文件及生产审核结果");
        List<ItemCheck> checks=new ArrayList<>();Set<String> environments=new HashSet<>();
        Map<String,Integer> dailyCounts=new HashMap<>();
        for(var item:batch.items())dailyCounts.merge(item.shopId()+":"+item.plannedDay(),1,Integer::sum);
        for(var item:batch.items()){
            List<String> issues=new ArrayList<>();
            try{
                var detail=captures.get(item.captureId(),user);
                if(detail.capture().getVersion()!=item.captureVersion())issues.add("商品版本已变化，请重新建批次以固定新版本");
                if(!localPhoto&&!Set.of("APPROVED","PUBLISHED").contains(detail.capture().getStatus()))issues.add("商品尚未完成人工审核");
                var missing=captures.approvalMissingFields(detail.capture(),detail.skus(),detail.images());
                missing.forEach(m->issues.add("商品资料缺失："+m));
            }catch(BusinessException e){
                if(e.getStatus()!=HttpStatus.NOT_FOUND && e.getStatus()!=HttpStatus.FORBIDDEN)throw e;
                issues.add("商品不存在或当前用户不可访问");
            }
            var shop=shops.selectById(item.shopId());
            if(shop==null || !user.organizationId().equals(shop.getOrganizationId()))issues.add("店铺不存在或不可访问");
            else{
                try { CommerceTemplateShopResolver.targetFor(config,shop,user.organizationId()); }
                catch(BusinessException e) { issues.add(e.getMessage()); }
                var setting=operating.get(user.organizationId(),item.shopId());
                if(setting==null || !Set.of("TEST","LIVE").contains(setting.environment()))issues.add("店铺环境尚未确认");
                else{
                    environments.add(setting.environment());
                    int cap=Math.min(setting.dailyListingLimit(),config.dailyListingsPerShop());
                    if(dailyCounts.get(item.shopId()+":"+item.plannedDay())>cap)issues.add("店铺额度已调整，本批该日排期超额");
                }
            }
            checks.add(new ItemCheck(item.id(),item.captureId(),item.shopId(),List.copyOf(issues)));
        }
        if(environments.size()>1)global.add("检测到测试店铺和正式店铺混用");
        return new Report(id,Instant.now(),false,checks.size(),(int)checks.stream().filter(i->i.blockers().isEmpty()).count(),List.copyOf(global),List.copyOf(checks));
    }
    private boolean localPhotoBatch(String batch,PlatformPrincipal user){
        if(plans==null||executions==null)return false;
        var run=executions.get(user.organizationId(),batch);
        // Started batches must keep their frozen policy; a newer draft cannot change their path.
        var row=run==null?plans.latest(user.organizationId(),batch):plans.forExecution(user.organizationId(),run.id());
        if(row==null||!user.organizationId().equals(row.organizationId())||!batch.equals(row.batchId()))return false;
        return CommerceBatchCreativePlanValidator.localPhotoOutput(json.readValue(row.planJson(),CommerceBatchCreativePlanValidator.Plan.class));
    }
}
