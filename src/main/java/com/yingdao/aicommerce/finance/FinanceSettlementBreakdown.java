package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Provider-signed settlement components. A positive deduction reduces the seller's income. */
public final class FinanceSettlementBreakdown {
    private FinanceSettlementBreakdown() {}
    public record Fee(String field,String label,String group,BigDecimal amount,BigDecimal sourceAmount,String note) {}
    public record Result(BigDecimal goods,BigDecimal net,List<Fee> fees) {
        public BigDecimal deductions(){return fees.stream().map(Fee::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    }
    static final Map<String,String> LABELS=labels();
    static Map<String,String> labels(){
        var labels=new LinkedHashMap<String,String>();
        for(String line:("""
            seller_discount_amount=商家优惠（含活动折扣及商家优惠券）
            seller_discount_refund_amount=退款返还商家优惠
            refund_subtotal_before_discount_amount=退款商品金额冲减（优惠前）
            cod_service_fee_amount=买家货到付款服务费
            refund_cod_service_fee_amount=货到付款服务费退款
            distant_item_fee_amount=商品偏远地区附加费
            platform_commission_amount=TikTok 平台佣金
            referral_fee_amount=推荐佣金
            refund_administration_fee_amount=退款管理费
            transaction_fee_amount=交易手续费
            credit_card_handling_fee_amount=信用卡分期手续费
            affiliate_commission_amount=达人佣金
            affiliate_partner_commission_amount=合作伙伴佣金
            affiliate_ads_commission_amount=达人广告佣金
            affiliate_commission_deposit=达人佣金预扣
            affiliate_commission_release=达人佣金预扣返还
            tap_shop_ads_commission=合作伙伴店铺广告佣金
            sfp_service_fee_amount=包邮计划服务费
            live_specials_fee_amount=LIVE 特别服务费
            bonus_cashback_service_fee_amount=返现计划服务费
            mall_service_fee_amount=商城服务费
            voucher_xtra_service_fee_amount=优惠券额外服务费
            flash_sales_service_fee_amount=闪购服务费
            cofunded_promotion_service_fee_amount=共同出资促销服务费
            pre_order_service_fee_amount=预购服务费
            tsp_commission_amount=TikTok 服务商佣金
            dt_handling_fee_amount=平台履约处理费
            epr_pob_service_fee_amount=环保责任服务费
            seller_paylater_handling_fee_amount=PayLater 分期服务费
            fee_per_item_sold_amount=每件商品成交费
            cofunded_creator_bonus_amount=共同承担达人奖励
            dynamic_commission_amount=商业增长费（动态佣金）
            external_affiliate_marketing_fee_amount=外部联盟营销服务费
            vn_fix_infrastructure_fee=基础设施费
            seller_growth_fee_amount=卖家增长费
            shipping_fee_guarantee_service_fee=运费保障服务费
            campaign_period_fee_sp_amount=活动资源服务费
            campaign_period_fee_cfp_amount=活动共同出资服务费
            platform_special_service_fee_amount=平台特别服务费
            insurance_fee=保险费
            brand_campaign_fee=品牌活动费
            gmv_max_coupon_fee=GMV Max 优惠券费用
            gmv_max_ad_fee_amount=GMV Max 广告费用
            installation_service_fee=安装服务费
            smart_promotion_fee_amount=智能推广费用
            store_gmv_growth_package_fee=店铺 GMV 增长计划费
            new_customer_growth_package_fee=新客增长计划费
            target_product_gmv_growth_package_fee=商品 GMV 增长计划费
            category_led_campaign_fee_amount=类目活动费
            platform_semi_managed_commission_fee=半托管佣金
            seller_funded_user_spoint_service_fee=商家承担积分服务费
            brand_amplification_program_commission=品牌放大计划佣金
            auto_post_shoppable_video_commission_fee=自动发布带货视频佣金
            failed_delivery_shipping_fee=配送失败运费
            buyer_fault_return_shipping_fee=买家责任退货运费
            actual_shipping_fee_amount=实际配送运费
            international_leg_logistics_amount=跨境国际段运费
            shipping_fee_discount_amount=平台运费补贴
            customer_paid_shipping_fee_amount=买家承担运费
            return_shipping_fee_amount=退货运费
            replacement_shipping_fee_amount=补发运费
            exchange_shipping_fee_amount=换货运费
            signature_confirmation_fee_amount=签收确认服务费
            shipping_insurance_fee_amount=运费保险费
            fbt_fulfillment_fee_reimbursement_amount=FBT 履约费用补偿
            return_shipping_label_fee_amount=退货面单费用
            seller_self_shipping_service_fee_amount=卖家自发货服务费
            return_shipping_fee_paid_buyer_amount=买家退货运费补偿
            failed_delivery_subsidy_amount=配送失败运费补贴
            shipping_fee_guarantee_reimbursement=运费保障补偿
            fbt_free_shipping_fee_amount=FBT 包邮费用
            free_return_subsidy_amount=免费退货补贴
            distant_shipping_fee_amount=偏远地区运费
            shipping_app_service_fee_amount=物流应用服务费
            logistics_service_fee=物流服务费
            fbt_overall_merchant_subsidy=FBT 商家通用补贴
            fbt_key_merchant_subsidy=FBT 重点商家补贴
            tiktok_shop_shipping_incentive_amount=平台物流奖励
            hazmat_noncompliance_fee=危险品申报违规费
            hazmat_handling_fee=危险品处理费
            non_standard_weight_fee=超重附加费
            non_standard_dimension_fee=超尺寸附加费
            gst_amount=商品与服务税 GST
            isr_amount=所得税 ISR
            iva_amount=增值税 IVA
            pit_amount=个人所得税 PIT
            sst_amount=销售及服务税 SST
            vat_amount=增值税 VAT
            local_vat_amount=本地增值税
            cedular_tax=分类所得税
            import_vat_amount=进口增值税
            customs_duty_amount=关税
            anti_dumping_duty_amount=反倾销税
            customs_clearance_amount=清关费用
            article_22_income_tax_withheld=第 22 条所得税代扣
            seller_discount=商家商品折扣
            voucher_from_seller=商家优惠券
            seller_coin_cash_back=商家承担返金币
            commission_fee=虾皮平台佣金
            service_fee=平台服务费
            seller_transaction_fee=交易手续费
            actual_shipping_fee=实际配送运费
            buyer_paid_shipping_fee=买家承担运费
            shopee_shipping_rebate=平台运费补贴
            shipping_fee_discount_from_3pl=第三方物流折扣
            reverse_shipping_fee=退货运费
            order_ams_commission_fee=达人营销佣金
            campaign_fee=活动费用
            withholding_tax=代扣税
            fbs_fee=仓储履约费
            seller_order_processing_fee=订单处理费
            pay_per_sale=成交广告费
            original_shopee_discount=平台商品补贴原额冲回
            shopee_discount=平台商品补贴
            rsf_seller_protection_fee_claim_amount=退货保障理赔
            final_return_to_seller_shipping_fee=退回卖家运费
            shipping_seller_protection_fee_amount=物流卖家保障费
            delivery_seller_protection_fee_premium_amount=配送保障保费
            final_escrow_product_gst=商品 GST
            escrow_tax=结算税
            sales_tax_on_lvg=低价值商品销售税
            reverse_shipping_fee_sst=退货运费 SST
            shipping_fee_sst=运费 SST
            overseas_return_service_fee=海外退货服务费
            vat_on_imported_goods=进口商品增值税
            withholding_vat_tax=代扣增值税
            withholding_pit_tax=代扣个人所得税
            withholding_cit_tax=代扣企业所得税
            buyer_paid_packaging_fee=买家承担包装费
            trade_in_bonus_by_seller=商家以旧换新补贴
            ads_escrow_top_up_fee_or_technical_support_fee=广告充值或技术支持费（平台合并字段）
            th_import_duty=泰国进口关税
            total_adjustment_amount=平台订单结算调整
            """).split("\\R")){
            String[] parts=line.trim().split("=",2);if(parts.length==2)labels.put(parts[0],parts[1]);
        }
        return Collections.unmodifiableMap(labels);
    }
    public static String label(String field){
        String key=field.substring(field.lastIndexOf('.')+1);
        if(LABELS.containsKey(key))return LABELS.get(key);
        if(key.endsWith("_tax_amount")||key.endsWith("_tax")){
            String root=key.replaceFirst("_tax(?:_amount)?$","");
            for(String candidate:List.of(root,root+"_amount"))if(LABELS.containsKey(candidate))return LABELS.get(candidate)+"税费";
        }
        return "平台费用（"+key+"）";
    }
    static void add(List<Fee> fees,String path,String label,String group,BigDecimal source,boolean negate,String note){
        if(source!=null&&source.signum()!=0)fees.add(new Fee(path,label,group,negate?source.negate():source,source,note));
    }
    static void component(List<Fee> fees,Map<String,Object> raw,String path,String group,boolean negate){
        add(fees,path,label(path),group,amount(raw,path),negate,"平台原始字段；正数为扣减，负数为返还或补贴");
    }
    /** Only contribution fields are summed. Supplementary values and before-tax aliases are reference-only. */
    static void tikTree(List<Fee> fees,Object value,String path,String group){
        if(!(value instanceof Map<?,?>))return;
        for(var e:map(value).entrySet()){
            if(Set.of("supplementary_component","affiliate_commission_amount_before_pit").contains(e.getKey()))continue;
            String field=path+"."+e.getKey();
            if(e.getValue() instanceof Map<?,?>)tikTree(fees,e.getValue(),field,group);
            else add(fees,field,label(field),group,decimal(e.getValue()),true,"平台有符号金额取反；补充字段及税前佣金参考值不重复计费");
        }
    }
    static void tikGroup(List<Fee> fees,Map<String,Object> raw,String tree,String total,String group){
        int start=fees.size();tikTree(fees,raw.get(tree),tree,group);
        BigDecimal expected=amount(raw,total);
        if(expected!=null){
            BigDecimal actual=fees.subList(start,fees.size()).stream().map(Fee::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            add(fees,total+".unitemized",group+"未拆分差额（待核对）","待核对",expected.negate().subtract(actual),false,
                "平台分项合计与 "+total+" 不一致，保留原始汇总差额，不猜测费用类别");
        }
    }
    public static Result parse(String platform,Map<String,Object> raw,BigDecimal net){
        var fees=new ArrayList<Fee>();BigDecimal goods=null;
        if(platform.equals("TIKTOK_SHOP")){
            // Seller Center's Total Revenue already includes seller discounts and refunds.
            // Keep the original breakdown as evidence, never turn it into another fee.
            goods=amount(raw,"revenue_amount");
            if(goods==null&&!first(raw,"type").isBlank()&&!Set.of("ORDER","RESERVE").contains(first(raw,"type")))goods=BigDecimal.ZERO;
            tikGroup(fees,raw,"fee_tax_breakdown","fee_tax_amount","平台费用及税费");
            tikGroup(fees,raw,"shipping_cost_breakdown","shipping_cost_amount","物流费用");
            String type=first(raw,"type");
            add(fees,"adjustment_amount",adjustmentLabel(type),"平台调整",amount(raw,"adjustment_amount"),true,"平台调整类型："+type);
        }else if(platform.equals("SHOPEE")){
            var income=map(raw.get("order_income"));goods=amount(income,"original_price","order_original_price");
            for(String field:List.of("seller_discount","voucher_from_seller","seller_coin_cash_back"))
                component(fees,raw,"order_income."+field,"商家优惠",false);
            // original_price/seller_discount are final values. The initial merchandise base and
            // signed seller_return_refund must be bridged together, never count a refunded item twice.
            BigDecimal initial=amount(income,"original_cost_of_goods_sold"),returned=amount(income,"seller_return_refund"),discount=amount(income,"seller_discount");
            if(goods!=null&&initial!=null&&discount!=null){
                BigDecimal bridge=goods.subtract(discount).subtract(initial).subtract(returned==null?BigDecimal.ZERO:returned);
                add(fees,"order_income.goods_refund_bridge","商品退款及折扣调整","退款与返还",bridge,false,
                    "original_price − seller_discount − original_cost_of_goods_sold − seller_return_refund；商品原价已反映退款时不重复扣减");
            }
            for(String field:List.of("original_shopee_discount","actual_shipping_fee","reverse_shipping_fee",
                    "final_return_to_seller_shipping_fee","seller_transaction_fee","service_fee","commission_fee",
                    "campaign_fee","shipping_seller_protection_fee_amount","delivery_seller_protection_fee_premium_amount",
                    "final_escrow_product_gst","order_ams_commission_fee","escrow_tax","sales_tax_on_lvg",
                    "reverse_shipping_fee_sst","shipping_fee_sst","withholding_tax","overseas_return_service_fee",
                    "vat_on_imported_goods","withholding_vat_tax","withholding_pit_tax","withholding_cit_tax",
                    "seller_order_processing_fee","trade_in_bonus_by_seller","fbs_fee",
                    "ads_escrow_top_up_fee_or_technical_support_fee","th_import_duty","pay_per_sale"))
                component(fees,raw,"order_income."+field,field.contains("shipping")?"物流费用":field.contains("tax")||field.contains("gst")?"税费":"平台费用",false);
            for(String field:List.of("shopee_discount","buyer_paid_shipping_fee","shopee_shipping_rebate",
                    "shipping_fee_discount_from_3pl","rsf_seller_protection_fee_claim_amount","buyer_paid_packaging_fee"))
                component(fees,raw,"order_income."+field,"补贴与返还",true);
            // The final platform amount is authoritative for the adjustment's sign.
            BigDecimal before=amount(income,"escrow_amount");
            if(before!=null&&net!=null)add(fees,"order_income.total_adjustment_amount","平台订单结算调整","平台调整",
                before.subtract(net),false,"escrow_amount − escrow_amount_after_adjustment；对应平台订单级调整");
            if(goods!=null){
                var merchandise=fees.stream().filter(f->Set.of("商家优惠","退款与返还").contains(f.group())).toList();
                goods=goods.subtract(merchandise.stream().map(Fee::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
                fees.removeAll(merchandise);
            }
        }
        if(goods!=null&&net!=null){
            BigDecimal remainder=goods.subtract(net).subtract(fees.stream().map(Fee::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
            add(fees,"unitemized_settlement_difference","未拆分结算差额（待核对）","待核对",remainder,false,
                "销售收入 − 平台净结算 − 已识别分项；接口未完整解释的差额，不视为已确认的平台收费");
        }
        return new Result(goods,net,List.copyOf(fees));
    }
    static String adjustmentLabel(String type){
        return switch(type){
            case "GMV_PAYMENT_FOR_ADS"->"广告账户资金划转（非广告消耗）";
            case "PLATFORM_COMMISSION_ADJUSTMENT","PLATFORM_COMMISSION_COMPENSATION"->"平台佣金调整或补偿";
            case "SHIPPING_FEE_ADJUSTMENT","SHIPPING_FEE_COMPENSATION","SHIPPING_FEE_REBATE"->"运费调整、补偿或返还";
            case "PLATFORM_PENALTY"->"平台处罚扣款";
            case "STAMP_DUTY"->"印花税";
            case "REBATE"->"平台佣金返利";
            case "CUSTOMER_SERVICE_COMPENSATION"->"客户服务补偿";
            case "LOGISTICS_REIMBURSEMENT"->"物流赔偿";
            case "SAMPLE_SHIPPING_FEE"->"样品运费";
            default->"平台调整（"+(type.isBlank()?"未提供类型":type)+"）";
        };
    }
    public static boolean supported(String platform,Map<String,Object> raw){
        return platform.equals("SHOPEE")&&!map(raw.get("order_income")).isEmpty()
            ||platform.equals("TIKTOK_SHOP")&&raw.containsKey("settlement_amount");
    }
}
