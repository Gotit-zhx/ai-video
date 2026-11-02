package com.yingdao.aicommerce.finance;

import java.math.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Display-only bridge from the platform's settled ledger to the configured operating costs. */
public final class FinanceProfitReport {
    private FinanceProfitReport() {}
    public static Map<String,Object> calculate(List<Map<String,Object>> totals,Map<String,Object> costs,
                                               String currency,FinanceFx.Quote quote){
        var result=new LinkedHashMap<String,Object>();
        var notes=new ArrayList<String>();
        BigDecimal costRate="CNY".equals(currency)?BigDecimal.ONE:quote==null?null:quote.rates().get(currency);
        if(costRate!=null&&costRate.signum()<=0)costRate=null;
        // Expose the exact same CNY-per-display-unit rate for lazy cost category/receipt drilldowns.
        result.put("costCnyRate",costRate);
        if(costRate!=null&&quote!=null&&!"CNY".equals(currency))result.put("fx",quote.metadata());
        Map<String,Object> settled=find(totals,"SETTLEMENT"),fees=find(totals,"FEE"),ads=find(totals,"AD"),logistics=find(totals,"COST");
        var cost=map(costs.get("total"));
        BigDecimal net=value(settled),fee=value(fees),ad=value(ads),shipping=value(logistics);
        BigDecimal product=decimal(cost.get("amount_cny")),converted=product;
        String state="READY";
        if(settled==null||number(settled.get("records"))==0){net=null;state="NO_SETTLEMENT";notes.add("当前范围暂无已结算记录");}
        else if(!complete(settled)||!complete(fees)||!complete(ads)||!complete(logistics)){
            state="INCOMPLETE_AMOUNTS";notes.add("部分结算或费用金额尚未同步完整，暂不计算利润");
        }
        boolean productProcessing=Boolean.TRUE.equals(costs.getOrDefault("productProcessing",costs.get("processing")));
        boolean labelProcessing=Boolean.TRUE.equals(costs.get("labelProcessing"));
        if(number(cost.get("pending_count"))>0||product==null||productProcessing){
            converted=null;if(state.equals("READY"))state="INCOMPLETE_COST";
            notes.add("已结算订单成本待补齐或计算中，暂不计算完整利润");
        }else if(!"CNY".equals(currency)&&product.signum()!=0){
            BigDecimal rate=quote==null?null:quote.rates().get(currency);
            if(rate==null||rate.signum()<=0){
                converted=null;if(state.equals("READY"))state="MISSING_FX";
                notes.add("缺少人民币成本到当前币种的汇率，暂不跨币种计算利润");
            }else{
                converted=product.divide(rate,6,RoundingMode.HALF_UP);
                result.put("fx",quote.metadata());
                notes.add("人民币商品成本按同一参考汇率折算为 "+currency);
            }
        }
        BigDecimal label=decimal(cost.get("label_cost_cny"));if(label==null)label=BigDecimal.ZERO;
        // Return inventory credits are disabled. Historical cached inputs must not
        // change profit or block it while Qianyi aftersale documents synchronize.
        result.put("returnCostsPending",false);
        result.put("returnPendingCount",0);
        result.put("returnedProductCostCny",BigDecimal.ZERO);
        if(!"CNY".equals(currency)&&label.signum()!=0){BigDecimal rate=quote==null?null:quote.rates().get(currency);label=rate==null||rate.signum()<=0?null:label.divide(rate,6,RoundingMode.HALF_UP);}
        if(label==null){if(state.equals("READY"))state="MISSING_FX";notes.add("缺少人民币贴单费用展示汇率");}
        if(number(cost.get("charges_pending"))>0){if(state.equals("READY"))state="INCOMPLETE_CHARGES";notes.add("贴单账单存在仓库计费失败或费用来源冲突，未确认部分未计入");}
        if(labelProcessing){if(state.equals("READY"))state="INCOMPLETE_CHARGES";notes.add("贴单费用更新中，已计算的商品成本保留，完成后更新完整利润");}
        shipping=label!=null&&shipping!=null?shipping.add(label):null;
        result.put("marketingBasis","刷单订单的结算金额、关联平台费用和成本已排除；平台原始账单与对账保留");
        boolean estimated=number(cost.get("estimated_count"))>0;
        if(estimated)notes.add("商品成本包含历史月份取价估算");
        var labelConfiguration=map(costs.get("labelConfiguration"));
        result.put("labelConfiguration",labelConfiguration);
        int missingLabels=rows(labelConfiguration.get("missingShops")).size();
        if(missingLabels>0||number(cost.get("label_missing_count"))>0){estimated=true;notes.add("部分订单尚无对应贴单账单，当前只包含已导入并匹配的贴单金额，未匹配部分不代表零成本");}
        for(var item:List.of(new String[]{"AD","广告消耗"},new String[]{"COST","物流贴单成本"})){
            if(item[0].equals("COST")&&!labelConfiguration.isEmpty())continue;
            if(find(totals,item[0])==null){estimated=true;notes.add(item[1]+"尚无记录，按 0 暂计，补录后利润将变化");}
        }
        BigDecimal gross=goods(settled);
        if(net!=null&&gross==null){if(state.equals("READY"))state="INCOMPLETE_AMOUNTS";notes.add("销售收入正在补齐，不用净结算或订单实付代替");}
        BigDecimal profit=state.equals("READY")?net.subtract(ad).subtract(shipping).subtract(converted):null;
        if(state.equals("READY")&&estimated)state="ESTIMATED";
        result.put("currency",currency);result.put("settledGrossAmount",gross);result.put("settledLedgerAmount",net);
        result.put("platformFees",fee);result.put("advertising",ad);result.put("logisticsLabelCost",shipping);
        result.put("productCostCny",product);result.put("productCost",converted);result.put("amount",profit);
        result.put("returnedProductCost",BigDecimal.ZERO);result.put("returnReceiptCount",0);
        BigDecimal known=decimal(cost.get("known_amount_cny"));
        if(known==null)known=product;
        if(known!=null&&!"CNY".equals(currency)&&known.signum()!=0){
            BigDecimal rate=quote==null?null:quote.rates().get(currency);
            known=rate==null||rate.signum()<=0?null:known.divide(rate,6,RoundingMode.HALF_UP);
        }
        result.put("operatingCost",converted!=null&&shipping!=null&&complete(logistics)&&number(cost.get("charges_pending"))==0&&!labelProcessing
                ?converted.add(shipping):null);
        result.put("knownProductCost",known);
        result.put("knownOperatingCost",known!=null&&shipping!=null?known.add(shipping):null);
        result.put("operatingCostBasis","营业成本 = 商品成本 + 物流贴单成本。商品成本优先使用实际出库凭据；样品成本属于商品成本子项，不重复加计。千易售后退款用于核对，不重复扣减利润。");
        result.put("status",state);result.put("estimated",estimated);result.put("notes",notes);
        result.put("formula","利润额 = 销售收入 − 已结算平台费用（含物流及调整）− 广告消耗 − 营业成本");
        result.put("settlementBasis","TikTok 销售收入取 revenue_amount（Excel 的 Total Revenue）；商家优惠和退款已体现在收入中，不再作为平台费用重复扣减。Shopee 同样扣除商品折扣与退款后展示收入。佣金、税费、物流及平台调整逐项保留原字段；销售收入减这些分项等于平台净结算。");
        result.put("refundBasis","退款对利润的影响以平台结算记录为准，不再重复扣减按退款日期汇总的确认退款金额。");
        result.put("basis","按查询范围内的结算账单日期统计；商品和贴单成本按订单首次结算日期计入一次。贴单金额取导入订单费用明细的金额或仓库账单操作费（人民币），按参考号绑定并去重，不再采用分组或国家单价。");
        return result;
    }
    static List<Map<String,Object>> withGross(List<Map<String,Object>> rows,String dimension){
        return rows.stream().map(row->{
            if(!"SETTLEMENT".equals(row.get("kind")))return row;
            var copy=new LinkedHashMap<>(row);
            copy.put("settled_gross_amount",goods(row));
            return (Map<String,Object>)copy;
        }).toList();
    }
    static BigDecimal goods(Map<String,Object> row){
        return row!=null&&decimal(row.get("revenue"))!=null&&number(row.get("known_revenues"))==number(row.get("records"))
                ?decimal(row.get("revenue")):null;
    }
    private static Map<String,Object> find(List<Map<String,Object>> rows,String kind){
        return rows.stream().filter(r->kind.equals(r.get("kind"))).findFirst().orElse(null);
    }
    private static BigDecimal value(Map<String,Object> row){return row==null?BigDecimal.ZERO:decimal(row.get("amount"));}
    private static boolean complete(Map<String,Object> row){
        return row==null||decimal(row.get("amount"))!=null
            &&(!row.containsKey("known_amounts")||number(row.get("known_amounts"))==number(row.get("records")));
    }
}
