package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pure planning: never creates products, authorizations, AI jobs or advertisements. */
@Service
public class CommerceBatchPlanner {
    public enum Mode { STOCK_MARKETING, POD_DESIGN }
    public enum Platform { SHOPEE, TIKTOK_SHOP, SHEIN, TEMU, AMAZON, ALIEXPRESS }
    public record Request(Mode mode, Platform platform, String market, int sourceProducts,
                          int designsPerProduct, int imagesPerDesign, int shopCount,
                          int dailyListingsPerShop, boolean rightsConfirmed,
                          boolean supplyConfirmed, boolean productionFilesConfirmed,
                          BigDecimal dailyBudgetPerShop, int observationDays,
                          BigDecimal targetRoi) {}
    public record DayAllocation(int day, List<Integer> listingsPerShop) {}
    public record Plan(String status, String templateVersion, String currency,
                       int designVersions, int materialImages, int listingDrafts,
                       int minimumPublishingDays, List<DayAllocation> schedule,
                       BigDecimal dailyBudgetAllShops, BigDecimal observationBudgetCeiling,
                       List<String> blockers, List<String> warnings, List<String> stages) {}

    public Plan preview(Request r) {
        if (r == null || r.mode() == null || r.platform() == null) fail("请选择商品模式和平台");
        String currency = switch (r.market() == null ? "" : r.market()) {
            case "PH" -> "PHP"; case "SG" -> "SGD"; case "MY" -> "MYR";
            case "TH" -> "THB"; case "VN" -> "VND"; case "ID" -> "IDR";
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "当前规划器仅支持六个东南亚市场");
        };
        range(r.sourceProducts(), 1, 500, "原始商品数");
        range(r.designsPerProduct(), 1, 20, "每款设计数");
        range(r.imagesPerDesign(), 1, 20, "每个设计的图片数");
        range(r.shopCount(), 1, 50, "店铺数");
        range(r.dailyListingsPerShop(), 1, 500, "每日每店刊登额度");
        range(r.observationDays(), 1, 90, "观察天数");
        if (r.dailyBudgetPerShop() == null || r.dailyBudgetPerShop().signum() < 0
                || r.dailyBudgetPerShop().compareTo(new BigDecimal("1000000000")) > 0
                || r.dailyBudgetPerShop().scale() > 2) fail("预算须为非负金额，最多两位小数且不超过十亿");
        if (r.targetRoi() == null || r.targetRoi().signum() <= 0
                || r.targetRoi().compareTo(new BigDecimal("1000")) > 0) fail("目标 ROI 须大于0且不超过1000");

        int versions = r.sourceProducts() * r.designsPerProduct();
        int images = versions * r.imagesPerDesign();
        // A change of marketing artwork is not a new physical product.
        int drafts = r.mode() == Mode.POD_DESIGN ? versions : r.sourceProducts();
        int days = (drafts + r.shopCount() * r.dailyListingsPerShop() - 1)
                / (r.shopCount() * r.dailyListingsPerShop());
        List<DayAllocation> schedule = new ArrayList<>();
        int remaining = drafts;
        for (int day = 1; day <= days; day++) {
            int[] allocation = new int[r.shopCount()];
            for (int slot = 0; slot < r.dailyListingsPerShop() && remaining > 0; slot++) {
                for (int shop = 0; shop < r.shopCount() && remaining > 0; shop++) {
                    allocation[shop]++; remaining--;
                }
            }
            schedule.add(new DayAllocation(day, java.util.Arrays.stream(allocation).boxed().toList()));
        }
        List<String> blockers = new ArrayList<>();
        if (!r.rightsConfirmed()) blockers.add("需确认图片、设计及品牌元素的使用权；风险检测不等于授权");
        if (!r.supplyConfirmed()) blockers.add("需确认供货 SKU、成本、物流及履约能力");
        if (r.mode() == Mode.POD_DESIGN && !r.productionFilesConfirmed())
            blockers.add("POD 需绑定可生产基础款及合格生产图，效果图不能代替生产文件");
        blockers.add("正式执行前还需验证具体店铺授权、平台站点能力、商品必填属性和素材质检");
        if (r.dailyBudgetPerShop().signum() > 0)
            blockers.add("广告仅为预算预估；尚未验证广告账户权限、GMV Max商品覆盖及API能力");
        List<String> warnings = new ArrayList<>();
        warnings.add("店铺数为规划占位，不代表已授权店铺；当前不会保存或执行任务");
        warnings.add("跨店分配不复制每个商品到所有店铺；排期是质量与平台审核通过后的理论最短时间");
        warnings.add("预算口径为每店每日总上限，不能为每日新增计划重复叠加；不是实际花费预测");
        warnings.add("观察周期从实际投放开始；ROI不代表利润，样本不足不能自动判失败");
        if (r.mode() == Mode.STOCK_MARKETING)
            warnings.add("现货裂变仅生成营销素材，不新增实物款式；不自动生成背面印花或重复刊登");
        if (r.platform() != Platform.SHOPEE && r.platform() != Platform.TIKTOK_SHOP)
            warnings.add("该平台当前仅支持规划，采集、刊登与广告适配尚未接入本工作流");
        BigDecimal daily = r.dailyBudgetPerShop().multiply(BigDecimal.valueOf(r.shopCount()));
        return new Plan("PREVIEW_ONLY", "commerce-plan-v1", currency, versions, images, drafts,
                days, List.copyOf(schedule), daily, daily.multiply(BigDecimal.valueOf(r.observationDays())),
                List.copyOf(blockers), List.copyOf(warnings), List.of("选品采集", "资料与权利检查",
                "模板与供货匹配", "素材生成", "质量复核", "分店刊登审批", "发布与状态核对",
                "广告权限与预算审批", "测款观察", "优化或暂停下架"));
    }

    private static void range(int value, int min, int max, String label) {
        if (value < min || value > max) fail(label + "须在" + min + "至" + max + "之间");
    }
    private static void fail(String message) { throw new BusinessException(HttpStatus.BAD_REQUEST, message); }
}
