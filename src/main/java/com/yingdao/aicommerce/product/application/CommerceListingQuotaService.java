package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@Service
public class CommerceListingQuotaService {
    public record Request(LocalDate firstListingDate,boolean confirmed) {}
    private final CommerceBatchMapper batches;
    private final CommerceListingQuotaMapper quota;
    private final ShopMapper shops;
    private final ShopOperatingSettingsMapper settings;
    public CommerceListingQuotaService(CommerceBatchMapper batches,CommerceListingQuotaMapper quota,
            ShopMapper shops,ShopOperatingSettingsMapper settings){this.batches=batches;this.quota=quota;this.shops=shops;this.settings=settings;}
    public List<CommerceListingReservation> list(String batch,PlatformPrincipal user){
        access(user,false);
        if(batches.get(user.organizationId(),batch)==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        return quota.list(user.organizationId(),batch);
    }
    @Transactional
    public List<CommerceListingReservation> reserve(String batchId,Request request,PlatformPrincipal user){
        access(user,true);
        if(request==null || request.firstListingDate()==null || !request.confirmed())throw error(HttpStatus.BAD_REQUEST,"请确认预留起始日期");
        var batch=batches.lock(user.organizationId(),batchId);
        if(batch==null)throw error(HttpStatus.NOT_FOUND,"批次不存在");
        if(!"DRAFT".equals(batch.status()))throw error(HttpStatus.CONFLICT,"仅草稿批次可以预留额度");
        var items=batches.items(user.organizationId(),batchId);
        if(items.isEmpty())throw error(HttpStatus.CONFLICT,"空批次不能预留额度");
        var previous=quota.list(user.organizationId(),batchId);
        if(!previous.isEmpty()){
            Map<String,LocalDate> expected=new HashMap<>();
            items.forEach(i->expected.put(i.id(),request.firstListingDate().plusDays(i.plannedDay()-1)));
            if(previous.size()!=items.size() || previous.stream().anyMatch(r->!r.listingDate().equals(expected.get(r.itemId()))))
                throw error(HttpStatus.CONFLICT,"批次已预留不同排期；当前不支持自动改期");
            return previous;
        }
        record Day(String shop,LocalDate date) implements Comparable<Day>{
            public int compareTo(Day other){int s=shop.compareTo(other.shop);return s!=0?s:date.compareTo(other.date);}
        }
        Map<Day,List<CommerceBatchItem>> groups=new TreeMap<>();
        Map<String,Integer> caps=new HashMap<>();Map<String,String> zones=new HashMap<>();Set<String> environments=new HashSet<>();
        for(var item:items){
            var shop=shops.selectById(item.shopId());
            if(shop==null||!user.organizationId().equals(shop.getOrganizationId())||!"ACTIVE".equals(shop.getStatus()))
                throw error(HttpStatus.CONFLICT,"店铺授权不可用");
            var setting=settings.get(user.organizationId(),item.shopId());
            if(setting==null||!Set.of("TEST","LIVE").contains(setting.environment()))throw error(HttpStatus.CONFLICT,"店铺环境未确认");
            environments.add(setting.environment());
            String zone=zone(shop.getRegion());zones.put(item.shopId(),zone);caps.put(item.shopId(),setting.dailyListingLimit());
            LocalDate date=request.firstListingDate().plusDays(item.plannedDay()-1);
            LocalDate today=LocalDate.now(ZoneId.of(zone));
            if(date.isBefore(today)||date.isAfter(today.plusDays(90)))throw error(HttpStatus.BAD_REQUEST,"预留日期须在店铺当地今天至90天内");
            groups.computeIfAbsent(new Day(item.shopId(),date),k->new ArrayList<>()).add(item);
        }
        if(environments.size()>1)throw error(HttpStatus.CONFLICT,"测试与正式店铺不能混用");
        List<CommerceListingReservation> result=new ArrayList<>();
        // All batches take daily rows in the same order; each conditional increment is atomic.
        for(var group:groups.entrySet()){
            Day d=group.getKey();quota.ensureDay(user.organizationId(),d.shop(),d.date());
            if(quota.reserve(user.organizationId(),d.shop(),d.date(),group.getValue().size(),caps.get(d.shop()))!=1)
                throw error(HttpStatus.CONFLICT,"店铺 "+d.shop()+" 在 "+d.date()+" 的额度不足，请选择其他日期");
            for(var item:group.getValue()){
                var reservation=new CommerceListingReservation(UUID.randomUUID().toString(),user.organizationId(),batchId,
                        item.id(),item.shopId(),d.date(),zones.get(d.shop()),user.userId(),Instant.now());
                quota.insert(reservation);result.add(reservation);
            }
        }
        return List.copyOf(result);
    }
    static String zone(String region){
        return switch(region==null?"":region.toUpperCase(Locale.ROOT)){
            case "PH"->"Asia/Manila";case "SG"->"Asia/Singapore";case "MY"->"Asia/Kuala_Lumpur";
            case "TH"->"Asia/Bangkok";case "VN"->"Asia/Ho_Chi_Minh";case "ID"->"Asia/Jakarta";
            default->throw error(HttpStatus.BAD_REQUEST,"当前站点未配置额度时区");};
    }
    private static void access(PlatformPrincipal u,boolean edit){
        if(u==null||u.organizationId()==null||!(edit?u.permissionCodes().contains("product:publish"):
                u.permissionCodes().contains("product:view")||u.permissionCodes().contains("product:publish")))
            throw error(HttpStatus.FORBIDDEN,"无额度管理权限");
    }
    private static BusinessException error(HttpStatus s,String m){return new BusinessException(s,m);}
}
