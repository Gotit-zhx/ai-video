package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.shop.domain.Shop;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Resolves concrete batch destinations; reusable workflow templates carry no shop selection. */
@Service
public class CommerceTemplateShopResolver {
    private final ShopMapper shops;
    public CommerceTemplateShopResolver(ShopMapper shops) { this.shops=shops; }

    public CommerceBatchPlanner.Request forTemplate(CommerceBatchPlanner.Request c) {
        return withoutDestinations(c,1);
    }

    /** Legacy snapshots remain unchanged; use the actual batch shop count when validating their rules. */
    public static CommerceBatchPlanner.Request withoutDestinations(CommerceBatchPlanner.Request c,int shopCount) {
        if(c==null)throw bad("模板配置不能为空");
        String currency=c.budgetCurrency();
        if(currency==null||currency.isBlank())currency=CommerceBatchPlanner.currencyForMarket(c.market());
        return new CommerceBatchPlanner.Request(c.mode(),c.platform(),c.market(),c.sourceProducts(),
                c.designsPerProduct(),c.imagesPerDesign(),shopCount,c.dailyListingsPerShop(),
                c.rightsConfirmed(),c.supplyConfirmed(),c.productionFilesConfirmed(),c.dailyBudgetPerShop(),
                c.observationDays(),c.targetRoi(),List.of(),List.of(),currency,c.printTemplateSnapshotId());
    }

    public CommerceBatchPlanner.Request normalize(CommerceBatchPlanner.Request c,String organizationId) {
        if(c==null)throw bad("模板配置不能为空");
        var ids=c.shopIds()==null ? List.<String>of() : c.shopIds();
        if(ids.size()>50 || ids.stream().anyMatch(id->id==null || id.isBlank())
                || new HashSet<>(ids).size()!=ids.size())throw bad("请选择1至50家不重复的目标店铺");
        var targets=new ArrayList<CommerceBatchPlanner.ShopTarget>();
        for(String id:ids)targets.add(targetFor(c,shops.selectById(id),organizationId));
        // The original budget remains denominated in its original currency even when the first shop changes.
        String budgetCurrency=c.budgetCurrency();
        if(budgetCurrency==null || budgetCurrency.isBlank()) {
            budgetCurrency=c.dailyBudgetPerShop()!=null && c.dailyBudgetPerShop().signum()>0
                    ? CommerceBatchPlanner.currencyForMarket(c.market())
                    : targets.isEmpty() ? CommerceBatchPlanner.currencyForMarket(c.market()) : targets.getFirst().currency();
        }
        var platform=targets.isEmpty() ? c.platform() : targets.getFirst().platform();
        String market=targets.isEmpty() ? c.market() : targets.getFirst().market();
        return new CommerceBatchPlanner.Request(c.mode(),platform,market,c.sourceProducts(),
                c.designsPerProduct(),c.imagesPerDesign(),targets.isEmpty() ? c.shopCount() : targets.size(),
                c.dailyListingsPerShop(),c.rightsConfirmed(),c.supplyConfirmed(),c.productionFilesConfirmed(),
                c.dailyBudgetPerShop(),c.observationDays(),c.targetRoi(),List.copyOf(ids),List.copyOf(targets),budgetCurrency,c.printTemplateSnapshotId());
    }

    /** Revalidate execution destinations; global platform/market are legacy display defaults, not restrictions. */
    public static CommerceBatchPlanner.ShopTarget targetFor(CommerceBatchPlanner.Request c,Shop shop,String organizationId) {
        if(shop==null || organizationId==null || organizationId.isBlank()
                || !organizationId.equals(shop.getOrganizationId()))
            throw new BusinessException(HttpStatus.NOT_FOUND,"店铺不存在或不可访问");
        if(!"ACTIVE".equals(shop.getStatus()))throw bad("目标店铺授权不可用");
        if(c==null)throw bad("模板配置不能为空");
        CommerceBatchPlanner.Platform platform;
        try { platform=CommerceBatchPlanner.Platform.valueOf(shop.getPlatform()); }
        catch(IllegalArgumentException | NullPointerException ex) { throw bad("目标店铺平台暂不支持自动适配"); }
        if(platform!=CommerceBatchPlanner.Platform.SHOPEE && platform!=CommerceBatchPlanner.Platform.TIKTOK_SHOP)
            throw bad("目标店铺平台暂不支持自动适配");
        String market=shop.getRegion()==null ? "" : shop.getRegion().trim().toUpperCase(Locale.ROOT);
        return new CommerceBatchPlanner.ShopTarget(shop.getId(),platform,market,CommerceBatchPlanner.currencyForMarket(market));
    }
    private static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,message); }
}
