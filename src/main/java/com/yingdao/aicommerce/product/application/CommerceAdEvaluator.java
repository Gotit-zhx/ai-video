package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Pure planning only. No account access, campaign mutation, or product deletion. */
@Service
public class CommerceAdEvaluator {
    public record Request(String currency, int elapsedDays, int observationDays,
            long impressions, long attributedOrders, long minimumImpressions, long minimumOrders,
            BigDecimal spend, BigDecimal netAttributedGmv, BigDecimal totalBudgetCap,
            BigDecimal optimizeThreshold, BigDecimal campaignTarget, BigDecimal retestTarget,
            boolean attributionSettled) {}
    public record Result(String mode,String decision,BigDecimal observedRoas,
            BigDecimal remainingBudget,BigDecimal proposedRetestTarget,List<String> reasons,
            List<String> suggestedActions,List<String> limitations) {}

    public Result evaluate(Request r){
        if(r==null)throw bad("缺少测款数据");
        if(r.currency()==null||!Set.of("PHP","SGD","MYR","THB","VND","IDR").contains(r.currency()))throw bad("请选择支持的站点币种，金额必须使用同一币种");
        if(r.elapsedDays()<0||r.elapsedDays()>3650||r.observationDays()<1||r.observationDays()>90)throw bad("观察周期须为1至90天");
        if(r.impressions()<0||r.attributedOrders()<0||r.minimumImpressions()<1||r.minimumOrders()<1)throw bad("样本数不能为负，最低曝光和订单样本须大于0");
        nonnegative(r.spend(),"花费");nonnegative(r.netAttributedGmv(),"净归因GMV");
        positive(r.totalBudgetCap(),"总预算上限");positive(r.optimizeThreshold(),"优化门槛");
        positive(r.campaignTarget(),"计划目标");positive(r.retestTarget(),"重测目标");
        if(r.optimizeThreshold().compareTo(r.campaignTarget())>0||r.retestTarget().compareTo(r.campaignTarget())<0)throw bad("优化门槛不能高于计划目标，重测目标不能低于原目标");
        BigDecimal remaining=r.totalBudgetCap().subtract(r.spend()).max(BigDecimal.ZERO);
        BigDecimal roas=r.spend().signum()==0?null:r.netAttributedGmv().divide(r.spend(),4,RoundingMode.HALF_UP);
        String decision;List<String> reasons,actions;BigDecimal next=null;
        if(r.spend().compareTo(r.totalBudgetCap())>=0){
            decision="BUDGET_LIMIT_REVIEW";reasons=List.of("已达到或超过本次测款总预算上限");
            actions=List.of("人工检查并暂停继续消耗，不自动加预算","核对延迟归因后再决定下一轮计划");
        }else if(r.elapsedDays()<r.observationDays()){
            decision="OBSERVE";reasons=List.of("尚未达到完整观察周期");actions=List.of("在现有预算上限内继续观察，不根据短期波动删除链接");
        }else if(!r.attributionSettled()){
            decision="WAIT_ATTRIBUTION";reasons=List.of("归因窗口尚未结算或数据仍不完整");actions=List.of("等待报表结算后重新评估，不把延迟订单视为零转化");
        }else if(r.spend().signum()==0||r.impressions()<r.minimumImpressions()){
            decision="INSUFFICIENT_DELIVERY";reasons=List.of("花费或曝光样本不足以判断商品表现");actions=List.of("检查计划投放状态、目标设置、审核及商品资格","不因无曝光直接删除商品");
        }else if(r.attributedOrders()<r.minimumOrders()){
            decision="LOW_CONVERSION_REVIEW";reasons=List.of("曝光已有样本，但订单样本不足；不能判定为稳定爆款");actions=List.of("人工复核点击、价格、详情及转化链路","按剩余预算决定暂停或继续测试，不自动延长预算");
        }else if(r.netAttributedGmv().compareTo(r.spend().multiply(r.optimizeThreshold()))>=0){
            decision="OPTIMIZE_CANDIDATE";reasons=List.of("完整观察周期内，足够样本的净归因ROAS达到优化门槛");
            actions=List.of("列入AI短视频优化候选","筛选达人后人工确认寄样成本与收件信息","核算退款、货品、物流及平台费用后再决定扩量");
        }else{
            decision="RETEST_REVIEW";reasons=List.of("完整观察周期及足够样本下未达到优化门槛");next=r.retestTarget();
            actions=List.of("人工确认暂停原计划并保留报表","可用新的独立预算评估更高目标的重测计划；提高目标不保证提高实际回报","优先下架观察，不自动永久删除链接");
        }
        return new Result("PREVIEW_ONLY",decision,roas,remaining,next,reasons,actions,
                List.of("数据由用户提供，尚未连接平台广告报表；需保持站点、币种、归因口径与统计周期一致",
                        "净归因GMV/广告花费为ROAS，不等于净利润ROI；退款后的GMV由调用方核对",
                        "只给出建议，不创建、修改、暂停或删除广告，不上架、下架或删除商品"));
    }
    private static void nonnegative(BigDecimal value,String name){if(value==null||value.signum()<0||value.precision()>24||Math.abs((long)value.scale())>8)throw bad(name+"必须为有效非负金额，最多8位小数");}
    private static void positive(BigDecimal value,String name){nonnegative(value,name);if(value.signum()==0)throw bad(name+"必须大于0");}
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,message);}
}
