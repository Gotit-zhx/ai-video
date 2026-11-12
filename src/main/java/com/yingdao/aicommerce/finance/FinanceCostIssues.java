package com.yingdao.aicommerce.finance;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable issue metadata. Descriptions preserve supplied evidence and never infer identifiers. */
public final class FinanceCostIssues {
    private FinanceCostIssues() {}

    public static Map<String,Object> of(String code) {
        String[] detail=switch(code==null?"":code) {
            case "IDENTIFIER_INVALID" -> new String[]{"型号编号格式不正确或数值精度已损坏","成本资料","将原始编号按文本重新导出，不要补猜数字"};
            case "IDENTIFIER_TYPE_UNKNOWN" -> new String[]{"未明确 SKU 或 SPU 编号类型","成本资料","声明编号类型；混合表逐行标明 SKU 或 SPU"};
            case "UNIT_UNKNOWN" -> new String[]{"成本计价单位不明","成本资料","补充并核对计价单位"};
            case "WAREHOUSE_UNKNOWN" -> new String[]{"成本仓身份不明","成本资料","明确成本仓或使用已确认的仓库别名"};
            case "WAREHOUSE_INVALID" -> new String[]{"成本仓格式不正确","成本资料","核对原始仓库字段"};
            case "CURRENCY_UNSUPPORTED" -> new String[]{"成本资料必须为人民币","成本资料","提供人民币成本及其来源依据"};
            case "PRICE_INVALID" -> new String[]{"单价格式不正确或超出支持范围","成本资料","提供非负人民币数值，核对公式和精度"};
            case "ZERO_PRICE" -> new String[]{"成本表为零值，需核对单价","成本资料","核对原始单价；明确的人工零价须另行确认"};
            case "DUPLICATE_PRICE_CONFLICT" -> new String[]{"同批相同身份和月份存在不同单价","成本资料","核对并消除同批冲突后重新提交"};
            case "CURRENT_PRICE_CONFLICT" -> new String[]{"现有资料同一身份和月份存在不同单价","有效成本资料","先核对现有冲突，不能选择任意一条价格"};
            case "PRICE_REVISION_REQUIRED" -> new String[]{"已有单价不同，尚未声明修订","有效成本资料","声明修订并补充变更依据"};
            case "PRICE_CHANGE_HIGH" -> new String[]{"单价变化超过复核阈值","有效成本资料","核对单价和单位并独立复核影响"};
            case "NEW_IDENTIFIER" -> new String[]{"新型号缺少同身份历史成本依据","成本资料","核对型号、单位和成本来源后独立复核"};
            case "IDENTITY_CHANGED" -> new String[]{"型号对应仓库、单位或编号类型发生变化","有效成本资料","确认仓库、单位和 SKU/SPU 适用关系"};
            case "SPU_UNIFORM_UNCONFIRMED" -> new String[]{"SPU 尚无统一成本适用证据","成本资料","确认适用规格及统一成本证据"};
            case "LEGACY_UNVERIFIED" -> new String[]{"历史成本的单位或编号类型尚未核验","历史成本资料","核对原始资料的单位和 SKU/SPU 类型后独立复核"};
            case "SOURCE_UNVERIFIED" -> new String[]{"成本来源尚未独立核验","来源配置","核对来源配置并由其他复核人确认"};
            case "MONTH_INVALID" -> new String[]{"成本月份格式不正确","成本资料","使用有效的 YYYY-MM 成本月份"};
            case "MONTH_OUT_OF_SCOPE" -> new String[]{"成本月份超出声明的适用期间","成本资料","核对并修正数据或适用起止月份"};
            case "PERIOD_INVALID" -> new String[]{"适用期间格式或先后顺序不正确","变更期间","填写有效且有序的起止月份"};
            case "NOW_MONTH_UNKNOWN" -> new String[]{"当前成本月份未明确","校验上下文","由服务端提供当前成本月份后重新校验"};
            case "FUTURE_MONTH" -> new String[]{"资料包含未来成本月份","成本资料","核对未来适用范围并独立复核"};
            case "HISTORICAL_MONTH" -> new String[]{"资料变更涉及历史成本月份","成本资料","核对历史影响及关账状态后复核"};
            case "POLICY_INVALID" -> new String[]{"成本风险阈值不正确","风险策略","将风险阈值设置为有限非负数值"};
            case "EMPTY_COSTS" -> new String[]{"没有可校验的成本单元格","成本资料","提供包含型号、成本仓、月份及单价的资料"};
            case "ORDER_LINE_CONFLICT" -> new String[]{"平台商品行重复且内容不一致","平台订单明细","核对原始商品行并重新同步"};
            case "QUANTITY_MISSING" -> new String[]{"缺少有效购买数量","平台订单明细","补齐原始购买数量后重新计算"};
            case "BUSINESS_DATE_MISSING" -> new String[]{"缺少所选成本口径的业务日期","平台订单","补齐所选口径的业务日期"};
            case "WAREHOUSE_AMBIGUOUS" -> new String[]{"无法唯一确定成本仓","成本仓配置","明确店铺或人工关联的成本仓"};
            case "MANUAL_COST_REMOVED" -> new String[]{"关联的手动成本已删除，请重新核对","人工成本关联","核对原关联并提交新的有效关联"};
            case "COST_SOURCE_MISSING" -> new String[]{"店铺分组尚未配置成本资料","成本资料","为当前分组补充并核验成本资料"};
            case "COST_MATCH_MISSING" -> new String[]{"商品 SKU 与平台 SPU 均未匹配到唯一成本型号，请人工确认","商品成本关联","核对 SKU、SPU 和成本型号后提交人工关联"};
            case "WAREHOUSE_COST_MISSING" -> new String[]{"指定成本仓缺少型号成本","成本资料","补充该成本仓的成本，不能借用其他国家价格"};
            case "CONVERSION_MISSING" -> new String[]{"人工关联缺少有效换算数量","人工成本关联","核对计价单位并补充换算数量"};
            case "MONTH_PRICE_MISSING" -> new String[]{"缺少业务月份可用成本价","成本资料","补充该业务月份适用的成本价"};
            case "COST_AMOUNT_OVERFLOW" -> new String[]{"成本金额超出支持范围","成本计算","核对原始单价、购买数量和换算数量"};
            case "ORDER_LINES_MISSING" -> new String[]{"平台尚未返回订单商品明细","平台订单明细","同步订单商品明细后重新计算"};
            case "ORDER_NOT_SYNCED" -> new String[]{"关联订单尚未同步","订单同步","补齐关联订单后重新计算"};
            case "CALCULATION_QUEUED" -> new String[]{"等待后台计算成本","成本计算队列","等待计算完成，失败时查看任务错误并重试"};
            default -> null;
        };
        if(detail==null)return Map.of("code","UNKNOWN","message","未识别的成本异常","source","未知","action","查看原始异常并核对对应资料或任务状态");
        return Map.of("code",code,"message",detail[0],"source",detail[1],"action",detail[2]);
    }

    public static Map<String,Object> describe(String issue) {
        String message=issue==null?"":issue;
        String code=switch(message) {
            case "平台商品行重复且内容不一致" -> "ORDER_LINE_CONFLICT";
            case "缺少有效购买数量" -> "QUANTITY_MISSING";
            case "缺少所选成本口径的业务日期" -> "BUSINESS_DATE_MISSING";
            case "手动成本存在多个成本仓，请配置明确的成本仓", "请配置成本仓，无法唯一匹配店铺国家" -> "WAREHOUSE_AMBIGUOUS";
            case "关联的手动成本已删除，请重新核对" -> "MANUAL_COST_REMOVED";
            case "店铺分组尚未配置成本资料" -> "COST_SOURCE_MISSING";
            case "商品 SKU 与平台 SPU 均未匹配到唯一成本型号，请人工确认" -> "COST_MATCH_MISSING";
            case "人工关联缺少有效换算数量" -> "CONVERSION_MISSING";
            case "成本表为零值，需核对单价" -> "ZERO_PRICE";
            case "成本金额超出支持范围", "订单成本合计超出支持范围" -> "COST_AMOUNT_OVERFLOW";
            case "平台尚未返回订单商品明细" -> "ORDER_LINES_MISSING";
            case "关联订单尚未同步" -> "ORDER_NOT_SYNCED";
            case "等待后台计算成本" -> "CALCULATION_QUEUED";
            default -> message.matches("缺少 \\d{4}-\\d{2} 可用成本价")?"MONTH_PRICE_MISSING":
                message.startsWith("成本表缺少 ")&&message.contains(" 的 ")&&
                    (message.endsWith(" 成本，不能自动使用其他国家成本")||message.endsWith(" 成本，不能自动使用其他国家成本；请人工确认"))?"WAREHOUSE_COST_MISSING":"UNKNOWN";
        };
        var result=new LinkedHashMap<>(of(code));result.put("message",message);return result;
    }
}
