package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import com.yingdao.aicommerce.product.mapper.CommerceBatchReviewMapper;
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
    public CommerceBatchPreflightService(CommerceBatchService batches,CommerceTemplateMapper templates,
            ProductCaptureService captures,ShopMapper shops,ShopOperatingSettingsMapper operating,ObjectMapper json,CommerceBatchReviewMapper reviews){
        this.batches=batches;this.templates=templates;this.captures=captures;this.shops=shops;this.operating=operating;this.json=json;this.reviews=reviews;
    }
    public Report check(String id,PlatformPrincipal user){
        var batch=batches.get(id,user);
        var snapshot=templates.snapshot(user.organizationId(),batch.templateSnapshotId());
        if(snapshot==null)throw new BusinessException(HttpStatus.CONFLICT,"批次模板快照不可用");
        var config=json.readValue(snapshot.configJson(),CommerceBatchPlanner.Request.class);
        List<String> global=new ArrayList<>();
        if(reviews.get(user.organizationId(),id)==null)
            global.add("批次尚未绑定使用权、供货和质量审核证据；需要针对本批实际商品审批");
        global.add("尚未预留跨批次全店每日额度，也未验证官方API环境与店铺环境的一致性");
        global.add("当前批次执行器未开放；检查不会生成、发布、投放或删除");
        if(config.mode()==CommerceBatchPlanner.Mode.POD_DESIGN)
            global.add("POD需逐设计绑定真实基础款、印刷区域、生产文件及生产审核结果");
        List<ItemCheck> checks=new ArrayList<>();Set<String> environments=new HashSet<>();
        Map<String,Integer> dailyCounts=new HashMap<>();
        for(var item:batch.items())dailyCounts.merge(item.shopId()+":"+item.plannedDay(),1,Integer::sum);
        for(var item:batch.items()){
            List<String> issues=new ArrayList<>();
            try{
                var detail=captures.get(item.captureId(),user);
                if(detail.capture().getVersion()!=item.captureVersion())issues.add("商品版本已变化，请重新建批次以固定新版本");
                if(!Set.of("APPROVED","PUBLISHED").contains(detail.capture().getStatus()))issues.add("商品尚未完成人工审核");
                var missing=captures.approvalMissingFields(detail.capture(),detail.skus(),detail.images());
                missing.forEach(m->issues.add("商品资料缺失："+m));
            }catch(BusinessException e){
                if(e.getStatus()!=HttpStatus.NOT_FOUND && e.getStatus()!=HttpStatus.FORBIDDEN)throw e;
                issues.add("商品不存在或当前用户不可访问");
            }
            var shop=shops.selectById(item.shopId());
            if(shop==null || !user.organizationId().equals(shop.getOrganizationId()))issues.add("店铺不存在或不可访问");
            else{
                if(!"ACTIVE".equals(shop.getStatus()))issues.add("店铺授权状态不可用");
                if(!config.platform().name().equals(shop.getPlatform())||!config.market().equalsIgnoreCase(shop.getRegion()))issues.add("店铺平台或站点与模板不一致");
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
}
